package org.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class VideoEditor {
    private static final String TAG = "VideoEditor";
    private static final int TIMEOUT_US = 10000;

    /**
     * 帧数计数器回调接口
     */
    interface FrameCounterCallback {
        void onFramesEncoded(int frames);
    }

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
     * 合并视频（带进度回调）- 改进版，确保流畅度
     */
    public static boolean mergeVideosWithProgress(String[] inputPaths, String outputPath,
                                                  MainActivity.ProgressCallback callback) {
        List<MediaExtractor> extractors = new ArrayList<>();
        MediaMuxer muxer = null;

        try {
            updateStatus(callback, "开始合并视频...", 5);

            // 1. 验证所有输入文件
            long totalSize = 0;
            List<File> inputFiles = new ArrayList<>();
            for (String path : inputPaths) {
                File file = new File(path);
                if (!file.exists()) {
                    updateStatus(callback, "文件不存在: " + file.getName(), 0);
                    return false;
                }
                inputFiles.add(file);
                totalSize += file.length();
            }

            // 2. 分析第一个视频的格式作为模板
            updateStatus(callback, "分析视频格式...", 10);
            MediaExtractor firstExtractor = new MediaExtractor();
            firstExtractor.setDataSource(inputPaths[0]);

            MediaFormat videoFormat = null, audioFormat = null;
            int videoTrackIndex = -1, audioTrackIndex = -1;

            for (int i = 0; i < firstExtractor.getTrackCount(); i++) {
                MediaFormat format = firstExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/") && videoFormat == null) {
                    videoFormat = format;
                    videoTrackIndex = i;
                    Log.d(TAG, String.format("视频格式: %dx%d, 帧率: %d, 码率: %d",
                            format.getInteger(MediaFormat.KEY_WIDTH),
                            format.getInteger(MediaFormat.KEY_HEIGHT),
                            format.getInteger(MediaFormat.KEY_FRAME_RATE),
                            format.getInteger(MediaFormat.KEY_BIT_RATE)));
                } else if (mime.startsWith("audio/") && audioFormat == null) {
                    audioFormat = format;
                    audioTrackIndex = i;
                    Log.d(TAG, "音频格式: " + format);
                }
            }

            if (videoFormat == null) {
                updateStatus(callback, "未找到视频轨道", 0);
                firstExtractor.release();
                return false;
            }

            // 3. 检查所有视频的格式一致性
            updateStatus(callback, "检查视频格式一致性...", 15);
            boolean allFormatsCompatible = true;
            List<String> formatWarnings = new ArrayList<>();

            for (int i = 1; i < inputPaths.length; i++) {
                MediaExtractor checkExtractor = new MediaExtractor();
                checkExtractor.setDataSource(inputPaths[i]);

                for (int j = 0; j < checkExtractor.getTrackCount(); j++) {
                    MediaFormat format = checkExtractor.getTrackFormat(j);
                    String mime = format.getString(MediaFormat.KEY_MIME);

                    if (mime.startsWith("video/")) {
                        int width = format.getInteger(MediaFormat.KEY_WIDTH);
                        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        int refWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
                        int refHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

                        if (width != refWidth || height != refHeight) {
                            formatWarnings.add(String.format("视频%d: 分辨率不一致 (%dx%d vs %dx%d)",
                                    i, width, height, refWidth, refHeight));
                            allFormatsCompatible = false;
                        }

                        // 检查编码格式
                        String refMime = videoFormat.getString(MediaFormat.KEY_MIME);
                        if (!mime.equals(refMime)) {
                            formatWarnings.add(String.format("视频%d: 编码格式不一致 (%s vs %s)",
                                    i, mime, refMime));
                            allFormatsCompatible = false;
                        }
                    }
                }
                checkExtractor.release();
            }

            if (!allFormatsCompatible) {
                Log.w(TAG, "视频格式不完全一致，可能出现播放问题");
                for (String warning : formatWarnings) {
                    Log.w(TAG, warning);
                }
            }

            // 4. 创建复用器
            updateStatus(callback, "创建输出文件...", 20);
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = audioFormat == null ? -1 : muxer.addTrack(audioFormat);

            muxer.start();

            // 5. 准备缓冲区
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // 6. 关键改进：使用更精确的时间戳处理
            long timeOffset = 0;
            long processedSize = 0;
            int totalFrames = 0;

            // 7. 逐文件处理，确保时间戳连续
            for (int fileIndex = 0; fileIndex < inputPaths.length; fileIndex++) {
                String path = inputPaths[fileIndex];
                File currentFile = inputFiles.get(fileIndex);

                updateStatus(callback, String.format("处理第%d/%d个视频: %s",
                                fileIndex + 1, inputPaths.length, currentFile.getName()),
                        20 + (fileIndex * 70 / inputPaths.length));

                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(path);
                extractors.add(extractor);

                // 查找轨道
                int vIdx = -1, aIdx = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/") && vIdx == -1) {
                        vIdx = i;
                    } else if (mime.startsWith("audio/") && aIdx == -1) {
                        aIdx = i;
                    }
                }

                // 关键改进：先处理视频，记录关键信息
                long fileStartTime = -1;
                long videoDuration = 0;
                int videoFrames = 0;

                if (vIdx != -1) {
                    extractor.selectTrack(vIdx);

                    // 获取视频信息
                    MediaFormat trackFormat = extractor.getTrackFormat(vIdx);
                    if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDuration = trackFormat.getLong(MediaFormat.KEY_DURATION);
                    }

                    // 确保从关键帧开始
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;

                        long pts = extractor.getSampleTime();
                        if (fileStartTime < 0) {
                            fileStartTime = pts;
                        }

                        // 关键改进：确保时间戳递增且连续
                        long adjustedPts = timeOffset + (pts - fileStartTime);

                        buffer.position(0);
                        buffer.limit(size);

                        info.set(0, size, adjustedPts,
                                convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                        muxer.writeSampleData(muxerVideoTrack, buffer, info);
                        extractor.advance();

                        videoFrames++;
                        processedSize += size;

                        // 更新进度
                        if (totalSize > 0) {
                            int progress = 20 + (int)((processedSize * 70.0) / totalSize);
                            progress = Math.min(progress, 90);

                            if (videoFrames % 30 == 0) { // 每30帧更新一次
                                updateStatus(callback,
                                        String.format("第%d个视频: 已处理 %d 帧", fileIndex + 1, videoFrames),
                                        progress);
                            }
                        }
                    }

                    totalFrames += videoFrames;
                    Log.d(TAG, String.format("视频%d: 处理了%d帧，持续时间%.2fs",
                            fileIndex + 1, videoFrames, videoDuration/1000000.0));
                }

                // 处理音频轨道
                if (aIdx != -1 && muxerAudioTrack != -1) {
                    extractor.unselectTrack(vIdx);
                    extractor.selectTrack(aIdx);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    long audioStartTime = -1;
                    int audioFrames = 0;

                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;

                        long pts = extractor.getSampleTime();
                        if (audioStartTime < 0) {
                            audioStartTime = pts;
                        }

                        // 关键改进：音频时间戳也要正确偏移
                        long adjustedPts = timeOffset + (pts - audioStartTime);

                        buffer.position(0);
                        buffer.limit(size);

                        info.set(0, size, adjustedPts,
                                convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                        muxer.writeSampleData(muxerAudioTrack, buffer, info);
                        extractor.advance();
                        audioFrames++;

                        processedSize += size;
                    }

                    Log.d(TAG, String.format("音频%d: 处理了%d帧", fileIndex + 1, audioFrames));
                }

                // 关键改进：使用视频的实际时长作为偏移量，而不是format中的时长
                // 这样可以确保时间戳连续
                if (videoDuration > 0) {
                    timeOffset += videoDuration;
                } else {
                    // 如果没有获取到时长，使用估计值
                    timeOffset += 5 * 1000000L; // 假设5秒
                }

                Log.d(TAG, String.format("文件%d处理完成，下一个偏移量: %.2fs",
                        fileIndex + 1, timeOffset/1000000.0));
            }

            muxer.stop();

            updateStatus(callback, String.format("合并完成！共%d帧", totalFrames), 95);

            // 验证输出文件
            File outputFile = new File(outputPath);
            if (outputFile.exists() && outputFile.length() > 0) {
                long outputSize = outputFile.length();
                double compressionRatio = (double) outputSize / totalSize * 100;

                updateStatus(callback,
                        String.format("合并成功！输出大小: %.1fMB (%.1f%%)",
                                outputSize/(1024.0*1024.0), compressionRatio),
                        100);
                return true;
            } else {
                updateStatus(callback, "合并失败：输出文件为空", 0);
                return false;
            }

        } catch (Exception e) {
            updateStatus(callback, "合并视频出错：" + e.getMessage(), 0);
            Log.e(TAG, "mergeVideosWithProgress error", e);
            return false;
        } finally {
            // 清理资源
            for (MediaExtractor ex : extractors) {
                try { ex.release(); } catch (Exception ignored) {}
            }

            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放复用器失败", e);
                }
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
    public static boolean compressVideo(String src, String dst, double ratio, MainActivity.ProgressCallback callback) {
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
                updateStatus(callback, "错误：未找到视频轨道", 0);
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 获取源视频码率
            int srcBitrate = videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 8_000_000;
            int targetBitrate = (int)(srcBitrate * ratio);
            targetBitrate = Math.max(targetBitrate, 500000); // 最小500kbps

            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;

            Log.d(TAG, String.format("视频信息: 时长%.2f秒, 码率%d->%d (压缩比%.2f)",
                    duration / 1000000.0, srcBitrate, targetBitrate, ratio));

            updateStatus(callback, String.format("视频信息：%.1f秒，码率%d->%dkbps",
                    duration / 1000000.0, srcBitrate/1000, targetBitrate/1000), 5);

            // 根据时长智能选择转码方式
            if (duration < 30 * 1000000L) { // 小于30秒
                Log.d(TAG, "视频较短，使用单线程转码");
                updateStatus(callback, "开始单线程转码...", 10);
                return compressToHevcSingleThread(src, dst, ratio, callback, duration);
            } else {
                Log.d(TAG, "视频较长，使用并行转码");
                updateStatus(callback, "开始并行转码...", 10);
                return compressParallelWithOriginalAudio(src, dst, ratio, videoTrack, duration, targetBitrate, callback);
            }
        } catch (Exception e) {
            updateStatus(callback, "压缩出错：" + e.getMessage(), 0);
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
    static boolean compressToHevcSingleThread(String src, String dst, double ratio,
                                              MainActivity.ProgressCallback callback, long duration) {
        MediaExtractor ext = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null, encoder = null;

        try {
            Log.d(TAG, "开始单线程转码: " + src);
            updateStatus(callback, "初始化转码器...", 10);

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
                updateStatus(callback, "错误：未找到视频轨道", 0);
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

            updateStatus(callback, String.format("视频信息: %dx%d, %dfps, 码率: %d->%dkbps",
                    width, height, frameRate, srcBr/1000, dstBr/1000), 20);

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

            updateStatus(callback, "创建编码器...", 25);

            // 4. 创建编解码器
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            encoder.configure(outVideoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            Log.d(TAG, "HEVC编码器已启动");

            decoder = MediaCodec.createDecoderByType(inVideoFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(inVideoFmt, null, null, 0);
            decoder.start();
            Log.d(TAG, "解码器已启动");

            updateStatus(callback, "开始解码视频...", 30);

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
            long lastProgressUpdateTime = System.currentTimeMillis();

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
                            updateStatus(callback, "开始编码视频...", 40);
                        }

                        ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                        if (videoStartTime < 0) {
                            videoStartTime = encInfo.presentationTimeUs;
                        }
                        encInfo.presentationTimeUs -= videoStartTime;

                        muxer.writeSampleData(outVideoTrack, encodedData, encInfo);
                        frameCount++;

                        // 更新进度（每处理5秒或每50帧更新一次）
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastProgressUpdateTime > 2000 || frameCount % 50 == 0) {
                            if (duration > 0) {
                                long processedTime = encInfo.presentationTimeUs + videoStartTime;
                                int progress = 40 + (int)((processedTime * 50.0) / duration);
                                progress = Math.min(progress, 90);
                                updateStatus(callback, String.format("已编码 %d 帧 (%.1f%%)",
                                        frameCount, (processedTime * 100.0) / duration), progress);
                            } else {
                                updateStatus(callback, String.format("已编码 %d 帧", frameCount), -1);
                            }
                            lastProgressUpdateTime = currentTime;
                        }
                    }

                    outputEos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encIndex, false);
                }
            }

            long processingTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, String.format("视频编码完成: %d帧, 耗时: %.2f秒",
                    frameCount, processingTime / 1000.0));

            updateStatus(callback, String.format("视频编码完成: %d帧, 耗时%.1f秒",
                    frameCount, processingTime / 1000.0), 90);

            // 6. 处理音频
            if (outAudioTrack >= 0) {
                updateStatus(callback, "处理音频轨道...", 92);
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

                updateStatus(callback, String.format("音频处理完成: %d帧", audioFrameCount), 95);
            }

            updateStatus(callback, "正在保存文件...", 98);
            Log.d(TAG, "单线程转码完成");
            return true;

        } catch (Exception e) {
            updateStatus(callback, "转码出错：" + e.getMessage(), 0);
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
                                                            int videoTrack, long duration, int targetBitrate, MainActivity.ProgressCallback callback) {
        MediaExtractor extractor = null;
        ExecutorService executor = null;
        List<Future<File>> futures = new ArrayList<>();
        List<File> videoSegments = new ArrayList<>();
        File audioFile = null;
        File mergedVideoFile = null;

        // 添加总进度管理
        AtomicLong totalFramesEncoded = new AtomicLong(0);
        AtomicLong estimatedTotalFrames = new AtomicLong(0);
        List<Long> segmentEstimatedFrames = new ArrayList<>();

        try {
            Log.d(TAG, String.format("开始并行转码: %s, 目标码率: %d kbps", src, targetBitrate/1000));
            updateStatus(callback, "准备并行转码...", 5);

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
                updateStatus(callback, "错误：未找到视频轨道", 0);
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 获取视频信息
            int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            // 估算总帧数
            double totalSeconds = duration / 1000000.0;
            long estimatedFrames = (long)(totalSeconds * frameRate);
            estimatedTotalFrames.set(estimatedFrames);

            updateStatus(callback, String.format("视频信息: %dx%d, %dfps, 时长: %.1fs, 估计总帧数: %d",
                    width, height, frameRate, totalSeconds, estimatedFrames), 10);

            // 2. 提取音频到临时文件
            if (audioTrack >= 0 && audioFormat != null) {
                updateStatus(callback, "提取音频...", 15);
                audioFile = extractAudioToFile(extractor, audioTrack, audioFormat, callback);
                if (audioFile != null) {
                    updateStatus(callback, String.format("音频提取完成: %.1fKB",
                            audioFile.length()/1024.0), 20);
                }
            } else {
                updateStatus(callback, "未找到音频轨道，继续处理视频", 20);
            }

            // 3. 动态计算线程数和分片大小
            int availableCores = Runtime.getRuntime().availableProcessors();
            Log.d(TAG, "可用处理器核心数: " + availableCores);

            // 根据视频时长和核心数确定线程数
            int maxThreads;
            if (duration > 10 * 60 * 1000000L) { // 超过10分钟
                maxThreads = Math.max(2, availableCores / 2);
            } else if (duration > 5 * 60 * 1000000L) { // 5-10分钟
                maxThreads = Math.min(4, availableCores - 1);
            } else { // 30秒-5分钟
                maxThreads = Math.min(3, availableCores);
            }

            // 确保至少2个线程用于并行
            maxThreads = Math.max(2, maxThreads);

            Log.d(TAG, "最大线程数: " + maxThreads);
            updateStatus(callback, String.format("使用%d个线程并行处理", maxThreads), 25);

            // 4. 查找关键帧位置
            updateStatus(callback, "分析视频关键帧...", 30);
            List<Long> keyFramePositions = findKeyFramePositionsEnhanced(src, videoTrack, duration, maxThreads, callback);

            // 5. 划分分片
            List<Long> segmentStarts = new ArrayList<>();
            List<Long> segmentEnds = new ArrayList<>();

            // 确保至少分成2个分片
            int targetSegments = Math.min(maxThreads, Math.max(2, keyFramePositions.size() - 1));

            // 如果关键帧数量足够，使用关键帧分片
            if (keyFramePositions.size() >= targetSegments + 1) {
                // 计算每个分片应包含的关键帧数量
                int keyFramesPerSegment = (keyFramePositions.size() - 1) / targetSegments;

                for (int i = 0; i < targetSegments; i++) {
                    int startIdx = i * keyFramesPerSegment;
                    int endIdx = (i == targetSegments - 1) ? keyFramePositions.size() - 1 :
                            (i + 1) * keyFramesPerSegment;

                    long segmentStart = keyFramePositions.get(startIdx);
                    long segmentEnd = keyFramePositions.get(endIdx);

                    // 确保分片时长合理（至少2秒）
                    long segmentDuration = segmentEnd - segmentStart;
                    if (segmentDuration < 2 * 1000000L) {
                        // 跳过太短的分片，将其合并到下一个分片
                        if (i < targetSegments - 1) {
                            targetSegments--;
                            continue;
                        }
                    }

                    segmentStarts.add(segmentStart);
                    segmentEnds.add(segmentEnd);

                    // 估算分片帧数
                    double segmentSeconds = segmentDuration / 1000000.0;
                    long segmentFrames = (long)(segmentSeconds * frameRate);
                    segmentEstimatedFrames.add(segmentFrames);
                }
            } else {
                // 关键帧不足，使用等时分片
                Log.w(TAG, "关键帧数量不足，使用等时分片策略");
                long segmentDuration = duration / targetSegments;
                for (int i = 0; i < targetSegments; i++) {
                    long segmentStart = i * segmentDuration;
                    long segmentEnd = (i == targetSegments - 1) ? duration : (i + 1) * segmentDuration;

                    segmentStarts.add(segmentStart);
                    segmentEnds.add(segmentEnd);

                    // 估算分片帧数
                    double segmentSeconds = (segmentEnd - segmentStart) / 1000000.0;
                    long segmentFrames = (long)(segmentSeconds * frameRate);
                    segmentEstimatedFrames.add(segmentFrames);
                }
            }

            int actualSegmentCount = segmentStarts.size();
            if (actualSegmentCount < 2) {
                Log.w(TAG, "有效分片数不足，退回到单线程转码");
                updateStatus(callback, "分片数不足，转为单线程转码", 30);
                // 清理音频文件
                if (audioFile != null && audioFile.exists()) {
                    audioFile.delete();
                }
                // 调用单线程转码
                return compressToHevcSingleThread(src, dst, ratio, callback, duration);
            }

            updateStatus(callback, String.format("将视频分为%d个分片进行转码", actualSegmentCount), 35);
            Log.d(TAG, "最终使用 " + actualSegmentCount + " 个分片进行并行转码");

            // 6. 并行转码每个分片
            executor = Executors.newFixedThreadPool(actualSegmentCount);

            // 计算每个分片的码率（平分总码率）
            int segmentBitrate = targetBitrate / actualSegmentCount;
            // 确保每个分片有足够的最小码率
            segmentBitrate = Math.max(segmentBitrate, 300000);

            updateStatus(callback, String.format("开始并行转码，每个分片%d kbps", segmentBitrate/1000), 40);

            // 用于跟踪进度
            AtomicInteger completedSegments = new AtomicInteger(0);
            CountDownLatch segmentLatch = new CountDownLatch(actualSegmentCount);

            // 存储每个分片的已编码帧数
            AtomicLong[] segmentFramesEncoded = new AtomicLong[actualSegmentCount];
            for (int i = 0; i < actualSegmentCount; i++) {
                segmentFramesEncoded[i] = new AtomicLong(0);
            }

            for (int i = 0; i < actualSegmentCount; i++) {
                final int segmentIndex = i;
                final long startTime = segmentStarts.get(i);
                final long endTime = segmentEnds.get(i);
                final int segBitrate = segmentBitrate;
                final long segmentFramesEstimate = segmentEstimatedFrames.get(i);

                MediaFormat finalVideoFormat = videoFormat;
                futures.add(executor.submit(() -> {
                    try {
                        File tempFile = File.createTempFile(
                                "video_segment_" + segmentIndex + "_",
                                ".mp4",
                                getCacheDir()
                        );

                        Log.d(TAG, String.format("分片%d开始转码: [%.1fs-%.1fs], 码率: %dkbps, 估计帧数: %d",
                                segmentIndex, startTime/1000000.0, endTime/1000000.0,
                                segBitrate/1000, segmentFramesEstimate));

                        // 创建分片进度回调
                        MainActivity.ProgressCallback segmentCallback = new MainActivity.ProgressCallback() {
                            @Override
                            public void onProgressUpdate(String status, int segmentProgress) {
                                // 这里可以空着，我们使用统一的进度更新机制
                            }
                        };

                        // 创建帧数回调，用于实时更新帧数
                        FrameCounterCallback frameCounter = new FrameCounterCallback() {
                            @Override
                            public void onFramesEncoded(int frames) {
                                // 更新这个分片的已编码帧数
                                segmentFramesEncoded[segmentIndex].set(frames);

                                // 计算总编码帧数
                                long totalEncoded = 0;
                                for (int j = 0; j < actualSegmentCount; j++) {
                                    totalEncoded += segmentFramesEncoded[j].get();
                                }
                                totalFramesEncoded.set(totalEncoded);

                                // 计算总体进度
                                double progressRatio = 0.0;
                                if (estimatedTotalFrames.get() > 0) {
                                    progressRatio = (double) totalEncoded / estimatedTotalFrames.get();
                                }

                                // 映射到40%-80%的进度范围（并行转码阶段）
                                int baseProgress = 40; // 并行转码从40%开始
                                int progressRange = 40; // 并行转码占40%的进度
                                int overallProgress = baseProgress + (int)(progressRatio * progressRange);
                                overallProgress = Math.min(overallProgress, 80); // 限制在80%

                                // 更新UI
                                updateStatus(callback,
                                        String.format("编码进度: %d/%d 帧 (%.1f%%)",
                                                totalEncoded, estimatedTotalFrames.get(),
                                                progressRatio * 100),
                                        overallProgress);
                            }
                        };

                        boolean success = transcodeSegmentWithFrameCounter(
                                src, tempFile.getAbsolutePath(),
                                startTime, endTime,
                                segBitrate,
                                finalVideoFormat,
                                segmentIndex,
                                segmentCallback,
                                frameCounter
                        );

                        if (success && tempFile.exists() && tempFile.length() > 10 * 1024) {
                            // 验证生成的视频
                            long segDuration = endTime - startTime;
                            long fileSize = tempFile.length();
                            double actualBitrate = (fileSize * 8.0) / (segDuration / 1000000.0);

                            completedSegments.incrementAndGet();
                            updateStatus(callback, String.format("分片%d完成: %.1fKB",
                                            segmentIndex, fileSize/1024.0),
                                    40 + (completedSegments.get() * 40 / actualSegmentCount));

                            Log.d(TAG, String.format("分片%d转码成功: 大小%.1fKB, 时长%.1fs, 实际码率%.1fkbps",
                                    segmentIndex, fileSize/1024.0, segDuration/1000000.0,
                                    actualBitrate/1000.0));
                            return tempFile;
                        } else {
                            Log.e(TAG, String.format("分片%d转码失败", segmentIndex));
                            if (tempFile.exists()) {
                                tempFile.delete();
                            }
                            return null;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "分片" + segmentIndex + "转码异常", e);
                        return null;
                    } finally {
                        segmentLatch.countDown();
                    }
                }));
            }

            // 等待所有分片完成
            try {
                segmentLatch.await(30, TimeUnit.MINUTES); // 最大等待30分钟
            } catch (InterruptedException e) {
                Log.e(TAG, "等待分片完成超时", e);
            }

            // 7. 收集分片结果
            boolean allSegmentsSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    File segmentFile = futures.get(i).get(10, TimeUnit.SECONDS);

                    if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 10 * 1024) {
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

            if (!allSegmentsSuccess || videoSegments.size() != actualSegmentCount) {
                updateStatus(callback, "分片处理失败，终止并行转码", 0);
                Log.e(TAG, "有分片处理失败，终止并行转码");
                cleanupTempFiles(videoSegments, null, audioFile);
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdownNow();
                }
                return false;
            }

            updateStatus(callback, String.format("所有%d个分片转码完成，共%d帧", videoSegments.size(), totalFramesEncoded.get()), 80);
            executor.shutdown();

            // 8. 合并视频分片
            updateStatus(callback, "开始合并视频分片...", 85);
            mergedVideoFile = File.createTempFile("merged_video_", ".mp4", getCacheDir());

            boolean mergeSuccess = mergeVideoSegmentsEnhanced(videoSegments, mergedVideoFile.getAbsolutePath(),
                    segmentStarts, callback);
            if (!mergeSuccess) {
                updateStatus(callback, "合并视频分片失败", 0);
                Log.e(TAG, "合并视频分片失败");
                cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);
                return false;
            }

            // 验证合并后的视频
            long mergedSize = mergedVideoFile.length();
            double mergedBitrate = (mergedSize * 8.0) / (duration / 1000000.0);
            updateStatus(callback, String.format("视频合并完成: %.1fMB", mergedSize/(1024.0*1024.0)), 90);
            Log.d(TAG, String.format("视频分片合并成功: 大小%.1fMB, 实际码率%.1fkbps",
                    mergedSize/(1024.0*1024.0), mergedBitrate/1000.0));

            // 9. 合并音频和视频
            updateStatus(callback, "开始合并音频...", 95);
            boolean finalMergeSuccess = mergeAudioAndVideoEnhanced(
                    mergedVideoFile.getAbsolutePath(),
                    audioFile != null ? audioFile.getAbsolutePath() : null,
                    dst,
                    targetBitrate,
                    callback
            );

            // 10. 最终验证
            if (finalMergeSuccess) {
                File finalFile = new File(dst);
                if (finalFile.exists()) {
                    long finalSize = finalFile.length();
                    double finalBitrate = (finalSize * 8.0) / (duration / 1000000.0);

                    updateStatus(callback, String.format("转码完成！最终大小: %.1fMB，总编码帧数: %d",
                            finalSize/(1024.0*1024.0), totalFramesEncoded.get()), 100);
                    Log.d(TAG, String.format("并行转码完成: 最终大小%.1fMB, 码率%.1fkbps, 压缩比%.2f, 总帧数%d",
                            finalSize/(1024.0*1024.0), finalBitrate/1000.0,
                            (double)finalBitrate / targetBitrate, totalFramesEncoded.get()));
                }
            } else {
                updateStatus(callback, "音频合并失败", 0);
            }

            // 11. 清理临时文件
            cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);

            Log.d(TAG, "并行转码完成，结果: " + finalMergeSuccess);
            return finalMergeSuccess;

        } catch (Exception e) {
            updateStatus(callback, "并行转码出错：" + e.getMessage(), 0);
            Log.e(TAG, "compressParallelWithOriginalAudio error", e);
            // 发生异常时清理临时文件
            cleanupTempFiles(videoSegments, mergedVideoFile, audioFile);
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
            return false;
        } finally {
            try {
                if (extractor != null) extractor.release();
            } catch (Exception e) {
                Log.e(TAG, "释放extractor失败", e);
            }
        }
    }

    private static boolean mergeAudioAndVideoEnhanced(String videoPath, String audioPath,
                                                      String outputPath, int targetBitrate,
                                                      MainActivity.ProgressCallback callback) {
        MediaMuxer muxer = null;
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;

        try {
            if (videoPath == null || !new File(videoPath).exists()) {
                Log.e(TAG, "视频文件不存在: " + videoPath);
                return false;
            }

            Log.d(TAG, "开始合并音视频，目标码率: " + targetBitrate/1000 + "kbps");
            updateStatus(callback, "正在合并音视频...", 95);

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
                        Log.d(TAG, "找到音频轨道: " + format);
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
                if (videoFrameCount % 100 == 0) {
                    updateStatus(callback, String.format("已合并 %d 帧视频", videoFrameCount), 97);
                }
            }

            Log.d(TAG, "写入视频完成: " + videoFrameCount + "帧");
            updateStatus(callback, "视频合并完成，正在合并音频...", 98);

            // 写入音频
            int audioFrameCount = 0;
            if (muxerAudioTrack != -1 && audioExtractor != null) {
                ByteBuffer audioBuffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

                while (true) {
                    int size = audioExtractor.readSampleData(audioBuffer, 0);
                    if (size < 0) break;

                    long pts = audioExtractor.getSampleTime();
                    audioInfo.set(0, size, pts, audioExtractor.getSampleFlags());
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo);
                    audioExtractor.advance();
                    audioFrameCount++;
                    if (audioFrameCount % 100 == 0) {
                        updateStatus(callback, String.format("已合并 %d 帧音频", audioFrameCount), 99);
                    }
                }

                Log.d(TAG, "写入音频完成: " + audioFrameCount + "帧");
            }

            muxer.stop();

            updateStatus(callback, "音视频合并完成", 100);
            Log.d(TAG, "音视频合并完成: " + outputPath);
            return true;

        } catch (Exception e) {
            updateStatus(callback, "合并出错：" + e.getMessage(), 0);
            Log.e(TAG, "mergeAudioAndVideoEnhanced error", e);
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

    private static boolean mergeVideoSegmentsEnhanced(List<File> segments, String outputPath,
                                                      List<Long> segmentStarts, MainActivity.ProgressCallback callback) {
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>();

        try {
            if (segments == null || segments.isEmpty()) {
                Log.e(TAG, "分片列表为空");
                return false;
            }

            Log.d(TAG, "开始合并 " + segments.size() + " 个视频分片");
            updateStatus(callback, String.format("正在合并%d个视频分片...", segments.size()), 85);

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = -1;
            boolean trackAdded = false;
            int totalFrames = 0;
            long totalDuration = 0;

            // 按顺序合并所有分片
            for (int segIndex = 0; segIndex < segments.size(); segIndex++) {
                File segment = segments.get(segIndex);
                long segmentStartTime = segmentStarts.get(segIndex);

                if (!segment.exists() || segment.length() == 0) {
                    Log.w(TAG, "分片" + segIndex + "无效，跳过");
                    continue;
                }

                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(segment.getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "分片" + segIndex + "设置数据源失败", e);
                    continue;
                }
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
                    muxerVideoTrack = muxer.addTrack(trackFormat);
                    muxer.start();
                    trackAdded = true;
                    Log.d(TAG, String.format("视频轨道已添加，分片%d起始时间: %.3fs",
                            segIndex, segmentStartTime/1000000.0));
                }

                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int frameCount = 0;
                long segmentDuration = 0;

                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;

                    long relativePts = extractor.getSampleTime(); // 分片内的相对时间戳
                    int flags = extractor.getSampleFlags();

                    // 将相对时间戳映射回全局时间戳
                    long globalPts = segmentStartTime + relativePts;

                    // 记录分片时长
                    if (globalPts > segmentDuration) {
                        segmentDuration = globalPts - segmentStartTime;
                    }

                    info.set(0, size, globalPts, flags);

                    try {
                        muxer.writeSampleData(muxerVideoTrack, buffer, info);
                        frameCount++;
                        totalFrames++;
                    } catch (Exception e) {
                        Log.e(TAG, "分片" + segIndex + "写入失败", e);
                        break;
                    }

                    extractor.advance();
                }

                totalDuration += segmentDuration;

                // 更新合并进度
                int progress = 85 + (int)((segIndex + 1) * 10.0 / segments.size());
                updateStatus(callback, String.format("已合并%d/%d个分片", segIndex + 1, segments.size()), progress);

                Log.d(TAG, String.format("分片%d合并完成: %d帧, 时长%.3fs",
                        segIndex, frameCount, segmentDuration/1000000.0));
            }

            muxer.stop();

            Log.d(TAG, String.format("视频合并完成: 总%d帧, 总时长%.3fs",
                    totalFrames, totalDuration/1000000.0));
            updateStatus(callback, String.format("视频合并完成: 共%d帧", totalFrames), 95);
            return totalFrames > 0;

        } catch (Exception e) {
            updateStatus(callback, "合并分片出错：" + e.getMessage(), 0);
            Log.e(TAG, "mergeVideoSegmentsEnhanced error", e);
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
     * 带有帧数计数的分片转码方法
     */
    private static boolean transcodeSegmentWithFrameCounter(String src, String dst,
                                                            long startTime, long endTime,
                                                            int targetBitrate,
                                                            MediaFormat originalFormat,
                                                            int segmentIndex,
                                                            MainActivity.ProgressCallback callback,
                                                            FrameCounterCallback frameCounter) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null, encoder = null;

        int frameCount = 0;
        long lastUpdateTime = System.currentTimeMillis();

        try {
            long segmentDuration = endTime - startTime;
            Log.d(TAG, String.format("分片%d: 开始转码 [%.3fs-%.3fs], 时长%.1fs, 码率%dkbps",
                    segmentIndex, startTime/1000000.0, endTime/1000000.0,
                    segmentDuration/1000000.0, targetBitrate/1000));

            updateStatus(callback, "初始化分片转码器...", 0);

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

            // 2. 定位到起始位置
            extractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            long actualStartTime = extractor.getSampleTime();
            if (actualStartTime < 0) actualStartTime = startTime;

            updateStatus(callback, "开始解码视频...", 20);
            Log.d(TAG, String.format("分片%d: 实际起始时间 %.3fs", segmentIndex, actualStartTime/1000000.0));

            // 3. 获取视频参数
            int width = originalFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = originalFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = originalFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? originalFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            // 4. 配置编码器
            MediaFormat encoderFormat = null;
            String codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC;

            try {
                MediaCodecInfo codecInfo = selectCodec(codecMime);
                if (codecInfo == null) {
                    codecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                    codecInfo = selectCodec(codecMime);
                }

                if (codecInfo == null) {
                    Log.e(TAG, "分片" + segmentIndex + ": 未找到合适的编码器");
                    return false;
                }

                encoderFormat = MediaFormat.createVideoFormat(codecMime, width, height);
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
                encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

                // 设置颜色格式
                MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(codecMime);
                int colorFormat = selectColorFormat(caps);
                if (colorFormat != 0) {
                    encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                }

                // 设置编码档次和级别
                setEncoderProfileLevel(encoderFormat, codecMime, width * height);

                updateStatus(callback, "创建编码器...", 30);
                Log.d(TAG, String.format("分片%d: 使用%s编码器，码率%dkbps",
                        segmentIndex, codecMime, targetBitrate/1000));

            } catch (Exception e) {
                Log.e(TAG, "分片" + segmentIndex + ": 编码器配置失败", e);
                return false;
            }

            // 5. 创建编码器和解码器
            encoder = MediaCodec.createEncoderByType(codecMime);
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            String decodeMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(decodeMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // 6. 创建Muxer
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            updateStatus(callback, "开始转码处理...", 40);

            // 7. 转码循环
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;
            boolean muxerStarted = false;
            int muxerVideoTrack = -1;
            long segmentStartOffset = actualStartTime;
            long lastProgressUpdateTime = System.currentTimeMillis();

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
                            Log.d(TAG, "分片" + segmentIndex + ": 解码器输入结束");
                        } else {
                            long pts = extractor.getSampleTime();

                            // 检查是否超过分片结束时间
                            if (pts >= endTime) {
                                decoder.queueInputBuffer(inputIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                                Log.d(TAG, "分片" + segmentIndex + ": 达到分片结束时间");
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
                            Log.d(TAG, "分片" + segmentIndex + ": 编码器输入结束");
                        }
                    } else if (decInfo.size > 0) {
                        long originalPts = decInfo.presentationTimeUs;

                        // 检查是否在分片时间范围内
                        if (originalPts >= segmentStartOffset && originalPts < endTime) {
                            // 计算相对于分片起始的时间戳
                            long relativePts = originalPts - segmentStartOffset;

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

                                    // 使用相对时间戳
                                    encoder.queueInputBuffer(encoderIdx, 0,
                                            decInfo.size, relativePts, 0);
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
                    updateStatus(callback, "开始编码视频...", 50);
                    Log.d(TAG, "分片" + segmentIndex + ": Muxer启动，输出格式: " + outputFormat);
                } else if (encoderIdx >= 0) {
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(encoderIdx, false);
                        continue;
                    }

                    if (encInfo.size > 0 && muxerStarted) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encoderIdx);

                        // 确保时间戳非负
                        if (encInfo.presentationTimeUs < 0) {
                            encInfo.presentationTimeUs = 0;
                        }

                        muxer.writeSampleData(muxerVideoTrack, encodedData, encInfo);
                        frameCount++;

                        // 定期更新帧数（每100帧或每2秒）
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime > 2000 || frameCount % 100 == 0) {
                            if (frameCounter != null) {
                                frameCounter.onFramesEncoded(frameCount);
                            }
                            lastUpdateTime = currentTime;
                        }
                    }

                    encoder.releaseOutputBuffer(encoderIdx, false);

                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                        Log.d(TAG, "分片" + segmentIndex + ": 编码器输出结束");
                    }
                }
            }

            // 最后更新一次总帧数
            if (frameCounter != null) {
                frameCounter.onFramesEncoded(frameCount);
            }

            updateStatus(callback, String.format("分片转码完成: %d帧", frameCount), 100);
            Log.d(TAG, String.format("分片%d: 转码完成 %d帧", segmentIndex, frameCount));
            return frameCount > 0;

        } catch (Exception e) {
            updateStatus(callback, "分片转码出错：" + e.getMessage(), 0);
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
     * 提取音频到临时文件 - 添加进度回调
     */
    private static File extractAudioToFile(MediaExtractor extractor, int audioTrack, MediaFormat audioFormat,
                                           MainActivity.ProgressCallback callback) {
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
                    updateStatus(callback, String.format("已提取 %d 帧音频", audioFrameCount), -1);
                }
            }

            muxer.stop();

            updateStatus(callback, String.format("音频提取完成: %d 帧", audioFrameCount), 100);
            Log.d(TAG, "音频提取完成，共 " + audioFrameCount + " 帧，大小: " + tempFile.length() / 1024 + "KB");
            return tempFile;

        } catch (Exception e) {
            updateStatus(callback, "提取音频出错：" + e.getMessage(), 0);
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

    private static List<Long> findKeyFramePositionsEnhanced(String dataSource, int videoTrack,
                                                            long duration, int maxThreads, MainActivity.ProgressCallback callback) {
        List<Long> keyFrames = new ArrayList<>();
        MediaExtractor extractor = null;

        try {
            Log.d(TAG, String.format("增强版关键帧查找，视频时长: %.1f秒", duration / 1000000.0));
            updateStatus(callback, "分析视频关键帧...", -1);

            // 总是添加开始和结束位置
            keyFrames.add(0L);

            // 根据线程数确定采样密度
            long sampleInterval = getSampleInterval(duration, maxThreads);

            Log.d(TAG, String.format("采样间隔: %.1f秒", sampleInterval / 1000000.0));

            extractor = new MediaExtractor();
            extractor.setDataSource(dataSource);
            extractor.selectTrack(videoTrack);

            // 采样关键帧位置
            long currentTime = 0;
            int sampleCount = 0;
            int totalSamples = (int) (duration / sampleInterval) + 1;

            while (currentTime < duration) {
                // 使用SEEK_TO_CLOSEST_SYNC查找最近的关键帧
                extractor.seekTo(currentTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long keyFrameTime = extractor.getSampleTime();

                if (keyFrameTime >= 0) {
                    // 检查是否已经存在类似的时间（避免重复）
                    boolean duplicate = false;
                    for (Long existingTime : keyFrames) {
                        if (Math.abs(existingTime - keyFrameTime) < 100000) { // 0.1秒内认为是重复
                            duplicate = true;
                            break;
                        }
                    }

                    if (!duplicate && keyFrameTime < duration) {
                        keyFrames.add(keyFrameTime);
                        sampleCount++;
                    }
                }

                currentTime += sampleInterval;

                // 更新进度
                if (totalSamples > 0) {
                    int progress = (int) ((currentTime * 100.0) / duration);
                    progress = Math.min(progress, 100);
                    updateStatus(callback, String.format("查找关键帧... (%d%%)", progress), -1);
                }
            }

            // 添加结束位置
            keyFrames.add(duration);

            // 按时间排序
            Collections.sort(keyFrames);

            Log.d(TAG, String.format("关键帧查找完成，共找到%d个位置", keyFrames.size()));

            // 如果关键帧太少，添加一些等间隔位置
            if (keyFrames.size() < maxThreads + 1) {
                Log.w(TAG, "关键帧数量不足，补充等间隔位置");
                int needed = maxThreads + 1 - keyFrames.size();
                long interval = duration / (needed + 1);

                for (int i = 1; i <= needed; i++) {
                    long position = i * interval;
                    keyFrames.add(position);
                }

                Collections.sort(keyFrames);
                Log.d(TAG, "补充后关键帧数量: " + keyFrames.size());
            }

            updateStatus(callback, String.format("找到%d个关键帧位置", keyFrames.size()), -1);
            return keyFrames;

        } catch (Exception e) {
            Log.e(TAG, "findKeyFramePositionsEnhanced error", e);
            // 返回一个基本的等分位置列表
            List<Long> fallbackPositions = new ArrayList<>();
            fallbackPositions.add(0L);

            int segments = Math.max(2, maxThreads);
            long interval = duration / segments;

            for (int i = 1; i < segments; i++) {
                fallbackPositions.add(i * interval);
            }

            fallbackPositions.add(duration);

            Log.w(TAG, "使用备用位置列表，共" + fallbackPositions.size() + "个位置");
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
     * 获取采样间隔
     */
    private static long getSampleInterval(long duration, int maxThreads) {
        long sampleInterval;
        if (duration > 30 * 60 * 1000000L) { // 超过30分钟
            sampleInterval = 20 * 1000000L; // 20秒
        } else if (duration > 10 * 60 * 1000000L) { // 10-30分钟
            sampleInterval = 10 * 1000000L; // 10秒
        } else if (duration > 5 * 60 * 1000000L) { // 5-10分钟
            sampleInterval = 5 * 1000000L; // 5秒
        } else {
            sampleInterval = 3 * 1000000L; // 3秒
        }

        // 根据最大线程数调整采样间隔，确保有足够的关键帧
        int targetKeyFrames = maxThreads * 5; // 每个线程对应5个关键帧位置
        long calculatedInterval = duration / targetKeyFrames;
        sampleInterval = Math.max(sampleInterval, calculatedInterval);
        return sampleInterval;
    }

    /**
     * 选择编码器
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    Log.d(TAG, "找到编码器: " + codecInfo.getName() + " for " + mimeType);
                    return codecInfo;
                }
            }
        }
        Log.w(TAG, "未找到编码器 for " + mimeType);
        return null;
    }

    /**
     * 选择颜色格式
     */
    private static int selectColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        int[] colorFormats = caps.colorFormats;
        for (int colorFormat : colorFormats) {
            // 优先选择最合适的格式
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                Log.d(TAG, "选择COLOR_FormatYUV420Flexible颜色格式");
                return colorFormat;
            }
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                Log.d(TAG, "选择COLOR_FormatYUV420Planar颜色格式");
                return colorFormat;
            }
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                Log.d(TAG, "选择COLOR_FormatYUV420SemiPlanar颜色格式");
                return colorFormat;
            }
        }

        // 如果有可用的格式，返回第一个
        if (colorFormats.length > 0) {
            Log.d(TAG, "选择默认颜色格式: " + colorFormats[0]);
            return colorFormats[0];
        }

        Log.w(TAG, "未找到合适的颜色格式");
        return 0;
    }

    /**
     * 设置编码器档次和级别
     */
    private static void setEncoderProfileLevel(MediaFormat format, String mimeType, int resolution) {
        try {
            if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                // HEVC/H.265
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);

                // 根据分辨率设置级别
                if (resolution <= 1280 * 720) {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31);
                    Log.d(TAG, "设置HEVC档次: Main, 级别: Level 3.1");
                } else if (resolution <= 1920 * 1080) {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4);
                    Log.d(TAG, "设置HEVC档次: Main, 级别: Level 4");
                } else {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5);
                    Log.d(TAG, "设置HEVC档次: Main, 级别: Level 5");
                }
            } else if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                // AVC/H.264
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);

                // 根据分辨率设置级别
                if (resolution <= 1280 * 720) {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                    Log.d(TAG, "设置AVC档次: High, 级别: Level 3.1");
                } else if (resolution <= 1920 * 1080) {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                    Log.d(TAG, "设置AVC档次: High, 级别: Level 4");
                } else {
                    format.setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel42);
                    Log.d(TAG, "设置AVC档次: High, 级别: Level 4.2");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "设置编码器档次级别失败: " + e.getMessage());
        }
    }

    /**
     * 更新状态辅助方法 - 增强版，添加日志和进度限制
     */
    private static void updateStatus(MainActivity.ProgressCallback callback, String status, int progress) {
        if (callback != null) {
            // 确保进度在0-100之间，或者-1表示不确定
            if (progress > 100) {
                progress = 100;
            } else if (progress < -1) {
                progress = 0;
            }
            callback.onProgressUpdate(status, progress);
        }
        // 添加详细日志，但避免过于频繁
        if (progress >= 0) {
            Log.d(TAG, "状态: " + status + " (" + progress + "%)");
        } else {
            Log.d(TAG, "状态: " + status);
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

