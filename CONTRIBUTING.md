# 贡献指南

欢迎参与字域开发。管理端为 Jetpack Compose 原生 Android App，模块运行时由 `common/` 与根目录启动脚本组成。

## 开发规范

- 基于 `main` 创建功能或修复分支，每个 PR 聚焦一类改动并注明验证范围。
- 模块 Shell 脚本需兼容 Android `/system/bin/sh` 及设备自带 BusyBox/Toybox。
- 字体负载只写入字域 systemless 目录，遵循用户所选挂载模式，保留源检查、输出校验、PID 1 核验与失败回滚；防裁切须保留可变字体连续字重。
- 新增运行文件时同步更新 `scripts/module_payload_manifest.txt`；引入第三方代码或资源时更新 `THIRD_PARTY_NOTICES.md` 与 `licenses/`。
- 禁止提交商业字体、ROM 提取字体、用户诊断导出、签名密钥、密码或临时脚本。

## 本地构建与检查

环境要求：JDK 17、Android SDK API 37、Gradle 9.5、ARM64 Python 运行时。

```sh
# 构建 App
gradle -p android-app --no-daemon :app:assembleDebug

# 准备运行时与执行源码检查
sh ./scripts/prepare_composite_runtime.sh
sh ./scripts/check.sh

# 打包完整模块 ZIP（将本次 APK 路径传给打包脚本）
ZIYU_APP_APK=android-app/app/build/outputs/apk/debug/app-debug.apk \
  sh ./scripts/build.sh
```

涉及字体或挂载逻辑时，建议同步覆盖：TTF/OTF/TTC/可变字体、复合字体、Emoji 独立管理、四种挂载模式及回滚、软重启/完整重启与恢复卸载。

## 许可证

所有提交均按 **GPL-3.0-only** 许可发布。
