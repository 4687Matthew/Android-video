package org.video;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
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

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SELECT_VIDEO = 1001;
    private static final int REQUEST_CODE_SELECT_SECOND_VIDEO = 1002;
    private static final int REQUEST_PERMISSION_CODE = 1000;

    private Button btnSelectVideo;
    private Button btnGetInfo;
    private Button btnCompress;
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

        btnCrop.setOnClickListener(v -> cropVideo());
        btnSplit.setOnClickListener(v -> splitVideo());
        btnMerge.setOnClickListener(v -> mergeVideos());
        btnSpeedUp.setOnClickListener(v -> adjustSpeed(true));
        btnSpeedDown.setOnClickListener(v -> adjustSpeed(false));
    }

    private void compressHevc() {
        if (selectedVideoPath == null) { showToast("请先选择视频"); return; }

        // 检查文件是否存在
        File inputFile = new File(selectedVideoPath);
        if (!inputFile.exists()) {
            showToast("输入文件不存在");
            return;
        }

        String fileName = "hevc_" + System.currentTimeMillis() + ".mp4";
        String outPath;
        try {
            outPath = createPublicVideoFile(fileName);

            // 检查输出目录是否可写
            File outFile = new File(outPath);
            File parentDir = outFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        } catch (IOException e) {
            showToast("创建输出文件失败: " + e.getMessage());
            return;
        }

        showProgress(true);
        tvStatus.setText("正在压缩视频...");

        new Thread(() -> {
            boolean ok = VideoEditor.compressToHevc(selectedVideoPath, outPath, 0.45);
            runOnUiThread(() -> {
                showProgress(false);
                if (ok) {
                    // 检查输出文件是否存在且有内容
                    File outputFile = new File(outPath);
                    if (outputFile.exists() && outputFile.length() > 0) {
                        scanMediaFile(outPath);
                        tvStatus.setText("压缩完成，文件大小: " +
                                (outputFile.length() / 1024 / 1024) + "MB");
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
        new Thread(() -> {
            String info = VideoEditor.getVideoInfo(selectedVideoPath);
            runOnUiThread(() -> {
                VideoInfo.setText(info);
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

            String outputPath = getOutputPath("cropped_" + System.currentTimeMillis() + ".mp4");
            showProgress(true);
            tvStatus.setText("正在裁剪视频...");

            new Thread(() -> {
                boolean success = VideoEditor.cropVideo(selectedVideoPath, outputPath, 
                        (long)(startTime * 1000000), (long)(endTime * 1000000));
                
                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        String savedPath = saveToPublicDirectory(outputPath, "cropped_" + System.currentTimeMillis() + ".mp4");
                        if (savedPath != null) {
                            tvStatus.setText("裁剪成功: " + new File(savedPath).getName());
                            showToast("裁剪成功，已保存到相册");
                            // 通知媒体库更新
                            scanMediaFile(savedPath);
                        } else {
                            tvStatus.setText("裁剪成功: " + new File(outputPath).getName());
                            showToast("裁剪成功（保存在应用目录）");
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
            String outputPath1 = getOutputPath("split1_" + System.currentTimeMillis() + ".mp4");
            String outputPath2 = getOutputPath("split2_" + System.currentTimeMillis() + ".mp4");
            
            showProgress(true);
            tvStatus.setText("正在分割视频...");

            new Thread(() -> {
                boolean success = VideoEditor.splitVideo(selectedVideoPath, outputPath1, 
                        outputPath2, (long)(splitTime * 1000000));
                
                runOnUiThread(() -> {
                    showProgress(false);
                    if (success) {
                        String savedPath1 = saveToPublicDirectory(outputPath1, "split1_" + System.currentTimeMillis() + ".mp4");
                        String savedPath2 = saveToPublicDirectory(outputPath2, "split2_" + System.currentTimeMillis() + ".mp4");
                        if (savedPath1 != null && savedPath2 != null) {
                            tvStatus.setText("分割成功，已保存到相册");
                            showToast("分割成功，已保存到相册");
                            scanMediaFile(savedPath1);
                            scanMediaFile(savedPath2);
                        } else {
                            tvStatus.setText("分割成功");
                            showToast("分割成功（保存在应用目录）");
                        }
                    } else {
                        tvStatus.setText("分割失败");
                        showToast("分割失败");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showToast("请输入有效的时间数字");
        }
    }

    private void mergeVideos() {
        if (selectedVideoPath == null || selectedSecondVideoPath == null) {
            showToast("请选择两个视频");
            return;
        }

        String outputPath = getOutputPath("merged_" + System.currentTimeMillis() + ".mp4");
        showProgress(true);
        tvStatus.setText("正在合并视频...");

        new Thread(() -> {
            boolean success = VideoEditor.mergeVideos(
                    new String[]{selectedVideoPath, selectedSecondVideoPath}, outputPath);
            
            runOnUiThread(() -> {
                showProgress(false);
                if (success) {
                    String savedPath = saveToPublicDirectory(outputPath, "merged_" + System.currentTimeMillis() + ".mp4");
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
        double defaultSpeedFactor = speedUp ? 2.0 : 0.5; // 默认快放2倍，慢放0.5倍
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

        final double finalSpeedFactor = speedFactor; // 创建final变量供lambda使用
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
        // 先保存到应用私有目录，处理完成后再复制到公共目录
        File outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "edited");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return new File(outputDir, filename).getAbsolutePath();
    }

    /**
     * 将文件保存到公共目录（Movies），这样用户可以在相册中看到
     */
    private String saveToPublicDirectory(String sourcePath, String filename) {
        try {
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                return null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore API
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
                // Android 9 及以下使用传统方式
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

    /**
     * 通知媒体库扫描新文件
     */
    private void scanMediaFile(String filePath) {
        if (filePath.startsWith("content://")) {
            // MediaStore URI，不需要扫描
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
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 一步到位：创建位于 Movies/VideoEditor 的空文件，并返回其 **真实文件路径**。
     * Android 10+ 用 MediaStore；Android 9- 用传统路径。
     * 返回的 File 已 createNewFile()，可直接作为 ffmpeg/MediaMuxer 输出。
     */
    private String createPublicVideoFile(String fileName) throws IOException {
        File targetFile;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 方案：MediaStore 返回真实路径
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor");
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            // 通过 openFileDescriptor 拿到可写 fd，再转 File 路径
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            targetFile = new File("/storage/emulated/0/" +
                    Environment.DIRECTORY_MOVIES + "/VideoEditor/" + fileName);
            pfd.close();          // 先关闭，后面 MediaMuxer/FFmpeg 会重新打开
        } else {
            // Android 9- 传统路径
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

