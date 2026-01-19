package org.video.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import org.video.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码器工具类
 */
public class CodecUtils {
    private static final String TAG = "CodecUtils";

    /**
     * 查找支持指定MIME类型的编码器
     * @param mimeType MIME类型
     * @return 编码器信息，未找到返回null
     */
    public static MediaCodecInfo findEncoderForType(String mimeType) {
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
     * 查找支持指定MIME类型的解码器
     * @param mimeType MIME类型
     * @return 解码器信息，未找到返回null
     */
    public static MediaCodecInfo findDecoderForType(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    Log.d(TAG, "找到解码器: " + codecInfo.getName() + " for " + mimeType);
                    return codecInfo;
                }
            }
        }
        Log.w(TAG, "未找到解码器 for " + mimeType);
        return null;
    }

    /**
     * 获取所有支持的编码器MIME类型
     * @return 支持的MIME类型列表
     */
    public static List<String> getAllSupportedEncoderMimeTypes() {
        List<String> mimeTypes = new ArrayList<>();
        int numCodecs = MediaCodecList.getCodecCount();

        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (!mimeTypes.contains(type)) {
                    mimeTypes.add(type);
                }
            }
        }

        return mimeTypes;
    }

    /**
     * 创建HEVC编码器格式
     * @param width 宽度
     * @param height 高度
     * @param bitrate 码率（bps）
     * @param frameRate 帧率
     * @return MediaFormat对象
     */
    public static MediaFormat createHevcFormat(int width, int height, int bitrate, int frameRate) {
        MediaFormat format = MediaFormat.createVideoFormat(
                Constants.MIME_TYPE_HEVC, width, height);

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Constants.KEY_I_FRAME_INTERVAL);

        // 设置颜色格式
        MediaCodecInfo codecInfo = findEncoderForType(Constants.MIME_TYPE_HEVC);
        if (codecInfo != null) {
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(Constants.MIME_TYPE_HEVC);
            int colorFormat = selectColorFormat(caps);
            if (colorFormat != 0) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            }
        }

        // 设置编码档次和级别
        setEncoderProfileLevel(format, Constants.MIME_TYPE_HEVC, width * height);

        return format;
    }

    /**
     * 创建AVC编码器格式
     * @param width 宽度
     * @param height 高度
     * @param bitrate 码率（bps）
     * @param frameRate 帧率
     * @return MediaFormat对象
     */
    public static MediaFormat createAvcFormat(int width, int height, int bitrate, int frameRate) {
        MediaFormat format = MediaFormat.createVideoFormat(
                Constants.MIME_TYPE_AVC, width, height);

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Constants.KEY_I_FRAME_INTERVAL);

        // 设置颜色格式
        MediaCodecInfo codecInfo = findEncoderForType(Constants.MIME_TYPE_AVC);
        if (codecInfo != null) {
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(Constants.MIME_TYPE_AVC);
            int colorFormat = selectColorFormat(caps);
            if (colorFormat != 0) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            }
        }

        // 设置编码档次和级别
        setEncoderProfileLevel(format, Constants.MIME_TYPE_AVC, width * height);

        return format;
    }

    /**
     * 创建音频编码器格式
     * @param mimeType MIME类型（如"audio/mp4a-latm"）
     * @param sampleRate 采样率
     * @param channelCount 声道数
     * @param bitrate 码率（bps）
     * @return MediaFormat对象
     */
    public static MediaFormat createAudioFormat(String mimeType, int sampleRate, int channelCount, int bitrate) {
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        return format;
    }

    /**
     * 选择颜色格式
     */
    public static int selectColorFormat(MediaCodecInfo.CodecCapabilities caps) {
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
     * @param format MediaFormat对象
     * @param mimeType MIME类型
     * @param resolution 分辨率（宽×高）
     */
    public static void setEncoderProfileLevel(MediaFormat format, String mimeType, int resolution) {
        try {
            if (mimeType.equals(Constants.MIME_TYPE_HEVC)) {
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
            } else if (mimeType.equals(Constants.MIME_TYPE_AVC)) {
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
     * 检查设备是否支持HEVC编码
     * @return 支持返回true
     */
    public static boolean isHevcEncoderSupported() {
        return findEncoderForType(Constants.MIME_TYPE_HEVC) != null;
    }

    /**
     * 检查设备是否支持AVC编码
     * @return 支持返回true
     */
    public static boolean isAvcEncoderSupported() {
        return findEncoderForType(Constants.MIME_TYPE_AVC) != null;
    }

    /**
     * 检查设备是否支持指定MIME类型的解码
     * @param mimeType MIME类型
     * @return 支持返回true
     */
    public static boolean isDecoderSupported(String mimeType) {
        return findDecoderForType(mimeType) != null;
    }

    /**
     * 获取编码器名称
     * @param mimeType MIME类型
     * @return 编码器名称，未找到返回null
     */
    public static String getEncoderName(String mimeType) {
        MediaCodecInfo codecInfo = findEncoderForType(mimeType);
        return codecInfo != null ? codecInfo.getName() : null;
    }

    /**
     * 获取解码器名称
     * @param mimeType MIME类型
     * @return 解码器名称，未找到返回null
     */
    public static String getDecoderName(String mimeType) {
        MediaCodecInfo codecInfo = findDecoderForType(mimeType);
        return codecInfo != null ? codecInfo.getName() : null;
    }

    /**
     * 创建解码器
     * @param mimeType MIME类型
     * @param format 解码格式
     * @return MediaCodec对象，失败返回null
     */
    public static MediaCodec createDecoder(String mimeType, MediaFormat format) {
        try {
            MediaCodecInfo decoderInfo = findDecoderForType(mimeType);
            if (decoderInfo == null) {
                Log.e(TAG, "未找到解码器 for " + mimeType);
                return null;
            }

            MediaCodec decoder = MediaCodec.createByCodecName(decoderInfo.getName());
            decoder.configure(format, null, null, 0);
            decoder.start();

            Log.d(TAG, "创建解码器成功: " + decoderInfo.getName());
            return decoder;
        } catch (Exception e) {
            Log.e(TAG, "创建解码器失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 创建编码器
     * @param mimeType MIME类型
     * @param format 编码格式
     * @return MediaCodec对象，失败返回null
     */
    public static MediaCodec createEncoder(String mimeType, MediaFormat format) {
        try {
            MediaCodecInfo encoderInfo = findEncoderForType(mimeType);
            if (encoderInfo == null) {
                Log.e(TAG, "未找到编码器 for " + mimeType);
                return null;
            }

            MediaCodec encoder = MediaCodec.createByCodecName(encoderInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            Log.d(TAG, "创建编码器成功: " + encoderInfo.getName());
            return encoder;
        } catch (Exception e) {
            Log.e(TAG, "创建编码器失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 安全释放MediaCodec
     * @param codec MediaCodec对象
     */
    public static void safeReleaseCodec(MediaCodec codec) {
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
                Log.d(TAG, "MediaCodec释放成功");
            } catch (Exception e) {
                Log.e(TAG, "释放MediaCodec失败", e);
            }
        }
    }

    /**
     * 检查编解码器是否支持特定分辨率
     * @param mimeType MIME类型
     * @param width 宽度
     * @param height 高度
     * @return 支持返回true
     */
    public static boolean supportsResolution(String mimeType, int width, int height) {
        MediaCodecInfo codecInfo = findEncoderForType(mimeType);
        if (codecInfo == null) {
            return false;
        }

        MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(mimeType);
        MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();

        if (videoCaps != null) {
            return videoCaps.isSizeSupported(width, height);
        }

        return false;
    }
}