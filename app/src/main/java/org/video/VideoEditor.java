package org.video;

import static org.video.utils.VideoUtils.convertSampleFlagsToBufferFlags;
import static org.video.utils.VideoUtils.getVideoDurationMs;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import org.video.utils.CodecUtils;
import org.video.utils.Constants;
import org.video.utils.FileUtils;
import org.video.utils.TimeUtils;
import org.video.utils.VideoUtils;

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

/**
 * 视频编辑器主类
 */
public class VideoEditor {
    private static final String TAG = "VideoEditor";

    /**
     * 帧数计数器回调接口
     */
    interface FrameCounterCallback {
        void onFramesEncoded(int frames);
    }

    /**
     * 简化版优化裁剪（直接复用分割视频逻辑）
     */
    public static boolean cropVideoOptimized(String inputPath, String outputPath,
                                             long startTimeUs, long endTimeUs,
                                             MainActivity.ProgressCallback callback) {
        try {
            updateStatus(callback, "开始裁剪视频...", 0);

            // 验证输入文件
            if (!VideoUtils.isValidVideoFile(inputPath)) {
                updateStatus(callback, "错误：输入文件不存在或为空", 0);
                return false;
            }

            // 获取视频时长
            long duration = VideoUtils.getVideoDurationUs(inputPath);
            if (duration <= 0) {
                updateStatus(callback, "无法获取视频时长", 0);
                return false;
            }

            // 验证裁剪时间
            if (startTimeUs < 0) startTimeUs = 0;
            if (endTimeUs > duration) endTimeUs = duration;

            // 使用TimeUtils验证时间范围
            if (!TimeUtils.isValidTimeRangeUs(startTimeUs, endTimeUs, Constants.MIN_VIDEO_DURATION_US)) {
                updateStatus(callback, "裁剪时长不能小于1秒", 0);
                return false;
            }

            Log.d(TAG, String.format("裁剪: %.3fs-%.3fs (时长%.3fs)",
                    TimeUtils.microsecondsToSeconds(startTimeUs),
                    TimeUtils.microsecondsToSeconds(endTimeUs),
                    TimeUtils.microsecondsToSeconds(endTimeUs - startTimeUs)));

            // 创建一个临时文件用于第二部分（我们不需要它）
            File tempDir = getCacheDir();
            String tempOutputPath = new File(tempDir,
                    Constants.PREFIX_TEMP + "crop_part2_" + System.currentTimeMillis() + Constants.FILE_EXT_MP4).getAbsolutePath();

            // 复用splitVideoOptimized的逻辑，但只取第一部分
            // 分割点设为裁剪结束时间
            updateStatus(callback, "处理视频...", 10);
            boolean success = splitVideoOptimized(inputPath, outputPath, tempOutputPath,
                    endTimeUs, callback);

            // 删除临时文件（第二部分）
            FileUtils.safeDeleteFile(new File(tempOutputPath));

            if (success) {
                // 现在我们需要从裁剪开始时间开始，而不是从0开始
                // 如果开始时间不是0，我们需要再次裁剪
                if (startTimeUs > 0) {
                    updateStatus(callback, "调整裁剪起始点...", 90);

                    // 创建最终输出文件
                    String finalOutputPath = outputPath + ".final";

                    // 再次裁剪，从startTimeUs开始
                    MediaExtractor extractor = new MediaExtractor();
                    extractor.setDataSource(outputPath);

                    int videoTrackIndex = -1, audioTrackIndex = -1;
                    MediaFormat videoFormat = null, audioFormat = null;

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime.startsWith("video/")) {
                            videoTrackIndex = i;
                            videoFormat = format;
                        } else if (mime.startsWith("audio/")) {
                            audioTrackIndex = i;
                            audioFormat = format;
                        }
                    }

                    extractor.release();

                    if (videoFormat == null) {
                        updateStatus(callback, "裁剪失败：未找到视频轨道", 0);
                        return false;
                    }

                    // 使用快速复制方法
                    MediaExtractor finalExtractor = new MediaExtractor();
                    finalExtractor.setDataSource(outputPath);

                    MediaMuxer finalMuxer = new MediaMuxer(finalOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int finalVideoTrack = finalMuxer.addTrack(videoFormat);
                    int finalAudioTrack = audioFormat != null ? finalMuxer.addTrack(audioFormat) : -1;
                    finalMuxer.start();

                    // 从0开始复制（因为第一次裁剪已经去掉了endTimeUs之后的部分）
                    boolean finalSuccess = copySegmentToMuxerImproved(finalExtractor,
                            videoTrackIndex, audioTrackIndex,
                            0, Long.MAX_VALUE, // 从0开始到结束
                            finalMuxer, finalVideoTrack, finalAudioTrack,
                            callback, 92);

                    finalMuxer.stop();
                    finalMuxer.release();
                    finalExtractor.release();

                    if (finalSuccess) {
                        // 删除中间文件，重命名最终文件
                        File intermediateFile = new File(outputPath);
                        File finalFile = new File(finalOutputPath);

                        if (intermediateFile.exists()) FileUtils.safeDeleteFile(intermediateFile);
                        if (finalFile.exists() && finalFile.length() > 0) {
                            finalFile.renameTo(new File(outputPath));
                            updateStatus(callback, "裁剪完成", 100);
                            return true;
                        }
                    }

                    return false;
                } else {
                    updateStatus(callback, "裁剪完成", 100);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            updateStatus(callback, "裁剪出错：" + e.getMessage(), 0);
            Log.e(TAG, "cropVideoOptimizedSimple error", e);
            return false;
        }
    }

    /**
     * 优化分割视频（快速+精确）
     */
    public static boolean splitVideoOptimized(String inputPath, String outputPath1,
                                              String outputPath2, long splitTimeUs,
                                              MainActivity.ProgressCallback callback) {
        MediaExtractor extractor = null;

        try {
            updateStatus(callback, "开始优化分割...", 0);

            // 验证输入文件
            File inputFile = new File(inputPath);
            if (!inputFile.exists() || inputFile.length() == 0) {
                updateStatus(callback, "错误：输入文件不存在或为空", 0);
                Log.e(TAG, "输入文件不存在: " + inputPath);
                return false;
            }

            // 1. 分析视频信息
            updateStatus(callback, "分析视频信息...", 5);
            extractor = new MediaExtractor();

            try {
                extractor.setDataSource(inputPath);
            } catch (Exception e) {
                updateStatus(callback, "无法读取视频文件: " + e.getMessage(), 0);
                Log.e(TAG, "设置数据源失败: " + inputPath, e);
                return false;
            }

            // 获取轨道信息
            int videoTrackIndex = -1, audioTrackIndex = -1;
            MediaFormat videoFormat = null, audioFormat = null;
            long duration = 0;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = format.getLong(MediaFormat.KEY_DURATION);
                    }
                } else if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }

            if (videoTrackIndex == -1) {
                updateStatus(callback, "错误：未找到视频轨道", 0);
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 验证分割时间点
            if (duration <= 0) {
                // 尝试通过MediaMetadataRetriever获取时长
                duration = getVideoDurationMs(inputPath) * 1000L;
                if (duration <= 0) {
                    updateStatus(callback, "无法获取视频时长", 0);
                    return false;
                }
            }

            // 确保分割时间在有效范围内
            if (splitTimeUs <= 1000000) {
                splitTimeUs = 1000000;
                updateStatus(callback, "分割时间调整到1秒", 10);
            } else if (splitTimeUs >= duration - 1000000) {
                splitTimeUs = duration - 1000000;
                updateStatus(callback, "分割时间调整到最后1秒前", 10);
            }

            Log.d(TAG, String.format("分割参数: 总时长=%.3fs, 分割点=%.3fs",
                    duration/1000000.0, splitTimeUs/1000000.0));

            // 2. 检测是否为高B帧视频
            updateStatus(callback, "检测视频编码特征...", 15);
            boolean isHighBFrameVideo = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isHighBFrameVideo = VideoUtils.isHighBFrameVideo(inputPath, videoTrackIndex);
            }

            if (isHighBFrameVideo) {
                Log.d(TAG, "检测到高B帧视频，使用优化方案");
                updateStatus(callback, "检测到高B帧视频，使用优化方案", 20);
            } else {
                Log.d(TAG, "常规视频，使用快速分割");
                updateStatus(callback, "常规视频，使用快速分割", 20);
            }

// 3. 查找分割点附近的关键帧
            updateStatus(callback, "查找最佳分割点...", 25);
            long actualSplitTimeUs = findNearestKeyframe(extractor, videoTrackIndex, splitTimeUs);

// 记录实际分割点和目标分割点的差异
            long diff = Math.abs(actualSplitTimeUs - splitTimeUs);
            Log.d(TAG, String.format("分割点: 目标=%.3fs, 实际=%.3fs, 差异=%.3fs",
                    splitTimeUs / 1000000.0,
                    actualSplitTimeUs / 1000000.0,
                    diff / 1000000.0));

// 4. 根据视频类型选择分割方案
            if (isHighBFrameVideo) {
                // 使用高B帧优化方案
                return splitVideoHighBFrames(inputPath, outputPath1, outputPath2,
                        actualSplitTimeUs, videoTrackIndex, audioTrackIndex,
                        videoFormat, audioFormat, callback);
            } else {
                // 使用原来的快速复制方法
                return splitVideoFastCopy(inputPath, outputPath1, outputPath2,
                        actualSplitTimeUs, videoTrackIndex, audioTrackIndex,
                        videoFormat, audioFormat, callback);
            }

        } catch (Exception e) {
            updateStatus(callback, "分割出错：" + e.getMessage(), 0);
            Log.e(TAG, "splitVideoOptimized error", e);
            return false;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放提取器失败", e);
                }
            }
        }
    }

