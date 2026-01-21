package org.video;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;
import static org.video.utils.Constants.REQUEST_CODE_SELECT_SECOND_VIDEO;
import static org.video.utils.Constants.REQUEST_CODE_SELECT_VIDEO;
import static org.video.utils.Constants.REQUEST_PERMISSION_CODE;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.video.utils.Constants;
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

    private String selectedVideoPath;
    private String selectedSecondVideoPath;

    // 封面相关控件
    private Button btnExtractCover;
    private Button btnSelectCover;
    private Button btnSetCover;
    private Button btnSaveCover;
    private EditText etCoverTime;
    private ImageView ivCoverPreview;
    private TextView tvCoverInfo;

    // 封面相关变量
    private Bitmap currentCover = null;
    private String selectedCoverImagePath = null;
    private static final int REQUEST_CODE_SELECT_COVER_IMAGE = 1003;

    // 进度弹窗相关变量
    private Dialog progressDialog;
    private ProgressBar dialogProgressBar;
    private TextView dialogStatusText;
    private TextView dialogPercentageText;
    private TextView dialogTitleText;
    private Button dialogCancelButton;
    private Handler mainHandler;

    // 用于取消操作的标志
    private volatile boolean isOperationCancelled = false;

    // 进度回调接口
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

        // 初始化主线程Handler
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (requestCode == REQUEST_CODE_SELECT_COVER_IMAGE) {
                    // 处理选择的封面图片
                    handleSelectedCoverImage(uri);
                } else if (requestCode == REQUEST_CODE_SELECT_VIDEO) {
                    // 原有视频选择逻辑
                    String path = VideoUtils.getPathFromUri(this, uri);
                    selectedVideoPath = path;
                    tvVideoPath.setText("视频: " + (path != null ? new File(path).getName() : "未知"));
                } else if (requestCode == REQUEST_CODE_SELECT_SECOND_VIDEO) {
                    // 原有第二视频选择逻辑
                    String path = VideoUtils.getPathFromUri(this, uri);
                    selectedSecondVideoPath = path;
                    tvSecondVideoPath.setText("视频2: " + (path != null ? new File(path).getName() : "未知"));
                }
            }
        }
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

        // 封面相关控件
        btnExtractCover = findViewById(R.id.btnExtractCover);
        btnSelectCover = findViewById(R.id.btnSelectCover);
        btnSetCover = findViewById(R.id.btnSetCover);
        btnSaveCover = findViewById(R.id.btnSaveCover);
        etCoverTime = findViewById(R.id.etCoverTime);
        ivCoverPreview = findViewById(R.id.ivCoverPreview);
        tvCoverInfo = findViewById(R.id.tvCoverInfo);

        // 设置默认时间
        etCoverTime.setText("1.0");
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

        // 封面相关监听器
        btnExtractCover.setOnClickListener(v -> extractCoverFromVideo());
        btnSelectCover.setOnClickListener(v -> selectCoverImage());
        btnSetCover.setOnClickListener(v -> setCoverToVideo());
        btnSaveCover.setOnClickListener(v -> saveCoverToGallery());

        // 封面预览点击事件
        ivCoverPreview.setOnClickListener(v -> {
            if (currentCover != null) {
                showCoverPreviewDialog();
            }
        });
    }

    /**
     * 显示进度弹窗
     */
    private void showProgressDialog(String title, boolean showCancelButton) {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            // 重置取消标志
            isOperationCancelled = false;

            progressDialog = new Dialog(this);
            progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            progressDialog.setContentView(R.layout.dialog_progress);
            progressDialog.setCancelable(false);

            // 设置窗口属性
            if (progressDialog.getWindow() != null) {
                Window window = progressDialog.getWindow();

                // 设置透明背景
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));

                // 设置弹窗宽度固定
                WindowManager.LayoutParams layoutParams = window.getAttributes();
                layoutParams.width = dpToPx(300); // 设置固定宽度为300dp
                layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(layoutParams);

                // 设置弹窗位置居中
                window.setGravity(Gravity.CENTER);

                // 设置弹窗动画（可选）
                window.setWindowAnimations(R.style.DialogAnimation);
            }

            // 初始化弹窗控件
            dialogTitleText = progressDialog.findViewById(R.id.tvDialogTitle);
            dialogProgressBar = progressDialog.findViewById(R.id.pbDialogProgress);
            dialogStatusText = progressDialog.findViewById(R.id.tvDialogStatus);
            dialogPercentageText = progressDialog.findViewById(R.id.tvDialogPercentage);
            dialogCancelButton = progressDialog.findViewById(R.id.btnDialogCancel);

            // 设置标题
            dialogTitleText.setText(title);

            // 设置取消按钮
            if (showCancelButton) {
                dialogCancelButton.setVisibility(View.VISIBLE);
                dialogCancelButton.setOnClickListener(v -> {
                    isOperationCancelled = true;
                    dialogStatusText.setText("正在取消操作...");
                    dialogCancelButton.setEnabled(false);
                });
            } else {
                dialogCancelButton.setVisibility(View.GONE);
            }

            // 显示弹窗
            progressDialog.show();
        });
    }

    /**
     * dp转px工具方法
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 更新进度弹窗
     */
    private void updateProgressDialog(String status, int progress) {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    if (status != null && !status.isEmpty()) {
                        dialogStatusText.setText(status);
                    }

                    if (progress >= 0) {
                        dialogProgressBar.setProgress(progress);
                        dialogPercentageText.setText(progress + "%");
                    }

                    // 检查是否需要显示取消按钮
                    if (isOperationCancelled && dialogCancelButton.getVisibility() == View.VISIBLE) {
                        dialogCancelButton.setEnabled(false);
                    }
                }
            });
        }
    }

    /**
     * 隐藏进度弹窗
     */
    private void hideProgressDialog() {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                    progressDialog = null;
                }
            });
        }
    }

    /**
     * 创建弹窗式进度回调
     */
    private ProgressCallback createDialogProgressCallback(String operationTitle) {
        return new ProgressCallback() {
            @Override
            public void onProgressUpdate(String status, int progress) {
                updateProgressDialog(status, progress);
            }
        };
    }

    /**
     * 处理操作完成
     */
    private void handleOperationComplete(boolean success, String message) {
        runOnUiThread(() -> {
            if (success) {
                // 更新弹窗显示完成信息
                updateProgressDialogForCompletion(message, success);

                // 延迟5秒后自动关闭弹窗
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    hideProgressDialog();
                    showToast("操作完成"); // 可选：显示简短Toast提示
                }, 5000);
            } else {
                if (isOperationCancelled) {
                    // 操作被取消，直接隐藏弹窗
                    hideProgressDialog();
                    showToast("操作已取消");
                } else {
                    // 显示错误信息弹窗
                    updateProgressDialogForCompletion("操作失败: " + message, false);

//                    // 延迟5秒后自动关闭弹窗
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        hideProgressDialog();
                    }, 5000);
                }
            }
        });
    }

    /**
     * 更新弹窗显示完成信息
     */
    private void updateProgressDialogForCompletion(String message, boolean isSuccess) {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    // 更新标题
                    dialogTitleText.setText(isSuccess ? "操作完成" : "操作失败");

                    // 更新状态文本，显示完成信息
                    dialogStatusText.setText(message);
                    dialogStatusText.setMaxLines(10); // 允许显示多行
                    dialogStatusText.setTextSize(14); // 稍微调大字体

                    // 隐藏进度条（如果有的话）
                    dialogProgressBar.setVisibility(View.GONE);

                    // 隐藏百分比文本
                    dialogPercentageText.setVisibility(View.GONE);

                    // 更新取消按钮为"关闭"
                    if (dialogCancelButton != null) {
                        dialogCancelButton.setText("关闭");
                        dialogCancelButton.setVisibility(View.VISIBLE);
                        dialogCancelButton.setEnabled(true);
                        dialogCancelButton.setOnClickListener(v -> hideProgressDialog());
                    }
                }
            });
        }
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

        // 创建输出路径
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

        // 显示进度弹窗
        showProgressDialog("串行压缩视频", true);
        updateProgressDialog("正在准备...", 0);

        // 创建进度回调
        ProgressCallback progressCallback = createDialogProgressCallback("串行压缩视频");

        // 在新线程中执行压缩
        new Thread(() -> {
            // 检查是否被取消
            if (isOperationCancelled) {
                handleOperationComplete(false, "操作已取消");
                return;
            }

            // 直接调用单线程转码方法
            boolean ok = VideoEditor.compressToHevcSingleThread(
                    selectedVideoPath, outPath, Constants.DEFAULT_COMPRESSION_RATIO,
                    progressCallback, 0);

            // 处理完成
            runOnUiThread(() -> {
                if (ok && !isOperationCancelled) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // 使用VideoUtils扫描媒体文件
                        VideoUtils.scanMediaFile(this, outPath);
                        long originalSize = inputFile.length();
                        long compressedSize = outputFile.length();

                        String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                        String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                        String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                        handleOperationComplete(true,
                                String.format("压缩完成！\n原始: %s\n压缩后: %s\n压缩比: %s",
                                        originalSizeStr, compressedSizeStr, ratioStr));
                    } else {
                        handleOperationComplete(false, "压缩失败：输出文件为空");
                    }
                } else if (!isOperationCancelled) {
                    handleOperationComplete(false, "串行压缩失败");
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

        // 显示进度弹窗
        showProgressDialog("智能压缩视频", true);
        updateProgressDialog("开始压缩视频...", 0);

        // 创建进度回调
        ProgressCallback progressCallback = createDialogProgressCallback("智能压缩视频");

        // 在新线程中执行智能压缩
        new Thread(() -> {
            // 检查是否被取消
            if (isOperationCancelled) {
                handleOperationComplete(false, "操作已取消");
                return;
            }

            boolean success = VideoEditor.compressVideo(
                    selectedVideoPath, outPath, Constants.DEFAULT_COMPRESSION_RATIO, progressCallback);

            runOnUiThread(() -> {
                if (success && !isOperationCancelled) {
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // 将文件从私有目录移动到公共目录
                        String publicPath = FileUtils.saveToPublicDirectory(this, outputFile, fileName);

                        if (publicPath != null) {
                            VideoUtils.scanMediaFile(this, publicPath);
                            long originalSize = inputFile.length();
                            long compressedSize = outputFile.length();
                            String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                            String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                            String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                            handleOperationComplete(true, String.format(
                                    "压缩完成！\n原始: %s\n压缩后: %s\n压缩比: %s\n已保存到相册",
                                    originalSizeStr, compressedSizeStr, ratioStr));

                            // 删除私有目录的临时文件
                            FileUtils.safeDeleteFile(outputFile);
                        } else {
                            long originalSize = inputFile.length();
                            long compressedSize = outputFile.length();
                            String ratioStr = TimeUtils.calculateCompressionRatio(originalSize, compressedSize);
                            String originalSizeStr = FileUtils.getFileSizeDescription(selectedVideoPath);
                            String compressedSizeStr = FileUtils.getFileSizeDescription(outPath);

                            handleOperationComplete(true, String.format(
                                    "压缩完成！\n原始: %s\n压缩后: %s\n压缩比: %s\n文件保存在应用目录",
                                    originalSizeStr, compressedSizeStr, ratioStr));
                        }
                    } else {
                        handleOperationComplete(false, "压缩失败：输出文件为空");
                    }
                } else if (!isOperationCancelled) {
                    handleOperationComplete(false, "压缩失败");
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
                    showToast("视频信息获取完成");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    VideoInfo.setText("获取信息失败: " + e.getMessage());
                    showToast("视频信息获取失败");
                });
            }
        }).start();
    }

    private void selectVideo(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(Constants.MIME_TYPE_VIDEO);
        startActivityForResult(Intent.createChooser(intent, "选择视频"), requestCode);
    }

    /**
     * 从视频中提取封面
     */
    private void extractCoverFromVideo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        if (!VideoUtils.isValidVideoFile(selectedVideoPath)) {
            showToast("视频文件无效");
            return;
        }

        String timeStr = etCoverTime.getText().toString();
        double time = Constants.DEFAULT_COVER_TIME_MS / 1000.0;

        if (!timeStr.isEmpty()) {
            try {
                time = Double.parseDouble(timeStr);
                if (time < 0) {
                    showToast("时间不能为负数");
                    return;
                }
            } catch (NumberFormatException e) {
                showToast("请输入有效的时间数字");
                return;
            }
        }

        final double finalTime = time;

        // 创建进度回调
        ProgressCallback progressCallback = createDialogProgressCallback("提取封面");

        new Thread(() -> {
            long timeMs = (long) (finalTime * 1000);

            // 提取封面
            Bitmap cover = VideoEditor.extractVideoCover(
                    selectedVideoPath, timeMs, progressCallback);

            runOnUiThread(() -> {

                if (cover != null && !isOperationCancelled) {
                    currentCover = cover;

                    // 生成高质量预览图（根据容器大小）
                    FrameLayout container = findViewById(R.id.coverPreviewContainer);
                    int containerWidth = container.getWidth();
                    int containerHeight = container.getHeight();

                    // 如果容器还没有测量，使用默认大小
                    if (containerWidth <= 0 || containerHeight <= 0) {
                        containerWidth = dpToPx(300);  // 大约300dp
                        containerHeight = dpToPx(200); // 大约200dp
                    }

                    // 生成适合容器大小的预览图，保持原图质量
                    Bitmap preview = generateQualityPreview(cover, containerWidth, containerHeight);

                    // 显示预览
                    ivCoverPreview.setImageBitmap(preview);
                    ivCoverPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);

                    // 显示信息
                    tvCoverInfo.setText(String.format("封面尺寸: %dx%d (原始: %dx%d)",
                            preview.getWidth(), preview.getHeight(),
                            cover.getWidth(), cover.getHeight()));

                    showToast("封面提取成功");
                } else if (!isOperationCancelled) {
                    showToast("封面提取失败");
                }
            });
        }).start();
    }

    /**
     * 生成高质量预览图
     */
    private Bitmap generateQualityPreview(Bitmap original, int targetWidth, int targetHeight) {
        // 计算保持宽高比的缩放尺寸
        float widthRatio = (float) targetWidth / original.getWidth();
        float heightRatio = (float) targetHeight / original.getHeight();
        float ratio = Math.max(widthRatio, heightRatio);

        int newWidth = Math.round(original.getWidth() * ratio);
        int newHeight = Math.round(original.getHeight() * ratio);

        // 使用高质量缩放
        return Bitmap.createScaledBitmap(
                original, newWidth, newHeight, true);  // 使用双线性过滤
    }

    /**
     * 选择封面图片
     */
    private void selectCoverImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(Constants.MIME_TYPE_IMAGE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/jpeg", "image/png", "image/jpg"
        });
        startActivityForResult(
                Intent.createChooser(intent, "选择封面图片"),
                REQUEST_CODE_SELECT_COVER_IMAGE
        );
    }

    /**
     * 设置封面到视频
     */
    private void setCoverToVideo() {
        if (selectedVideoPath == null) {
            showToast("请先选择视频");
            return;
        }

        if (currentCover == null) {
            showToast("请先提取或选择封面");
            return;
        }

        // 创建输出文件路径
        String baseName = new File(selectedVideoPath).getName();
        String outputPath = FileUtils.createPrivateOutputPath(
                this, selectedVideoPath, Constants.SUFFIX_WITH_COVER);

        if (outputPath == null) {
            showToast("无法创建输出文件");
            return;
        }

        // 显示进度弹窗
        showProgressDialog("设置封面", true);
        updateProgressDialog("正在设置封面...", 0);

        // 创建进度回调
        ProgressCallback progressCallback = createDialogProgressCallback("设置封面");

        new Thread(() -> {
            // 调用封面设置功能
            boolean success = VideoEditor.setVideoCover(
                    selectedVideoPath, outputPath, currentCover, progressCallback);

            runOnUiThread(() -> {
                hideProgressDialog();
                if (success) {
                    // 保存到公共目录
                    String fileName = FileUtils.generateTimestampFilename(
                            new File(selectedVideoPath).getName(),
                            Constants.SUFFIX_WITH_COVER);

                    boolean saved = saveToPublicAndScan(new File(outputPath), fileName);

                    if (saved) {
                        showToast("封面设置完成！已保存到相册");
                    } else {
                        showToast("封面设置完成（保存在应用目录）");
                    }
                } else {
                    // 如果设置封面失败，可以保存封面图片
                    saveCoverToGallery();
                    showToast("封面已保存为图片");
                }
            });
        }).start();
    }

    /**
     * 保存封面到相册
     */
    private void saveCoverToGallery() {
        if (currentCover == null) {
            showToast("没有可保存的封面");
            return;
        }

        showProgressDialog("保存封面", false);
        updateProgressDialog("正在保存封面到相册...", 0);

        new Thread(() -> {
            String baseName = selectedVideoPath != null ?
                    new File(selectedVideoPath).getName() : "video_cover";
            String title = baseName.replaceFirst("[.][^.]+$", "") + "_cover";

            boolean success;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                success = VideoUtils.saveCoverToGallery(
                        MainActivity.this, currentCover, title);
            } else {
                success = false;
            }

            runOnUiThread(() -> {
                hideProgressDialog();
                if (success) {
                    showToast("封面已保存到相册");
                } else {
                    showToast("保存封面失败");
                }
            });
        }).start();
    }

    /**
     * 显示封面预览对话框
     */
    private void showCoverPreviewDialog() {
        if (currentCover == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("封面预览");

        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(currentCover);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // 设置最大尺寸
        int maxSize = Math.min(
                getResources().getDisplayMetrics().widthPixels - 100,
                getResources().getDisplayMetrics().heightPixels - 200
        );

        imageView.setLayoutParams(new ViewGroup.LayoutParams(maxSize, maxSize));

        builder.setView(imageView);
        builder.setPositiveButton("确定", null);
        builder.setNegativeButton("保存", (dialog, which) -> saveCoverToGallery());

        builder.show();
    }

    /**
     * 显示封面预览（正确方法：不强制缩放位图）
     */
    private void displayCoverPreview(Bitmap cover) {
        if (cover == null) return;

        runOnUiThread(() -> {
            int originalWidth = cover.getWidth();
            int originalHeight = cover.getHeight();

            // 获取屏幕尺寸
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            int screenWidth = displayMetrics.widthPixels;
            int screenHeight = displayMetrics.heightPixels;

            // 计算最大显示尺寸（留出边距）
            int maxDisplayWidth = screenWidth - dpToPx(32);
            int maxDisplayHeight = (int) (screenHeight * 0.6); // 使用屏幕高度的60%

            // 检查是否需要缩放
            if (originalWidth <= maxDisplayWidth && originalHeight <= maxDisplayHeight) {
                // 原始图片在允许范围内，直接显示
                ivCoverPreview.setScaleType(ImageView.ScaleType.CENTER);
                ivCoverPreview.setImageBitmap(cover);

                // 调整容器尺寸为图片原始尺寸
                adjustContainerSize(originalWidth, originalHeight);

                tvCoverInfo.setText(String.format("封面尺寸: %dx%d (原始尺寸，无需缩放)",
                        originalWidth, originalHeight));
            } else {
                // 需要缩放，计算保持宽高比的最大尺寸
                float widthRatio = (float) maxDisplayWidth / originalWidth;
                float heightRatio = (float) maxDisplayHeight / originalHeight;
                float scaleRatio = Math.min(widthRatio, heightRatio);

                int scaledWidth = (int) (originalWidth * scaleRatio);
                int scaledHeight = (int) (originalHeight * scaleRatio);

                // 创建高质量缩放后的图片
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                        cover, scaledWidth, scaledHeight, true);

                // 设置图片
                ivCoverPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ivCoverPreview.setImageBitmap(scaledBitmap);

                // 调整容器尺寸
                adjustContainerSize(scaledWidth, scaledHeight);

                tvCoverInfo.setText(String.format("封面尺寸: %dx%d (原始: %dx%d, 缩放比例: %.2f)",
                        scaledWidth, scaledHeight, originalWidth, originalHeight, scaleRatio));
            }
        });
    }

    /**
     * 调整容器尺寸
     */
    private void adjustContainerSize(int width, int height) {
        FrameLayout container = findViewById(R.id.coverPreviewContainer);
        if (container == null) return;

        // 设置最小和最大限制
        int minWidth = dpToPx(100);
        int minHeight = dpToPx(150);
        int maxWidth = getResources().getDisplayMetrics().widthPixels;
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.7);

        int finalWidth = Math.max(minWidth, Math.min(width, maxWidth));
        int finalHeight = Math.max(minHeight, Math.min(height, maxHeight));

        ViewGroup.LayoutParams params = container.getLayoutParams();
        params.width = finalWidth;
        params.height = finalHeight;
        container.setLayoutParams(params);
    }

    /**
     * 优化容器高度以适应图片宽高比
     */
    private void optimizeContainerHeight(FrameLayout container, int imgWidth, int imgHeight) {
        if (container == null) return;

        float aspectRatio = (float) imgWidth / imgHeight;

        // 获取当前容器宽度（减去padding）
        int currentWidth = container.getWidth();
        if (currentWidth <= 0) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            currentWidth = displayMetrics.widthPixels - dpToPx(32);
        }

        // 根据图片宽高比计算最佳高度
        int optimalHeight = (int) (currentWidth / aspectRatio);

        // 限制高度范围（150dp-400dp），避免过高或过矮
        int minHeight = dpToPx(200);  // 最小200dp
        int maxHeight = dpToPx(400);  // 最大400dp
        optimalHeight = Math.max(minHeight, Math.min(optimalHeight, maxHeight));

        // 动态调整容器高度
        ViewGroup.LayoutParams params = container.getLayoutParams();
        params.height = optimalHeight;
        container.setLayoutParams(params);
    }

    /**
     * 处理选择的封面图片
     */
    private void handleSelectedCoverImage(Uri uri) {
        // 显示弹窗
        showProgressDialog("加载封面", false);
        updateProgressDialog("正在加载封面图片...", 0);

        new Thread(() -> {
            try {
                // 从Uri加载图片
                Bitmap cover = VideoUtils.loadCoverFromUri(MainActivity.this, uri);

                runOnUiThread(() -> {
                    hideProgressDialog();
                    if (cover != null) {
                        currentCover = cover;
                        displayCoverPreview(cover);

                        tvCoverInfo.setText(String.format("封面尺寸: %dx%d",
                                cover.getWidth(), cover.getHeight()));
                        showToast("封面图片加载成功");
                    } else {
                        showToast("封面图片加载失败");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    showToast("加载封面失败");
                });
            }
        }).start();
    }

    /**
     * 获取最佳封面时间
     */
    private void getBestCoverTime() {
        if (selectedVideoPath == null) {
            return;
        }

        new Thread(() -> {
            long bestTimeMs = VideoUtils.getBestCoverTime(selectedVideoPath);
            double bestTimeSec = bestTimeMs / 1000.0;

            runOnUiThread(() -> {
                etCoverTime.setText(String.format("%.1f", bestTimeSec));
                showToast(String.format("推荐封面时间: %.1f秒", bestTimeSec));
            });
        }).start();
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

            // 显示进度弹窗
            showProgressDialog("裁剪视频", true);
            updateProgressDialog("开始裁剪视频...", 0);

            // 创建进度回调
            ProgressCallback progressCallback = createDialogProgressCallback("裁剪视频");

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
                    hideProgressDialog();
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

                            showToast(String.format(
                                    "裁剪完成！\n" +
                                            "大小: %s\n" +
                                            "时长: %s\n" +
                                            "已保存到相册",
                                    fileSizeStr, durationStr));

                            // 使用FileUtils清理缓存
                            FileUtils.cleanupVideoEditorCache(MainActivity.this);
                        } else {
                            showToast("裁剪失败：输出文件不存在");
                        }
                    } else {
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

            // 显示进度弹窗
            showProgressDialog("分割视频", true);
            updateProgressDialog("开始分割视频...", 0);

            // 创建进度回调
            ProgressCallback progressCallback = createDialogProgressCallback("分割视频");

            // 在新线程中执行分割
            new Thread(() -> {
                // 使用TimeUtils转换时间单位
                long splitTimeUs = TimeUtils.secondsToMicroseconds(splitTime);

                boolean success = VideoEditor.splitVideoOptimized(
                        selectedVideoPath, outputPath1, outputPath2,
                        splitTimeUs, progressCallback);

                runOnUiThread(() -> {
                    hideProgressDialog();
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

                            showToast(String.format(
                                    "分割完成！\n" +
                                            "第一部分: %s\n" +
                                            "第二部分: %s\n" +
                                            "已保存到相册",
                                    part1SizeStr, part2SizeStr));

                            // 使用FileUtils清理缓存
                            FileUtils.cleanupVideoEditorCache(MainActivity.this);
                        } else {
                            showToast("分割失败：输出文件不存在");
                        }
                    } else {
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

        // 显示进度弹窗
        showProgressDialog("合并视频", true);
        updateProgressDialog("开始合并视频...", 0);

        // 创建进度回调
        ProgressCallback progressCallback = createDialogProgressCallback("合并视频");

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
                hideProgressDialog();

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

                        showToast("合并完成！已保存到相册");

                        // 调用统一的缓存清理
                        FileUtils.cleanupVideoEditorCache(this);
                    } else {
                        // 如果保存失败，显示应用目录位置
                        showToast("合并完成（保存在应用目录）");
                    }
                } else if (success && fileExists) {
                    // 文件大小为0的情况
                    FileUtils.safeDeleteFile(outputFile);
                    showToast("合并失败：输出文件大小为0");
                } else {
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

        // 显示进度弹窗
        showProgressDialog("调节速度", true);
        updateProgressDialog("正在调节速度...", 0);

        new Thread(() -> {
            boolean success = VideoEditor.adjustSpeed(selectedVideoPath, outputPath, finalSpeedFactor);

            runOnUiThread(() -> {
                hideProgressDialog();
                if (success) {
                    File outputFile = new File(outputPath);

                    // 使用FileUtils生成文件名
                    String fileName = FileUtils.generateTimestampFilename(
                            new File(selectedVideoPath).getName(),
                            speedUp ? Constants.SUFFIX_SPEEDUP : Constants.SUFFIX_SLOWDOWN);

                    // 使用FileUtils保存到公共目录
                    String savedPath = FileUtils.saveToPublicDirectory(MainActivity.this, outputFile, fileName);

                    if (savedPath != null) {
                        showToast("速度调节成功，已保存到相册");
                        VideoUtils.scanMediaFile(MainActivity.this, savedPath);
                    } else {
                        showToast("速度调节成功（保存在应用目录）");
                    }
                } else {
                    showToast("速度调节失败");
                }
            });
        }).start();
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
}