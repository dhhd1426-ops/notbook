# 笔记与打字机

一个安卓应用，底部导航切换两个页面：

- **笔记**：3D 磨砂玻璃书本卷轴，点书展开笔记列表，点笔记进入编辑页。
- **打字机**：复古便携打字机，可以用键盘或屏幕按键打字，支持中英文。

## 下载

最新安装包：<https://github.com/dhhd1426-ops/notbook/releases/latest/download/notbook.apk>

所有版本见 [Releases](https://github.com/dhhd1426-ops/notbook/releases)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/src/main/assets/www/index.html` | 应用外壳和底部导航 |
| `app/src/main/assets/www/notes.html` | 笔记卷轴页面 |
| `app/src/main/assets/www/typewriter.html` | 打字机页面 |
| `app/src/main/java/com/notbook/app/MainActivity.java` | 全屏 WebView，本地加载上面三个页面 |
| `tools/localize_fonts.py` | 打包时把页面用到的字体下载进安装包，启动不再联网等字体 |
| `.github/workflows/build-apk.yml` | 每次推送到 `main` 自动打包 APK 并发布 Release |

笔记保存在手机本地，卸载应用会清空。

## 本地构建

需要 JDK 17、Android SDK 和 Gradle 8.7：

```
gradle :app:assembleDebug
```

生成的安装包在 `app/build/outputs/apk/debug/app-debug.apk`。
