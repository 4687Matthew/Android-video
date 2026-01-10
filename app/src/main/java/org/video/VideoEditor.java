package org.video;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.media.MediaCodecInfo;
import android.view.Surface;
import android.os.Build;

import java.nio.ByteBuffer;
import java.util.Locale;

public class VideoEditor {
    private static final String TAG = "VideoEditor";
    private static final int TIMEOUT_US = 10000;

    /** 将 MediaExtractor 样本标志位转换为 MediaCodec Buffer 标志位 */
    private static int convertSampleFlagsToBufferFlags(int extractorFlags) {
        int bufferFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            bufferFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;
        }
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        // 忽略 SAMPLE_FLAG_ENCRYPTED 等 MediaCodec 不支持的标志位
        return bufferFlags;
    }

    /**
     * 裁剪视频
     */
    public static boolean cropVideo(String inputPath, String outputPath,
                                    long startTimeUs, long endTimeUs) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int videoTrackIndex = -1, audioTrackIndex = -1;
            MediaFormat videoFormat = null, audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i;
                    videoFormat = format;
                } else if (mime != null && mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = videoFormat == null ? -1 : muxer.addTrack(videoFormat);
            int muxerAudioTrack = audioFormat == null ? -1 : muxer.addTrack(audioFormat);
            muxer.start();

            // 处理视频
            if (videoTrackIndex != -1) {
                extractor.selectTrack(videoTrackIndex);
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    long pts = extractor.getSampleTime();
                    if (pts < startTimeUs) {
                        extractor.advance();
                        continue;
                    }
                    if (pts >= endTimeUs) break;
                    buffer.position(0); buffer.limit(size);
                    info.set(0, size, pts - startTimeUs, convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                    muxer.writeSampleData(muxerVideoTrack, buffer, info);
                    extractor.advance();
                }
            }

            // 处理音频
            if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    long pts = extractor.getSampleTime();
                    if (pts < startTimeUs) {
                        extractor.advance();
                        continue;
                    }
                    if (pts >= endTimeUs) break;
                    buffer.position(0); buffer.limit(size);
                    info.set(0, size, pts - startTimeUs, convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                    muxer.writeSampleData(muxerAudioTrack, buffer, info);
                    extractor.advance();
                }
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "裁剪视频失败", e);
            return false;
        } finally {
            if (extractor != null) extractor.release();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 分割视频
     */
    public static boolean splitVideo(String inputPath, String outputPath1,
                                     String outputPath2, long splitTimeUs) {
        MediaExtractor temp = new MediaExtractor();
        long duration = 0;
        try {
            temp.setDataSource(inputPath);
            for (int i = 0; i < temp.getTrackCount(); i++) {
                MediaFormat f = temp.getTrackFormat(i);
                if (f.containsKey(MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, f.getLong(MediaFormat.KEY_DURATION));
            }
        } catch (Exception e) {
            Log.e(TAG, "获取时长失败", e);
            return false;
        } finally {
            temp.release();
        }
        return cropVideo(inputPath, outputPath1, 0, splitTimeUs) &&
                cropVideo(inputPath, outputPath2, splitTimeUs, duration);
    }

    /**
     * 合并视频
     */
    public static boolean mergeVideos(String[] inputPaths, String outputPath) {
        MediaMuxer muxer = null;
        try {
            MediaExtractor first = new MediaExtractor();
            first.setDataSource(inputPaths[0]);
            MediaFormat videoFormat = null, audioFormat = null;
            for (int i = 0; i < first.getTrackCount(); i++) {
                MediaFormat f = first.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && videoFormat == null)
                    videoFormat = f;
                else if (mime != null && mime.startsWith("audio/") && audioFormat == null)
                    audioFormat = f;
            }
            first.release();
            if (videoFormat == null) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = muxer.addTrack(videoFormat);
            int aTrack = audioFormat == null ? -1 : muxer.addTrack(audioFormat);
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offset = 0;

            for (String path : inputPaths) {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(path);
                int vIdx = -1, aIdx = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/") && vIdx == -1) vIdx = i;
                    else if (mime != null && mime.startsWith("audio/") && aIdx == -1) aIdx = i;
                }
                long start = -1;
                if (vIdx != -1) {
                    ex.selectTrack(vIdx);
                    while (true) {
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) break;
                        if (start < 0) start = ex.getSampleTime();
                        buf.position(0); buf.limit(size);
                        info.set(0, size, offset + (ex.getSampleTime() - start),
                                convertSampleFlagsToBufferFlags(ex.getSampleFlags()));
                        muxer.writeSampleData(vTrack, buf, info);
                        ex.advance();
                    }
                }
                if (aIdx != -1 && aTrack != -1) {
                    ex.unselectTrack(vIdx);
                    ex.selectTrack(aIdx);
                    start = -1;
                    while (true) {
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) break;
                        if (start < 0) start = ex.getSampleTime();
                        buf.position(0); buf.limit(size);
                        info.set(0, size, offset + (ex.getSampleTime() - start),
                                convertSampleFlagsToBufferFlags(ex.getSampleFlags()));
                        muxer.writeSampleData(aTrack, buf, info);
                        ex.advance();
                    }
                }
                MediaFormat vf = ex.getTrackFormat(vIdx);
                offset += vf.getLong(MediaFormat.KEY_DURATION);
                ex.release();
            }
            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "合并视频失败", e);
            return false;
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 调节视频速度（仅调整时间戳，不重新编码）
     */
    public static boolean adjustSpeed(String inputPath, String outputPath, double speedFactor) {
        MediaExtractor ex = null;
        MediaMuxer muxer = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(inputPath);
            MediaFormat videoFormat = null, audioFormat = null;
            int vIdx = -1, aIdx = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && vIdx == -1) {
                    vIdx = i; videoFormat = f;
                } else if (mime != null && mime.startsWith("audio/") && aIdx == -1) {
                    aIdx = i; audioFormat = f;
                }
            }
            if (videoFormat == null) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = muxer.addTrack(videoFormat);
            int aTrack = audioFormat == null ? -1 : muxer.addTrack(audioFormat);
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            if (vIdx != -1) {
                ex.selectTrack(vIdx);
                while (true) {
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long pts = (long) (ex.getSampleTime() / speedFactor);
                    buf.position(0); buf.limit(size);
                    info.set(0, size, pts, convertSampleFlagsToBufferFlags(ex.getSampleFlags()));
                    muxer.writeSampleData(vTrack, buf, info);
                    ex.advance();
                }
            }
            if (aIdx != -1 && aTrack != -1) {
                ex.unselectTrack(vIdx);
                ex.selectTrack(aIdx);
                while (true) {
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long pts = (long) (ex.getSampleTime() / speedFactor);
                    buf.position(0); buf.limit(size);
                    info.set(0, size, pts, convertSampleFlagsToBufferFlags(ex.getSampleFlags()));
                    muxer.writeSampleData(aTrack, buf, info);
                    ex.advance();
                }
            }
            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "调节速度失败", e);
            return false;
        } finally {
            if (ex != null) ex.release();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 返回格式：1920×1080  5000 kbps  30 fps
     */
    public static String getVideoInfo(String path) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    int w = f.getInteger(MediaFormat.KEY_WIDTH);
                    int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    int rate = f.containsKey(MediaFormat.KEY_FRAME_RATE) ?
                            f.getInteger(MediaFormat.KEY_FRAME_RATE) : 0;
                    int br = f.containsKey(MediaFormat.KEY_BIT_RATE) ?
                            f.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
                    return String.format(Locale.CHINA,
                            "%d×%d  %d kbps  %d fps", w, h, br / 1000, rate);
                }
            }
            return "未找到视频轨道";
        } catch (Exception e) {
            Log.e(TAG, "getVideoInfo error", e);
            return "读取失败";
        } finally {
            ex.release();
        }
    }

    public static boolean compressToHevc(String src, String dst, double ratio) {
        MediaExtractor ext = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null, encoder = null;

        try {
            Log.d(TAG, "开始压缩视频: " + src);

            /* ---------- 1. 解封装 ---------- */
            ext = new MediaExtractor();
            ext.setDataSource(src);
            int videoTrack = -1, audioTrack = -1;
            MediaFormat inVideoFmt = null, inAudioFmt = null;

            for (int i = 0; i < ext.getTrackCount(); i++) {
                MediaFormat f = ext.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrack = i;
                    inVideoFmt = f;
                    Log.d(TAG, "找到视频轨道: " + mime + ", 宽度: " +
                            f.getInteger(MediaFormat.KEY_WIDTH) +
                            ", 高度: " + f.getInteger(MediaFormat.KEY_HEIGHT));
                } else if (mime.startsWith("audio/")) {
                    audioTrack = i;
                    inAudioFmt = f;
                    Log.d(TAG, "找到音频轨道: " + mime);
                }
            }

            if (inVideoFmt == null) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 获取视频信息
            int width = inVideoFmt.getInteger(MediaFormat.KEY_WIDTH);
            int height = inVideoFmt.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = inVideoFmt.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inVideoFmt.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            long duration = inVideoFmt.containsKey(MediaFormat.KEY_DURATION)
                    ? inVideoFmt.getLong(MediaFormat.KEY_DURATION) : 0;

            Log.d(TAG, "视频信息: " + width + "x" + height + ", " + frameRate + "fps, 时长: " + duration);

            /* ---------- 2. 创建复用器（先创建以便编码器能写入） ---------- */
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideoTrack = -1;
            int outAudioTrack = -1;

            // 先添加音频轨道（如果存在）
            if (inAudioFmt != null) {
                outAudioTrack = muxer.addTrack(inAudioFmt);
                Log.d(TAG, "添加音频轨道到复用器: " + outAudioTrack);
            }

            /* ---------- 3. 配置编码器 ---------- */
            int srcBr = inVideoFmt.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? inVideoFmt.getInteger(MediaFormat.KEY_BIT_RATE) : 8_000_000;
            int dstBr = (int) (srcBr * ratio);
            dstBr = Math.max(dstBr, 500000); // 最小 500kbps

            Log.d(TAG, "原始码率: " + srcBr + ", 目标码率: " + dstBr);

            MediaFormat outVideoFmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            outVideoFmt.setInteger(MediaFormat.KEY_BIT_RATE, dstBr);
            outVideoFmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outVideoFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            // 使用 COLOR_FormatYUV420Flexible 而不是 Surface
            outVideoFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 设置其他重要参数
            outVideoFmt.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            outVideoFmt.setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31);

            Log.d(TAG, "编码器配置完成");

            /* ---------- 4. 创建编解码器 ---------- */
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            encoder.configure(outVideoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            Log.d(TAG, "HEVC编码器已启动");

            decoder = MediaCodec.createDecoderByType(inVideoFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(inVideoFmt, null, null, 0);
            decoder.start();
            Log.d(TAG, "解码器已启动");

            /* ---------- 5. 处理视频数据 ---------- */
            ext.selectTrack(videoTrack);

            // 处理解码输入
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;
            long videoStartTime = -1;
            int frameCount = 0;

            // 用于存储解码后的帧
            ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
            ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
            ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
            ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

            while (!outputEos) {
                // 5.1 向解码器输入数据
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoderInputBuffers[inIndex];
                        int sampleSize = ext.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                            Log.d(TAG, "解码器输入结束");
                        } else {
                            long presentationTime = ext.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize,
                                    presentationTime, 0);
                            ext.advance();
                        }
                    }
                }

                // 5.2 从解码器获取输出
                int decIndex = decoder.dequeueOutputBuffer(decInfo, 10000);
                if (decIndex >= 0) {
                    // 解码器输出一帧，现在编码这一帧
                    ByteBuffer decodedFrame = decoderOutputBuffers[decIndex];

                    // 将解码后的帧送入编码器
                    int encInIndex = encoder.dequeueInputBuffer(10000);
                    if (encInIndex >= 0) {
                        ByteBuffer encoderInput = encoderInputBuffers[encInIndex];
                        encoderInput.clear();

                        // 这里需要将YUV数据复制到编码器输入缓冲区
                        // 注意：实际应用中需要正确处理YUV格式转换
                        if (decInfo.size > 0) {
                            encoderInput.put(decodedFrame);
                        }

                        encoder.queueInputBuffer(encInIndex, 0, decInfo.size,
                                decInfo.presentationTimeUs,
                                (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                    }

                    decoder.releaseOutputBuffer(decIndex, false);
                } else if (decIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    decoderOutputBuffers = decoder.getOutputBuffers();
                } else if (decIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    Log.d(TAG, "解码器输出格式改变: " + newFormat);
                }

                // 5.3 从编码器获取输出
                int encIndex = encoder.dequeueOutputBuffer(encInfo, 10000);
                if (encIndex >= 0) {
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(encIndex, false);
                        continue;
                    }

                    if (encInfo.size > 0) {
                        if (outVideoTrack == -1) {
                            // 获取编码器输出格式并添加到复用器
                            MediaFormat newFormat = encoder.getOutputFormat();
                            outVideoTrack = muxer.addTrack(newFormat);
                            muxer.start();
                            Log.d(TAG, "添加视频轨道到复用器: " + outVideoTrack + ", 格式: " + newFormat);
                        }

                        ByteBuffer encodedData = encoderOutputBuffers[encIndex];
                        if (videoStartTime < 0) {
                            videoStartTime = encInfo.presentationTimeUs;
                        }
                        encInfo.presentationTimeUs -= videoStartTime;

                        muxer.writeSampleData(outVideoTrack, encodedData, encInfo);
                        frameCount++;

                        if (frameCount % 30 == 0) {
                            Log.d(TAG, "已编码 " + frameCount + " 帧，时间戳: " +
                                    encInfo.presentationTimeUs);
                        }
                    }

                    outputEos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encIndex, false);
                } else if (encIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                    Log.d(TAG, "编码器输出缓冲区改变");
                } else if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "编码器输出格式改变");
                }
            }

            Log.d(TAG, "视频编码完成，共 " + frameCount + " 帧");

            /* ---------- 6. 处理音频 ---------- */
            if (outAudioTrack >= 0) {
                Log.d(TAG, "开始处理音频轨道");
                ext.unselectTrack(videoTrack);
                ext.selectTrack(audioTrack);

                ByteBuffer audioBuf = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
                long audioStartTime = -1;
                int audioFrameCount = 0;

                while (true) {
                    int size = ext.readSampleData(audioBuf, 0);
                    if (size < 0) {
                        Log.d(TAG, "音频结束，共 " + audioFrameCount + " 帧");
                        break;
                    }

                    long pts = ext.getSampleTime();
                    if (audioStartTime < 0) {
                        audioStartTime = pts;
                    }

                    aInfo.set(0, size, pts - audioStartTime, ext.getSampleFlags());
                    muxer.writeSampleData(outAudioTrack, audioBuf, aInfo);
                    audioFrameCount++;
                    ext.advance();
                }
            }

            Log.d(TAG, "编码完成，停止复用器");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "compressToHevc error", e);
            return false;
        } finally {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
                if (muxer != null) {
                    muxer.stop();   // 放到最后
                    muxer.release();
                }
                if (ext != null) ext.release();
            } catch (Exception e) {
                Log.e(TAG, "释放资源失败", e);
            }
        }
    }
}

