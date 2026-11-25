**修改自** [https://github.com/zhihu/Matisse](https://github.com/zhihu/Matisse)

**引入：**
```groovy
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

```groovy
dependencies {
    implementation 'com.github.LuckyCodeer:Matisse:0.6.0'
}
```

**修复以下问题：**
* 相册切换页面空白的问题

**新功能：**

### 1. 相册「拍一张」：内置相机 / 系统相机切换

相册中的「拍一张」默认使用**内置相机**（全屏预览、点击对焦、前后摄像头切换、拍照后二次确认，相比系统相机启动更快、内存占用更低）。

如需改回调用系统相机，在开启拍照的基础上追加 `captureMode(SelectionCreator.CAPTURE_MODE_SYSTEM)`：

```java
Matisse.from(this)
        .choose(MimeType.ofAll())
        .capture(true)                                        // 开启「拍一张」入口
        .captureStrategy(
                new CaptureStrategy(true, "com.example.app.fileprovider", "Matisse"))
        .captureMode(SelectionCreator.CAPTURE_MODE_SYSTEM)     // 使用系统相机
        .forResult(REQUEST_CODE_CHOOSE);
```

* `SelectionCreator.CAPTURE_MODE_CUSTOM`：内置相机（**默认值**，无需配置）
* `SelectionCreator.CAPTURE_MODE_SYSTEM`：调用系统相机应用
* 两种模式的结果完全一致，均通过 `Matisse.obtainResult(data)` / `Matisse.obtainPathResult(data)` 获取
* API 21 以下设备自动回退到系统相机

### 2. 单独调起自定义拍摄界面

不需要进入相册，可直接拉起内置相机界面，并在 `onActivityResult` 中获取照片路径 / Uri（Activity 与 Fragment 均支持）：

```java
CameraCapture.from(this)
        .directory("MyApp")                          // 可选：保存到 Pictures/MyApp，默认 Pictures/Matisse
        // .outputPath("/sdcard/your/dir/photo.jpg")   // 可选：完全自定义输出文件路径（优先于 directory）
        .uriAuthority("com.example.app.fileprovider") // 可选：提供自身 FileProvider 的 authority 以同时获取 content Uri
        .forResult(REQUEST_CODE_CAPTURE);
```

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK) {
        // 拍摄图片的本地绝对路径，始终可用
        String path = CameraCapture.obtainPath(data);
        // 拍摄图片的 content Uri：需在调用时传入 uriAuthority 且 FileProvider 的 file_paths 覆盖该路径，否则为 null
        Uri uri = CameraCapture.obtainUri(data);
    }
}
```

使用 `uriAuthority` 时需确保调用方 FileProvider 的 `file_paths` 覆盖照片所在目录，例如：

```xml
<paths>
    <external-path name="my_images" path="Pictures" />
</paths>
```

说明：

* 用户点击确认后照片才会保存（默认存入系统相册可见的 `Pictures/<目录>` 并自动入库），取消则不产生任何文件
* 照片固定为竖版 16:9，前置摄像头拍摄自动做水平镜像，与预览所见一致
* 内置相机需要 `CAMERA` 权限（库已自动声明并在首次进入时申请）