//    /**
//     * 检测是否为高B帧视频 - 优化版（无需读取数据）
//     */
//    private static boolean isHighBFrameVideo(String inputPath, int videoTrackIndex) {
//        MediaExtractor extractor = null;
//        try {
//            extractor = new MediaExtractor();
//            extractor.setDataSource(inputPath);
//            extractor.selectTrack(videoTrackIndex);
//
//            // 只分析前30秒或前500帧
//            int totalFrames = 0;
//            int bFrames = 0;
//            long analysisDuration = 30 * 1000000L; // 30秒
//
//            // 定位到开始
//            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
//
//            while (totalFrames < 500) {
//                // 获取样本大小但不读取数据
//                long sampleTime = extractor.getSampleTime();
//                if (sampleTime > analysisDuration) {
//                    break;
//                }
//
//                int flags = extractor.getSampleFlags();
//                boolean isKeyframe = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
//
//                totalFrames++;
//                if (!isKeyframe) {
//                    bFrames++;
//                }
//
//                // 前进到下一帧
//                if (!extractor.advance()) {
//                    break;
//                }
//            }
//
//            double bFrameRatio = totalFrames > 0 ? (double) bFrames / totalFrames : 0;
//            Log.d(TAG, String.format("B帧检测: 总帧数=%d, B帧数=%d, B帧比例=%.1f%%",
//                    totalFrames, bFrames, bFrameRatio * 100));
//
//            return bFrameRatio >= Constants.HIGH_BFRAME_THRESHOLD;
//
//        } catch (Exception e) {
//            Log.e(TAG, "检测B帧比例失败", e);
//            return false;
//        } finally {
//            if (extractor != null) {
//                extractor.release();
//            }
//        }
//    }

    /**
     * 简化的高B帧分割方案（基于你现有代码结构）
     */
    private static boolean splitVideoHighBFrames(String inputPath, String outputPath1,
                                                 String outputPath2, long splitTimeUs,
                                                 int videoTrackIndex, int audioTrackIndex,
                                                 MediaFormat videoFormat, MediaFormat audioFormat,
                                                 MainActivity.ProgressCallback callback) {

        MediaExtractor extractor1 = null, extractor2 = null;
        MediaMuxer muxer1 = null, muxer2 = null;

        try {
            updateStatus(callback, "使用高B帧优化方案...", 35);

            // 创建两个独立的extractor
            extractor1 = new MediaExtractor();
            extractor1.setDataSource(inputPath);

            extractor2 = new MediaExtractor();
            extractor2.setDataSource(inputPath);

            // 创建输出文件
            muxer1 = new MediaMuxer(outputPath1, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer2 = new MediaMuxer(outputPath2, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int muxer1VideoTrack = muxer1.addTrack(videoFormat);
            int muxer1AudioTrack = audioFormat != null ? muxer1.addTrack(audioFormat) : -1;

            int muxer2VideoTrack = muxer2.addTrack(videoFormat);
            int muxer2AudioTrack = audioFormat != null ? muxer2.addTrack(audioFormat) : -1;

            muxer1.start();
            muxer2.start();

            // 对于高B帧视频，使用更保守的分割策略
            // 1. 确保分割点在一个完整的GOP边界
            long adjustedSplitTime = adjustSplitForGOP(extractor1, videoTrackIndex, splitTimeUs);

            Log.d(TAG, String.format("高B帧分割: 原分割点=%.3fs, 调整后=%.3fs",
                    splitTimeUs/1000000.0, adjustedSplitTime/1000000.0));

            // 2. 处理第一部分
            updateStatus(callback, "处理第一部分（高B帧优化）...", 40);
            boolean part1Success = copySegmentHighBFrames(extractor1,
                    videoTrackIndex, audioTrackIndex,
                    0, adjustedSplitTime,
                    muxer1, muxer1VideoTrack, muxer1AudioTrack,
                    callback);

            if (!part1Success) {
                updateStatus(callback, "第一部分处理失败", 0);
                return false;
            }

            // 3. 处理第二部分
            updateStatus(callback, "处理第二部分（高B帧优化）...", 70);
            boolean part2Success = copySegmentHighBFrames(extractor2,
                    videoTrackIndex, audioTrackIndex,
                    adjustedSplitTime, Long.MAX_VALUE,
                    muxer2, muxer2VideoTrack, muxer2AudioTrack,
                    callback);

            if (!part2Success) {
                updateStatus(callback, "第二部分处理失败", 0);
                return false;
            }

            muxer1.stop();
            muxer2.stop();

            updateStatus(callback, "高B帧优化分割完成", 95);
            return true;

        } catch (Exception e) {
            updateStatus(callback, "高B帧分割失败: " + e.getMessage(), 0);
            Log.e(TAG, "splitVideoHighBFrames error", e);
            return false;
        } finally {
            try { if (muxer1 != null) muxer1.release(); } catch (Exception ignored) {}
            try { if (muxer2 != null) muxer2.release(); } catch (Exception ignored) {}
            try { if (extractor1 != null) extractor1.release(); } catch (Exception ignored) {}
            try { if (extractor2 != null) extractor2.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * 调整分割点以确保GOP完整
     */
    private static long adjustSplitForGOP(MediaExtractor extractor, int videoTrackIndex, long targetTimeUs) {
        try {
            extractor.selectTrack(videoTrackIndex);

            // 向前查找关键帧
            extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long prevKeyframe = extractor.getSampleTime();

            // 向后查找关键帧
            extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_NEXT_SYNC);
            long nextKeyframe = extractor.getSampleTime();

            // 选择离目标更近的关键帧
            long prevDiff = Math.abs(targetTimeUs - prevKeyframe);
            long nextDiff = Math.abs(nextKeyframe - targetTimeUs);

            long selectedKeyframe = (prevDiff <= nextDiff) ? prevKeyframe : nextKeyframe;

            // 对于高B帧视频，可能需要更保守的调整
            // 检查这个关键帧之后的几帧，确保不是B帧密集区域
            extractor.seekTo(selectedKeyframe, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            int bFrameCount = 0;
            for (int i = 0; i < 5; i++) {
                int flags = extractor.getSampleFlags();
                boolean isKeyframe = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;

                if (!isKeyframe) {
                    bFrameCount++;
                }

                if (!extractor.advance()) {
                    break;
                }
            }

            // 如果关键帧后面紧跟很多B帧，可能不是好的分割点
            if (bFrameCount >= 4) { // 5帧中有4帧是B帧
                Log.d(TAG, "分割点处于B帧密集区域，尝试调整...");
                // 尝试向前找一个更好的关键帧
                extractor.seekTo(selectedKeyframe - 2000000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); // 向前2秒
                long alternativeKeyframe = extractor.getSampleTime();

                if (selectedKeyframe - alternativeKeyframe < 5000000) { // 5秒内
                    selectedKeyframe = alternativeKeyframe;
                    Log.d(TAG, "调整到前一个关键帧: " + selectedKeyframe);
                }
            }

            return selectedKeyframe;

        } catch (Exception e) {
            Log.e(TAG, "调整分割点失败", e);
            return targetTimeUs;
        }
    }

    /**
     * 高B帧视频的片段复制（优化版本）- 修复样本大小不匹配问题
     */
    private static boolean copySegmentHighBFrames(MediaExtractor extractor,
                                                  int videoTrackIndex, int audioTrackIndex,
                                                  long startTimeUs, long endTimeUs,
                                                  MediaMuxer muxer,
                                                  int muxerVideoTrack, int muxerAudioTrack,
                                                  MainActivity.ProgressCallback callback) {

        try {
            Log.d(TAG, "开始高B帧视频处理...");

            // 使用一个足够大的缓冲区（16MB）
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // ========== 1. 处理视频轨道 ==========
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            long firstVideoPts = -1;
            int videoFrames = 0;
            int skippedFrames = 0;

            // 直接处理视频帧，不重新定位和重新读取
            while (true) {
                // 清空缓冲区
                buffer.clear();

                // 直接读取样本数据
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long pts = extractor.getSampleTime();
                if (endTimeUs != Long.MAX_VALUE && pts >= endTimeUs) {
                    Log.d(TAG, "达到结束时间，停止视频处理: " + pts);
                    break;
                }

                if (firstVideoPts == -1) {
                    firstVideoPts = pts;
                    Log.d(TAG, "第一个视频PTS: " + pts + "us, 样本大小: " + sampleSize);
                }

                buffer.position(0);
                buffer.limit(sampleSize);

                long relativePts = pts - firstVideoPts;
                if (relativePts < 0) {
                    // 如果时间戳为负，跳过这一帧
                    Log.w(TAG, "跳过负时间戳帧: " + pts);
                    extractor.advance();
                    skippedFrames++;
                    continue;
                }

                info.set(0, sampleSize, relativePts,
                        convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                try {
                    muxer.writeSampleData(muxerVideoTrack, buffer, info);
                    videoFrames++;

                    // 每100帧记录一次
                    if (videoFrames % 100 == 0) {
                        Log.d(TAG, String.format("已处理 %d 视频帧, 当前PTS: %.3fs",
                                videoFrames, relativePts/1000000.0));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "写入视频数据失败: " + e.getMessage(), e);
                    break;
                }

                if (!extractor.advance()) {
                    break;
                }
            }

            Log.d(TAG, String.format("视频处理完成: %d帧, 跳过%d帧", videoFrames, skippedFrames));

            // ========== 2. 处理音频轨道 ==========
            int audioFrames = 0;
            if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                Log.d(TAG, "开始处理音频轨道...");

                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);

                // 音频应从视频起始时间开始
                extractor.seekTo(firstVideoPts, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long audioStartPts = extractor.getSampleTime();

                Log.d(TAG, "音频起始PTS: " + audioStartPts + "us");

                // 如果音频起始时间为负，调整
                long audioOffset = 0;
                if (audioStartPts < 0) {
                    audioOffset = -audioStartPts;
                    Log.d(TAG, "音频初始负偏移: " + audioOffset + "us");
                }

                int skippedAudioFrames = 0;

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (endTimeUs != Long.MAX_VALUE && pts >= endTimeUs) {
                        break;
                    }

                    buffer.position(0);
                    buffer.limit(sampleSize);

                    // 音频时间戳对齐视频
                    long alignedPts = pts - firstVideoPts + audioOffset;
                    if (alignedPts < 0) {
                        // 跳过负时间戳的音频帧
                        Log.w(TAG, "跳过负时间戳音频帧: " + pts);
                        extractor.advance();
                        skippedAudioFrames++;
                        continue;
                    }

                    info.set(0, sampleSize, alignedPts,
                            convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                    try {
                        muxer.writeSampleData(muxerAudioTrack, buffer, info);
                        audioFrames++;

                        // 每200帧记录一次
                        if (audioFrames % 200 == 0) {
                            Log.d(TAG, String.format("已处理 %d 音频帧", audioFrames));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "写入音频数据失败: " + e.getMessage(), e);
                        break;
                    }

                    if (!extractor.advance()) {
                        break;
                    }
                }

                Log.d(TAG, String.format("音频处理完成: %d帧, 跳过%d帧",
                        audioFrames, skippedAudioFrames));

                // 恢复视频轨道选择
                extractor.unselectTrack(audioTrackIndex);
                extractor.selectTrack(videoTrackIndex);
            }

            if (callback != null) {
                callback.onProgressUpdate(
                        String.format("完成: %d视频帧, %d音频帧", videoFrames, audioFrames),
                        100);
            }

            return videoFrames > 0;

        } catch (Exception e) {
            Log.e(TAG, "copySegmentHighBFrames error", e);
            return false;
        }
    }

    private static boolean splitVideoFastCopy(String inputPath, String outputPath1, String outputPath2,
                                              long splitTimeUs, int videoTrackIndex, int audioTrackIndex,
                                              MediaFormat videoFormat, MediaFormat audioFormat,
                                              MainActivity.ProgressCallback callback) {
        MediaExtractor extractor1 = null, extractor2 = null;
        MediaMuxer muxer1 = null, muxer2 = null;

        try {
            updateStatus(callback, "准备分割视频...", 30);

            // 创建两个独立的extractor
            extractor1 = new MediaExtractor();
            extractor1.setDataSource(inputPath);

            extractor2 = new MediaExtractor();
            extractor2.setDataSource(inputPath);

            // 创建输出文件
            muxer1 = new MediaMuxer(outputPath1, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer2 = new MediaMuxer(outputPath2, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int muxer1VideoTrack = muxer1.addTrack(videoFormat);
            int muxer1AudioTrack = audioFormat != null ? muxer1.addTrack(audioFormat) : -1;

            int muxer2VideoTrack = muxer2.addTrack(videoFormat);
            int muxer2AudioTrack = audioFormat != null ? muxer2.addTrack(audioFormat) : -1;

            // 启动muxer
            muxer1.start();
            muxer2.start();

            // 处理第一部分（0 到 splitTimeUs）
            updateStatus(callback, "处理第一部分...", 40);

            // 使用改进的copy方法（你可以选择使用原来的或新的）
            boolean part1Success = copySegmentToMuxerImproved(extractor1,
                    videoTrackIndex, audioTrackIndex,
                    0, splitTimeUs,
                    muxer1, muxer1VideoTrack, muxer1AudioTrack,
                    callback, 40);

            if (!part1Success) {
                updateStatus(callback, "第一部分处理失败", 0);
                return false;
            }

            // 处理第二部分（splitTimeUs 到结束）
            updateStatus(callback, "处理第二部分...", 65);

            boolean part2Success = copySegmentToMuxerImproved(extractor2,
                    videoTrackIndex, audioTrackIndex,
                    splitTimeUs, Long.MAX_VALUE,
                    muxer2, muxer2VideoTrack, muxer2AudioTrack,
                    callback, 65);

            if (!part2Success) {
                updateStatus(callback, "第二部分处理失败", 0);
                return false;
            }

            // 完成
            muxer1.stop();
            muxer2.stop();

            updateStatus(callback, "分割完成", 95);

            // 验证输出文件
            File outputFile1 = new File(outputPath1);
            File outputFile2 = new File(outputPath2);

            if (outputFile1.exists() && outputFile1.length() > 1000 &&
                    outputFile2.exists() && outputFile2.length() > 1000) {

                updateStatus(callback, String.format("分割成功！\n第一部分: %.1fMB\n第二部分: %.1fMB",
                        outputFile1.length()/(1024.0*1024.0),
                        outputFile2.length()/(1024.0*1024.0)), 100);
                return true;
            } else {
                updateStatus(callback, "分割失败：输出文件无效", 0);
                return false;
            }

        } catch (Exception e) {
            updateStatus(callback, "快速分割失败：" + e.getMessage(), 0);
            Log.e(TAG, "splitVideoFastCopy error", e);
            return false;
        } finally {
            // 释放所有资源
            try { if (muxer1 != null) muxer1.release(); } catch (Exception ignored) {}
            try { if (muxer2 != null) muxer2.release(); } catch (Exception ignored) {}
            try { if (extractor1 != null) extractor1.release(); } catch (Exception ignored) {}
            try { if (extractor2 != null) extractor2.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * 改进的视频片段复制方法（常规视频用）
     */
    private static boolean copySegmentToMuxerImproved(MediaExtractor extractor,
                                                      int videoTrackIndex, int audioTrackIndex,
                                                      long startTimeUs, long endTimeUs,
                                                      MediaMuxer muxer,
                                                      int muxerVideoTrack, int muxerAudioTrack,
                                                      MainActivity.ProgressCallback callback,
                                                      int startProgress) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // 处理视频
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            long firstPts = -1;
            int videoFrames = 0;

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long pts = extractor.getSampleTime();
                if (pts >= endTimeUs) break;

                if (firstPts == -1) firstPts = pts;

                buffer.position(0);
                buffer.limit(sampleSize);

                // 使用原始PTS，不重新计算时间戳
                long relativePts = pts - firstPts;
                info.set(0, sampleSize, relativePts, convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                muxer.writeSampleData(muxerVideoTrack, buffer, info);

                videoFrames++;
                extractor.advance();

                // 更新进度
                if (videoFrames % 30 == 0 && callback != null) {
                    long processed = pts - startTimeUs;
                    long total = Math.min(endTimeUs - startTimeUs,
                            extractor.getTrackFormat(videoTrackIndex)
                                    .getLong(MediaFormat.KEY_DURATION) - startTimeUs);

                    if (total > 0) {
                        int progress = startProgress + (int)((processed * 25) / total);
                        updateStatus(callback, String.format("已处理 %d 视频帧", videoFrames), progress);
                    }
                }
            }

            Log.d(TAG, String.format("视频处理完成: %d帧", videoFrames));

            // 处理音频（如果有）
            if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                int audioFrames = 0;
                long audioFirstPts = -1;

                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (pts >= endTimeUs) break;

                    // 找到第一个时间戳 >= firstPts 的音频样本
                    if (firstPts != -1 && pts < firstPts) {
                        extractor.advance();
                        continue;
                    }

                    if (audioFirstPts == -1) audioFirstPts = Math.max(pts, firstPts);

                    buffer.position(0);
                    buffer.limit(sampleSize);

                    long relativePts = pts - audioFirstPts;
                    info.set(0, sampleSize, relativePts, convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                    muxer.writeSampleData(muxerAudioTrack, buffer, info);

                    audioFrames++;
                    extractor.advance();
                }

                Log.d(TAG, String.format("音频处理完成: %d帧", audioFrames));
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "copySegmentToMuxerImproved error", e);
            return false;
        }
    }

    /**
     * 合并视频（带音频）- 确保时间戳正确并正确写入文件
     */
    public static boolean mergeVideos(String[] inputPaths, String outputPath,
                                      MainActivity.ProgressCallback callback) {
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>();

        try {
            updateStatus(callback, "开始合并视频和音频...", 5);

            // 验证输入文件
            if (inputPaths == null || inputPaths.length < 2) {
                updateStatus(callback, "需要至少2个视频", 0);
                return false;
            }

            // 检查所有输入文件
            for (String path : inputPaths) {
                if (!VideoUtils.isValidVideoFile(path)) {
                    updateStatus(callback, "文件无效: " + new File(path).getName(), 0);
                    return false;
                }
            }

            // 1. 分析第一个视频的格式
            updateStatus(callback, "分析视频格式...", 10);
            MediaExtractor firstExtractor = new MediaExtractor();
            firstExtractor.setDataSource(inputPaths[0]);

            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            int firstVideoTrack = -1;
            int firstAudioTrack = -1;

            for (int i = 0; i < firstExtractor.getTrackCount(); i++) {
                MediaFormat format = firstExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null) {
                    if (mime.startsWith("video/") && videoFormat == null) {
                        videoFormat = format;
                        firstVideoTrack = i;
                    } else if (mime.startsWith("audio/") && audioFormat == null) {
                        audioFormat = format;
                        firstAudioTrack = i;
                    }
                }
            }

            if (videoFormat == null) {
                updateStatus(callback, "未找到视频轨道", 0);
                firstExtractor.release();
                return false;
            }

            firstExtractor.release();

            // 2. 创建输出文件
            updateStatus(callback, "创建输出文件...", 15);
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = audioFormat != null ? muxer.addTrack(audioFormat) : -1;

            muxer.start();

            // 3. 准备缓冲区
            ByteBuffer videoBuffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_4MB);
            ByteBuffer audioBuffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_512KB);
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

            // 4. 合并所有视频和音频
            long currentVideoPts = 0;
            long currentAudioPts = 0;
            long totalVideoFrames = 0;
            long totalAudioFrames = 0;
            long videoFrameDuration = Constants.VIDEO_FRAME_DURATION_30FPS; // 默认30fps，每帧33.3ms

            // 获取帧率
            if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int frameRate = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (frameRate > 0) {
                    videoFrameDuration = TimeUtils.calculateVideoFrameDuration(frameRate);
                }
            }

            for (int fileIndex = 0; fileIndex < inputPaths.length; fileIndex++) {
                String inputPath = inputPaths[fileIndex];
                File inputFile = new File(inputPath);

                updateStatus(callback, String.format("处理第%d/%d个视频: %s",
                                fileIndex + 1, inputPaths.length, inputFile.getName()),
                        20 + (fileIndex * 70 / inputPaths.length));

                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(inputPath);
                    extractors.add(extractor);

                    // 查找轨道
                    int videoTrackIndex = -1;
                    int audioTrackIndex = -1;

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null) {
                            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                                videoTrackIndex = i;
                            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                                audioTrackIndex = i;
                            }
                        }
                    }

                    if (videoTrackIndex == -1) {
                        Log.w(TAG, "跳过无视频轨道的文件: " + inputFile.getName());
                        continue;
                    }

                    // 5. 处理视频轨道
                    extractor.selectTrack(videoTrackIndex);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    int videoFrames = 0;
                    boolean isFirstVideoFrame = true;

                    while (true) {
                        videoBuffer.clear();
                        int sampleSize = extractor.readSampleData(videoBuffer, 0);
                        if (sampleSize < 0) break;

                        int flags = extractor.getSampleFlags();

                        videoBuffer.position(0);
                        videoBuffer.limit(sampleSize);

                        // 使用递增的时间戳
                        long ptsToUse = currentVideoPts;

                        // 每个视频的第一帧设为关键帧
                        if (isFirstVideoFrame) {
                            flags |= MediaExtractor.SAMPLE_FLAG_SYNC;
                            isFirstVideoFrame = false;
                        }

                        videoInfo.set(0, sampleSize, ptsToUse,
                                convertSampleFlagsToBufferFlags(flags));

                        try {
                            muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoInfo);
                            videoFrames++;
                            totalVideoFrames++;
                            currentVideoPts += videoFrameDuration;

                            // 更新进度
                            if (videoFrames % 50 == 0) {
                                int progress = 20 + (int)((fileIndex * 70.0 / inputPaths.length) +
                                        (videoFrames * 20.0 / 1000));
                                updateStatus(callback,
                                        String.format("已处理 %d 视频帧", totalVideoFrames),
                                        Math.min(progress, 80));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "写入视频帧失败: " + e.getMessage(), e);
                            break;
                        }

                        if (!extractor.advance()) {
                            break;
                        }
                    }

                    Log.d(TAG, String.format("视频%d: 处理了%d帧", fileIndex + 1, videoFrames));

                    // 6. 处理音频轨道（如果有）
                    if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                        extractor.unselectTrack(videoTrackIndex);
                        extractor.selectTrack(audioTrackIndex);
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                        int audioFrames = 0;
                        long lastAudioPts = -1;

                        while (true) {
                            audioBuffer.clear();
                            int sampleSize = extractor.readSampleData(audioBuffer, 0);
                            if (sampleSize < 0) break;

                            int flags = extractor.getSampleFlags();

                            audioBuffer.position(0);
                            audioBuffer.limit(sampleSize);

                            // 计算音频时间戳（基于视频时间戳）
                            long ptsToUse = currentAudioPts;

                            // 确保音频时间戳递增
                            if (lastAudioPts >= 0 && ptsToUse <= lastAudioPts) {
                                ptsToUse = lastAudioPts + Constants.MICROSECONDS_PER_MILLISECOND; // 增加1ms
                            }
                            lastAudioPts = ptsToUse;

                            audioInfo.set(0, sampleSize, ptsToUse,
                                    convertSampleFlagsToBufferFlags(flags));

                            try {
                                muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo);
                                audioFrames++;
                                totalAudioFrames++;

                                // 计算下一帧的音频时间戳
                                MediaFormat trackFormat = extractor.getTrackFormat(audioTrackIndex);
                                if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    int sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                    // 使用TimeUtils计算音频帧时长
                                    long frameDuration = TimeUtils.calculateAudioFrameDuration(sampleRate, Constants.AUDIO_SAMPLES_PER_FRAME);
                                    currentAudioPts += frameDuration;
                                } else {
                                    currentAudioPts += Constants.AUDIO_FRAME_DURATION_DEFAULT; // 默认21ms
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "写入音频帧失败: " + e.getMessage(), e);
                                break;
                            }

                            if (!extractor.advance()) {
                                break;
                            }
                        }

                        Log.d(TAG, String.format("音频%d: 处理了%d帧", fileIndex + 1, audioFrames));

                        // 重新选择视频轨道
                        extractor.unselectTrack(audioTrackIndex);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "处理视频" + (fileIndex + 1) + "失败", e);
                    updateStatus(callback, "处理视频" + (fileIndex + 1) + "失败: " + e.getMessage(), -1);
                }
            }

            // 7. 确保正确停止muxer
            try {
                muxer.stop();
                Log.d(TAG, "Muxer已停止，开始验证输出文件");

                // 检查输出文件
                File outputFile = new File(outputPath);
                if (outputFile.exists()) {
                    long fileSize = outputFile.length();
                    Log.d(TAG, String.format("输出文件信息: 路径=%s, 大小=%d字节, 是否存在=%s",
                            outputPath, fileSize, outputFile.exists()));

                    if (fileSize > 0) {
                        updateStatus(callback, String.format("合并完成！视频%d帧，音频%d帧，大小: %s",
                                totalVideoFrames, totalAudioFrames,
                                FileUtils.getFileSizeDescription(outputPath)), 95);
                        return true;
                    } else {
                        updateStatus(callback, "合并失败：输出文件大小为0", 0);
                        return false;
                    }
                } else {
                    updateStatus(callback, "合并失败：输出文件未创建", 0);
                    return false;
                }

            } catch (Exception e) {
                Log.e(TAG, "停止muxer失败", e);
                updateStatus(callback, "停止muxer失败: " + e.getMessage(), 0);
                return false;
            }

        } catch (Exception e) {
            updateStatus(callback, "合并出错：" + e.getMessage(), 0);
            Log.e(TAG, "mergeVideosWithAudio error", e);
            return false;
        } finally {
            // 释放资源
            for (MediaExtractor extractor : extractors) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放提取器失败", e);
                }
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

            ByteBuffer buf = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
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
     * 获取视频信息
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
                            Constants.VIDEO_INFO_FORMAT, w, h, br / 1000, rate);
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
            targetBitrate = Math.max(targetBitrate, Constants.MIN_BITRATE); // 最小500kbps

            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;

            Log.d(TAG, String.format("视频信息: 时长%.2f秒, 码率%d->%d (压缩比%.2f)",
                    TimeUtils.microsecondsToSeconds(duration), srcBitrate, targetBitrate, ratio));

            updateStatus(callback, String.format("视频信息：%.1f秒，码率%d->%dkbps",
                    TimeUtils.microsecondsToSeconds(duration), srcBitrate/1000, targetBitrate/1000), 5);

            // 根据时长智能选择转码方式
            if (TimeUtils.microsecondsToSeconds(duration) < Constants.MIN_VIDEO_DURATION_FOR_PARALLEL_SECONDS) { // 小于30秒
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
                    ? inVideoFmt.getInteger(MediaFormat.KEY_FRAME_RATE) : Constants.DEFAULT_FRAME_RATE;
            int srcBr = inVideoFmt.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? inVideoFmt.getInteger(MediaFormat.KEY_BIT_RATE) : 8_000_000;
            int dstBr = (int) (srcBr * ratio);
            dstBr = Math.max(dstBr, Constants.MIN_BITRATE);

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
            MediaFormat outVideoFmt;

            // 使用CodecUtils创建编码器格式
            if (CodecUtils.isHevcEncoderSupported()) {
                outVideoFmt = CodecUtils.createHevcFormat(width, height, dstBr, frameRate);
                Log.d(TAG, "使用HEVC编码器");
            } else {
                outVideoFmt = CodecUtils.createAvcFormat(width, height, dstBr, frameRate);
                Log.d(TAG, "HEVC编码器不可用，使用AVC编码器");
            }

            updateStatus(callback, "创建编码器...", 25);

            // 4. 创建编解码器
            String encoderMimeType = outVideoFmt.getString(MediaFormat.KEY_MIME);
            encoder = CodecUtils.createEncoder(encoderMimeType, outVideoFmt);
            if (encoder == null) {
                updateStatus(callback, "创建编码器失败", 0);
                return false;
            }

            decoder = CodecUtils.createDecoder(inVideoFmt.getString(MediaFormat.KEY_MIME), inVideoFmt);
            if (decoder == null) {
                updateStatus(callback, "创建解码器失败", 0);
                return false;
            }

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
                    int inIndex = decoder.dequeueInputBuffer(Constants.TIMEOUT_US);
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
                int decIndex = decoder.dequeueOutputBuffer(decInfo, Constants.TIMEOUT_US);
                if (decIndex >= 0) {
                    // 解码器输出一帧，现在编码这一帧
                    int encInIndex = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
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
                int encIndex = encoder.dequeueOutputBuffer(encInfo, Constants.TIMEOUT_US);
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
                        if (currentTime - lastProgressUpdateTime > Constants.PROGRESS_UPDATE_INTERVAL_MS || frameCount % 50 == 0) {
                            if (duration > 0) {
                                long processedTime = encInfo.presentationTimeUs + videoStartTime;
                                int progress = TimeUtils.calculateProgress(processedTime, duration, 40, 90);
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

                ByteBuffer audioBuf = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
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

                    aInfo.set(0, size, pts - audioStartTime, convertSampleFlagsToBufferFlags(ext.getSampleFlags()));
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
            CodecUtils.safeReleaseCodec(encoder);
            CodecUtils.safeReleaseCodec(decoder);
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

            int audioTrackIndex = -1;
            MediaFormat videoFormat = null, audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoFormat = format;
                } else if (mime.startsWith("audio/")) {
                    audioTrackIndex = i;
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
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : Constants.DEFAULT_FRAME_RATE;

            // 估算总帧数
            double totalSeconds = TimeUtils.microsecondsToSeconds(duration);
            long estimatedFrames = (long)(totalSeconds * frameRate);
            estimatedTotalFrames.set(estimatedFrames);

            updateStatus(callback, String.format("视频信息: %dx%d, %dfps, 时长: %.1fs, 估计总帧数: %d",
                    width, height, frameRate, totalSeconds, estimatedFrames), 10);

            // 2. 提取音频到临时文件
            if (audioTrackIndex >= 0 && audioFormat != null) {
                updateStatus(callback, "提取音频...", 15);
                audioFile = extractAudioToFile(extractor, audioTrackIndex, audioFormat, callback);
                if (audioFile != null) {
                    updateStatus(callback, String.format("音频提取完成: %s",
                            FileUtils.getFileSizeDescription(audioFile.getAbsolutePath())), 20);
                }
            } else {
                updateStatus(callback, "未找到音频轨道，继续处理视频", 20);
            }

            // 3. 动态计算线程数和分片大小
            int availableCores = Runtime.getRuntime().availableProcessors();
            Log.d(TAG, "可用处理器核心数: " + availableCores);


            Log.d(TAG, String.format("duration计算: duration=%d微秒(%.1f秒), 10分钟阈值=%d微秒, 比较结果=%s",
                    duration,
                    TimeUtils.microsecondsToSeconds(duration),
                    TimeUtils.secondsToMicroseconds(10 * 60),
                    duration > TimeUtils.secondsToMicroseconds(10 * 60) ? "大于" : "小于等于"));

            // 根据视频时长和核心数确定线程数
            int maxThreads;
            if (duration > TimeUtils.secondsToMicroseconds(10 * 60)) { // 超过10分钟
                maxThreads = Constants.MAX_THREADS_FOR_LONG_VIDEO;
            } else if (duration > TimeUtils.secondsToMicroseconds(5 * 60)) { // 5-10分钟
                maxThreads = Math.min(Constants.MAX_THREADS_FOR_MEDIUM_VIDEO, availableCores - 1);
            } else { // 30秒-5分钟
                maxThreads = Math.min(Constants.MAX_THREADS_FOR_SHORT_VIDEO, availableCores);
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
                    if (segmentDuration < Constants.MIN_SEGMENT_DURATION_US) {
                        // 跳过太短的分片，将其合并到下一个分片
                        if (i < targetSegments - 1) {
                            targetSegments--;
                            continue;
                        }
                    }

                    segmentStarts.add(segmentStart);
                    segmentEnds.add(segmentEnd);

                    // 估算分片帧数
                    double segmentSeconds = TimeUtils.microsecondsToSeconds(segmentDuration);
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
                    double segmentSeconds = TimeUtils.microsecondsToSeconds(segmentEnd - segmentStart);
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
                    FileUtils.safeDeleteFile(audioFile);
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
            segmentBitrate = Math.max(segmentBitrate, Constants.MIN_SEGMENT_BITRATE);

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
                                Constants.PREFIX_VIDEO_SEGMENT + segmentIndex + "_",
                                Constants.FILE_EXT_MP4,
                                getCacheDir()
                        );

                        Log.d(TAG, String.format("分片%d开始转码: [%.1fs-%.1fs], 码率: %dkbps, 估计帧数: %d",
                                segmentIndex,
                                TimeUtils.microsecondsToSeconds(startTime),
                                TimeUtils.microsecondsToSeconds(endTime),
                                segBitrate/1000, segmentFramesEstimate));

                        // 创建分片进度回调
                        MainActivity.ProgressCallback segmentCallback = new MainActivity.ProgressCallback() {
                            @Override
                            public void onProgressUpdate(String status, int segmentProgress) {
                                // 这里可以空着，我们使用统一的进度更新机制
                            }
                        };

                        // 创建帧数回调，用于实时更新帧数
                        FrameCounterCallback frameCounter = frames -> {
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

                        if (success && tempFile.exists() && tempFile.length() > Constants.MIN_FILE_SIZE_KB * Constants.BYTES_PER_KB) {
                            // 验证生成的视频
                            long segDuration = endTime - startTime;
                            long fileSize = tempFile.length();
                            double actualBitrate = TimeUtils.calculateBitrate(fileSize, TimeUtils.microsecondsToSeconds(segDuration));

                            completedSegments.incrementAndGet();
                            updateStatus(callback, String.format("分片%d完成: %s",
                                            segmentIndex, FileUtils.getFileSizeDescription(tempFile.getAbsolutePath())),
                                    40 + (completedSegments.get() * 40 / actualSegmentCount));

                            Log.d(TAG, String.format("分片%d转码成功: 大小%s, 时长%.1fs, 实际码率%.1fkbps",
                                    segmentIndex, FileUtils.getFileSizeDescription(tempFile.getAbsolutePath()),
                                    TimeUtils.microsecondsToSeconds(segDuration),
                                    actualBitrate));
                            return tempFile;
                        } else {
                            Log.e(TAG, String.format("分片%d转码失败", segmentIndex));
                            if (tempFile.exists()) {
                                FileUtils.safeDeleteFile(tempFile);
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

                    if (segmentFile != null && segmentFile.exists() && segmentFile.length() > Constants.MIN_FILE_SIZE_KB * Constants.BYTES_PER_KB) {
                        videoSegments.add(segmentFile);
                        Log.d(TAG, "分片" + i + "完成，大小: " + FileUtils.getFileSizeDescription(segmentFile.getAbsolutePath()));
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
                FileUtils.cleanupTempFiles(videoSegments.toArray(new File[0]));
                FileUtils.cleanupTempFiles(mergedVideoFile, audioFile);
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdownNow();
                }
                return false;
            }

            updateStatus(callback, String.format("所有%d个分片转码完成，共%d帧", videoSegments.size(), totalFramesEncoded.get()), 80);
            executor.shutdown();

            // 8. 合并视频分片
            updateStatus(callback, "开始合并视频分片...", 85);
            mergedVideoFile = File.createTempFile(Constants.PREFIX_MERGED, Constants.FILE_EXT_MP4, getCacheDir());

            boolean mergeSuccess = mergeVideoSegmentsEnhanced(videoSegments, mergedVideoFile.getAbsolutePath(),
                    segmentStarts, callback);
            if (!mergeSuccess) {
                updateStatus(callback, "合并视频分片失败", 0);
                Log.e(TAG, "合并视频分片失败");
                FileUtils.cleanupTempFiles(videoSegments.toArray(new File[0]));
                FileUtils.cleanupTempFiles(mergedVideoFile, audioFile);
                return false;
            }

            // 验证合并后的视频
            long mergedSize = mergedVideoFile.length();
            double mergedBitrate = TimeUtils.calculateBitrate(mergedSize, TimeUtils.microsecondsToSeconds(duration));
            updateStatus(callback, String.format("视频合并完成: %s", FileUtils.getFileSizeDescription(mergedVideoFile.getAbsolutePath())), 90);
            Log.d(TAG, String.format("视频分片合并成功: 大小%s, 实际码率%.1fkbps",
                    FileUtils.getFileSizeDescription(mergedVideoFile.getAbsolutePath()), mergedBitrate));

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
                    double finalBitrate = TimeUtils.calculateBitrate(finalSize, TimeUtils.microsecondsToSeconds(duration));

                    updateStatus(callback, String.format("转码完成！最终大小: %s，总编码帧数: %d",
                            FileUtils.getFileSizeDescription(dst), totalFramesEncoded.get()), 100);
                    Log.d(TAG, String.format("并行转码完成: 最终大小%s, 码率%.1fkbps, 压缩比%.2f, 总帧数%d",
                            FileUtils.getFileSizeDescription(dst), finalBitrate,
                            TimeUtils.calculateCompressionRatioValue(targetBitrate, (long)finalBitrate), totalFramesEncoded.get()));
                }
            } else {
                updateStatus(callback, "音频合并失败", 0);
            }

            // 11. 清理临时文件
            FileUtils.cleanupTempFiles(videoSegments.toArray(new File[0]));
            FileUtils.cleanupTempFiles(mergedVideoFile, audioFile);

            Log.d(TAG, "并行转码完成，结果: " + finalMergeSuccess);
            return finalMergeSuccess;

        } catch (Exception e) {
            updateStatus(callback, "并行转码出错：" + e.getMessage(), 0);
            Log.e(TAG, "compressParallelWithOriginalAudio error", e);
            // 发生异常时清理临时文件
            FileUtils.cleanupTempFiles(videoSegments.toArray(new File[0]));
            FileUtils.cleanupTempFiles(mergedVideoFile, audioFile);
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
            ByteBuffer videoBuffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            int videoFrameCount = 0;

            while (true) {
                int size = videoExtractor.readSampleData(videoBuffer, 0);
                if (size < 0) break;

                long pts = videoExtractor.getSampleTime();
                videoInfo.set(0, size, pts, convertSampleFlagsToBufferFlags(videoExtractor.getSampleFlags()));
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
                ByteBuffer audioBuffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

                while (true) {
                    int size = audioExtractor.readSampleData(audioBuffer, 0);
                    if (size < 0) break;

                    long pts = audioExtractor.getSampleTime();
                    audioInfo.set(0, size, pts, convertSampleFlagsToBufferFlags(audioExtractor.getSampleFlags()));
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
                            segIndex, TimeUtils.microsecondsToSeconds(segmentStartTime)));
                }

                ByteBuffer buffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
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

                    info.set(0, size, globalPts, convertSampleFlagsToBufferFlags(flags));

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
                        segIndex, frameCount, TimeUtils.microsecondsToSeconds(segmentDuration)));
            }

            muxer.stop();

            Log.d(TAG, String.format("视频合并完成: 总%d帧, 总时长%.3fs",
                    totalFrames, TimeUtils.microsecondsToSeconds(totalDuration)));
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
                    segmentIndex,
                    TimeUtils.microsecondsToSeconds(startTime),
                    TimeUtils.microsecondsToSeconds(endTime),
                    TimeUtils.microsecondsToSeconds(segmentDuration),
                    targetBitrate/1000));

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
            Log.d(TAG, String.format("分片%d: 实际起始时间 %.3fs", segmentIndex,
                    TimeUtils.microsecondsToSeconds(actualStartTime)));

            // 3. 获取视频参数
            int width = originalFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = originalFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = originalFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? originalFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : Constants.DEFAULT_FRAME_RATE;

            // 4. 配置编码器
            MediaFormat encoderFormat;
            String codecMime = Constants.MIME_TYPE_HEVC;

            if (!CodecUtils.isHevcEncoderSupported()) {
                codecMime = Constants.MIME_TYPE_AVC;
                if (!CodecUtils.isAvcEncoderSupported()) {
                    Log.e(TAG, "分片" + segmentIndex + ": 未找到合适的编码器");
                    return false;
                }
            }

            // 使用CodecUtils创建编码器格式
            if (codecMime.equals(Constants.MIME_TYPE_HEVC)) {
                encoderFormat = CodecUtils.createHevcFormat(width, height, targetBitrate, frameRate);
            } else {
                encoderFormat = CodecUtils.createAvcFormat(width, height, targetBitrate, frameRate);
            }

            updateStatus(callback, "创建编码器...", 30);
            Log.d(TAG, String.format("分片%d: 使用%s编码器，码率%dkbps",
                    segmentIndex, codecMime, targetBitrate/1000));

            // 5. 创建编码器和解码器
            encoder = CodecUtils.createEncoder(codecMime, encoderFormat);
            if (encoder == null) {
                Log.e(TAG, "分片" + segmentIndex + ": 创建编码器失败");
                return false;
            }

            String decodeMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = CodecUtils.createDecoder(decodeMime, inputFormat);
            if (decoder == null) {
                Log.e(TAG, "分片" + segmentIndex + ": 创建解码器失败");
                return false;
            }

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
                    int inputIdx = decoder.dequeueInputBuffer(Constants.TIMEOUT_US);
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
                int decoderIdx = decoder.dequeueOutputBuffer(decInfo, Constants.TIMEOUT_US);
                if (decoderIdx >= 0) {
                    if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 通知编码器输入结束
                        int encoderIdx = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
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
                            int encoderIdx = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
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
                int encoderIdx = encoder.dequeueOutputBuffer(encInfo, Constants.TIMEOUT_US);
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
                        if (currentTime - lastUpdateTime > Constants.PROGRESS_UPDATE_INTERVAL_MS || frameCount % Constants.FRAME_COUNT_UPDATE_INTERVAL == 0) {
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
            CodecUtils.safeReleaseCodec(encoder);
            CodecUtils.safeReleaseCodec(decoder);
            if (muxer != null) {
                try { muxer.stop(); muxer.release(); } catch (Exception e) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception e) {}
            }
        }
    }

    /**
     * 提取音频到临时文件 - 添加进度回调
     */
    private static File extractAudioToFile(MediaExtractor extractor, int audioTrack, MediaFormat audioFormat,
                                           MainActivity.ProgressCallback callback) {
        MediaMuxer muxer = null;
        try {
            File tempFile = File.createTempFile(Constants.PREFIX_AUDIO, Constants.FILE_EXT_AAC, getCacheDir());

            muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            extractor.selectTrack(audioTrack);
            ByteBuffer buffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_1MB);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long startTime = -1;
            int audioFrameCount = 0;

            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;

                long pts = extractor.getSampleTime();
                if (startTime < 0) startTime = pts;

                info.set(0, size, pts - startTime, convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                muxer.writeSampleData(trackIndex, buffer, info);
                audioFrameCount++;
                extractor.advance();

                if (audioFrameCount % 100 == 0) {
                    updateStatus(callback, String.format("已提取 %d 帧音频", audioFrameCount), -1);
                }
            }

            muxer.stop();

            updateStatus(callback, String.format("音频提取完成: %d 帧", audioFrameCount), 100);
            Log.d(TAG, "音频提取完成，共 " + audioFrameCount + " 帧，大小: " +
                    FileUtils.getFileSizeDescription(tempFile.getAbsolutePath()));
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
            Log.d(TAG, String.format("增强版关键帧查找，视频时长: %.1f秒", TimeUtils.microsecondsToSeconds(duration)));
            updateStatus(callback, "分析视频关键帧...", -1);

            // 总是添加开始和结束位置
            keyFrames.add(0L);

            // 根据线程数确定采样密度
            long sampleInterval = getSampleInterval(duration, maxThreads);

            Log.d(TAG, String.format("采样间隔: %.1f秒", TimeUtils.microsecondsToSeconds(sampleInterval)));

            extractor = new MediaExtractor();
            extractor.setDataSource(dataSource);
            extractor.selectTrack(videoTrack);

            // 采样关键帧位置
            long currentTime = 0;
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

        Log.d(TAG, String.format("maxThreads计算: 分支=%s, MAX_THREADS_FOR_LONG_VIDEO=%d, 结果=%d",
                (duration > TimeUtils.secondsToMicroseconds(10 * 60)) ? "长视频" :
                        (duration > TimeUtils.secondsToMicroseconds(5 * 60)) ? "中视频" : "短视频",
                Constants.MAX_THREADS_FOR_LONG_VIDEO,
                maxThreads));

        long sampleInterval;
        if (duration > TimeUtils.secondsToMicroseconds(30 * 60)) { // 超过30分钟
            sampleInterval = TimeUtils.secondsToMicroseconds(20); // 20秒
        } else if (duration > TimeUtils.secondsToMicroseconds(10 * 60)) { // 10-30分钟
            sampleInterval = TimeUtils.secondsToMicroseconds(10); // 10秒
        } else if (duration > TimeUtils.secondsToMicroseconds(5 * 60)) { // 5-10分钟
            sampleInterval = TimeUtils.secondsToMicroseconds(5); // 5秒
        } else {
            sampleInterval = TimeUtils.secondsToMicroseconds(3); // 3秒
        }

        // 根据最大线程数调整采样间隔，确保有足够的关键帧
        int targetKeyFrames = maxThreads * Constants.KEY_FRAMES_PER_SEGMENT;
        long calculatedInterval = duration / targetKeyFrames;
        sampleInterval = Math.max(sampleInterval, calculatedInterval);
        return sampleInterval;
    }

    /**
     * 设置视频封面（完整修复版）- 修复复用器启动和编码器格式问题
     */
    public static boolean setVideoCover(String inputPath, String outputPath,
                                        Bitmap coverBitmap,
                                        MainActivity.ProgressCallback callback) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        MediaCodec videoDecoder = null;
        MediaCodec videoEncoder = null;

        int muxerVideoTrack = -1;
        int muxerAudioTrack = -1;
        MediaFormat encoderOutputFormat = null;
        boolean muxerStarted = false;

        try {
            updateStatus(callback, "开始设置视频封面...", 0);

            // 1. 验证输入文件
            if (!VideoUtils.isValidVideoFile(inputPath)) {
                updateStatus(callback, "错误：输入文件不存在或为空", 0);
                Log.e(TAG, "无效的输入文件: " + inputPath);
                return false;
            }

            if (coverBitmap == null || coverBitmap.isRecycled()) {
                updateStatus(callback, "错误：封面图片无效", 0);
                Log.e(TAG, "无效的封面图片");
                return false;
            }

            // 2. 分析原始视频
            updateStatus(callback, "分析视频信息...", 10);
            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(inputPath);
            } catch (Exception e) {
                updateStatus(callback, "无法读取视频文件: " + e.getMessage(), 0);
                Log.e(TAG, "设置数据源失败", e);
                return false;
            }

            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            long videoDuration = 0;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDuration = format.getLong(MediaFormat.KEY_DURATION);
                    }
                    Log.d(TAG, "找到视频轨道: " + format);
                } else if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    Log.d(TAG, "找到音频轨道: " + format);
                }
            }

            if (videoFormat == null) {
                updateStatus(callback, "错误：未找到视频轨道", 0);
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 3. 获取视频参数
            int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : Constants.DEFAULT_FRAME_RATE;
            int bitrate = videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) : Constants.DEFAULT_BITRATE;
            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);

            updateStatus(callback, String.format("视频信息: %dx%d, %dfps, 码率: %dkbps",
                    width, height, frameRate, bitrate / 1000), 15);

            Log.d(TAG, String.format("视频参数: %dx%d, %dfps, 码率%dkbps, MIME: %s",
                    width, height, frameRate, bitrate / 1000, videoMime));

            // 4. 调整封面尺寸
            updateStatus(callback, "处理封面图片...", 20);
            Bitmap resizedCover = resizeAndCropBitmap(coverBitmap, width, height);
            if (resizedCover == null) {
                updateStatus(callback, "封面图片处理失败", 0);
                Log.e(TAG, "无法调整封面尺寸");
                return false;
            }

            // 5. 创建输出文件
            updateStatus(callback, "准备输出文件...", 25);
            try {
                muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                updateStatus(callback, "创建输出文件失败: " + e.getMessage(), 0);
                Log.e(TAG, "创建MediaMuxer失败", e);
                return false;
            }

            // 6. 创建视频编码器（使用原始视频参数）
            updateStatus(callback, "创建视频编码器...", 30);

            String encoderMime = videoMime;
            // 如果HEVC不可用，降级到AVC
            if (encoderMime.equals(Constants.MIME_TYPE_HEVC) && !CodecUtils.isHevcEncoderSupported()) {
                encoderMime = Constants.MIME_TYPE_AVC;
                Log.w(TAG, "HEVC编码器不可用，降级到AVC");
            }

            MediaFormat encoderFormat = MediaFormat.createVideoFormat(encoderMime, width, height);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Constants.I_FRAME_INTERVAL_SECONDS);
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // 设置编码器参数
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            }

            try {
                videoEncoder = MediaCodec.createEncoderByType(encoderMime);
                videoEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();
                Log.d(TAG, "视频编码器创建成功: " + encoderMime);
            } catch (Exception e) {
                updateStatus(callback, "创建编码器失败: " + e.getMessage(), 0);
                Log.e(TAG, "创建视频编码器失败", e);
                return false;
            }

            // 7. 创建视频解码器
            try {
                videoDecoder = MediaCodec.createDecoderByType(videoMime);
                videoDecoder.configure(videoFormat, null, null, 0);
                videoDecoder.start();
                Log.d(TAG, "视频解码器创建成功: " + videoMime);
            } catch (Exception e) {
                updateStatus(callback, "创建解码器失败: " + e.getMessage(), 0);
                Log.e(TAG, "创建视频解码器失败", e);
                return false;
            }

            // 8. 先添加音频轨道（如果存在）
            if (audioFormat != null) {
                muxerAudioTrack = muxer.addTrack(audioFormat);
                Log.d(TAG, "添加音频轨道到复用器，索引: " + muxerAudioTrack);
            }

            // 9. 编码封面帧并获取输出格式
            updateStatus(callback, "编码封面帧...", 35);

            // 首先获取编码器的输出格式（必须在提交数据前获取）
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long startTime = System.currentTimeMillis();

            // 等待编码器输出格式变更
            while (encoderOutputFormat == null &&
                    System.currentTimeMillis() - startTime < Constants.DEFAULT_TIMEOUT_MS) {
                int encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US);

                if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoderOutputFormat = videoEncoder.getOutputFormat();
                    Log.d(TAG, "获取到编码器输出格式: " + encoderOutputFormat);
                } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 没有输出可用，提交一个空帧以触发格式变更
                    int inputIndex = videoEncoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                    if (inputIndex >= 0) {
                        videoEncoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        Log.d(TAG, "提交空帧以触发格式变更");
                    }
                }

                if (encoderOutputFormat == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            if (encoderOutputFormat == null) {
                Log.e(TAG, "无法获取编码器输出格式");
                return false;
            }

            // 添加视频轨道到复用器
            muxerVideoTrack = muxer.addTrack(encoderOutputFormat);
            Log.d(TAG, "添加视频轨道到复用器，索引: " + muxerVideoTrack);

            // 启动复用器
            muxer.start();
            muxerStarted = true;
            Log.d(TAG, "复用器已启动，视频轨道: " + muxerVideoTrack + ", 音频轨道: " + muxerAudioTrack);

            // 10. 编码封面帧并写入复用器
            updateStatus(callback, "生成封面帧...", 40);
            boolean coverEncoded = encodeCoverFrame(videoEncoder, resizedCover,
                    width, height, frameRate,
                    muxer, muxerVideoTrack, callback);

            if (!coverEncoded) {
                updateStatus(callback, "封面编码失败", 0);
                Log.e(TAG, "封面帧编码失败");
                return false;
            }

            // 回收封面图片
            if (!resizedCover.isRecycled()) {
                resizedCover.recycle();
            }

            // 11. 处理原始视频内容（重新编码）
            updateStatus(callback, "处理原始视频内容...", 45);

            // 选择视频轨道
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            boolean success = reencodeVideoWithCover(extractor, videoDecoder, videoEncoder,
                    muxer, muxerVideoTrack, muxerAudioTrack,
                    videoDuration, frameRate, callback);

            // 12. 处理音频轨道（如果有）
            if (success && audioTrackIndex != -1 && muxerAudioTrack != -1) {
                updateStatus(callback, "处理音频轨道...", 90);
                success = copyAudioTrack(extractor, audioTrackIndex, muxer, muxerAudioTrack, frameRate);
            }

            // 13. 完成
            if (success) {
                updateStatus(callback, "正在保存文件...", 98);

                // 发送编码结束标志
                if (videoEncoder != null) {
                    try {
                        int inputIndex = videoEncoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                        if (inputIndex >= 0) {
                            videoEncoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            Log.d(TAG, "发送编码结束标志");
                        }

                        // 等待所有输出完成
                        while (true) {
                            int encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US);
                            if (encoderStatus >= 0) {
                                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    Log.d(TAG, "编码器输出结束");
                                    break;
                                }
                                videoEncoder.releaseOutputBuffer(encoderStatus, false);
                            } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "发送编码结束标志失败", e);
                    }
                }

                try {
                    if (muxer != null && muxerStarted) {
                        muxer.stop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "停止复用器失败", e);
                }

                // 验证输出文件
                File outputFile = new File(outputPath);
                if (outputFile.exists() && outputFile.length() > 0) {
                    long outputSize = outputFile.length();
                    updateStatus(callback, String.format("封面设置完成！文件大小: %s",
                            FileUtils.getFileSizeDescription(outputPath)), 100);
                    Log.d(TAG, String.format("封面设置完成: 输入=%s, 输出=%s, 大小=%s",
                            inputPath, outputPath, FileUtils.getFileSizeDescription(outputPath)));
                    return true;
                } else {
                    updateStatus(callback, "输出文件创建失败", 0);
                    return false;
                }
            } else {
                updateStatus(callback, "处理视频内容失败", 0);
                return false;
            }

        } catch (Exception e) {
            updateStatus(callback, "设置封面失败: " + e.getMessage(), 0);
            Log.e(TAG, "setVideoCover error", e);
            return false;
        } finally {
            // 清理资源
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                    videoEncoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放编码器失败", e);
                }
            }

            if (videoDecoder != null) {
                try {
                    videoDecoder.stop();
                    videoDecoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放解码器失败", e);
                }
            }

            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        try {
                            muxer.stop();
                        } catch (Exception e) {
                            Log.e(TAG, "停止复用器失败", e);
                        }
                    }
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放复用器失败", e);
                }
            }

            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放提取器失败", e);
                }
            }
        }
    }

    /**
     * 编码封面帧（修复版）- 直接写入复用器
     */
    private static boolean encodeCoverFrame(MediaCodec encoder, Bitmap cover,
                                            int width, int height, int frameRate,
                                            MediaMuxer muxer, int muxerVideoTrack,
                                            MainActivity.ProgressCallback callback) {
        try {
            updateStatus(callback, "将封面转换为YUV格式...", -1);

            // 1. 将Bitmap转换为YUV420格式
            byte[] yuvData = bitmapToYuv420(cover, width, height);
            if (yuvData == null) {
                Log.e(TAG, "YUV数据转换失败");
                return false;
            }

            // 2. 获取编码器输入缓冲区
            int inputBufferIndex = -1;
            long startTime = System.currentTimeMillis();

            while (inputBufferIndex < 0 &&
                    System.currentTimeMillis() - startTime < Constants.DEFAULT_TIMEOUT_MS) {
                inputBufferIndex = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                if (inputBufferIndex < 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            if (inputBufferIndex < 0) {
                Log.e(TAG, "获取编码器输入缓冲区超时");
                return false;
            }

            // 3. 输入YUV数据到编码器
            ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer == null) {
                Log.e(TAG, "获取编码器输入缓冲区失败");
                return false;
            }

            inputBuffer.clear();
            inputBuffer.put(yuvData);

            // 第一帧必须是关键帧，时间戳为0
            int flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
            long presentationTimeUs = 0;

            encoder.queueInputBuffer(inputBufferIndex, 0, yuvData.length,
                    presentationTimeUs, flags);

            Log.d(TAG, "封面帧已提交编码，大小: " + yuvData.length + " bytes");

            // 4. 等待封面帧编码完成并写入复用器
            updateStatus(callback, "等待编码器处理封面帧...", -1);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean coverWritten = false;
            startTime = System.currentTimeMillis();

            while (!coverWritten &&
                    System.currentTimeMillis() - startTime < Constants.DEFAULT_TIMEOUT_MS) {
                int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US);

                if (encoderStatus >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 编码配置信息，释放但不写入
                        encoder.releaseOutputBuffer(encoderStatus, false);
                        continue;
                    }

                    if (bufferInfo.size > 0) {
                        // 封面帧编码完成，写入复用器
                        ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                        if (encodedData != null) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);

                            try {
                                muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);
                                coverWritten = true;
                                Log.d(TAG, "封面帧写入完成，大小: " + bufferInfo.size + " bytes");
                            } catch (Exception e) {
                                Log.e(TAG, "写入封面帧失败: " + e.getMessage(), e);
                                encoder.releaseOutputBuffer(encoderStatus, false);
                                return false;
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "编码器输出结束");
                        break;
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 编码器输出格式已更改，继续等待实际帧数据
                    Log.d(TAG, "编码器输出格式变更");
                } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 没有输出可用，稍等
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            if (!coverWritten) {
                Log.e(TAG, "等待封面帧编码完成超时");
                return false;
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "编码封面帧失败", e);
            return false;
        }
    }

    /**
     * 重新编码视频（封面之后的内容）- 修复版
     */
    private static boolean reencodeVideoWithCover(MediaExtractor extractor,
                                                  MediaCodec decoder, MediaCodec encoder,
                                                  MediaMuxer muxer, int muxerVideoTrack, int muxerAudioTrack,
                                                  long videoDuration, int frameRate,
                                                  MainActivity.ProgressCallback callback) {

        try {
            updateStatus(callback, "开始重新编码视频内容...", 50);

            MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

            boolean inputEos = false;
            boolean outputEos = false;

            // 记录当前时间戳（从封面帧之后开始）
            long currentPts = 1000000L / frameRate; // 封面帧显示一帧的时间
            int totalFrames = 0;
            long lastProgressUpdateTime = System.currentTimeMillis();

            while (!outputEos) {
                // 1. 向解码器输入数据
                if (!inputEos) {
                    int inputIndex = decoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        inputBuffer.clear();

                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            // 输入结束
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                            Log.d(TAG, "解码器输入结束");
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // 2. 从解码器获取输出
                int decoderIndex = decoder.dequeueOutputBuffer(decoderInfo, Constants.TIMEOUT_US);
                if (decoderIndex >= 0) {
                    // 解码器输出一帧
                    if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 解码器输出结束，通知编码器
                        int encoderInputIndex = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                        if (encoderInputIndex >= 0) {
                            encoder.queueInputBuffer(encoderInputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            Log.d(TAG, "向编码器发送结束标志");
                        }
                    } else if (decoderInfo.size > 0) {
                        // 将解码后的帧送入编码器
                        int encoderInputIndex = encoder.dequeueInputBuffer(Constants.TIMEOUT_US);
                        if (encoderInputIndex >= 0) {
                            ByteBuffer encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex);
                            encoderInputBuffer.clear();

                            ByteBuffer decoderOutputBuffer = decoder.getOutputBuffer(decoderIndex);
                            if (decoderOutputBuffer != null) {
                                decoderOutputBuffer.position(decoderInfo.offset);
                                decoderOutputBuffer.limit(decoderInfo.offset + decoderInfo.size);
                                encoderInputBuffer.put(decoderOutputBuffer);

                                // 使用递增的时间戳
                                encoder.queueInputBuffer(encoderInputIndex, 0,
                                        decoderInfo.size, currentPts, 0);

                                // 下一帧时间戳（根据帧率）
                                currentPts += (1000000L / frameRate);
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(decoderIndex, false);
                }

                // 3. 从编码器获取输出并写入文件
                int encoderIndex = encoder.dequeueOutputBuffer(encoderInfo, Constants.TIMEOUT_US);
                if (encoderIndex >= 0) {
                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(encoderIndex, false);
                        continue;
                    }

                    if (encoderInfo.size > 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encoderIndex);
                        if (encodedData != null) {
                            encodedData.position(encoderInfo.offset);
                            encodedData.limit(encoderInfo.offset + encoderInfo.size);

                            try {
                                muxer.writeSampleData(muxerVideoTrack, encodedData, encoderInfo);
                                totalFrames++;

                                // 更新进度
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastProgressUpdateTime > Constants.PROGRESS_UPDATE_INTERVAL_MS ||
                                        totalFrames % 30 == 0) {
                                    if (videoDuration > 0) {
                                        double progress = 50 + ((double) encoderInfo.presentationTimeUs / videoDuration * 35);
                                        updateStatus(callback,
                                                String.format("已重新编码 %d 帧", totalFrames),
                                                Math.min((int) progress, 85));
                                    } else {
                                        updateStatus(callback, String.format("已重新编码 %d 帧", totalFrames), -1);
                                    }
                                    lastProgressUpdateTime = currentTime;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "写入重新编码的视频帧失败: " + e.getMessage(), e);
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encoderIndex, false);

                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                        Log.d(TAG, "重新编码完成，共 " + totalFrames + " 帧");
                    }
                } else if (encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 编码器输出格式已更改（应该已经在封面帧编码时处理了）
                    Log.d(TAG, "编码器输出格式变更（重新编码阶段）");
                } else if (encoderIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 没有输出可用，继续循环
                    if (inputEos) {
                        // 如果输入已结束，但没有输出，可能编码器还在处理
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            Log.d(TAG, "重新编码完成: " + totalFrames + " 帧");
            updateStatus(callback, String.format("重新编码完成: %d帧", totalFrames), 85);
            return totalFrames > 0;

        } catch (Exception e) {
            Log.e(TAG, "重新编码视频失败", e);
            return false;
        }
    }

    /**
     * 复制音频轨道 - 修复时间戳对齐
     */
    private static boolean copyAudioTrack(MediaExtractor extractor, int audioTrackIndex,
                                          MediaMuxer muxer, int muxerAudioTrack, int frameRate) {
        try {
            // 保存当前轨道选择状态
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                extractor.unselectTrack(i);
            }

            extractor.selectTrack(audioTrackIndex);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            ByteBuffer buffer = ByteBuffer.allocate(Constants.BUFFER_SIZE_512KB);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long firstPts = -1;
            int audioFrameCount = 0;

            // 音频起始时间与视频对齐（封面帧之后）
            long videoFrameDuration = 1000000L / frameRate; // 视频一帧的时长
            long audioStartOffset = videoFrameDuration; // 音频从封面帧后开始

            while (true) {
                buffer.clear();
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long pts = extractor.getSampleTime();
                if (firstPts == -1) {
                    firstPts = pts;
                }

                buffer.position(0);
                buffer.limit(sampleSize);

                // 音频时间戳从封面之后开始
                long newPts = audioStartOffset + (pts - firstPts);
                info.set(0, sampleSize, newPts,
                        convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                try {
                    muxer.writeSampleData(muxerAudioTrack, buffer, info);
                    audioFrameCount++;
                } catch (Exception e) {
                    Log.e(TAG, "写入音频帧失败", e);
                    break;
                }

                if (!extractor.advance()) {
                    break;
                }
            }

            Log.d(TAG, "音频复制完成: " + audioFrameCount + " 帧");
            return audioFrameCount > 0;

        } catch (Exception e) {
            Log.e(TAG, "复制音频轨道失败", e);
            return false;
        }
    }

    /**
     * 转换样本标志为缓冲区标志
     */
    private static int convertSampleFlagsToBufferFlags(int sampleFlags) {
        int flags = 0;
        if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        return flags;
    }

    /**
     * 调整和裁剪Bitmap以匹配视频尺寸
     */
    private static Bitmap resizeAndCropBitmap(Bitmap original, int targetWidth, int targetHeight) {
        if (original == null || original.isRecycled()) {
            return null;
        }

        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        // 如果尺寸完全匹配，直接返回副本
        if (originalWidth == targetWidth && originalHeight == targetHeight) {
            return original.copy(Bitmap.Config.ARGB_8888, true);
        }

        // 计算缩放比例，保持宽高比，填充整个目标区域（可能裁剪）
        float widthRatio = (float) targetWidth / originalWidth;
        float heightRatio = (float) targetHeight / originalHeight;
        float scale = Math.max(widthRatio, heightRatio);

        int scaledWidth = Math.round(originalWidth * scale);
        int scaledHeight = Math.round(originalHeight * scale);

        Log.d(TAG, String.format("调整Bitmap: %dx%d -> %dx%d (缩放比例: %.2f)",
                originalWidth, originalHeight, scaledWidth, scaledHeight, scale));

        // 创建缩放后的Bitmap
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        Bitmap scaledBitmap;
        try {
            scaledBitmap = Bitmap.createBitmap(original, 0, 0,
                    originalWidth, originalHeight, matrix, true);
        } catch (Exception e) {
            Log.e(TAG, "缩放Bitmap失败", e);
            return null;
        }

        // 如果需要裁剪
        if (scaledWidth > targetWidth || scaledHeight > targetHeight) {
            int startX = Math.max(0, (scaledWidth - targetWidth) / 2);
            int startY = Math.max(0, (scaledHeight - targetHeight) / 2);

            // 确保裁剪区域在边界内
            int cropWidth = Math.min(targetWidth, scaledWidth - startX);
            int cropHeight = Math.min(targetHeight, scaledHeight - startY);

            Log.d(TAG, String.format("裁剪Bitmap: 起始(%d,%d), 尺寸%dx%d",
                    startX, startY, cropWidth, cropHeight));

            try {
                Bitmap croppedBitmap = Bitmap.createBitmap(scaledBitmap,
                        startX, startY, cropWidth, cropHeight);
                scaledBitmap.recycle();
                return croppedBitmap;
            } catch (Exception e) {
                Log.e(TAG, "裁剪Bitmap失败", e);
                scaledBitmap.recycle();
                return null;
            }
        } else {
            // 不需要裁剪，但可能需要添加黑边
            if (scaledWidth < targetWidth || scaledHeight < targetHeight) {
                Bitmap paddedBitmap = Bitmap.createBitmap(targetWidth, targetHeight,
                        Bitmap.Config.ARGB_8888);

                Canvas canvas = new Canvas(paddedBitmap);
                canvas.drawColor(Color.BLACK);

                int left = (targetWidth - scaledWidth) / 2;
                int top = (targetHeight - scaledHeight) / 2;
                canvas.drawBitmap(scaledBitmap, left, top, null);

                scaledBitmap.recycle();
                return paddedBitmap;
            } else {
                return scaledBitmap;
            }
        }
    }

    /**
     * 将Bitmap转换为YUV420格式
     */
    private static byte[] bitmapToYuv420(Bitmap bitmap, int width, int height) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Bitmap无效");
            return null;
        }

        // 确保Bitmap尺寸正确
        if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
            Log.w(TAG, String.format("Bitmap尺寸不匹配: %dx%d -> %dx%d",
                    bitmap.getWidth(), bitmap.getHeight(), width, height));
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, width, height, true);
            bitmap.recycle();
            bitmap = resized;
        }

        // 分配YUV缓冲区
        int ySize = width * height;
        int uvSize = ySize / 2; // YUV420: Y占1/2，U和V各占1/4
        byte[] yuvData = new byte[ySize + uvSize];

        try {
            int[] argb = new int[width * height];
            bitmap.getPixels(argb, 0, width, 0, 0, width, height);

            int yIndex = 0;
            int uvIndex = ySize;

            // 转换每一行
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = argb[y * width + x];

                    // 提取RGB分量
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;

                    // 计算Y分量（亮度）
                    // BT.601标准公式: Y = 0.299R + 0.587G + 0.114B
                    int yValue = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    yuvData[yIndex++] = (byte) (Math.max(0, Math.min(255, yValue)));

                    // 每隔2x2像素计算一次UV分量
                    if (y % 2 == 0 && x % 2 == 0) {
                        // 计算2x2区域的平均RGB
                        int rSum = 0, gSum = 0, bSum = 0;
                        int count = 0;

                        for (int dy = 0; dy < 2 && y + dy < height; dy++) {
                            for (int dx = 0; dx < 2 && x + dx < width; dx++) {
                                int p = argb[(y + dy) * width + (x + dx)];
                                rSum += (p >> 16) & 0xFF;
                                gSum += (p >> 8) & 0xFF;
                                bSum += p & 0xFF;
                                count++;
                            }
                        }

                        if (count > 0) {
                            int rAvg = rSum / count;
                            int gAvg = gSum / count;
                            int bAvg = bSum / count;

                            // 计算U分量（色度）
                            // U = -0.147R - 0.289G + 0.436B + 128
                            int uValue = (int) (-0.147 * rAvg - 0.289 * gAvg + 0.436 * bAvg + 128);
                            yuvData[uvIndex++] = (byte) (Math.max(0, Math.min(255, uValue)));

                            // 计算V分量（色度）
                            // V = 0.615R - 0.515G - 0.100B + 128
                            int vValue = (int) (0.615 * rAvg - 0.515 * gAvg - 0.100 * bAvg + 128);
                            yuvData[uvIndex++] = (byte) (Math.max(0, Math.min(255, vValue)));
                        }
                    }
                }
            }

            Log.d(TAG, String.format("YUV转换完成: YUV大小=%d, 原始ARGB=%d",
                    yuvData.length, argb.length * 4));
            return yuvData;

        } catch (Exception e) {
            Log.e(TAG, "YUV转换异常", e);
            return null;
        }
    }

    /**
     * 提取视频封面（简单版本）
     * 只提取封面图片，不修改视频
     */
    public static Bitmap extractVideoCover(String videoPath, long timeMs,
                                           MainActivity.ProgressCallback callback) {
        try {
            updateStatus(callback, "正在提取封面...", 0);

            Bitmap cover = VideoUtils.extractCoverFromVideo(videoPath, timeMs);

            if (cover != null) {
                updateStatus(callback, "封面提取成功", 100);
                return cover;
            } else {
                updateStatus(callback, "封面提取失败", 0);
                return null;
            }

        } catch (Exception e) {
            updateStatus(callback, "提取封面出错: " + e.getMessage(), 0);
            Log.e(TAG, "extractVideoCover error", e);
            return null;
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
     * 获取应用缓存目录
     */
    public static File getCacheDir() {
        File dir = new File("/storage/emulated/0/Android/data/org.video/" + Constants.DIR_CACHE);
        if (!FileUtils.ensureDirectoryExists(dir)) {
            Log.e(TAG, "无法创建缓存目录: " + dir.getAbsolutePath());
            return new File("/storage/emulated/0/Android/data/org.video/cache");
        }
        return dir;
    }

    /**
     * 查找最近的关键帧
     */
    private static long findNearestKeyframe(MediaExtractor extractor, int videoTrackIndex, long targetTimeUs) {
        try {
            extractor.selectTrack(videoTrackIndex);

            // 保存当前状态
            long currentPosition = extractor.getSampleTime();

            // 向前查找关键帧
            extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long prevKeyframeTime = extractor.getSampleTime();

            // 恢复状态
            extractor.seekTo(currentPosition, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            // 向后查找关键帧
            extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_NEXT_SYNC);
            long nextKeyframeTime = extractor.getSampleTime();

            // 恢复到原始位置
            extractor.seekTo(currentPosition, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            // 选择更近的关键帧
            long prevDiff = Math.abs(targetTimeUs - prevKeyframeTime);
            long nextDiff = Math.abs(nextKeyframeTime - targetTimeUs);

            return (prevDiff <= nextDiff) ? prevKeyframeTime : nextKeyframeTime;

        } catch (Exception e) {
            Log.e(TAG, "查找关键帧失败", e);
            return targetTimeUs;
        }
    }
}

