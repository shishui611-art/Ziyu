# 字域 Android App

本目录包含字域的原生 Android 管理端。App 通过 Root 桥接调用已安装模块中的
`/data/adb/modules/LuoShu/common/app_bridge.sh`；字体扫描、导入、编译、挂载、验证和恢复由模块运行时执行。

## 当前功能

- Jetpack Compose 原生界面，不依赖 WebView。
- 字体库、导入与预览、中文/英文/数字选择、复合字体、字重与任务进度。
- 挂载方式选择及重启确认；显示本次启动的 Root、提供者、实现方式、验证与警告。
- 诊断报告导出、字体应用结果及必要时的 KernelSU 软重启入口。
- App 与模块共用 `module.prop` 中的版本号和 versionCode；正式发布只交付内置 App 的模块 ZIP 与校验文件。

App 应与相同版本的字域模块配套使用。没有已安装模块或 Root 授权时，模块桥接功能不可用。

## 本地构建

需要 JDK 17、Android SDK API 37 和 Gradle 9.5：

```sh
gradle --no-daemon :app:assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果提供正式签名环境变量，Debug 构建使用正式包名和证书，可覆盖正式 App 并保留数据；没有签名配置时，Debug 会使用 `.debug` 后缀包名和本地 Debug 签名。Release workflow 负责正式证书、Lint、单元测试、模块 ZIP 和发布门禁。

刷入完整模块包时会尝试安装或更新 App；若安装环境暂不可用，可在重启后点击 Root 管理器中的模块“操作”按钮再次安装。
