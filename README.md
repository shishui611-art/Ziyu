<div align="center">

# 字域（Ziyu）

**Android 全局字体管理与复合引擎**

适用于 **Magisk · KernelSU · SukiSU Ultra · APatch**

[![Release](https://img.shields.io/badge/version-v1.5.92-blue)](https://github.com/shishui611-art/Ziyu/releases)
[![Build](https://github.com/shishui611-art/Ziyu/actions/workflows/build.yml/badge.svg)](https://github.com/shishui611-art/Ziyu/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-orange)](LICENSE)

[下载正式版](https://github.com/shishui611-art/Ziyu/releases/latest) · [使用指南](docs/USER_GUIDE.md) · [挂载与排查](docs/MOUNT_FLOW.md) · [真机验证矩阵](docs/TEST_MATRIX.md) · [酷安 @柔性体](https://www.coolapk.com/u/28601616)

</div>

> 字域由酷安 **[@柔性体](https://www.coolapk.com/u/28601616)** 独立维护，重点适配 ColorOS 与 vivo/iQOO，同时兼容其他主流 Android 系统。历史作者与第三方组件声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 `licenses/`。

## 快速开始

1. 从 [Releases](https://github.com/shishui611-art/Ziyu/releases/latest) 下载 `Ziyu-vX.Y.Z.zip`（已内置正式签名 App）与对应 `.sha256`。
2. 在 Magisk / KernelSU / SukiSU Ultra / APatch 中刷入模块 ZIP 并重启。
3. 若刷入时未自动安装 App，在 Root 管理器点击字域模块的「操作」按钮完成安装，打开后授予 Root 权限。
4. 在首页确认「字体挂载方式」，进入「字体库」导入字体（支持 TTF / OTF / TTC / 可变字体 / ZIP / MTZ），点击「应用」并按提示重启即可生效。

## 核心功能

- **全局与复合字体**：可单选一款字体全局应用，也可在「组合」页分别指定中文、英文、数字字体与字重/可变轴，自动合入原厂字库结构并保留防裁切连续字重。
- **日韩字体与 Emoji 独立管理**：字体库支持导入静态日文/韩文字体按语言局部替换；「设置 → 字体工具 → Emoji 管理」可单独导入、预览、应用或恢复彩色 Emoji，与文字字体互不影响。
- **多格式导入与字库预览**：支持 TTF、OTF、TTC、可变字体、WOFF/WOFF2、字体模块 ZIP 与 `.mtz` 字体包，并提供基于酷安 53620096 语料的分类预览及 TTF 导出。
- **多种挂载方式与软重启**：支持自动挂载（元模块 / 管理器内置后端）、字域自挂载、Magic Mount 与 OverlayFS；兼容 FinaSU 内置 VFS 的环境支持在重启弹窗选择内核软重启，同时保留普通重启。
- **外观、主题商店与图标自定义**：内置 Material、Miuix、COUI 三套原生界面风格；主题商店支持多镜像自动选路、离线下载与试用；支持 v6 动态壁纸（MP4 / WebP）、4 套内置桌面图标、单图/ZIP 图标包快捷方式及自定义底栏图标。
- **扩展兼容与安全保护**：提供 [Google 字体兼容](docs/GOOGLE_FONT_COMPATIBILITY_ZH.md)、[LSPosed WebView 网页字体](docs/WEBVIEW_LSP.md)、开机/OTA 自动保护、按应用字体检查及脱敏诊断导出。

## 用户目录与包信息

```text
/sdcard/ziyu/
├── fonts/      # 导入的字体文件（支持自定义位置）
├── import/     # 待导入的字体 ZIP / MTZ
└── reports/    # 导出的脱敏诊断报告
```

- **App 包名**：`io.github.shishui611_art.ziyu`（正式版与正式签名 Debug 版共享包名与证书，可直接覆盖保留数据）。
- **版本规则**：`module.prop` 为模块与 App 唯一版本来源，`versionCode = major*10000 + minor*1000 + patch`（如 `v1.5.92` 对应 `15092`）。

## 文档导航

- [文档索引](docs/README.md)
- [使用指南](docs/USER_GUIDE.md) — 安装、导入、字体与 Emoji、主题/图标、备份与恢复
- [挂载方式与故障排查](docs/MOUNT_FLOW.md) — 四种挂载模式、软重启、应用字体检查与诊断日志
- [Google 字体兼容](docs/GOOGLE_FONT_COMPATIBILITY_ZH.md) — 解决 Google 应用英数回退默认字体
- [LSPosed 网页字体](docs/WEBVIEW_LSP.md) — 系统 WebView 网页跟随系统字体
- [真机验证矩阵](docs/TEST_MATRIX.md) — 各系统与 Root 管理器当前实测状态
- [字库固定测试基准](docs/FONT_TEST_BASELINE.md) — 酷安 53620096 验收语料与核对标准
- [发布流程](docs/RELEASING.md) — 签名、打包清单与 Release 流程
- [更新日志](更新日志/CHANGELOG.md) · [贡献指南](CONTRIBUTING.md) · [第三方许可证](THIRD_PARTY_NOTICES.md)

## 从源码构建

环境要求：JDK 17、Android SDK API 37、Gradle 9.5 及 ARM64 Python 字体运行时。

```sh
# 1. 构建 App（正式构建需通过受控环境变量提供 ZIYU_KEYSTORE_* 正式签名）
gradle -p android-app --no-daemon :app:assembleRelease

# 2. 准备模块运行时并打包模块 ZIP
sh ./scripts/prepare_composite_runtime.sh
ZIYU_APP_APK=android-app/app/build/outputs/apk/release/app-release.apk \
  sh ./scripts/build.sh
```

更多开发与发布细则见 [android-app/README.md](android-app/README.md)、[CONTRIBUTING.md](CONTRIBUTING.md) 与 [docs/RELEASING.md](docs/RELEASING.md)。

## 反馈与许可证

提交 Issue 时请附上机型、ROM 与 Android 版本、Root 管理器版本、所选挂载方式，以及 App 导出的诊断报告，请勿上传无授权商业字体。

本项目源码采用 **GNU General Public License v3.0 only**（`GPL-3.0-only`）许可，详见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
