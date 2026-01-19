package org.video;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SELECT_VIDEO = 1001;
    private static final int REQUEST_CODE_SELECT_SECOND_VIDEO = 1002;
    private static final int REQUEST_PERMISSION_CODE = 1000;

    private Button btnSelectVideo;
    private Button btnGetInfo;
    private Button btnCompress;
    private Button btnCompressBase;
    private Button btnSelectSecondVideo;
    private Button btnCrop;
    private Button btnSplit;
    private Button btnMerge;
    private Button btnSpeedUp;
    private Button btnSpeedDown;

    private EditText etCropStart;
    private EditText etCropEnd;
    private EditText etSplitTime;
    private EditText etSpeedFactor;

    private TextView tvVideoPath;
    private TextView VideoInfo;
    private TextView tvSecondVideoPath;
    private TextView tvStatus;
    private ProgressBar progressBar;

    private String selectedVideoPath;
    private String selectedSecondVideoPath;

    // 进度回调接口 - 保持不变
    public interface ProgressCallback {
        void onProgressUpdate(String status, int progress);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        setupClickListeners();
    }

    private void initViews() {
        btnSelectVideo = findViewById(R.id.btnSelectVideo);
        btnGetInfo = findViewById(R.id.btnGetInfo);
        btnCompress = findViewById(R.id.btnCompress);
        btnCompressBase = findViewById(R.id.btnCompressBase);
        btnSelectSecondVideo = findViewById(R.id.btnSelectSecondVideo);
        btnCrop = findViewById(R.id.btnCrop);
        btnSplit = findViewById(R.id.btnSplit);
        btnMerge = findViewById(R.id.btnMerge);
        btnSpeedUp = findViewById(R.id.btnSpeedUp);
        btnSpeedDown = findViewById(R.id.btnSpeedDown);

        etCropStart = findViewById(R.id.etCropStart);
        etCropEnd = findViewById(R.id.etCropEnd);
        etSplitTime = findViewById(R.id.etSplitTime);
        etSpeedFactor = findViewById(R.id.etSpeedFactor);

        tvVideoPath = findViewById(R.id.tvVideoPath);
        VideoInfo = findViewById(R.id.VideoInfo);
        tvSecondVideoPath = findViewById(R.id.tvSecondVideoPath);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);

        // 设置进度条为水平样式
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要READ_MEDIA_VIDEO权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要MANAGE_EXTERNAL_STORAGE权限才能直接访问所有文件
            // 或者使用MediaStore API（推荐）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else {
            // Android 10及以下
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissions.toArray(new String[0]), REQUEST_PERMISSION_CODE);
        }
    }

    private void setupClickListeners() {
        btnSelectVideo.setOnClickListener(v -> selectVideo(REQUEST_CODE_SELECT_VIDEO));
        btnSelectSecondVideo.setOnClickListener(v -> selectVideo(REQUEST_CODE_SELECT_SECOND_VIDEO));
        btnGetInfo.setOnClickListener(v -> getVideoInfo());
        btnCompress.setOnClickListener(v -> compressHevc());
        btnCompressBase.setOnClickListener(v -> compressHevcBase());

        btnCrop.setOnClickListener(v -> cropVideo());
        btnSplit.setOnClickListener(v -> splitVideo());
        btnMerge.setOnClickListener(v -> mergeVideos());
        btnSpeedUp.setOnClickListener(v -> adjustSpeed(true));
        btnSpeedDown.setOnClickListener(v -> adjustSpeed(false));
    }

    /**
     * 串行压缩（基础版）- 已添加进度反馈
     */
    private void compressHevcBase() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        File inputFile = new File(selectedVideoPath);
        if (!inputFile.exists()) {
            showToast("输入文件不存在");
            return;
        }

        String baseName = inputFile.getName();
        int dot = baseName.lastIndexOf('.');
        String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String fileName = pureName + "_base_hevc" + ext;

        String outPath;
        try {
            outPath = createPublicVideoFile(fileName);
            File outFile = new File(outPath);
            File parentDir = outFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        } catch (IOException e) {
            showToast("创建输出文件失败: " + e.getMessage());
            return;
        }

        // 显示进度条和状态
        showProgress(true);
        progressBar.setProgress(0);
        tvStatus.setText("正在使用串行方式压缩视频...");

        // 创建进度回调
        MainActivity.ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
            if (status != null && !status.isEmpty()) {
                tvStatus.setText(status);
            }
            if (progress >= 0) {
                progressBar.setProgress(progress);
            }
        });

        // 在新线程中执行压缩
        new Thread(() -> {
            // 直接调用单线程转码方法
            boolean ok = VideoEditor.compressToHevcSingleThread(
                    selectedVideoPath, outPath, 0.45, progressCallback, 0);

            runOnUiThread(() -> {
                showProgress(false);
                if (ok) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        scanMediaFile(outPath);
                        long originalSize = inputFile.length();
                        long compressedSize = outputFile.length();
                        double ratio = (double) compressedSize / originalSize * 100;

                        tvStatus.setText(String.format(
                                "串行压缩完成！原始大小: %.1fMB, 压缩后: %.1fMB, 压缩比: %.1f%%",
                                originalSize/(1024.0*1024.0),
                                compressedSize/(1024.0*1024.0),
                                ratio));
                        progressBar.setProgress(100);
                        showToast("串行压缩完成");
                    } else {
                        tvStatus.setText("压缩失败：输出文件为空");
                        showToast("压缩失败：输出文件为空");
                    }
                } else {
                    tvStatus.setText("串行压缩失败");
                    showToast("串行压缩失败");
                }
            });
        }).start();
    }

    /**
     * 并行压缩（智能选择）- 已添加进度反馈
     */
    private void compressHevc() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        File inputFile = new File(selectedVideoPath);
        if (!inputFile.exists()) {
            showToast("输入文件不存在");
            return;
        }

        // 使用应用私有目录避免权限问题
        File outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (outputDir == null) {
            showToast("无法创建输出目录");
            return;
        }

        String baseName = inputFile.getName();
        int dot = baseName.lastIndexOf('.');
        String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String fileName = pureName + "_parallel_hevc" + ext;

        // 在应用私有目录中创建输出文件
        String outPath = new File(outputDir, fileName).getAbsolutePath();

        // 确保目录存在
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 删除已存在的文件
        new File(outPath).delete();

        // 显示进度条和状态
        showProgress(true);
        progressBar.setProgress(0);
        tvStatus.setText("开始压缩视频...");

        // 创建进度回调
        MainActivity.ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
            if (status != null && !status.isEmpty()) {
                tvStatus.setText(status);
            }
            if (progress >= 0) {
                progressBar.setProgress(progress);
            }
        });

        // 在新线程中执行智能压缩
        new Thread(() -> {
            boolean success = VideoEditor.compressVideo(
                    selectedVideoPath, outPath, 0.45, progressCallback);

            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // 将文件从私有目录移动到公共目录（使用正确的方法）
                        String publicPath = saveVideoToPublicDirectory(outputFile, fileName);

                        long originalSize = 0;
                        double ratio = 0;
                        long compressedSize = 0;
                        if (publicPath != null) {
                            // 扫描到媒体库
                            scanMediaFile(publicPath);
                            originalSize = inputFile.length();
                            compressedSize = outputFile.length();
                            ratio = (double) compressedSize / originalSize * 100;

                            tvStatus.setText(String.format(
                                    "压缩完成！\n" +
                                            "原始大小: %.1fMB\n" +
                                            "压缩后: %.1fMB\n" +
                                            "压缩比: %.1f%%\n" +
                                            "已保存到相册",
                                    originalSize / (1024.0 * 1024.0),
                                    compressedSize / (1024.0 * 1024.0),
                                    ratio));
                            progressBar.setProgress(100);
                            showToast("压缩完成，已保存到相册");

                            // 删除私有目录的临时文件
                            outputFile.delete();
                        } else {
                            // 如果保存到公共目录失败，显示私有目录位置
                            tvStatus.setText(String.format(
                                    "压缩完成！\n" +
                                            "原始大小: %.1fMB\n" +
                                            "压缩后: %.1fMB\n" +
                                            "压缩比: %.1f%%\n" +
                                            "文件保存在应用目录",
                                    originalSize / (1024.0 * 1024.0),
                                    compressedSize / (1024.0 * 1024.0),
                                    ratio));
                            showToast("压缩完成（文件保存在应用目录）");
                        }
                    } else {
                        tvStatus.setText("压缩失败：输出文件为空");
                        showToast("压缩失败：输出文件为空");
                    }
                } else {
                    tvStatus.setText("压缩失败");
                    showToast("压缩失败");
                }
            });
        }).start();
    }

    /**
     * 将视频文件保存到公共目录（相册）
     */
    private String saveVideoToPublicDirectory(File sourceFile, String fileName) {
        if (!sourceFile.exists()) {
            return null;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/VideoEditor");

                // 需要等待一段时间让系统准备好URI
                Uri uri = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        uri = getContentResolver().insert(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) break;
                        Thread.sleep(500);
                    } catch (Exception e) {
                        Log.e(TAG, "尝试插入MediaStore失败，重试 " + (i+1), e);
                    }
                }

                if (uri == null) {
                    Log.e(TAG, "无法获取MediaStore URI");
                    return null;
                }

                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        Log.e(TAG, "无法打开输出流");
                        return null;
                    }

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();

                    // 返回URI字符串供后续使用
                    return uri.toString();
                }
            } else {
                // Android 9及以下
                File moviesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES);
                File videoEditorDir = new File(moviesDir, "VideoEditor");
                if (!videoEditorDir.exists()) {
                    if (!videoEditorDir.mkdirs()) {
                        Log.e(TAG, "无法创建目录: " + videoEditorDir.getAbsolutePath());
                        return null;
                    }
                }

                File destFile = new File(videoEditorDir, fileName);
                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }

                // 扫描到媒体库
                MediaScannerConnection.scanFile(this,
                        new String[]{destFile.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);

                return destFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存到公共目录失败", e);
            return null;
        }
    }

    private void getVideoInfo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }
        showProgress(true);
        tvStatus.setText("正在获取视频信息...");

        new Thread(() -> {
            String info = VideoEditor.getVideoInfo(selectedVideoPath);
            runOnUiThread(() -> {
                VideoInfo.setText(info);
                showProgress(false);
                tvStatus.setText("视频信息获取完成");
            });
        }).start();
    }

    private void selectVideo(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        startActivityForResult(Intent.createChooser(intent, "选择视频"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = VideoEditorUtils.getPathFromUri(this, uri);
                if (requestCode == REQUEST_CODE_SELECT_VIDEO) {
                    selectedVideoPath = path;
                    tvVideoPath.setText("视频: " + (path != null ? new File(path).getName() : "未知"));
                } else if (requestCode == REQUEST_CODE_SELECT_SECOND_VIDEO) {
                    selectedSecondVideoPath = path;
                    tvSecondVideoPath.setText("视频2: " + (path != null ? new File(path).getName() : "未知"));
                }
            }
        }
    }

    private void cropVideo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        String startStr = etCropStart.getText().toString();
        String endStr = etCropEnd.getText().toString();

        if (startStr.isEmpty() || endStr.isEmpty()) {
            showToast("请输入开始和结束时间");
            return;
        }

        try {
            double startTime = Double.parseDouble(startStr);
            double endTime = Double.parseDouble(endStr);

            if (startTime >= endTime) {
                showToast("开始时间必须小于结束时间");
                return;
            }

            // 获取视频总时长
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            double totalDuration = 0;
            try {
                retriever.setDataSource(selectedVideoPath);
                String durationStr = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    totalDuration = Long.parseLong(durationStr) / 1000.0;

                    // 验证裁剪时间
                    if (startTime < 0) startTime = 0;
                    if (endTime > totalDuration) endTime = totalDuration;

                    if (endTime - startTime < 1.0) {
                        showToast("裁剪时长不能小于1秒");
                        return;
                    }

                    tvStatus.setText(String.format("视频总时长: %.1f秒，将裁剪 %.1f-%.1f秒",
                            totalDuration, startTime, endTime));
                } else {
                    showToast("无法获取视频时长");
                    return;
                }
            } finally {
                retriever.release();
            }

            // 使用应用私有目录
            File outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (outputDir == null) {
                showToast("无法创建输出目录");
                return;
            }

            String baseName = new File(selectedVideoPath).getName();
            int dot = baseName.lastIndexOf('.');
            String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
            String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";

            String outputPath = new File(outputDir,
                    pureName + "_cropped_" +
                            String.format(Locale.US, "%.1f-%.1f", startTime, endTime) +
                            ext).getAbsolutePath();

            // 确保输出文件不存在
            new File(outputPath).delete();

            Log.d(TAG, "输出路径: " + outputPath);

            // 显示进度
            showProgress(true);
            progressBar.setProgress(0);
            tvStatus.setText("开始裁剪视频...");

            // 创建进度回调
            ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
                if (status != null && !status.isEmpty()) {
                    tvStatus.setText(status);
                }
                if (progress >= 0) {
                    progressBar.setProgress(progress);
                }
            });

            // 在新线程中执行裁剪
            double finalStartTime = startTime;
            double finalEndTime = endTime;
            new Thread(() -> {
                boolean success = VideoEditor.cropVideoOptimized(
                        selectedVideoPath, outputPath,
                        (long)(finalStartTime * 1000000),
                        (long)(finalEndTime * 1000000),
                        progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File croppedFile = new File(outputPath);

                        if (croppedFile.exists() && croppedFile.length() > 0) {
                            // 先记录文件大小
                            long fileSize = croppedFile.length();

                            // 将文件保存到公共目录并扫描到媒体库
                            boolean saved = saveToPublicAndScan(croppedFile,
                                    pureName + "_cropped_" +
                                            String.format(Locale.US, "%.1f-%.1f", finalStartTime, finalEndTime) +
                                            ext);

                            // 保存成功后删除临时文件
                            if (saved && croppedFile.exists()) {
                                croppedFile.delete();
                                Log.d(TAG, "删除裁剪临时文件: " + croppedFile.getAbsolutePath());
                            }

                            tvStatus.setText(String.format(
                                    "裁剪完成！\n" +
                                            "大小: %.1fMB\n" +
                                            "时长: %.1f秒\n" +
                                            "已保存到相册",
                                    fileSize/(1024.0*1024.0),
                                    (finalEndTime - finalStartTime)));
                            progressBar.setProgress(100);

                            if (saved) {
                                showToast("裁剪完成！已保存到相册");
                            } else {
                                showToast("裁剪完成，但保存到相册失败");
                            }

                            // 清理缓存
                            cleanupVideoCache();
                        } else {
                            tvStatus.setText("裁剪失败：输出文件不存在");
                            showToast("裁剪失败：输出文件不存在");
                        }
                    } else {
                        tvStatus.setText("裁剪失败");
                        showToast("裁剪失败");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showToast("请输入有效的时间数字");
        } catch (Exception e) {
            showToast("裁剪出错: " + e.getMessage());
            Log.e(TAG, "裁剪视频异常", e);
        }
    }

    private void splitVideo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        String splitTimeStr = etSplitTime.getText().toString();
        if (splitTimeStr.isEmpty()) {
            showToast("请输入分割时间");
            return;
        }

        try {
            double splitTime = Double.parseDouble(splitTimeStr);

            // 获取视频总时长
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            double totalDuration = 0;
            try {
                retriever.setDataSource(selectedVideoPath);
                String durationStr = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    totalDuration = Long.parseLong(durationStr) / 1000.0;

                    // 验证分割时间
                    if (splitTime < 1.0) {
                        showToast("分割时间不能小于1秒");
                        return;
                    }
                    if (splitTime > totalDuration - 1.0) {
                        showToast(String.format("分割时间不能超过%.1f秒", totalDuration - 1.0));
                        return;
                    }

                    tvStatus.setText(String.format("视频总时长: %.1f秒，将在 %.1f秒 处分割",
                            totalDuration, splitTime));
                } else {
                    showToast("无法获取视频时长");
                    return;
                }
            } finally {
                retriever.release();
            }

            // 使用应用私有目录避免权限问题
            File outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (outputDir == null) {
                showToast("无法创建输出目录");
                return;
            }

            String baseName = new File(selectedVideoPath).getName();
            int dot = baseName.lastIndexOf('.');
            String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;

            String outputPath1 = new File(outputDir, pureName + "_part1.mp4").getAbsolutePath();
            String outputPath2 = new File(outputDir, pureName + "_part2.mp4").getAbsolutePath();

            // 确保输出文件不存在
            new File(outputPath1).delete();
            new File(outputPath2).delete();

            Log.d(TAG, "输出路径1: " + outputPath1);
            Log.d(TAG, "输出路径2: " + outputPath2);

            // 显示进度
            showProgress(true);
            progressBar.setProgress(0);
            tvStatus.setText("开始分割视频...");

            // 创建进度回调
            ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
                if (status != null && !status.isEmpty()) {
                    tvStatus.setText(status);
                }
                if (progress >= 0) {
                    progressBar.setProgress(progress);
                }
            });

            // 在新线程中执行分割
            new Thread(() -> {
                boolean success = VideoEditor.splitVideoOptimized(
                        selectedVideoPath, outputPath1, outputPath2,
                        (long)(splitTime * 1000000), progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File part1 = new File(outputPath1);
                        File part2 = new File(outputPath2);

                        if (part1.exists() && part2.exists()) {
                            // 先记录文件大小
                            long part1Size = part1.length();
                            long part2Size = part2.length();

                            // 将文件保存到公共目录并扫描到媒体库
                            boolean saved1 = saveToPublicAndScan(part1, pureName + "_part1.mp4");
                            boolean saved2 = saveToPublicAndScan(part2, pureName + "_part2.mp4");

                            // 保存成功后删除临时文件
                            if (saved1 && part1.exists()) {
                                part1.delete();
                                Log.d(TAG, "删除临时文件1: " + part1.getAbsolutePath());
                            }
                            if (saved2 && part2.exists()) {
                                part2.delete();
                                Log.d(TAG, "删除临时文件2: " + part2.getAbsolutePath());
                            }

                            // 使用之前记录的文件大小显示
                            tvStatus.setText(String.format(
                                    "分割完成！\n" +
                                            "第一部分: %.1fMB\n" +
                                            "第二部分: %.1fMB\n" +
                                            "已保存到相册",
                                    part1Size/(1024.0*1024.0),
                                    part2Size/(1024.0*1024.0)));
                            progressBar.setProgress(100);

                            if (saved1 && saved2) {
                                showToast("分割完成！已保存到相册");
                            } else {
                                showToast("分割完成，部分文件保存失败");
                            }

                            // 清理视频编辑器的临时缓存目录
                            cleanupVideoCache();
                        } else {
                            tvStatus.setText("分割失败：输出文件不存在");
                            showToast("分割失败：输出文件不存在");
                        }
                    } else {
                        tvStatus.setText("分割失败");
                        showToast("分割失败");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showToast("请输入有效的时间数字");
        } catch (Exception e) {
            showToast("分割出错: " + e.getMessage());
            Log.e(TAG, "分割视频异常", e);
        }
    }

    // 辅助方法：保存到公共目录并扫描
    private boolean saveToPublicAndScan(File sourceFile, String fileName) {
        if (!sourceFile.exists()) {
            Log.e(TAG, "源文件不存在: " + sourceFile.getAbsolutePath());
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/VideoEditor");

                Uri uri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                            Log.d(TAG, "文件已保存到MediaStore: " + uri.toString());

                            // 触发媒体扫描
                            scanMediaFile(uri.toString());
                            return true;
                        }
                    }
                }
            } else {
                // Android 9及以下
                File moviesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES);
                File videoEditorDir = new File(moviesDir, "VideoEditor");
                if (!videoEditorDir.exists()) {
                    videoEditorDir.mkdirs();
                }

                File destFile = new File(videoEditorDir, fileName);
                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }

                // 扫描到媒体库
                MediaScannerConnection.scanFile(this,
                        new String[]{destFile.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);

                Log.d(TAG, "文件已保存到公共目录: " + destFile.getAbsolutePath());
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "保存到公共目录失败", e);
            return false;
        }
        return false;
    }

    /**
     * 清理视频编辑器的所有临时缓存（使用强制删除）
     */
    private void cleanupVideoCache() {
        // 使用同步锁避免多个线程同时清理
        synchronized (MainActivity.class) {
            new Thread(() -> {
                try {
                    Log.d(TAG, "开始清理视频缓存...");

                    // 清理应用缓存目录
                    File cacheDir = getExternalCacheDir();
                    if (cacheDir != null && cacheDir.exists()) {
                        Log.d(TAG, "清理应用缓存目录: " + cacheDir.getAbsolutePath());
                        safeDeleteDirectory(cacheDir);
                    }

                    // 清理视频编辑器私有目录的临时文件（使用强制删除）
                    File videoDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (videoDir != null && videoDir.exists()) {
                        Log.d(TAG, "清理视频临时文件目录: " + videoDir.getAbsolutePath());
                        safeDeleteTempFiles(videoDir);
                    }

                    // 清理VideoEditor类的缓存目录（使用强制删除）
                    File editorCacheDir = VideoEditor.getCacheDir();
                    if (editorCacheDir.exists()) {
                        Log.d(TAG, "清理VideoEditor缓存目录: " + editorCacheDir.getAbsolutePath());
                        safeDeleteDirectory(editorCacheDir);
                    }

                    Log.d(TAG, "视频缓存清理完成");
                } catch (Exception e) {
                    Log.e(TAG, "清理缓存失败", e);
                }
            }).start();
        }
    }

    /**
     * 安全删除目录（避免竞争条件）
     */
    private void safeDeleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        synchronized (MainActivity.class) {
            try {
                // 首先检查目录是否仍然存在
                if (!directory.exists()) {
                    return;
                }

                // 递归删除目录内容
                if (directory.isDirectory()) {
                    File[] files = directory.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isDirectory()) {
                                safeDeleteDirectory(file);
                            } else {
                                safeDeleteFile(file);
                            }
                        }
                    }
                }

                // 删除目录本身
                if (directory.exists()) {
                    boolean deleted = directory.delete();
                    if (!deleted) {
                        // 如果删除失败，尝试重试一次
                        Thread.sleep(100);
                        directory.delete();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "安全删除目录异常: " + directory.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 安全删除文件（避免竞争条件）
     */
    private boolean safeDeleteFile(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        synchronized (MainActivity.class) {
            try {
                // 再次检查文件是否存在
                if (!file.exists()) {
                    return true;
                }

                // 方法1：直接删除
                boolean deleted = file.delete();

                if (!deleted) {
                    // 方法2：设置可写后删除
                    file.setWritable(true);
                    deleted = file.delete();

                    if (!deleted) {
                        // 方法3：重命名为临时名称再删除
                        String tempName = file.getAbsolutePath() + ".deleted_" + System.currentTimeMillis();
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
    }

    /**
     * 安全删除临时文件
     */
    private void safeDeleteTempFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        synchronized (MainActivity.class) {
            try {
                File[] files = directory.listFiles();
                if (files == null) return;

                for (File file : files) {
                    if (file.isDirectory()) {
                        // 如果是临时目录，完全删除
                        String dirName = file.getName().toLowerCase();
                        if (dirName.contains("temp") || dirName.contains("cache")) {
                            safeDeleteDirectory(file);
                        } else {
                            safeDeleteTempFiles(file);
                        }
                    } else {
                        // 删除临时文件，保留.mp4文件（用户可能还没保存）
                        String name = file.getName().toLowerCase();
                        if (name.startsWith("temp_") ||
                                name.startsWith("video_segment_") ||
                                name.startsWith("audio_") ||
                                name.startsWith("merged_video_") ||
                                name.startsWith("cache_")) {
                            safeDeleteFile(file);
                            Log.d(TAG, "安全删除临时文件: " + file.getName());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "安全删除临时文件异常", e);
            }
        }
    }

    /**
     * 强制删除临时文件（保留.mp4扩展名的文件，因为它们可能是用户需要的）
     */
    private void deleteTempFilesForce(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                deleteTempFilesForce(file);
                // 如果是空目录，强制删除它
                if (file.listFiles() == null || Objects.requireNonNull(file.listFiles()).length == 0) {
                    forceDeleteFile(file);
                }
            } else {
                // 删除临时文件，保留.mp4文件（用户可能还没保存）
                String name = file.getName().toLowerCase();
                if (name.startsWith("temp_") ||
                        name.startsWith("video_segment_") ||
                        name.startsWith("audio_") ||
                        name.startsWith("merged_video_") ||
                        name.startsWith("cache_")) {
                    forceDeleteFile(file);
                    Log.d(TAG, "强制删除临时文件: " + file.getName());
                }
            }
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            }
            // 不删除目录本身，只删除内容
            if (!file.isDirectory()) {
                file.delete();
            }
        }
    }

    private void mergeVideos() {
        if (selectedVideoPath == null || selectedSecondVideoPath == null) {
            showToast("请选择两个视频");
            return;
        }

        // 使用与分割、裁剪一致的路径格式
        File outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (outputDir == null) {
            showToast("无法创建输出目录");
            return;
        }

        String baseName = new File(selectedVideoPath).getName();
        int dot = baseName.lastIndexOf('.');
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";

        // 使用final变量，以便在lambda中使用
        final String outputPath = new File(outputDir,
                pureName + "_merged_" + System.currentTimeMillis() + ext).getAbsolutePath();

        // 用于lambda表达式的final数组，用于存储文件大小
        final long[] fileSizeHolder = new long[1];

        // 使用final变量存储文件名
        final String finalPublicFileName = pureName + "_merged_" + System.currentTimeMillis() + ext;

        // 确保输出文件不存在
        File outputFile = new File(outputPath);
        if (outputFile.exists()) {
            // 强制删除，不进入回收站
            forceDeleteFile(outputFile);
        }

        // 显示进度条和状态
        showProgress(true);
        progressBar.setProgress(0);
        tvStatus.setText("开始合并视频...");

        // 创建进度回调
        ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
            if (status != null && !status.isEmpty()) {
                tvStatus.setText(status);
            }
            if (progress >= 0) {
                progressBar.setProgress(progress);
            }
        });

        // 在新线程中执行合并
        new Thread(() -> {
            // 使用带音频的合并方法
            boolean success = VideoEditor.mergeVideos(
                    new String[]{selectedVideoPath, selectedSecondVideoPath},
                    outputPath,
                    progressCallback);

            // 在子线程中获取文件信息，避免lambda中的变量问题
            File resultFile = new File(outputPath);
            long fileSize = resultFile.exists() ? resultFile.length() : 0;
            fileSizeHolder[0] = fileSize;

            runOnUiThread(() -> {
                showProgress(false);

                // 重新检查文件
                File finalOutputFile = new File(outputPath);
                boolean fileExists = finalOutputFile.exists();
                long finalFileSize = fileSizeHolder[0];

                if (success && fileExists && finalFileSize > 0) {
                    // 调用已有的保存方法
                    boolean saved = saveToPublicAndScan(finalOutputFile, finalPublicFileName);

                    if (saved) {
                        // 强制删除临时文件，不进入回收站
                        boolean deleted = safeDeleteFile(finalOutputFile);
                        if (deleted) {
                            Log.d(TAG, "成功删除合并临时文件: " + outputPath);
                        } else {
                            Log.w(TAG, "删除合并临时文件失败: " + outputPath);
                        }

                        // 计算文件大小
                        double sizeInMB = finalFileSize / (1024.0 * 1024.0);
                        tvStatus.setText(String.format(
                                "合并完成！\n" +
                                        "大小: %.1fMB\n" +
                                        "已保存到相册",
                                sizeInMB));
                        progressBar.setProgress(100);
                        showToast("合并完成！已保存到相册");

                        // 调用统一的缓存清理
                        cleanupVideoCache();
                    } else {
                        // 如果保存失败，显示应用目录位置
                        double sizeInMB = finalFileSize / (1024.0 * 1024.0);
                        tvStatus.setText(String.format(
                                "合并完成！\n" +
                                        "大小: %.1fMB\n" +
                                        "文件保存在应用目录",
                                sizeInMB));
                        showToast("合并完成（保存在应用目录）");
                    }
                } else if (success && fileExists) {
                    // 文件大小为0的情况
                    forceDeleteFile(finalOutputFile);
                    tvStatus.setText("合并失败：输出文件大小为0");
                    showToast("合并失败：输出文件大小为0");
                } else {
                    tvStatus.setText("合并失败");
                    showToast("合并失败");

                    // 清理可能创建的无效文件
                    forceDeleteFile(finalOutputFile);
                }

                // 无论成功失败，都清理缓存
                cleanupVideoCache();
            });
        }).start();
    }

    /**
     * 强制删除文件，不进入回收站
     * 使用多种方法确保文件被彻底删除
     */
    private boolean forceDeleteFile(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        // 使用同步锁避免竞争条件
        synchronized (MainActivity.class) {
            try {
                // 再次检查文件是否存在
                if (!file.exists()) {
                    return true;
                }

                // 方法1：直接删除
                boolean deleted = file.delete();

                if (!deleted) {
                    // 方法2：设置可写后删除
                    file.setWritable(true);
                    deleted = file.delete();

                    if (!deleted) {
                        // 方法3：重命名为临时名称再删除
                        String tempName = file.getAbsolutePath() + ".deleted_" + System.currentTimeMillis();
                        File tempFile = new File(tempName);

                        if (file.renameTo(tempFile)) {
                            deleted = tempFile.delete();
                            if (!deleted) {
                                Log.w(TAG, "重命名后删除失败: " + tempFile.getAbsolutePath());
                            }
                        } else {
                            Log.w(TAG, "重命名失败，直接删除: " + file.getAbsolutePath());
                        }
                    }
                }

                return deleted;
            } catch (Exception e) {
                Log.e(TAG, "强制删除文件异常: " + file.getAbsolutePath(), e);

                // 最后尝试方案
                try {
                    file.setWritable(true);
                    return file.delete();
                } catch (Exception ex) {
                    Log.e(TAG, "设置可写后删除失败", ex);
                    return false;
                }
            }
        }
    }

    /**
     * 强制删除目录及其内容
     */
    private boolean forceDeleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return true;
        }

        // 使用同步锁避免竞争条件
        synchronized (MainActivity.class) {
            try {
                // 再次检查目录是否存在
                if (!directory.exists()) {
                    return true;
                }

                if (directory.isDirectory()) {
                    File[] files = directory.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isDirectory()) {
                                forceDeleteDirectory(file);
                            } else {
                                forceDeleteFile(file);
                            }
                        }
                    }
                }

                // 删除目录本身
                if (directory.exists()) {
                    return forceDeleteFile(directory);
                }

                return true;
            } catch (Exception e) {
                Log.e(TAG, "强制删除目录异常: " + directory.getAbsolutePath(), e);
                return false;
            }
        }
    }

    private void adjustSpeed(boolean speedUp) {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        String speedStr = etSpeedFactor.getText().toString();
        double defaultSpeedFactor = speedUp ? 2.0 : 0.5;
        double speedFactor = defaultSpeedFactor;

        if (!speedStr.isEmpty()) {
            try {
                double parsedSpeed = Double.parseDouble(speedStr);
                if (parsedSpeed <= 0) {
                    showToast("速度倍数必须大于0");
                    return;
                }
                speedFactor = parsedSpeed;
            } catch (NumberFormatException e) {
                showToast("请输入有效的速度倍数");
                return;
            }
        }

        final double finalSpeedFactor = speedFactor;
        String outputPath = getOutputPath((speedUp ? "speedup_" : "slowdown_") +
                System.currentTimeMillis() + ".mp4");
        showProgress(true);
        tvStatus.setText("正在调节速度...");

        new Thread(() -> {
            boolean success = VideoEditor.adjustSpeed(selectedVideoPath, outputPath, finalSpeedFactor);

            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    String savedPath = saveToPublicDirectory(outputPath,
                            (speedUp ? "speedup_" : "slowdown_") + System.currentTimeMillis() + ".mp4");
                    if (savedPath != null) {
                        tvStatus.setText("速度调节成功: " + new File(savedPath).getName());
                        showToast("速度调节成功，已保存到相册");
                        scanMediaFile(savedPath);
                    } else {
                        tvStatus.setText("速度调节成功: " + new File(outputPath).getName());
                        showToast("速度调节成功（保存在应用目录）");
                    }
                } else {
                    tvStatus.setText("速度调节失败");
                    showToast("速度调节失败");
                }
            });
        }).start();
    }

    private String getOutputPath(String filename) {
        File outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "edited");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return new File(outputDir, filename).getAbsolutePath();
    }

    private String saveToPublicDirectory(String sourcePath, String filename) {
        try {
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                return null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor");

                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            return uri.toString();
                        }
                    }
                }
            } else {
                File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                File videoEditorDir = new File(moviesDir, "VideoEditor");
                if (!videoEditorDir.exists()) {
                    videoEditorDir.mkdirs();
                }

                File destFile = new File(videoEditorDir, filename);
                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    return destFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    private void scanMediaFile(String filePath) {
        if (filePath.startsWith("content://")) {
            return;
        }
        try {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            File file = new File(filePath);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            sendBroadcast(mediaScanIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showProgress(boolean show) {
        runOnUiThread(() -> {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            tvStatus.setVisibility(View.VISIBLE);
            if (!show) {
                progressBar.setProgress(0);
            }
        });
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private String createPublicVideoFile(String fileName) throws IOException {
        File targetFile;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor");
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            targetFile = new File("/storage/emulated/0/" +
                    Environment.DIRECTORY_MOVIES + "/VideoEditor/" + fileName);
            pfd.close();
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File dir = new File(movies, "VideoEditor");
            if (!dir.exists()) dir.mkdirs();
            targetFile = new File(dir, fileName);
            targetFile.createNewFile();
        }
        return targetFile.getAbsolutePath();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showToast("需要存储权限才能使用此功能");
                    return;
                }
            }
        }
    }
}

