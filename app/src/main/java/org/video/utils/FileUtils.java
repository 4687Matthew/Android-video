package org.video.utils;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.video.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件操作工具类
 */
public class FileUtils {
    private static final String TAG = "FileUtils";

    /**
     * 生成带时间戳的文件名
     * @param originalName 原始文件名
     * @param suffix 后缀（如"_compressed", "_cropped"等）
     * @return 生成的文件名
     */
    public static String generateTimestampFilename(String originalName, String suffix) {
        String baseName = new File(originalName).getName();
        int dot = baseName.lastIndexOf('.');
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot >= 0 ? baseName.substring(dot) : Constants.FILE_EXT_MP4;

        SimpleDateFormat sdf = new SimpleDateFormat(Constants.DATE_FORMAT_FILENAME, Locale.getDefault());
        String timestamp = sdf.format(new Date());

        return pureName + "_" + suffix + "_" + timestamp + ext;
    }

    /**
     * 生成带序列号的文件名
     * @param originalName 原始文件名
     * @param prefix 前缀（如"part"）
     * @param sequence 序列号
     * @return 生成的文件名
     */
    public static String generateSequentialFilename(String originalName, String prefix, int sequence) {
        String baseName = new File(originalName).getName();
        int dot = baseName.lastIndexOf('.');
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot >= 0 ? baseName.substring(dot) : Constants.FILE_EXT_MP4;

        return pureName + "_" + prefix + sequence + ext;
    }

    /**
     * 创建应用私有目录输出文件路径
     * @param context 上下文
     * @param originalPath 原始文件路径
     * @param suffix 文件名后缀
     * @return 输出文件路径，失败返回null
     */
    public static String createPrivateOutputPath(Context context, String originalPath, String suffix) {
        File outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (outputDir == null) {
            Log.e(TAG, "无法创建输出目录");
            return null;
        }

        if (!ensureDirectoryExists(outputDir)) {
            return null;
        }

        String fileName = generateTimestampFilename(originalPath, suffix);
        return new File(outputDir, fileName).getAbsolutePath();
    }

