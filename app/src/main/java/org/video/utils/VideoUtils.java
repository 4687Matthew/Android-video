package org.video.utils;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.video.VideoEditor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 视频处理工具类
 */
public class VideoUtils {
    private static final String TAG = "VideoUtils";

    /**
     * 将 MediaExtractor 样本标志位转换为 MediaCodec Buffer 标志位
     * @param extractorFlags MediaExtractor 样本标志位
     * @return MediaCodec Buffer 标志位
     */
    public static int convertSampleFlagsToBufferFlags(int extractorFlags) {
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
     * 获取视频信息字符串
     * @param format MediaFormat对象
     * @return 视频信息字符串
     */
    public static String getVideoInfoString(MediaFormat format) {
        if (format == null) {
            return "格式为空";
        }

        try {
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = format.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : Constants.DEFAULT_FRAME_RATE;
            int bitrate = format.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
            long duration = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            return String.format(Locale.getDefault(), Constants.VIDEO_DETAILED_INFO_FORMAT,
                    width, height, frameRate, bitrate / 1000,
                    TimeUtils.microsecondsToSeconds(duration));
        } catch (Exception e) {
            Log.e(TAG, "获取视频信息失败", e);
            return "获取信息失败";
        }
    }

    /**
     * 获取简化的视频信息字符串
     * @param format MediaFormat对象
     * @return 简化视频信息字符串
     */
    public static String getSimpleVideoInfo(MediaFormat format) {
        if (format == null) {
            return "未找到视频轨道";
        }

        try {
            int w = format.getInteger(MediaFormat.KEY_WIDTH);
            int h = format.getInteger(MediaFormat.KEY_HEIGHT);
            int rate = format.containsKey(MediaFormat.KEY_FRAME_RATE) ?
                    format.getInteger(MediaFormat.KEY_FRAME_RATE) : 0;
            int br = format.containsKey(MediaFormat.KEY_BIT_RATE) ?
                    format.getInteger(MediaFormat.KEY_BIT_RATE) : 0;

            return String.format(Locale.getDefault(), Constants.VIDEO_INFO_FORMAT,
                    w, h, br / 1000, rate);
        } catch (Exception e) {
            Log.e(TAG, "getSimpleVideoInfo error", e);
            return "读取失败";
        }
    }

    /**
     * 验证视频文件
     * @param path 文件路径
     * @return 有效返回true
     */
    public static boolean isValidVideoFile(String path) {
        if (path == null) {
            Log.e(TAG, "视频路径为空");
            return false;
        }

        File file = new File(path);
        if (!file.exists() || file.length() == 0) {
            Log.e(TAG, "视频文件不存在或为空: " + path);
            return false;
        }

        // 验证是否是视频文件（检查扩展名）
        String lowerPath = path.toLowerCase();
        if (!lowerPath.endsWith(".mp4") &&
                !lowerPath.endsWith(".mov") &&
                !lowerPath.endsWith(".avi") &&
                !lowerPath.endsWith(".mkv") &&
                !lowerPath.endsWith(".flv") &&
                !lowerPath.endsWith(".wmv") &&
                !lowerPath.endsWith(".3gp")) {
            Log.w(TAG, "文件格式可能不是常见视频: " + path);
        }

        // 检查文件大小是否太小
        if (file.length() < Constants.MIN_FILE_SIZE_KB * Constants.BYTES_PER_KB) {
            Log.w(TAG, "视频文件大小太小: " + file.length() + " bytes");
        }

        return true;
    }

    /**
     * 获取视频时长（毫秒）
     * @param videoPath 视频路径
     * @return 时长（毫秒），失败返回0
     */
    public static long getVideoDurationMs(String videoPath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取视频时长失败: " + e.getMessage());
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放MediaMetadataRetriever失败", e);
                }
            }
        }
        return 0;
    }

    /**
     * 获取视频时长（微秒）
     * @param videoPath 视频路径
     * @return 时长（微秒），失败返回0
     */
    public static long getVideoDurationUs(String videoPath) {
        long durationMs = getVideoDurationMs(videoPath);
        return TimeUtils.millisecondsToMicroseconds(durationMs);
    }

    /**
     * 从URI获取文件路径
     * @param context 上下文
     * @param uri URI
     * @return 文件路径，失败返回null
     */
    public static String getPathFromUri(Context context, Uri uri) {
        String path = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                && DocumentsContract.isDocumentUri(context, uri)) {
            // DocumentProvider
            String docId = DocumentsContract.getDocumentId(uri);

            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                // ExternalStorageProvider
                String[] split = docId.split(":");
                String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    path = Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                // DownloadsProvider
                Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.parseLong(docId));
                path = getDataColumn(context, contentUri, null, null);
            } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                // MediaProvider
                String[] split = docId.split(":");
                String type = split[0];
                Uri contentUri = null;

                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};
                path = getDataColumn(context, contentUri, selection, selectionArgs);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            // MediaStore (and general)
            path = getDataColumn(context, uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            // File
            path = uri.getPath();
        }

        return path;
    }

    /**
     * 从数据库列获取数据
     */
    private static String getDataColumn(Context context, Uri uri, String selection,
                                        String[] selectionArgs) {
        Cursor cursor = null;
        String column = MediaStore.Images.Media.DATA;
        String[] projection = {column};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection,
                    selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (Exception e) {
            Log.e(TAG, "查询数据库列失败", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * 检测是否为高B帧视频
     * @param inputPath 输入文件路径
     * @param videoTrackIndex 视频轨道索引
     * @return B帧比例超过阈值返回true
     */
    public static boolean isHighBFrameVideo(String inputPath, int videoTrackIndex) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);
            extractor.selectTrack(videoTrackIndex);

            // 只分析前30秒或前500帧
            int totalFrames = 0;
            int bFrames = 0;
            long analysisDuration = 30 * 1000000L; // 30秒

            // 定位到开始
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            while (totalFrames < 500) {
                // 获取样本大小但不读取数据
                long sampleTime = extractor.getSampleTime();
                if (sampleTime > analysisDuration) {
                    break;
                }

                int flags = extractor.getSampleFlags();
                boolean isKeyframe = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;

                totalFrames++;
                if (!isKeyframe) {
                    bFrames++;
                }

                // 前进到下一帧
                if (!extractor.advance()) {
                    break;
                }
            }

            double bFrameRatio = totalFrames > 0 ? (double) bFrames / totalFrames : 0;
            Log.d(TAG, String.format("B帧检测: 总帧数=%d, B帧数=%d, B帧比例=%.1f%%",
                    totalFrames, bFrames, bFrameRatio * 100));

            return bFrameRatio >= Constants.HIGH_BFRAME_THRESHOLD;

        } catch (Exception e) {
            Log.e(TAG, "检测B帧比例失败", e);
            return false;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * 获取视频的轨道信息
     * @param inputPath 输入文件路径
     * @return 包含视频和音频轨道信息的数组 [视频轨道索引, 音频轨道索引]，未找到返回[-1, -1]
     */
    public static int[] getTrackIndices(String inputPath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

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

            return new int[]{videoTrackIndex, audioTrackIndex};
        } catch (Exception e) {
            Log.e(TAG, "获取轨道信息失败", e);
            return new int[]{-1, -1};
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * 获取视频的MediaFormat信息
     * @param inputPath 输入文件路径
     * @return 包含视频和音频MediaFormat的数组 [视频格式, 音频格式]，未找到返回null
     */
    public static MediaFormat[] getTrackFormats(String inputPath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null) {
                    if (mime.startsWith("video/") && videoFormat == null) {
                        videoFormat = format;
                    } else if (mime.startsWith("audio/") && audioFormat == null) {
                        audioFormat = format;
                    }
                }
            }

            return new MediaFormat[]{videoFormat, audioFormat};
        } catch (Exception e) {
            Log.e(TAG, "获取轨道格式失败", e);
            return null;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * 验证视频分辨率是否有效
     * @param width 宽度
     * @param height 高度
     * @return 有效返回true
     */
    public static boolean isValidResolution(int width, int height) {
        return width >= Constants.MIN_WIDTH_HEIGHT && height >= Constants.MIN_WIDTH_HEIGHT;
    }

    /**
     * 验证视频帧率是否有效
     * @param frameRate 帧率
     * @return 有效返回true
     */
    public static boolean isValidFrameRate(int frameRate) {
        return frameRate > 0 && frameRate <= 240; // 最大240fps
    }

    /**
     * 验证视频码率是否有效
     * @param bitrate 码率（bps）
     * @return 有效返回true
     */
    public static boolean isValidBitrate(int bitrate) {
        return bitrate >= Constants.MIN_BITRATE;
    }

    public static void scanMediaFile(Context context, String filePath) {
        if (filePath.startsWith("content://")) {
            return;
        }
        try {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            File file = new File(filePath);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从视频中提取指定时间的帧作为封面
     */
    public static Bitmap extractCoverFromVideo(String videoPath, long timeMs) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);

            // 将毫秒转换为微秒
            long timeUs = timeMs * 1000;
            Bitmap bitmap = retriever.getFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

            if (bitmap != null) {
                Log.d(TAG, String.format("封面提取成功: %dx%d, 时间: %.1fs",
                        bitmap.getWidth(), bitmap.getHeight(), timeMs / 1000.0));

                // 调整尺寸
//                bitmap = resizeBitmap(bitmap, Constants.COVER_WIDTH, Constants.COVER_HEIGHT);
                return bitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "提取封面失败", e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放MediaMetadataRetriever失败", e);
                }
            }
        }
        return null;
    }

    /**
     * 从Uri加载图片作为封面
     */
    public static Bitmap loadCoverFromUri(Context context, Uri uri) {
        InputStream inputStream = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            inputStream = resolver.openInputStream(uri);

            // 只解码尺寸
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);

            // 计算采样率
            options.inSampleSize = calculateInSampleSize(options,
                    Constants.COVER_WIDTH, Constants.COVER_HEIGHT);

            // 重新读取流
            inputStream.close();
            inputStream = resolver.openInputStream(uri);
            options.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            if (bitmap != null) {
                bitmap = resizeBitmap(bitmap, Constants.COVER_WIDTH, Constants.COVER_HEIGHT);
                Log.d(TAG, String.format("从Uri加载封面: %dx%d",
                        bitmap.getWidth(), bitmap.getHeight()));
                return bitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "从Uri加载封面失败", e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭流失败", e);
                }
            }
        }
        return null;
    }

    /**
     * 保存封面到临时文件
     */
    public static String saveCoverToTempFile(Context context, Bitmap cover) {
        if (cover == null) {
            return null;
        }

        try {
            File tempDir = VideoEditor.getCacheDir();
            String fileName = Constants.PREFIX_TEMP + "cover_" +
                    System.currentTimeMillis() + Constants.FILE_EXT_JPEG;
            File tempFile = new File(tempDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                cover.compress(Bitmap.CompressFormat.JPEG,
                        Constants.COVER_QUALITY, fos);
                fos.flush();
            }

            Log.d(TAG, "封面保存到临时文件: " + tempFile.getAbsolutePath());
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "保存封面到临时文件失败", e);
            return null;
        }
    }

    /**
     * 调整Bitmap尺寸以匹配视频尺寸
     */
    public static Bitmap resizeBitmapToVideoSize(Bitmap original, int videoWidth, int videoHeight) {
        if (original == null) return null;

        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        if (originalWidth == videoWidth && originalHeight == videoHeight) {
            return original.copy(original.getConfig(), false);
        }

        // 计算保持宽高比的缩放
        float widthRatio = (float) videoWidth / originalWidth;
        float heightRatio = (float) videoHeight / originalHeight;
        float ratio = Math.min(widthRatio, heightRatio);

        int newWidth = Math.round(originalWidth * ratio);
        int newHeight = Math.round(originalHeight * ratio);

        // 缩放图片
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);

        // 如果需要，添加黑色背景
        if (newWidth < videoWidth || newHeight < videoHeight) {
            Bitmap finalBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(finalBitmap);
            canvas.drawColor(Color.BLACK);

            int left = (videoWidth - newWidth) / 2;
            int top = (videoHeight - newHeight) / 2;
            canvas.drawBitmap(scaledBitmap, left, top, null);

            scaledBitmap.recycle();
            return finalBitmap;
        }

        return scaledBitmap;
    }

    /**
     * 创建带封面的视频（通过复制原始视频并附加封面信息）
     * 注意：这只是一个简化实现，实际可能需要重新编码
     */
    private static boolean createVideoWithCover(String videoPath,
                                                String outputPath,
                                                Bitmap cover) {
        try {
            // 保存封面到临时文件
            String coverPath = saveCoverToFile(cover);
            if (coverPath == null) {
                return false;
            }

            // 使用FFmpeg或重新编码来实现封面设置
            // 这里我们先实现一个简单的版本：复制原始视频
            File inputFile = new File(videoPath);
            File outputFile = new File(outputPath);

            if (outputFile.exists()) {
                FileUtils.safeDeleteFile(outputFile);
            }

            // 复制文件
            return FileUtils.copyFile(inputFile, outputFile);

        } catch (Exception e) {
            Log.e(TAG, "创建带封面视频失败", e);
            return false;
        }
    }

    /**
     * 保存封面到文件
     */
    private static String saveCoverToFile(Bitmap cover) {
        try {
            File tempDir = VideoEditor.getCacheDir();
            String fileName = "video_cover_" + System.currentTimeMillis() +
                    Constants.FILE_EXT_JPEG;
            File coverFile = new File(tempDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(coverFile)) {
                cover.compress(Bitmap.CompressFormat.JPEG,
                        Constants.COVER_QUALITY, fos);
                fos.flush();
            }

            return coverFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "保存封面文件失败", e);
            return null;
        }
    }

    /**
     * 调整Bitmap尺寸
     */
    private static Bitmap resizeBitmap(Bitmap bitmap, int targetWidth, int targetHeight) {
        if (bitmap == null) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width == targetWidth && height == targetHeight) {
            return bitmap;
        }

        float scaleX = (float) targetWidth / width;
        float scaleY = (float) targetHeight / height;
        float scale = Math.min(scaleX, scaleY);

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        return Bitmap.createBitmap(bitmap, 0, 0,
                width, height, matrix, true);
    }

    /**
     * 计算采样率
     */
    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * 保存封面到相册
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static boolean saveCoverToGallery(Context context, Bitmap cover, String title) {
        if (cover == null) {
            return false;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    title + Constants.FILE_EXT_JPEG);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/VideoEditorCovers");
            }

            Uri uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri != null) {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                    if (output != null) {
                        cover.compress(Bitmap.CompressFormat.JPEG,
                                Constants.COVER_QUALITY, output);
                        output.flush();

                        Log.d(TAG, "封面已保存到相册: " + title);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "保存封面到相册失败", e);
        }

        return false;
    }

    /**
     * 从视频中获取最佳封面时间（尝试获取关键帧）
     */
    public static long getBestCoverTime(String videoPath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);

            // 尝试几个时间点
            long[] times = {1000, 3000, 5000, 10000}; // 1,3,5,10秒

            for (long timeMs : times) {
                Bitmap frame = retriever.getFrameAtTime(timeMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    frame.recycle();
                    return timeMs;
                }
            }

            // 默认返回1秒
            return Constants.DEFAULT_COVER_TIME_MS;

        } catch (Exception e) {
            Log.e(TAG, "获取最佳封面时间失败", e);
            return Constants.DEFAULT_COVER_TIME_MS;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放retriever失败", e);
                }
            }
        }
    }

    /**
     * 预览封面（生成缩略图）
     */
    public static Bitmap generateCoverThumbnail(Bitmap original, int maxWidth, int maxHeight) {
        if (original == null) {
            return null;
        }

        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= maxWidth && height <= maxHeight) {
            return original.copy(original.getConfig(), true);
        }

        float ratio = Math.min((float) maxWidth / width, (float) maxHeight / height);
        int newWidth = (int) (width * ratio);
        int newHeight = (int) (height * ratio);

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
    }

}

