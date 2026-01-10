package org.video;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.media.MediaCodecInfo;
import android.view.Surface;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * 主入口：智能选择转码方式
     */
    public static boolean compressVideo(String src, String dst, double ratio) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(src);

            // 获取视频信息
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    videoFormat = format;
                    break;
                }
            }

            if (videoFormat == null) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;

            Log.d(TAG, String.format("视频时长: %.2f秒", duration / 1000000.0));

            // 根据时长智能选择转码方式
            if (duration < 30 * 1000000L) { // 小于30秒
                Log.d(TAG, "视频较短，使用单线程转码");
                return compressToHevcSingleThread(src, dst, ratio);
            } else {
                Log.d(TAG, "视频较长，使用并行转码");
                return compressParallelWithOriginalAudio(src, dst, ratio);
            }
        } catch (Exception e) {
            Log.e(TAG, "compressVideo error", e);
            return false;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * 单线程转码（用于短视频）
     */
    private static boolean compressToHevcSingleThread(String src, String dst, double ratio) {
        MediaExtractor ext = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null, encoder = null;

        try {
            Log.d(TAG, "开始单线程转码: " + src);

            // 1. 解封装
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
                } else if (mime.startsWith("audio/")) {
                    audioTrack = i;
                    inAudioFmt = f;
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
            int srcBr = inVideoFmt.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? inVideoFmt.getInteger(MediaFormat.KEY_BIT_RATE) : 8_000_000;
            int dstBr = (int) (srcBr * ratio);
            dstBr = Math.max(dstBr, 500000);

            Log.d(TAG, String.format("视频信息: %dx%d, %dfps, 码率: %d->%d",
                    width, height, frameRate, srcBr, dstBr));

            // 2. 创建复用器
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outAudioTrack = -1;

            // 先添加音频轨道（如果存在）
            if (inAudioFmt != null) {
                outAudioTrack = muxer.addTrack(inAudioFmt);
                Log.d(TAG, "添加音频轨道到复用器");
            }

            // 3. 配置编码器
            MediaFormat outVideoFmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            outVideoFmt.setInteger(MediaFormat.KEY_BIT_RATE, dstBr);
            outVideoFmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outVideoFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            outVideoFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 4. 创建编解码器
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            encoder.configure(outVideoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            Log.d(TAG, "HEVC编码器已启动");

            decoder = MediaCodec.createDecoderByType(inVideoFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(inVideoFmt, null, null, 0);
            decoder.start();
            Log.d(TAG, "解码器已启动");

            // 5. 处理视频数据
            ext.selectTrack(videoTrack);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;
            long videoStartTime = -1;
            int outVideoTrack = -1;
            int frameCount = 0;
            long startTime = System.currentTimeMillis();

            while (!outputEos) {
                // 5.1 向解码器输入数据
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
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
                    int encInIndex = encoder.dequeueInputBuffer(10000);
                    if (encInIndex >= 0) {
                        ByteBuffer encoderInput = encoder.getInputBuffer(encInIndex);
                        encoderInput.clear();

                        ByteBuffer decodedFrame = decoder.getOutputBuffer(decIndex);
                        if (decInfo.size > 0) {
                            decodedFrame.limit(decInfo.offset + decInfo.size);
                            decodedFrame.position(decInfo.offset);
                            encoderInput.put(decodedFrame);
                        }

                        encoder.queueInputBuffer(encInIndex, 0, decInfo.size,
                                decInfo.presentationTimeUs,
                                (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                    }

                    decoder.releaseOutputBuffer(decIndex, false);

                    if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        inputEos = true;
                    }
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
                            Log.d(TAG, "添加视频轨道到复用器: " + newFormat);
                        }

                        ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                        if (videoStartTime < 0) {
                            videoStartTime = encInfo.presentationTimeUs;
                        }
                        encInfo.presentationTimeUs -= videoStartTime;

                        muxer.writeSampleData(outVideoTrack, encodedData, encInfo);
                        frameCount++;

                        if (frameCount % 30 == 0) {
                            Log.d(TAG, "已编码 " + frameCount + " 帧");
                        }
                    }

                    outputEos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encIndex, false);
                }
            }

            long processingTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, String.format("视频编码完成: %d帧, 耗时: %.2f秒",
                    frameCount, processingTime / 1000.0));

            // 6. 处理音频
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

            Log.d(TAG, "单线程转码完成");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "compressToHevcSingleThread error", e);
            return false;
        } finally {
            // 清理资源
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放编码器失败", e);
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放解码器失败", e);
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放复用器失败", e);
                }
            }
            if (ext != null) {
                try {
                    ext.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放提取器失败", e);
                }
            }
        }
    }

    /**
     * 并行转码（用于长视频）
     */
    public static boolean compressParallelWithOriginalAudio(String src, String dst, double ratio) {
        MediaExtractor extractor = null;
        ExecutorService executor = null;
        List<Future<File>> futures = new ArrayList<>();
        List<File> videoSegments = new ArrayList<>();
        File audioFile = null;
        File mergedVideoFile = null;

        try {
            Log.d(TAG, "开始并行转码: " + src);

            // 1. 提取视频信息
            extractor = new MediaExtractor();
            extractor.setDataSource(src);

            int videoTrack = -1, audioTrack = -1;
            MediaFormat videoFormat = null, audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                } else if (mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                }
            }

            if (videoFormat == null) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 获取视频信息
            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            int bitrate = videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 8_000_000;
            int targetBitrate = (int)(bitrate * ratio);
            targetBitrate = Math.max(targetBitrate, 500000);

            Log.d(TAG, String.format("视频信息: %dx%d, %dfps, 时长: %.1fs, 码率: %d->%d",
                    width, height, frameRate, duration / 1000000.0, bitrate, targetBitrate));

            // 2. 提取音频到临时文件
            if (audioTrack >= 0 && audioFormat != null) {
                audioFile = extractAudioToFile(extractor, audioTrack, audioFormat);
                if (audioFile != null) {
                    Log.d(TAG, "音频已提取到: " + audioFile.getAbsolutePath());
                }
            }

            // 3. 动态计算线程数和分片大小
            // 原则：每个分片至少15秒，最多4个线程
            long minSegmentDuration = 15 * 1000000L; // 15秒
            int maxThreads = Math.min(4, Runtime.getRuntime().availableProcessors() - 1);
            maxThreads = Math.max(2, maxThreads); // 至少2个线程

            int optimalThreads = (int) Math.min(
                    maxThreads,
                    Math.max(2, duration / minSegmentDuration)
            );

            Log.d(TAG, "使用 " + optimalThreads + " 个线程进行并行转码");

            // 4. 计算分片（基于关键帧）
            long segmentDuration = duration / optimalThreads;
            List<Long> segmentStarts = new ArrayList<>();
            List<Long> segmentEnds = new ArrayList<>();

            extractor.selectTrack(videoTrack);
            for (int i = 0; i < optimalThreads; i++) {
                long startTime = i * segmentDuration;
                long endTime = (i == optimalThreads - 1) ? duration : (i + 1) * segmentDuration;

                // 寻找最近的关键帧作为开始时间（除了第一个分片）
                if (i > 0) {
                    extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    startTime = extractor.getSampleTime();
                    if (startTime < 0) startTime = i * segmentDuration;
                    Log.d(TAG, String.format("分片%d: 调整开始时间到关键帧 %.3fs",
                            i, startTime / 1000000.0));
                }

                segmentStarts.add(startTime);
                segmentEnds.add(endTime);
                Log.d(TAG, String.format("分片%d: %.3fs - %.3fs (时长: %.3fs)",
                        i, startTime / 1000000.0, endTime / 1000000.0,
                        (endTime - startTime) / 1000000.0));
            }

            // 5. 并行转码每个分片
            executor = Executors.newFixedThreadPool(optimalThreads);
            final int finalTargetBitrate = targetBitrate;
            final MediaFormat finalVideoFormat = videoFormat;

            for (int i = 0; i < optimalThreads; i++) {
                final int segmentIndex = i;
                final long startTime = segmentStarts.get(i);
                final long endTime = segmentEnds.get(i);

                futures.add(executor.submit(() -> {
                    try {
                        // 使用缓存目录
                        File tempFile = File.createTempFile(
                                "video_segment_" + segmentIndex + "_",
                                ".mp4",
                                getCacheDir()
                        );

                        Log.d(TAG, String.format("开始处理分片%d [%.3fs-%.3fs]",
                                segmentIndex, startTime/1000000.0, endTime/1000000.0));

                        boolean success = transcodeSegment(
                                src, tempFile.getAbsolutePath(),
                                startTime, endTime,
                                finalTargetBitrate,
                                finalVideoFormat,
                                segmentIndex
                        );

                        if (success && tempFile.exists() && tempFile.length() > 1024) {
                            Log.d(TAG, String.format("分片%d处理成功, 大小: %.1fKB",
                                    segmentIndex, tempFile.length() / 1024.0));
                            return tempFile;
                        } else {
                            Log.e(TAG, "分片" + segmentIndex + "处理失败或文件太小");
                            if (tempFile.exists()) tempFile.delete();
                            return null;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "分片" + segmentIndex + "异常: " + e.getMessage(), e);
                        return null;
                    }
                }));
            }

            // 6. 等待所有分片完成，设置超时
            boolean allSegmentsSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    // 设置超时：每个分片最多处理原始视频时长的2倍
                    long timeout = (long)(duration * 2.0 / optimalThreads / 1000); // 转换为毫秒
                    File segmentFile = futures.get(i).get(timeout, TimeUnit.MILLISECONDS);

                    if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 1024) {
                        videoSegments.add(segmentFile);
                        Log.d(TAG, "分片" + i + "完成，大小: " + segmentFile.length() / 1024 + "KB");
                    } else {
                        Log.e(TAG, "分片" + i + "返回无效结果");
                        allSegmentsSuccess = false;
                        break;
                    }
                } catch (TimeoutException e) {
                    Log.e(TAG, "分片" + i + "处理超时");
                    allSegmentsSuccess = false;
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "获取分片" + i + "结果异常", e);
                    allSegmentsSuccess = false;
                    break;
                }
            }

            // 如果任何分片失败，清理并返回
            if (!allSegmentsSuccess) {
                Log.e(TAG, "有分片处理失败，终止并行转码");
                cleanupTempFiles(videoSegments, null, audioFile);
                return false;
            }

            executor.shutdown();

            // 7. 合并视频分片
            mergedVideoFile = File.createTempFile("merged_video_", ".mp4", getCacheDir());

            boolean mergeSuccess = mergeVideoSegments(videoSegments, mergedVideoFile.getAbsolutePath());
            if (!mergeSuccess) {
                Log.e(TAG, "合并视频分片失败");
                cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);
                return false;
            }

            Log.d(TAG, "视频分片合并成功，大小: " + mergedVideoFile.length() / 1024 + "KB");

            // 8. 合并音频和视频
            boolean finalMergeSuccess = mergeAudioAndVideo(
                    mergedVideoFile.getAbsolutePath(),
                    audioFile != null ? audioFile.getAbsolutePath() : null,
                    dst
            );

            // 9. 清理临时文件
            cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);

            Log.d(TAG, "并行转码完成，结果: " + finalMergeSuccess);
            return finalMergeSuccess;

        } catch (Exception e) {
            Log.e(TAG, "compressParallelWithOriginalAudio error", e);
            // 发生异常时清理临时文件
            cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);
            return false;
        } finally {
            try {
                if (extractor != null) extractor.release();
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdownNow();
                }
            } catch (Exception e) {
                Log.e(TAG, "释放资源失败", e);
            }
        }
    }

    /**
     * 提取音频到临时文件
     */
    private static File extractAudioToFile(MediaExtractor extractor, int audioTrack, MediaFormat audioFormat) {
        MediaMuxer muxer = null;
        try {
            File tempFile = File.createTempFile("audio_", ".aac", getCacheDir());

            muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            extractor.selectTrack(audioTrack);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long startTime = -1;
            int audioFrameCount = 0;

            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;

                long pts = extractor.getSampleTime();
                if (startTime < 0) startTime = pts;

                info.set(0, size, pts - startTime, extractor.getSampleFlags());
                muxer.writeSampleData(trackIndex, buffer, info);
                audioFrameCount++;
                extractor.advance();

                if (audioFrameCount % 100 == 0) {
                    Log.d(TAG, "已提取 " + audioFrameCount + " 帧音频");
                }
            }

            muxer.stop();

            Log.d(TAG, "音频提取完成，共 " + audioFrameCount + " 帧，大小: " + tempFile.length() / 1024 + "KB");
            return tempFile;

        } catch (Exception e) {
            Log.e(TAG, "extractAudioToFile error", e);
            return null;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放音频复用器失败", e);
                }
            }
        }
    }

    /**
     * 修复后的transcodeSegment方法 - 简化版本
     */
    private static boolean transcodeSegment(String src, String dst,
                                            long startTime, long endTime,
                                            int targetBitrate,
                                            MediaFormat originalFormat,
                                            int segmentIndex) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null, encoder = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(src);

            // 选择视频轨道
            int trackIndex = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    trackIndex = i;
                    videoFormat = format;
                    break;
                }
            }

            if (trackIndex == -1) {
                Log.e(TAG, "分片" + segmentIndex + ": 未找到视频轨道");
                return false;
            }

            extractor.selectTrack(trackIndex);

            // 定位到分片开始时间
            if (startTime > 0) {
                extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            int width = originalFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = originalFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = originalFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? originalFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            // 码率计算：分片的码率应该基于分片时长按比例调整
            long segmentDuration = endTime - startTime;
            double segmentRatio = segmentDuration / (double)(100 * 1000000L); // 假设总时长100秒
            int segmentBitrate = (int)(targetBitrate * Math.max(0.5, Math.min(2.0, segmentRatio)));
            segmentBitrate = Math.max(500000, segmentBitrate);

            Log.d(TAG, String.format("分片%d: %dx%d, %dfps, 码率: %d, 时长: %.2fs",
                    segmentIndex, width, height, frameRate, segmentBitrate,
                    segmentDuration / 1000000.0));

            // ============================================
            // 修复编码器配置 - 简化版本
            // ============================================
            MediaFormat encoderFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, segmentBitrate);
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            // 关键修复：使用正确的颜色格式
            // 先尝试使用设备支持的格式
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 添加必要的编码参数
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);

            Log.d(TAG, "分片" + segmentIndex + ": 编码器配置 - " + encoderFormat);

            // 创建编码器 - 添加重试机制
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                Log.d(TAG, "分片" + segmentIndex + ": HEVC编码器启动成功");
            } catch (Exception e) {
                Log.e(TAG, "分片" + segmentIndex + ": 创建HEVC编码器失败，尝试使用AVC", e);
                // 如果HEVC失败，尝试使用AVC
                encoderFormat = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, segmentBitrate);
                encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);

                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                Log.d(TAG, "分片" + segmentIndex + ": AVC编码器启动成功");
            }

            // 创建解码器
            String mimeType = videoFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mimeType);
            decoder.configure(videoFormat, null, null, 0);
            decoder.start();
            Log.d(TAG, "分片" + segmentIndex + ": 解码器启动成功");

            // 创建复用器
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(TAG, "分片" + segmentIndex + ": 复用器创建成功");

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;
            boolean decoderOutputEos = false;
            int outVideoTrack = -1;
            int frameCount = 0;
            long segmentStartPts = -1;
            long startProcessingTime = System.currentTimeMillis();

            // 主循环 - 简化版本，避免队列问题
            while (!outputEos) {
                // 1. 向解码器输入数据
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(buffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                            Log.d(TAG, "分片" + segmentIndex + ": 解码器输入EOS");
                        } else {
                            long pts = extractor.getSampleTime();

                            // 检查是否超过分片结束时间
                            if (pts >= endTime) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                                Log.d(TAG, "分片" + segmentIndex + ": 到达分片结束时间");
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                // 2. 从解码器获取输出
                if (!decoderOutputEos) {
                    int decIndex = decoder.dequeueOutputBuffer(decInfo, 10000);
                    if (decIndex >= 0) {
                        if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderOutputEos = true;
                            Log.d(TAG, "分片" + segmentIndex + ": 解码器输出EOS");
                            // 解码器结束，通知编码器
                            int encInIndex = encoder.dequeueInputBuffer(10000);
                            if (encInIndex >= 0) {
                                encoder.queueInputBuffer(encInIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        } else if (decInfo.size > 0) {
                            // 检查时间戳是否在分片范围内
                            if (decInfo.presentationTimeUs >= startTime &&
                                    decInfo.presentationTimeUs < endTime) {

                                // 准备编码器输入
                                int encInIndex = encoder.dequeueInputBuffer(10000);
                                if (encInIndex >= 0) {
                                    ByteBuffer encoderInput = encoder.getInputBuffer(encInIndex);
                                    encoderInput.clear();

                                    ByteBuffer decodedFrame = decoder.getOutputBuffer(decIndex);
                                    if (decodedFrame != null) {
                                        decodedFrame.limit(decInfo.offset + decInfo.size);
                                        decodedFrame.position(decInfo.offset);
                                        encoderInput.put(decodedFrame);

                                        long adjustedPts = decInfo.presentationTimeUs - startTime;
                                        if (segmentStartPts < 0) {
                                            segmentStartPts = adjustedPts;
                                        }

                                        encoder.queueInputBuffer(encInIndex, 0, decInfo.size,
                                                adjustedPts, 0);

                                        frameCount++;
                                        if (frameCount % 30 == 0) {
                                            Log.d(TAG, String.format("分片%d: 已处理%d帧",
                                                    segmentIndex, frameCount));
                                        }
                                    }
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(decIndex, false);
                    }
                }

                // 3. 从编码器获取输出
                int encIndex = encoder.dequeueOutputBuffer(encInfo, 10000);
                if (encIndex >= 0) {
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 编码器配置数据
                        encoder.releaseOutputBuffer(encIndex, false);

                        // 获取编码器输出格式
                        if (outVideoTrack == -1) {
                            try {
                                MediaFormat newFormat = encoder.getOutputFormat();
                                outVideoTrack = muxer.addTrack(newFormat);
                                muxer.start();
                                Log.d(TAG, "分片" + segmentIndex + ": 编码器输出格式就绪 - " + newFormat);
                            } catch (Exception e) {
                                Log.e(TAG, "分片" + segmentIndex + ": 添加轨道失败", e);
                            }
                        }
                        continue;
                    }

                    if (encInfo.size > 0) {
                        if (outVideoTrack == -1) {
                            try {
                                MediaFormat newFormat = encoder.getOutputFormat();
                                outVideoTrack = muxer.addTrack(newFormat);
                                muxer.start();
                                Log.d(TAG, "分片" + segmentIndex + ": 编码器输出格式就绪 - " + newFormat);
                            } catch (Exception e) {
                                Log.e(TAG, "分片" + segmentIndex + ": 添加轨道失败", e);
                            }
                        }

                        if (outVideoTrack != -1) {
                            ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                            if (encodedData != null) {
                                muxer.writeSampleData(outVideoTrack, encodedData, encInfo);
                                Log.d(TAG, String.format("分片%d: 写入编码帧 %d, pts: %.3fs",
                                        segmentIndex, frameCount, encInfo.presentationTimeUs / 1000000.0));
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encIndex, false);

                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                        Log.d(TAG, "分片" + segmentIndex + ": 编码器输出EOS");
                    }
                }

                // 4. 检查超时
                long processingTime = System.currentTimeMillis() - startProcessingTime;
                if (processingTime > 60000) { // 60秒超时
                    Log.e(TAG, "分片" + segmentIndex + ": 处理超时");
                    break;
                }

                // 5. 检查是否应该退出
                if (decoderOutputEos && inputEos) {
                    // 等待编码器完成
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }

                    // 检查是否还有编码器输出
                    encIndex = encoder.dequeueOutputBuffer(encInfo, 1000);
                    if (encIndex >= 0) {
                        encoder.releaseOutputBuffer(encIndex, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true;
                        }
                    } else if (System.currentTimeMillis() - startProcessingTime > 30000) {
                        // 30秒内无输出，强制退出
                        Log.w(TAG, "分片" + segmentIndex + ": 编码器无输出，强制退出");
                        break;
                    }
                }
            }

            long processingTime = System.currentTimeMillis() - startProcessingTime;
            Log.d(TAG, String.format("分片%d转码完成: %d帧, 处理时间: %.3fs, 输出轨道: %s",
                    segmentIndex, frameCount, processingTime / 1000.0,
                    outVideoTrack != -1 ? "成功" : "失败"));

            return frameCount > 0 && outVideoTrack != -1;

        } catch (Exception e) {
            Log.e(TAG, "分片" + segmentIndex + "转码错误", e);
            return false;
        } finally {
            // 清理资源
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "分片" + segmentIndex + "释放编码器失败", e);
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "分片" + segmentIndex + "释放解码器失败", e);
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "分片" + segmentIndex + "释放复用器失败", e);
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "分片" + segmentIndex + "释放提取器失败", e);
                }
            }
        }
    }

    /**
     * 合并视频分片
     */
    private static boolean mergeVideoSegments(List<File> segments, String outputPath) {
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>();

        try {
            if (segments == null || segments.isEmpty()) {
                Log.e(TAG, "没有视频分片可合并");
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = -1;
            long totalDuration = 0;

            // 首先获取第一个分片的轨道格式
            MediaExtractor firstExtractor = new MediaExtractor();
            firstExtractor.setDataSource(segments.get(0).getAbsolutePath());
            extractors.add(firstExtractor);

            int firstVideoTrack = -1;
            MediaFormat firstFormat = null;

            for (int i = 0; i < firstExtractor.getTrackCount(); i++) {
                MediaFormat format = firstExtractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    firstVideoTrack = i;
                    firstFormat = format;
                    break;
                }
            }

            if (firstFormat == null) {
                Log.e(TAG, "第一个分片没有视频轨道");
                return false;
            }

            videoTrack = muxer.addTrack(firstFormat);
            muxer.start();

            // 合并所有分片
            for (int segIndex = 0; segIndex < segments.size(); segIndex++) {
                File segment = segments.get(segIndex);
                if (!segment.exists() || segment.length() == 0) {
                    Log.e(TAG, "分片" + segIndex + "不存在或为空");
                    continue;
                }

                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(segment.getAbsolutePath());
                extractors.add(extractor);

                // 找到视频轨道
                int trackIndex = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                        trackIndex = i;
                        break;
                    }
                }

                if (trackIndex == -1) {
                    Log.e(TAG, "分片" + segIndex + "没有视频轨道");
                    continue;
                }

                extractor.selectTrack(trackIndex);
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int frameCount = 0;

                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;

                    long pts = extractor.getSampleTime();
                    long adjustedPts = pts + totalDuration;

                    info.set(0, size, adjustedPts, extractor.getSampleFlags());
                    muxer.writeSampleData(videoTrack, buffer, info);
                    extractor.advance();
                    frameCount++;
                }

                // 更新总时长（使用最后一个样本的时间戳+持续时间）
                MediaFormat format = extractor.getTrackFormat(trackIndex);
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    totalDuration += format.getLong(MediaFormat.KEY_DURATION);
                }

                Log.d(TAG, String.format("分片%d合并完成: %d帧, 当前总时长: %.3fs",
                        segIndex, frameCount, totalDuration / 1000000.0));
            }

            muxer.stop();

            Log.d(TAG, "视频分片合并完成，总时长: " + totalDuration / 1000000.0 + "秒");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "mergeVideoSegments error", e);
            return false;
        } finally {
            // 清理资源
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放合并复用器失败", e);
                }
            }
            // 释放所有extractor
            for (MediaExtractor ex : extractors) {
                try {
                    ex.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放extractor失败", e);
                }
            }
        }
    }

    /**
     * 合并音频和视频
     */
    private static boolean mergeAudioAndVideo(String videoPath, String audioPath, String outputPath) {
        MediaMuxer muxer = null;
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;

        try {
            if (videoPath == null || !new File(videoPath).exists()) {
                Log.e(TAG, "视频文件不存在: " + videoPath);
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // 处理视频
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoPath);

            int videoTrackIndex = -1;
            MediaFormat videoFormat = null;
            int muxerVideoTrack = -1;

            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                    muxerVideoTrack = muxer.addTrack(format);
                    break;
                }
            }

            if (videoFormat == null) {
                Log.e(TAG, "合并视频中没有视频轨道");
                return false;
            }

            // 处理音频
            int muxerAudioTrack = -1;
            if (audioPath != null && new File(audioPath).exists()) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audioPath);

                for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                    MediaFormat format = audioExtractor.getTrackFormat(i);
                    if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                        muxerAudioTrack = muxer.addTrack(format);
                        audioExtractor.selectTrack(i);
                        break;
                    }
                }
            }

            muxer.start();

            // 写入视频
            videoExtractor.selectTrack(videoTrackIndex);
            ByteBuffer videoBuffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            int videoFrameCount = 0;

            while (true) {
                int size = videoExtractor.readSampleData(videoBuffer, 0);
                if (size < 0) break;

                long pts = videoExtractor.getSampleTime();
                videoInfo.set(0, size, pts, videoExtractor.getSampleFlags());
                muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoInfo);
                videoExtractor.advance();
                videoFrameCount++;
            }

            Log.d(TAG, "写入视频完成: " + videoFrameCount + "帧");

            // 写入音频
            if (muxerAudioTrack != -1 && audioExtractor != null) {
                ByteBuffer audioBuffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                int audioFrameCount = 0;

                while (true) {
                    int size = audioExtractor.readSampleData(audioBuffer, 0);
                    if (size < 0) break;

                    long pts = audioExtractor.getSampleTime();
                    audioInfo.set(0, size, pts, audioExtractor.getSampleFlags());
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo);
                    audioExtractor.advance();
                    audioFrameCount++;
                }

                Log.d(TAG, "写入音频完成: " + audioFrameCount + "帧");
            }

            muxer.stop();

            Log.d(TAG, "音视频合并完成: " + outputPath);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "mergeAudioAndVideo error", e);
            return false;
        } finally {
            // 清理资源
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放合并复用器失败", e);
                }
            }
            if (videoExtractor != null) {
                try {
                    videoExtractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放视频提取器失败", e);
                }
            }
            if (audioExtractor != null) {
                try {
                    audioExtractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放音频提取器失败", e);
                }
            }
        }
    }

    /**
     * 清理临时文件
     */
    private static void cleanupTempFiles(List<File> videoSegments, File mergedVideoFile, File audioFile) {
        if (videoSegments != null) {
            for (File segment : videoSegments) {
                if (segment != null && segment.exists()) {
                    try {
                        boolean deleted = segment.delete();
                        if (!deleted) {
                            Log.w(TAG, "无法删除临时文件: " + segment.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "删除临时文件失败", e);
                    }
                }
            }
        }

        if (mergedVideoFile != null && mergedVideoFile.exists()) {
            try {
                boolean deleted = mergedVideoFile.delete();
                if (!deleted) {
                    Log.w(TAG, "无法删除合并视频文件: " + mergedVideoFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "删除合并视频文件失败", e);
            }
        }

        if (audioFile != null && audioFile.exists()) {
            try {
                boolean deleted = audioFile.delete();
                if (!deleted) {
                    Log.w(TAG, "无法删除音频文件: " + audioFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "删除音频文件失败", e);
            }
        }
    }

    /**
     * 获取应用缓存目录
     */
    public static File getCacheDir() {
        File dir = new File("/storage/emulated/0/Android/data/org.video/cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

}

