package org.video;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;
import static org.video.Constants.REQUEST_CODE_SELECT_SECOND_VIDEO;
import static org.video.Constants.REQUEST_CODE_SELECT_VIDEO;
import static org.video.Constants.REQUEST_PERMISSION_CODE;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaFormat;
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

import org.video.utils.FileUtils;
import org.video.utils.TimeUtils;
import org.video.utils.VideoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

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

        // 使用FileUtils生成文件名
        String baseName = inputFile.getName();
        int dot = baseName.lastIndexOf('.');
        String ext = dot >= 0 ? baseName.substring(dot) : Constants.FILE_EXT_MP4;
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String fileName = pureName + Constants.SUFFIX_BASE_HEVC + ext;

        String outPath;
        try {
            outPath = createPublicVideoFile(fileName);
            File outFile = new File(outPath);
            File parentDir = outFile.getParentFile();
            if (!FileUtils.ensureDirectoryExists(parentDir)) {
                showToast("创建输出目录失败");
                return;
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
                    selectedVideoPath, outPath, Constants.DEFAULT_COMPRESSION_RATIO,
                    progressCallback, 0);

            runOnUiThread(() -> {
                showProgress(false);
                if (ok) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // 使用VideoUtils扫描媒体文件
                        VideoUtils.scanMediaFile(this, outPath);
                        long originalSize = inputFile.length();
                        long compressedSize = outputFile.length();

                        // 使用TimeUtils计算压缩比
                        String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                        // 使用FileUtils获取文件大小描述
                        String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                        String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                        tvStatus.setText(String.format(
                                "串行压缩完成！\n原始大小: %s\n压缩后: %s\n压缩比: %s",
                                originalSizeStr, compressedSizeStr, ratioStr));
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

        // 使用FileUtils生成文件名
        String baseName = inputFile.getName();
        int dot = baseName.lastIndexOf('.');
        String ext = dot >= 0 ? baseName.substring(dot) : Constants.FILE_EXT_MP4;
        String pureName = dot >= 0 ? baseName.substring(0, dot) : baseName;
        String fileName = pureName + Constants.SUFFIX_PARALLEL_HEVC + ext;

        // 在应用私有目录中创建输出文件
        String outPath = new File(outputDir, fileName).getAbsolutePath();

        // 确保目录存在
        if (!FileUtils.ensureDirectoryExists(outputDir)) {
            showToast("无法创建输出目录");
            return;
        }

        // 删除已存在的文件
        FileUtils.deleteIfExists(outPath);

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
                    selectedVideoPath, outPath, Constants.DEFAULT_COMPRESSION_RATIO, progressCallback);

            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // 将文件从私有目录移动到公共目录（使用FileUtils）
                        String publicPath = FileUtils.saveToPublicDirectory(this, outputFile, fileName);

                        if (publicPath != null) {
                            // 扫描到媒体库
                            VideoUtils.scanMediaFile(this, publicPath);
                            long originalSize = inputFile.length();
                            long compressedSize = outputFile.length();

                            // 使用TimeUtils计算压缩比
                            String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                            // 使用FileUtils获取文件大小描述
                            String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                            String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                            tvStatus.setText(String.format(
                                    "压缩完成！\n" +
                                            "原始大小: %s\n" +
                                            "压缩后: %s\n" +
                                            "压缩比: %s\n" +
                                            "已保存到相册",
                                    originalSizeStr, compressedSizeStr, ratioStr));
                            progressBar.setProgress(100);
                            showToast("压缩完成，已保存到相册");

                            // 删除私有目录的临时文件
                            FileUtils.safeDeleteFile(outputFile);
                        } else {
                            // 如果保存到公共目录失败，显示私有目录位置
                            long originalSize = inputFile.length();
                            long compressedSize = outputFile.length();
                            String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                            String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                            String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                            tvStatus.setText(String.format(
                                    "压缩完成！\n" +
                                            "原始大小: %s\n" +
                                            "压缩后: %s\n" +
                                            "压缩比: %s\n" +
                                            "文件保存在应用目录",
                                    originalSizeStr, compressedSizeStr, ratioStr));
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

    private void getVideoInfo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        if (!VideoUtils.isValidVideoFile(selectedVideoPath)) {
            showToast("视频文件无效");
            return;
        }

        showProgress(true);
        tvStatus.setText("正在获取视频信息...");

        new Thread(() -> {
            try {
                // 使用VideoUtils获取轨道信息
                MediaFormat[] formats = VideoUtils.getTrackFormats(selectedVideoPath);
                MediaFormat videoFormat = formats[0];

                String info;
                if (videoFormat != null) {
                    // 使用VideoUtils获取详细视频信息
                    info = VideoUtils.getVideoInfoString(videoFormat);
                } else {
                    info = "未找到视频轨道";
                }

                final String finalInfo = info;
                runOnUiThread(() -> {
                    VideoInfo.setText(finalInfo);
                    showProgress(false);
                    tvStatus.setText("视频信息获取完成");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    VideoInfo.setText("获取信息失败: " + e.getMessage());
                    showProgress(false);
                    tvStatus.setText("视频信息获取失败");
                });
            }
        }).start();
    }

    private void selectVideo(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(Constants.MIME_TYPE_VIDEO);
        startActivityForResult(Intent.createChooser(intent, "选择视频"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = VideoUtils.getPathFromUri(this, uri);
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

        if (!VideoUtils.isValidVideoFile(selectedVideoPath)) {
            showToast("视频文件无效");
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

            // 使用TimeUtils验证时间范围
            if (!TimeUtils.isValidTimeRange(startTime, endTime, Constants.MIN_VIDEO_DURATION)) {
                showToast("开始时间必须小于结束时间，且时长不小于1秒");
                return;
            }

            // 获取视频总时长（使用TimeUtils转换）
            long totalDurationMs = VideoUtils.getVideoDurationMs(selectedVideoPath);
            double totalDuration = TimeUtils.microsecondsToSeconds(TimeUtils.millisecondsToMicroseconds(totalDurationMs));

            if (totalDuration <= 0) {
                showToast("无法获取视频时长");
                return;
            }

            // 验证裁剪时间
            if (startTime < 0) startTime = 0;
            if (endTime > totalDuration) endTime = totalDuration;

            // 使用TimeUtils验证裁剪时长
            if (!TimeUtils.isValidTimeRange(startTime, endTime, Constants.MIN_VIDEO_DURATION)) {
                showToast("裁剪时长不能小于1秒");
                return;
            }

            tvStatus.setText(String.format("视频总时长: %.1f秒，将裁剪 %.1f-%.1f秒",
                    totalDuration, startTime, endTime));

            // 使用FileUtils创建输出路径
            String baseName = new File(selectedVideoPath).getName();
            String outputPath = FileUtils.createPrivateOutputPath(this, selectedVideoPath, Constants.SUFFIX_CROPPED);

            if (outputPath == null) {
                showToast("无法创建输出文件");
                return;
            }

            // 确保输出文件不存在
            FileUtils.deleteIfExists(outputPath);

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
                // 使用TimeUtils转换时间单位
                long startTimeUs = TimeUtils.secondsToMicroseconds(finalStartTime);
                long endTimeUs = TimeUtils.secondsToMicroseconds(finalEndTime);

                boolean success = VideoEditor.cropVideoOptimized(
                        selectedVideoPath, outputPath,
                        startTimeUs, endTimeUs,
                        progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File croppedFile = new File(outputPath);

                        if (croppedFile.exists() && croppedFile.length() > 0) {
                            // 使用FileUtils获取文件大小
                            String fileSizeStr = FileUtils.getFileSizeDescription(outputPath);

                            // 将文件保存到公共目录并扫描到媒体库
                            String fileName = FileUtils.generateTimestampFilename(
                                    new File(selectedVideoPath).getName(),
                                    Constants.SUFFIX_CROPPED);

                            boolean saved = saveToPublicAndScan(croppedFile, fileName);

                            // 保存成功后删除临时文件
                            if (saved && croppedFile.exists()) {
                                FileUtils.safeDeleteFile(croppedFile);
                                Log.d(TAG, "删除裁剪临时文件: " + croppedFile.getAbsolutePath());
                            }

                            // 使用TimeUtils格式化时长
                            String durationStr = TimeUtils.formatSeconds(finalEndTime - finalStartTime);

                            tvStatus.setText(String.format(
                                    "裁剪完成！\n" +
                                            "大小: %s\n" +
                                            "时长: %s\n" +
                                            "已保存到相册",
                                    fileSizeStr, durationStr));
                            progressBar.setProgress(100);

                            if (saved) {
                                showToast("裁剪完成！已保存到相册");
                            } else {
                                showToast("裁剪完成，但保存到相册失败");
                            }

                            // 使用FileUtils清理缓存
                            FileUtils.cleanupVideoEditorCache(MainActivity.this);
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

        if (!VideoUtils.isValidVideoFile(selectedVideoPath)) {
            showToast("视频文件无效");
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
            long totalDurationMs = VideoUtils.getVideoDurationMs(selectedVideoPath);
            double totalDuration = TimeUtils.microsecondsToSeconds(TimeUtils.millisecondsToMicroseconds(totalDurationMs));

            if (totalDuration <= 0) {
                showToast("无法获取视频时长");
                return;
            }

            // 使用TimeUtils验证分割时间
            if (splitTime < Constants.MIN_VIDEO_DURATION) {
                showToast("分割时间不能小于1秒");
                return;
            }

            if (splitTime > totalDuration - Constants.MIN_VIDEO_DURATION) {
                showToast(String.format("分割时间不能超过%.1f秒", totalDuration - Constants.MIN_VIDEO_DURATION));
                return;
            }

            tvStatus.setText(String.format("视频总时长: %.1f秒，将在 %.1f秒 处分割",
                    totalDuration, splitTime));

            // 使用FileUtils创建输出路径
            String baseName = new File(selectedVideoPath).getName();

            String outputPath1 = FileUtils.createPrivateOutputPath(this, selectedVideoPath, Constants.PREFIX_PART + "1");
            String outputPath2 = FileUtils.createPrivateOutputPath(this, selectedVideoPath, Constants.PREFIX_PART + "2");

            if (outputPath1 == null || outputPath2 == null) {
                showToast("无法创建输出文件");
                return;
            }

            // 确保输出文件不存在
            FileUtils.deleteIfExists(outputPath1);
            FileUtils.deleteIfExists(outputPath2);

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
                // 使用TimeUtils转换时间单位
                long splitTimeUs = TimeUtils.secondsToMicroseconds(splitTime);

                boolean success = VideoEditor.splitVideoOptimized(
                        selectedVideoPath, outputPath1, outputPath2,
                        splitTimeUs, progressCallback);

                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        File part1 = new File(outputPath1);
                        File part2 = new File(outputPath2);

                        if (part1.exists() && part2.exists()) {
                            // 使用FileUtils获取文件大小
                            String part1SizeStr = FileUtils.getFileSizeDescription(outputPath1);
                            String part2SizeStr = FileUtils.getFileSizeDescription(outputPath2);

                            // 将文件保存到公共目录并扫描到媒体库
                            String part1Name = FileUtils.generateSequentialFilename(
                                    new File(selectedVideoPath).getName(),
                                    Constants.PREFIX_PART, 1);
                            String part2Name = FileUtils.generateSequentialFilename(
                                    new File(selectedVideoPath).getName(),
                                    Constants.PREFIX_PART, 2);

                            boolean saved1 = saveToPublicAndScan(part1, part1Name);
                            boolean saved2 = saveToPublicAndScan(part2, part2Name);

                            // 保存成功后删除临时文件
                            if (saved1 && part1.exists()) {
                                FileUtils.safeDeleteFile(part1);
                                Log.d(TAG, "删除临时文件1: " + part1.getAbsolutePath());
                            }
                            if (saved2 && part2.exists()) {
                                FileUtils.safeDeleteFile(part2);
                                Log.d(TAG, "删除临时文件2: " + part2.getAbsolutePath());
                            }

                            // 使用FileUtils获取的文件大小显示
                            tvStatus.setText(String.format(
                                    "分割完成！\n" +
                                            "第一部分: %s\n" +
                                            "第二部分: %s\n" +
                                            "已保存到相册",
                                    part1SizeStr, part2SizeStr));
                            progressBar.setProgress(100);

                            if (saved1 && saved2) {
                                showToast("分割完成！已保存到相册");
                            } else {
                                showToast("分割完成，部分文件保存失败");
                            }

                            // 使用FileUtils清理缓存
                            FileUtils.cleanupVideoEditorCache(MainActivity.this);
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

    private void mergeVideos() {
        if (selectedVideoPath == null || selectedSecondVideoPath == null) {
            showToast("请选择两个视频");
            return;
        }

        if (!VideoUtils.isValidVideoFile(selectedVideoPath) || !VideoUtils.isValidVideoFile(selectedSecondVideoPath)) {
            showToast("视频文件无效");
            return;
        }

        // 使用FileUtils创建输出路径
        String outputPath = FileUtils.createPrivateOutputPath(this, selectedVideoPath, Constants.SUFFIX_MERGED);

        if (outputPath == null) {
            showToast("无法创建输出文件");
            return;
        }

        // 确保输出文件不存在
        File outputFile = new File(outputPath);
        FileUtils.deleteIfExists(outputPath);

        // 用于lambda表达式的final数组，用于存储文件大小
        final long[] fileSizeHolder = new long[1];

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

            // 在子线程中获取文件信息
            long fileSize = outputFile.exists() ? outputFile.length() : 0;
            fileSizeHolder[0] = fileSize;

            runOnUiThread(() -> {
                showProgress(false);

                // 重新检查文件
                boolean fileExists = outputFile.exists();
                long finalFileSize = fileSizeHolder[0];

                if (success && fileExists && finalFileSize > 0) {
                    // 使用FileUtils生成文件名
                    String fileName = FileUtils.generateTimestampFilename(
                            new File(selectedVideoPath).getName(),
                            Constants.SUFFIX_MERGED);

                    // 调用已有的保存方法
                    boolean saved = saveToPublicAndScan(outputFile, fileName);

                    if (saved) {
                        // 删除临时文件
                        boolean deleted = FileUtils.safeDeleteFile(outputFile);
                        if (deleted) {
                            Log.d(TAG, "成功删除合并临时文件: " + outputPath);
                        } else {
                            Log.w(TAG, "删除合并临时文件失败: " + outputPath);
                        }

                        // 使用FileUtils获取文件大小
                        String sizeStr = FileUtils.getFileSizeDescription(outputPath);
                        tvStatus.setText(String.format(
                                "合并完成！\n" +
                                        "大小: %s\n" +
                                        "已保存到相册",
                                sizeStr));
                        progressBar.setProgress(100);
                        showToast("合并完成！已保存到相册");

                        // 调用统一的缓存清理
                        FileUtils.cleanupVideoEditorCache(this);
                    } else {
                        // 如果保存失败，显示应用目录位置
                        String sizeStr = FileUtils.getFileSizeDescription(outputPath);
                        tvStatus.setText(String.format(
                                "合并完成！\n" +
                                        "大小: %s\n" +
                                        "文件保存在应用目录",
                                sizeStr));
                        showToast("合并完成（保存在应用目录）");
                    }
                } else if (success && fileExists) {
                    // 文件大小为0的情况
                    FileUtils.safeDeleteFile(outputFile);
                    tvStatus.setText("合并失败：输出文件大小为0");
                    showToast("合并失败：输出文件大小为0");
                } else {
                    tvStatus.setText("合并失败");
                    showToast("合并失败");

                    // 清理可能创建的无效文件
                    FileUtils.safeDeleteFile(outputFile);
                }

                // 无论成功失败，都清理缓存
                FileUtils.cleanupVideoEditorCache(this);
            });
        }).start();
    }

    private void adjustSpeed(boolean speedUp) {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        if (!VideoUtils.isValidVideoFile(selectedVideoPath)) {
            showToast("视频文件无效");
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

        // 使用FileUtils创建输出路径
        String suffix = speedUp ? Constants.SUFFIX_SPEEDUP : Constants.SUFFIX_SLOWDOWN;
        String outputPath = FileUtils.createPrivateOutputPath(this, selectedVideoPath, suffix);

        if (outputPath == null) {
            showToast("无法创建输出文件");
            return;
        }

        showProgress(true);
        tvStatus.setText("正在调节速度...");

        new Thread(() -> {
            boolean success = VideoEditor.adjustSpeed(selectedVideoPath, outputPath, finalSpeedFactor);

            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    File outputFile = new File(outputPath);

                    // 使用FileUtils生成文件名
                    String fileName = FileUtils.generateTimestampFilename(
                            new File(selectedVideoPath).getName(),
                            speedUp ? Constants.SUFFIX_SPEEDUP : Constants.SUFFIX_SLOWDOWN);

                    // 使用FileUtils保存到公共目录
                    String savedPath = FileUtils.saveToPublicDirectory(MainActivity.this, outputFile, fileName);

                    if (savedPath != null) {
                        tvStatus.setText("速度调节成功: " + new File(savedPath).getName());
                        showToast("速度调节成功，已保存到相册");
                        VideoUtils.scanMediaFile(MainActivity.this, savedPath);
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
            values.put(MediaStore.Video.Media.MIME_TYPE, Constants.MIME_TYPE_MP4);
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + Constants.DIR_VIDEO_EDITOR);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            targetFile = new File("/storage/emulated/0/" +
                    Environment.DIRECTORY_MOVIES + "/" + Constants.DIR_VIDEO_EDITOR + "/" + fileName);
            pfd.close();
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File dir = new File(movies, Constants.DIR_VIDEO_EDITOR);
            if (!FileUtils.ensureDirectoryExists(dir)) {
                throw new IOException("无法创建目录: " + dir.getAbsolutePath());
            }
            targetFile = new File(dir, fileName);
            targetFile.createNewFile();
        }
        return targetFile.getAbsolutePath();
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
                values.put(MediaStore.Video.Media.MIME_TYPE, Constants.MIME_TYPE_MP4);
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/" + Constants.DIR_VIDEO_EDITOR);

                Uri uri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buffer = new byte[Constants.BUFFER_SIZE_8KB];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                            Log.d(TAG, "文件已保存到MediaStore: " + uri);

                            // 触发媒体扫描
                            VideoUtils.scanMediaFile(this, uri.toString());
                            return true;
                        }
                    }
                }
            } else {
                // Android 9及以下
                File moviesDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES);
                File videoEditorDir = new File(moviesDir, Constants.DIR_VIDEO_EDITOR);
                if (!FileUtils.ensureDirectoryExists(videoEditorDir)) {
                    return false;
                }

                File destFile = new File(videoEditorDir, fileName);
                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[Constants.BUFFER_SIZE_8KB];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }

                // 扫描到媒体库
                MediaScannerConnection.scanFile(this,
                        new String[]{destFile.getAbsolutePath()},
                        new String[]{Constants.MIME_TYPE_MP4}, null);

                Log.d(TAG, "文件已保存到公共目录: " + destFile.getAbsolutePath());
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "保存到公共目录失败", e);
            return false;
        }
        return false;
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

