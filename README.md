# Android 视频编辑器

一个使用Java开发的Android视频编辑应用，实现了基础的视频编辑功能。

## 功能特性

- ✅ **视频裁剪**：选择视频的开始和结束时间进行裁剪
- ✅ **视频分割**：在指定时间点将视频分割成两部分
- ✅ **视频合并**：将多个视频文件合并成一个
- ✅ **速度调节**：支持快放和慢放功能

## 技术栈

- **开发语言**：Java
- **平台**：Android (minSdk 24, targetSdk 34)
- **核心库**：
  - MediaExtractor：提取视频和音频轨道
  - MediaMuxer：合并视频和音频轨道
  - AndroidX AppCompat & Material Design

## 项目结构

```
app/
├── src/main/
│   ├── java/org/video/
│   │   ├── MainActivity.java          # 主Activity，UI界面
│   │   ├── VideoEditor.java           # 视频编辑核心功能
│   │   └── VideoEditorUtils.java      # 工具类
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml      # 主界面布局
│   │   └── values/
│   │       ├── strings.xml            # 字符串资源
│   │       └── colors.xml             # 颜色资源
│   └── AndroidManifest.xml            # 应用清单文件
└── build.gradle.kts                   # 模块构建配置
```

## 使用方法

1. **选择视频**：点击"选择视频"按钮，从设备中选择要编辑的视频文件

2. **裁剪视频**：
   - 输入开始时间（秒）
   - 输入结束时间（秒）
   - 点击"裁剪视频"按钮

3. **分割视频**：
   - 输入分割时间点（秒）
   - 点击"分割视频"按钮
   - 视频将被分割成两部分

4. **合并视频**：
   - 选择第一个视频
   - 选择第二个视频
   - 点击"合并视频"按钮

5. **速度调节**：
   - 输入速度倍数（可选，默认快放2倍，慢放0.5倍）
   - 点击"快放"或"慢放"按钮

## 权限说明

应用需要以下权限：
- `READ_EXTERNAL_STORAGE` (Android 12及以下)
- `READ_MEDIA_VIDEO` (Android 13及以上)
- `WRITE_EXTERNAL_STORAGE` (Android 12及以下，用于保存编辑后的视频)

## 输出位置

编辑后的视频文件保存在：
`/Android/data/org.video/files/Movies/edited/`

## 构建说明

1. 确保已安装 Android SDK
2. 使用 Gradle 构建：
   ```bash
   ./gradlew build
   ```
3. 在 Android Studio 中打开项目并运行

## 注意事项

- 视频编辑是CPU密集型操作，处理大文件可能需要较长时间
- 速度调节功能通过调整时间戳实现，音频可能需要重新编码以获得更好的效果
- 建议在真机上测试，模拟器可能性能不足

## 未来改进

- [ ] 添加视频预览功能
- [ ] 支持更多视频格式
- [ ] 添加视频滤镜效果
- [ ] 优化音频速度调节（使用音频重采样）
- [ ] 添加进度条显示处理进度
- [ ] 支持批量处理

