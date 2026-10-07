# 交付规范

- 每次完成源码修改，必须重新生成可安装 APK 和内置同一 APK 的模块 ZIP，无需再次询问用户；不能以仅编译成功或旧产物代替交付。
- 使用本工作副本的 `module.prop` 统一 App / 模块版本。APK 构建完成后再打包模块，并核对包名、版本、签名有效性、内置 APK SHA-256、模块清单和 ZIP 完整性。
- 交付文件放入 `dist/<北京时间时间戳>-<用途>/`，保留旧包。最终回复必须给出独立 APK 和模块 ZIP 的绝对路径链接。
- 当前日常交付使用 `:app:assembleDebug` 及 `scripts/build_debug_fast.sh`。设置 `LUOSHU_BUILD_OUT` 到新的交付目录，并传入本次生成的 APK、真实包名和版本；确保 PATH 中有 Python 3。Debug 构建不等于正式发布。
- 每次成功生成新的 Debug 交付包，都必须比上一个包递增一个修订号；即使不发布，也不能复用旧版本。递增 `module.prop` 中的 `version` 和 `versionCode`，版本仍以 `module.prop` 为唯一来源。本次正式版为 v1.2.20 / 12020；发布后下一份 Debug 包从 v1.2.21 / 12021 起。
- 未配置正式签名时交付 Debug 包；不创建或替换正式密钥。未完成构建时明确报告失败，不宣称生成完成。
- 构建和打包完整性检查不代表完整测试或实机验收。没有用户要求时不新增或运行测试，交付时明确验证范围。
- 构建授权不包含上传、发布、安装 APK 或刷入模块。

## 本机 Android 构建环境

- Android SDK 固定使用 `F:\Program Files\AndroidSDK`；运行 Gradle 前将 `ANDROID_HOME` 和 `ANDROID_SDK_ROOT` 都设为该目录。该 SDK 包含本项目使用的 Android API 37 平台与 Build Tools。
- 当前机器的 Java 17 使用 `E:\Android\openjdk\jdk-17.0.8.101-hotspot`。Gradle 构建时将其设为 `JAVA_HOME`，并把 `%JAVA_HOME%\bin` 放在 PATH 前面。
- 若通过 `android-app/local.properties` 配置 SDK，使用 `sdk.dir=F\:\\Program Files\\AndroidSDK`（Windows properties 转义格式）。
- 不要改用其他只安装了较低 Android 平台版本的 SDK；更换机器时再按该机器实际安装的 SDK 更新此节。