    /**
     * 创建应用私有目录中的临时文件路径
     * @param context 上下文
     * @param prefix 文件前缀
     * @param suffix 文件后缀
     * @return 临时文件路径
     */
    public static String createTempFilePath(Context context, String prefix, String suffix) {
        File tempDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), Constants.DIR_EDITED);
        if (!ensureDirectoryExists(tempDir)) {
            return null;
        }

        SimpleDateFormat sdf = new SimpleDateFormat(Constants.DATE_FORMAT_FILENAME, Locale.getDefault());
        String timestamp = sdf.format(new Date());

        String filename = prefix + timestamp + suffix;
        return new File(tempDir, filename).getAbsolutePath();
    }

    /**
     * 删除文件（如果存在）
     * @param filePath 文件路径
     * @return 删除成功返回true，文件不存在返回true，删除失败返回false
     */
    public static boolean deleteIfExists(String filePath) {
        if (filePath == null) {
            return true;
        }

        File file = new File(filePath);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "删除文件 " + filePath + ": " + (deleted ? "成功" : "失败"));
            return deleted;
        }
        return true;
    }

    /**
     * 安全删除文件（带重试机制）
     * @param file 要删除的文件
     * @return 删除成功返回true
     */
    public static boolean safeDeleteFile(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        try {
            // 方法1：直接删除
            boolean deleted = file.delete();

            if (!deleted) {
                // 方法2：设置可写后删除
                file.setWritable(true);
                deleted = file.delete();

                if (!deleted) {
                    // 方法3：重命名为临时名称再删除
                    String tempName = file.getAbsolutePath() + Constants.FILE_EXT_TEMP_DELETED + System.currentTimeMillis();
                    File tempFile = new File(tempName);

                    if (file.renameTo(tempFile)) {
                        deleted = tempFile.delete();
                        if (!deleted) {
                            Log.w(TAG, "重命名后删除失败: " + tempFile.getAbsolutePath());
                        }
                    } else {
                        Log.w(TAG, "重命名失败: " + file.getAbsolutePath());
                    }
                }
            }

            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "安全删除文件异常: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 递归删除目录
     * @param directory 要删除的目录
     * @return 删除成功返回true
     */
    public static boolean deleteDirectoryRecursive(File directory) {
        if (directory == null || !directory.exists()) {
            return true;
        }

        try {
            if (directory.isDirectory()) {
                File[] children = directory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory()) {
                            deleteDirectoryRecursive(child);
                        } else {
                            safeDeleteFile(child);
                        }
                    }
                }
            }

            return safeDeleteFile(directory);
        } catch (Exception e) {
            Log.e(TAG, "递归删除目录异常: " + directory.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 确保目录存在
     * @param directory 目录
     * @return 目录存在或创建成功返回true
     */
    public static boolean ensureDirectoryExists(File directory) {
        if (directory == null) {
            return false;
        }

        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (created) {
                Log.d(TAG, "创建目录: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "创建目录失败: " + directory.getAbsolutePath());
            }
            return created;
        }
        return true;
    }

    /**
     * 获取文件大小（MB）
     * @param filePath 文件路径
     * @return 文件大小（MB），文件不存在返回0
     */
    public static double getFileSizeInMB(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            return file.length() / (double) Constants.BYTES_PER_MB;
        }
        return 0.0;
    }

    /**
     * 获取文件大小描述（带单位）
     * @param filePath 文件路径
     * @return 文件大小描述字符串
     */
    public static String getFileSizeDescription(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "0 Bytes";
        }

        long bytes = file.length();
        return formatFileSize(bytes);
    }

    /**
     * 格式化文件大小
     * @param bytes 字节数
     * @return 格式化后的文件大小字符串
     */
    public static String formatFileSize(long bytes) {
        if (bytes < Constants.BYTES_PER_KB) {
            return bytes + " Bytes";
        } else if (bytes < Constants.BYTES_PER_MB) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / (double) Constants.BYTES_PER_KB);
        } else if (bytes < Constants.BYTES_PER_GB) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (double) Constants.BYTES_PER_MB);
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (double) Constants.BYTES_PER_GB);
        }
    }

    /**
     * 复制文件
     * @param source 源文件
     * @param dest 目标文件
     * @return 复制成功返回true
     */
    public static boolean copyFile(File source, File dest) {
        if (!source.exists()) {
            Log.e(TAG, "源文件不存在: " + source.getAbsolutePath());
            return false;
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[Constants.BUFFER_SIZE_8KB];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "复制文件失败", e);
            return false;
        }
    }

    /**
     * 创建MediaStore视频文件URI（Android 10+）
     * @param context 上下文
     * @param fileName 文件名
     * @return MediaStore URI，失败返回null
     */
    public static Uri createMediaStoreVideoUri(Context context, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, Constants.MIME_TYPE_MP4);
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/" + Constants.DIR_VIDEO_EDITOR);

                Uri uri = context.getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                Log.d(TAG, "创建MediaStore URI成功: " + uri);
                return uri;
            } catch (Exception e) {
                Log.e(TAG, "创建MediaStore URI失败", e);
                return null;
            }
        }
        return null;
    }

    /**
     * 创建公共目录输出文件（Android 9及以下）
     * @param fileName 文件名
     * @return 文件对象，失败返回null
     */
    public static File createPublicOutputFile(String fileName) {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES);
        File videoEditorDir = new File(moviesDir, Constants.DIR_VIDEO_EDITOR);

        if (ensureDirectoryExists(videoEditorDir)) {
            return new File(videoEditorDir, fileName);
        }
        return null;
    }

    /**
     * 保存文件到公共目录
     * @param context 上下文
     * @param sourceFile 源文件
     * @param fileName 目标文件名
     * @return 保存后的文件路径或URI，失败返回null
     */
    public static String saveToPublicDirectory(Context context, File sourceFile, String fileName) {
        if (!sourceFile.exists()) {
            Log.e(TAG, "源文件不存在: " + sourceFile.getAbsolutePath());
            return null;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                Uri uri = createMediaStoreVideoUri(context, fileName);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buffer = new byte[Constants.BUFFER_SIZE_8KB];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                            Log.d(TAG, "文件已保存到MediaStore: " + uri.toString());
                            return uri.toString();
                        }
                    }
                }
            } else {
                // Android 9及以下
                File destFile = createPublicOutputFile(fileName);
                if (destFile != null && copyFile(sourceFile, destFile)) {
                    Log.d(TAG, "文件已保存到公共目录: " + destFile.getAbsolutePath());
                    return destFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "保存到公共目录失败", e);
        }

        return null;
    }

    /**
     * 清理应用缓存目录
     * @param context 上下文
     */
    public static void clearCacheDirectory(Context context) {
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir != null) {
            deleteDirectoryRecursive(cacheDir);
            Log.d(TAG, "清理缓存目录: " + cacheDir.getAbsolutePath());
        }
    }

    /**
     * 清理临时文件
     * @param files 临时文件列表
     */
    public static void cleanupTempFiles(File... files) {
        for (File file : files) {
            if (file != null) {
                safeDeleteFile(file);
            }
        }
    }

    /**
     * 清理视频编辑器相关缓存
     * @param context 上下文
     */
    public static void cleanupVideoEditorCache(Context context) {
        try {
            Log.d(TAG, "开始清理视频编辑器缓存...");

            // 清理应用缓存目录
            clearCacheDirectory(context);

            // 清理视频编辑器私有目录的临时文件
            File videoDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (videoDir != null && videoDir.exists()) {
                File editedDir = new File(videoDir, Constants.DIR_EDITED);
                if (editedDir.exists()) {
                    deleteDirectoryRecursive(editedDir);
                }
            }

            Log.d(TAG, "视频编辑器缓存清理完成");
        } catch (Exception e) {
            Log.e(TAG, "清理缓存失败", e);
        }
    }
}
