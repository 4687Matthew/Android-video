package org.video.utils;

import java.util.Locale;

/**
 * 时间工具类
 */
public class TimeUtils {

    /**
     * 将微秒转换为秒（带小数）
     * @param microseconds 微秒
     * @return 秒
     */
    public static double microsecondsToSeconds(long microseconds) {
        return microseconds / (double) Constants.MICROSECONDS_PER_SECOND;
    }

    /**
     * 将秒转换为微秒
     * @param seconds 秒
     * @return 微秒
     */
    public static long secondsToMicroseconds(double seconds) {
        return (long) (seconds * Constants.MICROSECONDS_PER_SECOND);
    }

    /**
     * 将毫秒转换为微秒
     * @param milliseconds 毫秒
     * @return 微秒
     */
    public static long millisecondsToMicroseconds(long milliseconds) {
        return milliseconds * Constants.MICROSECONDS_PER_MILLISECOND;
    }

    /**
     * 将微秒转换为毫秒
     * @param microseconds 微秒
     * @return 毫秒
     */
    public static long microsecondsToMilliseconds(long microseconds) {
        return microseconds / Constants.MICROSECONDS_PER_MILLISECOND;
    }

    /**
     * 格式化时间显示（秒）
     * @param seconds 秒数
     * @return 格式化后的时间字符串
     */
    public static String formatSeconds(double seconds) {
        return String.format(Locale.getDefault(), "%.1f秒", seconds);
    }

    /**
     * 格式化时间显示（时:分:秒）
     * @param milliseconds 毫秒
     * @return 格式化后的时间字符串
     */
    public static String formatTimeHMS(long milliseconds) {
        long totalSeconds = milliseconds / Constants.MILLISECONDS_PER_SECOND;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), Constants.TIME_FORMAT_HMS, hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), Constants.TIME_FORMAT_MS, minutes, seconds);
        }
    }

    /**
     * 格式化时间显示（时:分:秒.毫秒）
     * @param microseconds 微秒
     * @return 格式化后的时间字符串
     */
    public static String formatTimeDetailed(long microseconds) {
        long milliseconds = microseconds / 1000;
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long ms = milliseconds % 1000;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, ms);
        }
    }

    /**
     * 验证时间范围是否有效
     * @param startTime 开始时间（秒）
     * @param endTime 结束时间（秒）
     * @param minDuration 最小持续时间（秒）
     * @return 有效返回true
     */
    public static boolean isValidTimeRange(double startTime, double endTime, double minDuration) {
        return startTime >= 0 && endTime > startTime &&
                (endTime - startTime) >= minDuration;
    }

    /**
     * 验证时间范围是否有效（微秒）
     * @param startTimeUs 开始时间（微秒）
     * @param endTimeUs 结束时间（微秒）
     * @param minDurationUs 最小持续时间（微秒）
     * @return 有效返回true
     */
    public static boolean isValidTimeRangeUs(long startTimeUs, long endTimeUs, long minDurationUs) {
        return startTimeUs >= 0 && endTimeUs > startTimeUs &&
                (endTimeUs - startTimeUs) >= minDurationUs;
    }

    /**
     * 计算压缩比（百分比）
     * @param originalSize 原始大小（字节）
     * @param compressedSize 压缩后大小（字节）
     * @return 压缩比字符串
     */
    public static String calculateCompressionRatio(long originalSize, long compressedSize) {
        if (originalSize <= 0) {
            return "0%";
        }
        double ratio = (double) compressedSize / originalSize * 100;
        return String.format(Locale.getDefault(), "%.1f%%", ratio);
    }

    /**
     * 计算压缩比（数值）
     * @param originalSize 原始大小（字节）
     * @param compressedSize 压缩后大小（字节）
     * @return 压缩比（0.0-1.0）
     */
    public static double calculateCompressionRatioValue(long originalSize, long compressedSize) {
        if (originalSize <= 0) {
            return 0.0;
        }
        return (double) compressedSize / originalSize;
    }

    /**
     * 计算处理速度（帧/秒）
     * @param frameCount 帧数
     * @param processingTimeMs 处理时间（毫秒）
     * @return 处理速度（帧/秒）
     */
    public static double calculateProcessingSpeed(int frameCount, long processingTimeMs) {
        if (processingTimeMs <= 0) {
            return 0.0;
        }
        return frameCount / (processingTimeMs / 1000.0);
    }

    /**
     * 计算码率（kbps）
     * @param fileSizeBytes 文件大小（字节）
     * @param durationSeconds 时长（秒）
     * @return 码率（kbps）
     */
    public static double calculateBitrate(long fileSizeBytes, double durationSeconds) {
        if (durationSeconds <= 0) {
            return 0.0;
        }
        return (fileSizeBytes * 8.0) / (durationSeconds * 1000.0);
    }

    /**
     * 计算音频帧时长
     * @param sampleRate 采样率
     * @param samplesPerFrame 每帧样本数
     * @return 音频帧时长（微秒）
     */
    public static long calculateAudioFrameDuration(int sampleRate, int samplesPerFrame) {
        if (sampleRate <= 0) {
            return Constants.AUDIO_FRAME_DURATION_DEFAULT;
        }
        return (long) (samplesPerFrame * Constants.MICROSECONDS_PER_SECOND / (double) sampleRate);
    }

    /**
     * 计算视频帧时长
     * @param frameRate 帧率
     * @return 视频帧时长（微秒）
     */
    public static long calculateVideoFrameDuration(int frameRate) {
        if (frameRate <= 0) {
            return Constants.VIDEO_FRAME_DURATION_30FPS;
        }
        return Constants.MICROSECONDS_PER_SECOND / frameRate;
    }

    /**
     * 确保时间戳递增
     * @param currentPts 当前时间戳
     * @param lastPts 上一个时间戳
     * @param minIncrement 最小增量
     * @return 调整后的时间戳
     */
    public static long ensurePtsIncrement(long currentPts, long lastPts, long minIncrement) {
        if (currentPts <= lastPts) {
            return lastPts + minIncrement;
        }
        return currentPts;
    }

    /**
     * 限制时间范围
     * @param time 原始时间
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的时间
     */
    public static long clampTime(long time, long min, long max) {
        if (time < min) return min;
        if (time > max) return max;
        return time;
    }

    /**
     * 计算进度百分比
     * @param current 当前值
     * @param total 总值
     * @param min 最小进度
     * @param max 最大进度
     * @return 进度百分比
     */
    public static int calculateProgress(long current, long total, int min, int max) {
        if (total <= 0) {
            return min;
        }
        double ratio = (double) current / total;
        int progress = min + (int)(ratio * (max - min));
        return Math.min(Math.max(progress, min), max);
    }
}
