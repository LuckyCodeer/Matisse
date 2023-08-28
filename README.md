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
    implementation 'com.github.LuckyCodeer:Matisse:0.5.5'
}
```

**修复以下问题：**
* 相册切换页面空白的问题