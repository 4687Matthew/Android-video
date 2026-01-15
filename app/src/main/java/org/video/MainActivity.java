package org.video;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
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

        String baseName = inputFile.getName();
        int dot = baseName.lastIndexOf('.');
        String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String fileName = pureName + "_parallel_hevc" + ext;

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
                        scanMediaFile(outPath);
                        long originalSize = inputFile.length();
                        long compressedSize = outputFile.length();
                        double ratio = (double) compressedSize / originalSize * 100;

                        tvStatus.setText(String.format(
                                "压缩完成！原始大小: %.1fMB, 压缩后: %.1fMB, 压缩比: %.1f%%",
                                originalSize/(1024.0*1024.0),
                                compressedSize/(1024.0*1024.0),
                                ratio));
                        progressBar.setProgress(100);
                        showToast("压缩完成");
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

            // 生成输出文件名
            File inputFile = new File(selectedVideoPath);
            String baseName = inputFile.getName();
            int dot = baseName.lastIndexOf('.');
            String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";
            String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
            String fileName = pureName + "_cropped_" +
                    String.format(Locale.US, "%.1f-%.1f", startTime, endTime) + ext;

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
            tvStatus.setText("准备裁剪视频...");

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
            new Thread(() -> {
                boolean success = VideoEditor.cropVideo(
                        selectedVideoPath, outPath,
                        (long)(startTime * 1000000),
                        (long)(endTime * 1000000),
                        progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File outputFile = new File(outPath);
                        if (outputFile.exists() && outputFile.length() > 0) {
                            // 计算原始和裁剪后的文件大小
                            File input = new File(selectedVideoPath);
                            long originalSize = input.length();
                            long croppedSize = outputFile.length();
                            double duration = (endTime - startTime);

                            // 添加到媒体库
                            scanMediaFile(outPath);

                            // 显示结果信息
                            try {
                                tvStatus.setText(String.format(
                                        "裁剪完成！\n" +
                                                "时长: %.1f秒 → %.1f秒\n" +
                                                "大小: %.1fMB → %.1fMB\n" +
                                                "保存位置: %s",
                                        getVideoDuration(selectedVideoPath),
                                        duration,
                                        originalSize/(1024.0*1024.0),
                                        croppedSize/(1024.0*1024.0),
                                        outputFile.getName()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            progressBar.setProgress(100);
                            showToast("裁剪完成，已保存到相册");
                        } else {
                            tvStatus.setText("裁剪失败：输出文件为空");
                            showToast("裁剪失败：输出文件为空");
                        }
                    } else {
                        tvStatus.setText("裁剪失败");
                        showToast("裁剪失败");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showToast("请输入有效的时间数字");
        }
    }

    /**
     * 获取视频时长（秒）
     */
    private double getVideoDuration(String path) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return durationMs / 1000.0;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            retriever.release();
        }
        return 0;
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

            // 获取视频总时长，验证分割时间是否合理
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(selectedVideoPath);
                String durationStr = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    long durationMs = Long.parseLong(durationStr);
                    double totalDuration = durationMs / 1000.0;

                    if (splitTime <= 1.0 || splitTime >= totalDuration - 1.0) {
                        showToast(String.format("分割点必须距离开始和结束至少1秒（总时长: %.1f秒）", totalDuration));
                        return;
                    }

                    // 显示时长信息
                    tvStatus.setText(String.format("视频总时长: %.1f秒，将精确分割为 %.1f秒 和 %.1f秒",
                            totalDuration, splitTime, totalDuration - splitTime));
                }
            } finally {
                retriever.release();
            }

            // 生成输出文件名
            File inputFile = new File(selectedVideoPath);
            String baseName = inputFile.getName();
            int dot = baseName.lastIndexOf('.');
            String ext = dot >= 0 ? baseName.substring(dot) : ".mp4";
            String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;

            String fileName1 = pureName + "_part1_precise" + ext;
            String fileName2 = pureName + "_part2_precise" + ext;

            String outputPath1, outputPath2;
            try {
                outputPath1 = createPublicVideoFile(fileName1);
                outputPath2 = createPublicVideoFile(fileName2);
            } catch (IOException e) {
                showToast("创建输出文件失败: " + e.getMessage());
                return;
            }

            // 显示进度条和状态
            showProgress(true);
            progressBar.setProgress(0);
            tvStatus.setText("开始精确分割...");

            // 创建进度回调
            ProgressCallback progressCallback = (status, progress) -> runOnUiThread(() -> {
                if (status != null && !status.isEmpty()) {
                    tvStatus.setText(status);
                }
                if (progress >= 0) {
                    progressBar.setProgress(progress);
                }
            });

            // 在新线程中执行精确分割
            new Thread(() -> {
                boolean success = VideoEditor.splitVideoOptimized(
                        selectedVideoPath, outputPath1, outputPath2,
                        (long)(splitTime * 1000000), progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File outputFile1 = new File(outputPath1);
                        File outputFile2 = new File(outputPath2);

                        if (outputFile1.exists() && outputFile1.length() > 0 &&
                                outputFile2.exists() && outputFile2.length() > 0) {

                            // 添加到媒体库
                            scanMediaFile(outputPath1);
                            scanMediaFile(outputPath2);

                            // 获取分割后视频的时长
                            try {
                                double duration1 = getVideoDuration(outputPath1);
                                double duration2 = getVideoDuration(outputPath2);

                                // 显示结果信息
                                tvStatus.setText(String.format(
                                        "精确分割完成！\n" +
                                                "第一部分: %.1f秒, %.1fMB\n" +
                                                "第二部分: %.1f秒, %.1fMB\n" +
                                                "分割点: %.1f秒 (精确)",
                                        duration1,
                                        outputFile1.length()/(1024.0*1024.0),
                                        duration2,
                                        outputFile2.length()/(1024.0*1024.0),
                                        splitTime));
                            } catch (IOException e) {
                                tvStatus.setText(String.format(
                                        "精确分割完成！\n" +
                                                "第一部分: %.1fMB\n" +
                                                "第二部分: %.1fMB",
                                        outputFile1.length()/(1024.0*1024.0),
                                        outputFile2.length()/(1024.0*1024.0)));
                            }

                            progressBar.setProgress(100);
                            showToast("精确分割完成，已保存到相册");
                        } else {
                            tvStatus.setText("分割失败：输出文件为空");
                            showToast("分割失败：输出文件为空");
                        }
                    } else {
                        tvStatus.setText("精确分割失败");
                        showToast("精确分割失败");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showToast("请输入有效的时间数字");
        } catch (Exception e) {
            showToast("分割出错: " + e.getMessage());
        }
    }

    private void mergeVideos() {
        if (selectedVideoPath == null || selectedSecondVideoPath == null) {
            showToast("请选择两个视频");
            return;
        }

        String outputPath = getOutputPath("merged_" + System.currentTimeMillis() + ".mp4");

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
            boolean success = VideoEditor.mergeVideosWithProgress(
                    new String[]{selectedVideoPath, selectedSecondVideoPath},
                    outputPath,
                    progressCallback);

            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    String savedPath = saveToPublicDirectory(outputPath,
                            "merged_" + System.currentTimeMillis() + ".mp4");
                    if (savedPath != null) {
                        tvStatus.setText("合并成功: " + new File(savedPath).getName());
                        showToast("合并成功，已保存到相册");
                        scanMediaFile(savedPath);
                    } else {
                        tvStatus.setText("合并成功: " + new File(outputPath).getName());
                        showToast("合并成功（保存在应用目录）");
                    }
                } else {
                    tvStatus.setText("合并失败");
                    showToast("合并失败");
                }
            });
        }).start();
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

