package org.video;

import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * 常量类
 * 集中管理应用中的所有常量
 */
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
public class Constants {

    // Activity 请求码
    public static final int REQUEST_CODE_SELECT_VIDEO = 1001;
    public static final int REQUEST_CODE_SELECT_SECOND_VIDEO = 1002;
    public static final int REQUEST_PERMISSION_CODE = 1000;
    public static final int STORAGE_PERMISSION_REQUEST_CODE = 1003;

    // 编码相关常量
    public static final int TIMEOUT_US = 10000;
    public static final double HIGH_BFRAME_THRESHOLD = 0.8; // B帧比例超过80%认为是高B帧视频
    public static final long VIDEO_FRAME_DURATION_30FPS = 33333; // 30fps每帧33.3ms
    public static final long AUDIO_FRAME_DURATION_DEFAULT = 21000; // 默认21ms音频帧时长
    public static final int AUDIO_SAMPLES_PER_FRAME = 1024; // 每帧音频样本数
    public static final int MIN_SEGMENT_BITRATE = 300000; // 每个分片的最小码率

    // 最小视频时长（秒）
    public static final double MIN_VIDEO_DURATION = 1.0;
    public static final long MIN_VIDEO_DURATION_US = 1000000; // 1秒对应的微秒数

    // 时间单位转换
    public static final long MICROSECONDS_PER_SECOND = 1000000L;
    public static final long MICROSECONDS_PER_MILLISECOND = 1000L;
    public static final long MILLISECONDS_PER_SECOND = 1000L;

    // 默认压缩参数
    public static final double DEFAULT_COMPRESSION_RATIO = 0.45;
    public static final int MIN_BITRATE = 500000; // 最小码率 500kbps

    // 视频类型
    public static final String MIME_TYPE_HEVC = "video/hevc";
    public static final String MIME_TYPE_AVC = "video/avc";
    public static final String MIME_TYPE_MP4 = "video/mp4";
    public static final String MIME_TYPE_AAC = "audio/aac";
    public static final String MIME_TYPE_VIDEO = "video/*";
    public static final String MIME_TYPE_AUDIO = "audio/*";

    // 文件扩展名
    public static final String FILE_EXT_MP4 = ".mp4";
    public static final String FILE_EXT_AAC = ".aac";
    public static final String FILE_EXT_TEMP_DELETED = ".deleted_";

    // 目录名
    public static final String DIR_VIDEO_EDITOR = "VideoEditor";
    public static final String SUBDIR_MOVIES = "Movies";
    public static final String DIR_CACHE = "cache";
    public static final String DIR_EDITED = "edited";

    // 临时文件前缀
    public static final String PREFIX_TEMP = "temp_";
    public static final String PREFIX_VIDEO_SEGMENT = "video_segment_";
    public static final String PREFIX_AUDIO = "audio_";
    public static final String PREFIX_MERGED = "merged_video_";
    public static final String PREFIX_CACHE = "cache_";
    public static final String PREFIX_PART = "part";

    // 编码器相关
    public static final int KEY_I_FRAME_INTERVAL = 2; // 关键帧间隔（秒）
    public static final int DEFAULT_FRAME_RATE = 30;
    public static final int DEFAULT_SAMPLE_RATE = 44100;
    public static final int DEFAULT_CHANNEL_COUNT = 2;

    // 缓冲区大小
    public static final int BUFFER_SIZE_4MB = 4 * 1024 * 1024; // 4MB
    public static final int BUFFER_SIZE_2MB = 2 * 1024 * 1024; // 2MB
    public static final int BUFFER_SIZE_1MB = 1024 * 1024; // 1MB
    public static final int BUFFER_SIZE_512KB = 512 * 1024; // 512KB
    public static final int BUFFER_SIZE_16MB = 16 * 1024 * 1024; // 16MB
    public static final int BUFFER_SIZE_8KB = 8192; // 8KB

    // 并行处理配置
    public static final int MIN_VIDEO_DURATION_FOR_PARALLEL_SECONDS = 30; // 30秒以下使用单线程
    public static final int MIN_SEGMENT_DURATION_SECONDS = 2; // 最小分片时长2秒
    public static final int MIN_SEGMENT_DURATION_US = 2000000; // 2秒对应的微秒数
    public static final int KEY_FRAMES_PER_SEGMENT = 5; // 每个分片对应5个关键帧位置
    public static final int MAX_THREADS_FOR_LONG_VIDEO = 2; // 长视频最大线程数
    public static final int MAX_THREADS_FOR_MEDIUM_VIDEO = 4; // 中等视频最大线程数
    public static final int MAX_THREADS_FOR_SHORT_VIDEO = 3; // 短视频最大线程数

    // 进度更新间隔
    public static final int PROGRESS_UPDATE_INTERVAL_MS = 2000; // 2秒更新一次进度
    public static final int FRAME_COUNT_UPDATE_INTERVAL = 100; // 每100帧更新一次
    public static final int VIDEO_FRAME_UPDATE_INTERVAL = 30; // 视频帧更新间隔
    public static final int AUDIO_FRAME_UPDATE_INTERVAL = 200; // 音频帧更新间隔

    // 视频分析相关
    public static final int DEFAULT_ANALYSIS_DURATION_MS = 30000; // 默认分析时长30秒
    public static final int DEFAULT_ANALYSIS_FRAMES = 500; // 默认分析500帧
    public static final long DEFAULT_SAMPLE_INTERVAL_US = 3000000; // 默认采样间隔3秒

    // 文件名后缀
    public static final String SUFFIX_BASE_HEVC = "_base_hevc";
    public static final String SUFFIX_PARALLEL_HEVC = "_parallel_hevc";
    public static final String SUFFIX_CROPPED = "_cropped";
    public static final String SUFFIX_MERGED = "_merged";
    public static final String SUFFIX_SPEEDUP = "_speedup";
    public static final String SUFFIX_SLOWDOWN = "_slowdown";

    // 视频信息显示格式
    public static final String VIDEO_INFO_FORMAT = "%d×%d  %d kbps  %d fps";
    public static final String VIDEO_DETAILED_INFO_FORMAT = "分辨率: %dx%d\n帧率: %dfps\n码率: %dkbps\n时长: %.1f秒";

    // 文件大小单位
    public static final long BYTES_PER_KB = 1024;
    public static final long BYTES_PER_MB = 1024 * 1024;
    public static final long BYTES_PER_GB = 1024 * 1024 * 1024;

    // 默认阈值
    public static final int MIN_FILE_SIZE_KB = 10; // 最小有效文件大小10KB
    public static final int MIN_FRAME_COUNT = 1; // 最小帧数
    public static final int MIN_WIDTH_HEIGHT = 32; // 最小视频宽高

    // 日期时间格式
    public static final String DATE_FORMAT_FILENAME = "yyyyMMdd_HHmmss";
    public static final String TIME_FORMAT_HMS = "%02d:%02d:%02d";
    public static final String TIME_FORMAT_MS = "%02d:%02d";

}
