# 字域 Android App

字域原生管理端（Jetpack Compose），通过 Root 桥接调用已安装模块的 `/data/adb/modules/ziyu/common/app_bridge.sh` 执行字体处理、挂载与恢复。

## 主要模块

- **首页**：模块与连接状态、当前字体、挂载方式选择与详情、DPI 调整、快捷重启/软重启入口。
- **字体库与组合**：TTF/OTF/TTC/可变字体/ZIP/MTZ 导入、酷安基准字库预览、中文/英文/数字复合方案、TTF/归档导出。
- **设置**：Emoji 独立管理、三套原生风格（Material / Miuix / COUI）、主题商店与动态壁纸、桌面/底栏图标自定义、开机/OTA 保护、Google 字体兼容、检查应用字体、模块更新、备份与诊断导出。
- **LSPosed 入口**：可选 Xposed API 82 钩子，用于目标应用系统 WebView 网页字体替换。

## 本地构建

环境要求：JDK 17、Android SDK API 37、Gradle 9.5。版本名与 `versionCode` 自动读取根目录 `module.prop`。

```sh
# 正式构建（需通过 ZIYU_KEYSTORE_* 环境变量提供正式签名）
gradle -p android-app --no-daemon :app:assembleRelease

# 调试构建（提供正式签名环境变量时复用正式签名）
gradle -p android-app --no-daemon :app:assembleDebug
```

输出路径：
- Release：`android-app/app/build/outputs/apk/release/app-release.apk`
- Debug：`android-app/app/build/outputs/apk/debug/app-debug.apk`
