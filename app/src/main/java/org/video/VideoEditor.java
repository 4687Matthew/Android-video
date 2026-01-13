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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
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
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    videoTrack = i;
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
                return compressParallelWithOriginalAudio(src, dst, ratio, videoTrack, duration);
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
    public static boolean compressParallelWithOriginalAudio(String src, String dst, double ratio,
                                                            int videoTrack, long duration) {
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

            int audioTrack = -1;
            MediaFormat videoFormat = null, audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
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
            int availableCores = Runtime.getRuntime().availableProcessors();
            Log.d(TAG, "可用处理器核心数: " + availableCores);

            // 大视频（超过5分钟）使用较少线程，避免内存不足
            int maxThreads;
            if (duration > 5 * 60 * 1000000L) { // 超过5分钟
                maxThreads = Math.max(2, availableCores / 2);
            } else {
                maxThreads = Math.min(4, availableCores - 1);
            }
            Log.d(TAG, "最大线程数: " + maxThreads);

            // 4. 查找关键帧位置
            Log.d(TAG, "开始查找关键帧位置...");
            List<Long> keyFramePositions = findKeyFramePositions(src, videoTrack, duration);

// 检查关键帧数量是否足够
            if (keyFramePositions.size() < 2) {
                Log.e(TAG, "关键帧数量不足，无法进行并行转码");
                return false;
            }

// 特别处理：如果关键帧数量太少，使用备用策略
            if (keyFramePositions.size() <= 5) {
                Log.w(TAG, "关键帧数量较少，可能影响分片效果。找到关键帧: " + keyFramePositions.size());

                // 尝试手动查找关键帧：在固定时间间隔采样
                keyFramePositions.clear();
                keyFramePositions.add(0L);

                // 每隔30秒采样一次
                long sampleInterval = 30 * 1000000L; // 30秒
                for (long t = sampleInterval; t < duration; t += sampleInterval) {
                    keyFramePositions.add(t);
                }

                keyFramePositions.add(duration);
                Log.w(TAG, "使用采样关键帧: " + keyFramePositions.size() + " 个");
            }

// ===============================================
// 5. 基于时长划分分片（确保短视频也能分片）
// ===============================================
            List<Long> segmentStarts = new ArrayList<>();
            List<Long> segmentEnds = new ArrayList<>();

// 5.1 设置分片参数
            long maxSegmentDuration = 5 * 60 * 1000000L; // 最大分片时长：5分钟
            long minSegmentDuration = 30 * 1000000L;     // 最小分片时长：30秒

// 5.2 确定分片数量 = 线程数，但不能超过关键帧数量
            int targetSegments = Math.min(maxThreads, Math.max(1, keyFramePositions.size() - 1));
            if (targetSegments < 2) {
                Log.w(TAG, "分片数少于2，退回到单线程转码");
                // 清理音频文件
                if (audioFile != null && audioFile.exists()) {
                    audioFile.delete();
                }
                // 调用单线程转码
                return compressToHevcSingleThread(src, dst, ratio);
            }

            long targetSegmentDuration = duration / targetSegments;

            Log.d(TAG, String.format("分片策略: 时长%.1fs, 线程数=%d, 目标分片数=%d, 目标分片时长%.1fs",
                    duration / 1000000.0, maxThreads, targetSegments, targetSegmentDuration / 1000000.0));

// 5.3 如果关键帧数量不足，使用等分策略
            if (keyFramePositions.size() < targetSegments * 2) {
                Log.w(TAG, "关键帧数量不足，使用等分策略");
                // 等分视频
                for (int i = 0; i < targetSegments; i++) {
                    long segmentStart = i * targetSegmentDuration;
                    long segmentEnd = (i == targetSegments - 1) ? duration : (i + 1) * targetSegmentDuration;

                    segmentStarts.add(segmentStart);
                    segmentEnds.add(segmentEnd);

                    Log.d(TAG, String.format("等分分片%d: %.3fs - %.3fs (时长: %.3fs)",
                            i, segmentStart / 1000000.0, segmentEnd / 1000000.0,
                            (segmentEnd - segmentStart) / 1000000.0));
                }
            } else {
                // 关键帧数量足够，使用关键帧分片
                // 按关键帧数量等分
                int keyFramesPerSegment = (keyFramePositions.size() - 1) / targetSegments;

                for (int i = 0; i < targetSegments; i++) {
                    int startIndex = i * keyFramesPerSegment;
                    int endIndex = (i == targetSegments - 1) ? keyFramePositions.size() - 1 : (i + 1) * keyFramesPerSegment;

                    long segmentStart = keyFramePositions.get(startIndex);
                    long segmentEnd = keyFramePositions.get(endIndex);

                    // 确保分片时长合理
                    long segmentLength = segmentEnd - segmentStart;
                    if (segmentLength < minSegmentDuration && i < targetSegments - 1) {
                        // 分片太短，尝试合并到下一个分片
                        Log.d(TAG, String.format("分片%d太短(%.1fs)，跳过", i, segmentLength / 1000000.0));
                        continue;
                    }

                    segmentStarts.add(segmentStart);
                    segmentEnds.add(segmentEnd);

                    Log.d(TAG, String.format("关键帧分片%d: %.3fs - %.3fs (时长: %.3fs, 关键帧: %d-%d)",
                            i, segmentStart / 1000000.0, segmentEnd / 1000000.0,
                            segmentLength / 1000000.0, startIndex, endIndex));
                }
            }

// 5.4 如果分片数量不足，调整
            int actualSegmentCount = segmentStarts.size();
            if (actualSegmentCount < 2) {
                Log.w(TAG, "有效分片数不足，退回到单线程转码");
                // 清理音频文件
                if (audioFile != null && audioFile.exists()) {
                    audioFile.delete();
                }
                // 调用单线程转码
                return compressToHevcSingleThread(src, dst, ratio);
            }

            Log.d(TAG, "最终使用 " + actualSegmentCount + " 个分片进行并行转码");
// 线程池大小 = 分片数
            int threadPoolSize = actualSegmentCount;
            Log.d(TAG, "设置线程池大小为: " + threadPoolSize);

            // 6. 并行转码每个分片
            executor = Executors.newFixedThreadPool(threadPoolSize);
            final int finalTargetBitrate = targetBitrate;
            final MediaFormat finalVideoFormat = videoFormat;

            for (int i = 0; i < actualSegmentCount; i++) {  // 修改为使用 actualSegmentCount
                final int finalSegmentIndex = i;  // 移除重复声明
                final long startTime = segmentStarts.get(i);
                final long endTime = segmentEnds.get(i);

                // 记录每个分片的详细信息
                long segmentDuration = endTime - startTime;
                Log.d(TAG, String.format("提交分片%d: [%.1fs-%.1fs] 时长: %.1fs",
                        finalSegmentIndex, startTime/1000000.0, endTime/1000000.0, segmentDuration/1000000.0));

                futures.add(executor.submit(() -> {
                    // 重试机制：最多尝试3次
                    for (int retry = 0; retry < 3; retry++) {
                        try {
                            File tempFile = File.createTempFile(
                                    "video_segment_" + finalSegmentIndex + "_",
                                    ".mp4",
                                    getCacheDir()
                            );

                            if (retry > 0) {
                                Log.w(TAG, String.format("分片%d第%d次重试", finalSegmentIndex, retry + 1));
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                            }

                            boolean success = transcodeSegment(
                                    src, tempFile.getAbsolutePath(),
                                    startTime, endTime,
                                    finalTargetBitrate,
                                    finalVideoFormat,
                                    finalSegmentIndex
                            );

                            // 验证生成的视频文件
                            if (success && tempFile.exists() && tempFile.length() > 100 * 1024) {
                                Log.d(TAG, String.format("分片%d处理成功, 大小: %.1fMB, 时长: %.1fs",
                                        finalSegmentIndex, tempFile.length() / (1024.0 * 1024.0),
                                        (endTime - startTime)/1000000.0));
                                return tempFile;
                            } else {
                                Log.e(TAG, String.format("分片%d处理失败: success=%s, exists=%s, size=%d",
                                        finalSegmentIndex, success, tempFile.exists(), tempFile.length()));
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }

                                if (retry == 2) {
                                    return null;
                                }
                                Thread.sleep(1000 * (retry + 1));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "分片" + finalSegmentIndex + "异常: " + e.getMessage(), e);
                            if (retry == 2) {
                                return null;
                            }
                            Thread.sleep(1000 * (retry + 1));
                        }
                    }
                    return null;
                }));
            }

            // 7. 等待所有分片完成，设置超时
            boolean allSegmentsSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    // 设置超时：每个分片最多处理原始视频时长的2倍
                    long timeout = (long)(duration * 2.0 / actualSegmentCount / 1000); // 修改为 actualSegmentCount
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

            // 8. 合并视频分片
            mergedVideoFile = File.createTempFile("merged_video_", ".mp4", getCacheDir());

            boolean mergeSuccess = mergeVideoSegments(videoSegments, mergedVideoFile.getAbsolutePath(), segmentStarts);
            if (!mergeSuccess) {
                Log.e(TAG, "合并视频分片失败");
                cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);
                return false;
            }

            Log.d(TAG, "视频分片合并成功，大小: " + mergedVideoFile.length() / 1024 + "KB");

            // 9. 合并音频和视频
            boolean finalMergeSuccess = mergeAudioAndVideo(
                    mergedVideoFile.getAbsolutePath(),
                    audioFile != null ? audioFile.getAbsolutePath() : null,
                    dst
            );

            // 10. 清理临时文件
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
     * 查找最接近指定时间的关键帧
     */
    private static long findClosestKeyFrame(List<Long> keyFrames, long targetTime) {
        if (keyFrames == null || keyFrames.isEmpty()) {
            return targetTime;
        }

        // 如果目标时间小于等于第一个关键帧，返回第一个关键帧
        if (targetTime <= keyFrames.get(0)) {
            return keyFrames.get(0);
        }

        // 如果目标时间大于等于最后一个关键帧，返回最后一个关键帧
        if (targetTime >= keyFrames.get(keyFrames.size() - 1)) {
            return keyFrames.get(keyFrames.size() - 1);
        }

        // 二分查找最接近的关键帧
        int left = 0;
        int right = keyFrames.size() - 1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            long midTime = keyFrames.get(mid);

            if (midTime == targetTime) {
                return midTime;
            } else if (midTime < targetTime) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }

        // left 指向第一个大于targetTime的关键帧，right指向最后一个小于targetTime的关键帧
        if (left >= keyFrames.size()) {
            return keyFrames.get(keyFrames.size() - 1);
        }
        if (right < 0) {
            return keyFrames.get(0);
        }

        long leftTime = keyFrames.get(left);
        long rightTime = keyFrames.get(right);

        return (Math.abs(leftTime - targetTime) < Math.abs(rightTime - targetTime))
                ? leftTime : rightTime;
    }

    /**
     * 查找下一个关键帧（大于指定时间的最小关键帧）
     */
    private static long findNextKeyFrame(List<Long> keyFrames, long targetTime) {
        if (keyFrames == null || keyFrames.isEmpty()) {
            return targetTime;
        }

        // 如果目标时间小于第一个关键帧，返回第一个关键帧
        if (targetTime < keyFrames.get(0)) {
            return keyFrames.get(0);
        }

        // 如果目标时间大于等于最后一个关键帧，返回最后一个关键帧
        if (targetTime >= keyFrames.get(keyFrames.size() - 1)) {
            return keyFrames.get(keyFrames.size() - 1);
        }

        // 二分查找
        int left = 0;
        int right = keyFrames.size() - 1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            long midTime = keyFrames.get(mid);

            if (midTime > targetTime) {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }

        // left 指向第一个大于targetTime的关键帧
        if (left < keyFrames.size()) {
            return keyFrames.get(left);
        }

        // 没有找到更大的关键帧，返回最后一个
        return keyFrames.get(keyFrames.size() - 1);
    }

    /**
     * 查找视频中的关键帧位置
     * @param dataSource 视频文件路径
     * @param videoTrack 视频轨道索引
     * @param duration 视频总时长（微秒）
     * @return 关键帧位置列表（微秒）
     */
    private static List<Long> findKeyFramePositions(String dataSource, int videoTrack, long duration) throws IOException {
        List<Long> keyFrames = new ArrayList<>();
        MediaExtractor extractor = null;

        try {
            Log.d(TAG, String.format("开始查找关键帧位置，视频时长: %.1f秒", duration / 1000000.0));

            // 总是添加开始和结束位置
            keyFrames.add(0L);
            keyFrames.add(duration);

            // 对于长视频，使用时间间隔采样法
            if (duration > 30 * 1000000L) { // 超过30秒的视频
                extractor = new MediaExtractor();
                extractor.setDataSource(dataSource);
                extractor.selectTrack(videoTrack);

                // 根据视频长度确定采样间隔
                long sampleInterval;
                if (duration > 60 * 60 * 1000000L) { // 超过1小时
                    sampleInterval = 30 * 1000000L; // 每30秒采样一次
                } else if (duration > 30 * 60 * 1000000L) { // 30分钟-1小时
                    sampleInterval = 20 * 1000000L; // 每20秒采样一次
                } else if (duration > 10 * 60 * 1000000L) { // 10-30分钟
                    sampleInterval = 15 * 1000000L; // 每15秒采样一次
                } else if (duration > 5 * 60 * 1000000L) { // 5-10分钟
                    sampleInterval = 10 * 1000000L; // 每10秒采样一次
                } else { // 30秒-5分钟
                    sampleInterval = 5 * 1000000L; // 每5秒采样一次
                }

                Log.d(TAG, String.format("使用时间间隔采样法，采样间隔: %.1f秒", sampleInterval / 1000000.0));

                // 从开始位置采样
                long currentTime = 0;
                while (currentTime < duration) {
                    // 尝试seek到当前时间
                    extractor.seekTo(currentTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    long sampleTime = extractor.getSampleTime();

                    if (sampleTime >= 0) {
                        // 避免重复添加
                        boolean alreadyExists = false;
                        for (Long existing : keyFrames) {
                            if (Math.abs(existing - sampleTime) < 100000) { // 0.1秒内认为是重复
                                alreadyExists = true;
                                break;
                            }
                        }

                        if (!alreadyExists && sampleTime > 0 && sampleTime < duration) {
                            keyFrames.add(sampleTime);
                        }
                    }

                    currentTime += sampleInterval;

                    // 显示进度
                    if ((currentTime / sampleInterval) % 20 == 0) {
                        Log.v(TAG, String.format("采样进度: %.1f%%", (currentTime * 100.0) / duration));
                    }
                }
            }

            // 按时间排序
            Collections.sort(keyFrames);

            Log.d(TAG, String.format("关键帧查找完成，共找到%d个位置", keyFrames.size()));

            // 输出前10个位置
            int showCount = Math.min(10, keyFrames.size());
            for (int i = 0; i < showCount; i++) {
                Log.d(TAG, String.format("位置%d: %.3fs", i, keyFrames.get(i)/1000000.0));
            }

            return keyFrames;

        } catch (Exception e) {
            Log.e(TAG, "findKeyFramePositions error", e);
            // 返回一个基本的位置列表（开始和结束，加上一些中间点）
            List<Long> fallbackPositions = new ArrayList<>();
            fallbackPositions.add(0L);

            // 添加一些中间位置
            if (duration > 60 * 1000000L) { // 超过1分钟
                long interval = duration / 10; // 分成10段
                for (int i = 1; i < 10; i++) {
                    fallbackPositions.add(i * interval);
                }
            }

            fallbackPositions.add(duration);
            Log.w(TAG, String.format("使用备用位置列表，共%d个位置", fallbackPositions.size()));
            return fallbackPositions;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放extractor失败", e);
                }
            }
        }
    }

    /**
     * 合并视频分片 - 简化版（分片已有正确时间戳）
     */
    private static boolean mergeVideoSegments(List<File> segments, String outputPath,
                                              List<Long> segmentStarts) { // 新增参数：分片全局起始时间
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>();

        try {
            if (segments == null || segments.isEmpty() ||
                    segmentStarts == null || segments.size() != segmentStarts.size()) {
                Log.e(TAG, "分片数据无效");
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = -1;
            boolean trackAdded = false;
            int totalFrames = 0;
            long minPts = Long.MAX_VALUE;
            long maxPts = Long.MIN_VALUE;

            // 按顺序合并所有分片
            for (int segIndex = 0; segIndex < segments.size(); segIndex++) {
                File segment = segments.get(segIndex);
                long segmentStartTime = segmentStarts.get(segIndex); // 获取该分片的全局起始时间

                if (!segment.exists() || segment.length() == 0) {
                    Log.w(TAG, "分片" + segIndex + "无效，跳过合并");
                    continue;
                }

                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(segment.getAbsolutePath());
                extractors.add(extractor);

                // 找到视频轨道
                int trackIndex = -1;
                MediaFormat trackFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                        trackIndex = i;
                        trackFormat = format;
                        break;
                    }
                }

                if (trackIndex == -1) {
                    Log.e(TAG, "分片" + segIndex + "没有视频轨道");
                    continue;
                }

                extractor.selectTrack(trackIndex);

                // 如果是第一个有效分片，添加轨道到复用器
                if (!trackAdded && trackFormat != null) {
                    videoTrack = muxer.addTrack(trackFormat);
                    muxer.start();
                    trackAdded = true;
                    Log.d(TAG, String.format("视频轨道已添加，分片%d起始时间: %.3fs",
                            segIndex, segmentStartTime/1000000.0));
                }

                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int frameCount = 0;

                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;

                    long relativePts = extractor.getSampleTime(); // 分片内的相对时间戳
                    int flags = extractor.getSampleFlags();

                    // 关键修复：将相对时间戳映射回全局时间戳
                    long globalPts = segmentStartTime + relativePts;

                    // 记录全局时间戳范围
                    if (globalPts < minPts) minPts = globalPts;
                    if (globalPts > maxPts) maxPts = globalPts;

                    info.set(0, size, globalPts, flags);

                    try {
                        muxer.writeSampleData(videoTrack, buffer, info);
                        frameCount++;
                        totalFrames++;

                        if (frameCount % 100 == 0) {
                            Log.v(TAG, String.format("分片%d: 第%d帧, 相对PTS=%.3fs, 全局PTS=%.3fs",
                                    segIndex, frameCount, relativePts/1000000.0,
                                    globalPts/1000000.0));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "分片" + segIndex + "写入失败", e);
                        break;
                    }

                    extractor.advance();
                }

                Log.d(TAG, String.format("分片%d合并完成: %d帧, 全局PTS范围: [%.3fs - %.3fs]",
                        segIndex, frameCount, segmentStartTime/1000000.0,
                        maxPts/1000000.0));
            }

            muxer.stop();

            Log.d(TAG, String.format("视频合并完成: 总%d帧, 全局时间范围: [%.3fs - %.3fs], 时长: %.3fs",
                    totalFrames, minPts/1000000.0, maxPts/1000000.0,
                    (maxPts - minPts)/1000000.0));

            return true;

        } catch (Exception e) {
            Log.e(TAG, "mergeVideoSegments error", e);
            return false;
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Exception e) {
                    Log.e(TAG, "释放合并复用器失败", e);
                }
            }
            for (MediaExtractor ex : extractors) {
                try { ex.release(); } catch (Exception e) {
                    Log.e(TAG, "释放extractor失败", e);
                }
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
     * 转码单个视频分片 - 简化稳定版（去除复杂的时间戳处理）
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
            Log.d(TAG, String.format("分片%d: 开始处理 [%.3fs-%.3fs]",
                    segmentIndex, startTime/1000000.0, endTime/1000000.0));

            // 1. 创建Extractor
            extractor = new MediaExtractor();
            extractor.setDataSource(src);

            // 找到视频轨道
            int videoTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    videoTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "分片" + segmentIndex + ": 未找到视频轨道");
                return false;
            }

            extractor.selectTrack(videoTrackIndex);

            // 2. 定位到起始位置（使用SEEK_TO_CLOSEST_SYNC）
            extractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            long actualStartTime = extractor.getSampleTime();
            if (actualStartTime < 0) actualStartTime = startTime;

            Log.d(TAG, String.format("分片%d: 实际起始时间 %.3fs",
                    segmentIndex, actualStartTime/1000000.0));

            // 3. 获取视频参数
            int width = originalFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = originalFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = originalFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? originalFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            // 4. 配置编码器（使用AVC，兼容性更好）
            MediaFormat encoderFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 尝试AVC编码器
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            } catch (Exception e) {
                Log.e(TAG, "AVC编码器创建失败，尝试HEVC", e);
                encoderFormat = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
                encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            }

            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // 5. 创建解码器
            String mimeType = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mimeType);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // 6. 创建Muxer
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // 7. 转码循环
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;
            boolean muxerStarted = false;
            int muxerVideoTrack = -1;
            int frameCount = 0;
            long presentationTime = 0;
            long timeStep = 1000000L / frameRate; // 基于帧率的时间间隔

            while (!outputEos) {
                // 向解码器输入数据
                if (!inputEos) {
                    int inputIdx = decoder.dequeueInputBuffer(10000);
                    if (inputIdx >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inputIdx);
                        int sampleSize = extractor.readSampleData(buffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            long pts = extractor.getSampleTime();

                            // 检查是否超过分片结束时间
                            if (pts >= endTime) {
                                decoder.queueInputBuffer(inputIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            } else {
                                decoder.queueInputBuffer(inputIdx, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                // 解码器输出
                int decoderIdx = decoder.dequeueOutputBuffer(decInfo, 10000);
                if (decoderIdx >= 0) {
                    if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 通知编码器输入结束
                        int encoderIdx = encoder.dequeueInputBuffer(10000);
                        if (encoderIdx >= 0) {
                            encoder.queueInputBuffer(encoderIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    } else if (decInfo.size > 0) {
                        long originalPts = decInfo.presentationTimeUs;

                        // 检查是否在分片时间范围内
                        if (originalPts >= actualStartTime && originalPts < endTime) {
                            // 编码器输入
                            int encoderIdx = encoder.dequeueInputBuffer(10000);
                            if (encoderIdx >= 0) {
                                ByteBuffer encoderBuffer = encoder.getInputBuffer(encoderIdx);
                                encoderBuffer.clear();

                                ByteBuffer decodedFrame = decoder.getOutputBuffer(decoderIdx);
                                if (decodedFrame != null) {
                                    decodedFrame.position(decInfo.offset);
                                    decodedFrame.limit(decInfo.offset + decInfo.size);
                                    encoderBuffer.put(decodedFrame);

                                    // 使用递增的时间戳，避免负数问题
                                    encoder.queueInputBuffer(encoderIdx, 0,
                                            decInfo.size, presentationTime, 0);
                                    presentationTime += timeStep;
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(decoderIdx, false);
                }

                // 编码器输出
                int encoderIdx = encoder.dequeueOutputBuffer(encInfo, 10000);
                if (encoderIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 获取输出格式
                    MediaFormat outputFormat = encoder.getOutputFormat();
                    muxerVideoTrack = muxer.addTrack(outputFormat);
                    muxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "分片" + segmentIndex + ": Muxer启动");
                } else if (encoderIdx >= 0) {
                    if (encInfo.size > 0 && muxerStarted) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encoderIdx);

                        // 确保时间戳非负
                        if (encInfo.presentationTimeUs < 0) {
                            encInfo.presentationTimeUs = 0;
                        }

                        muxer.writeSampleData(muxerVideoTrack, encodedData, encInfo);
                        frameCount++;

                        if (frameCount % 100 == 0) {
                            Log.v(TAG, String.format("分片%d: 已编码%d帧", segmentIndex, frameCount));
                        }
                    }

                    encoder.releaseOutputBuffer(encoderIdx, false);

                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                    }
                }
            }

            Log.d(TAG, String.format("分片%d: 完成 %d帧", segmentIndex, frameCount));
            return frameCount > 0;

        } catch (Exception e) {
            Log.e(TAG, "分片" + segmentIndex + "转码失败", e);
            return false;
        } finally {
            // 清理资源
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception e) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception e) {}
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception e) {}
            try { if (extractor != null) { extractor.release(); } } catch (Exception e) {}
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

