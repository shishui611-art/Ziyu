<div align="center">

# 字域

**Android 全局字体替换与复合引擎**

适用于 **Magisk · KernelSU · SukiSU Ultra · APatch**

[![Fork](https://img.shields.io/badge/repository-ColorOS17%20fork-blue)](https://github.com/shishui611-art/Ziyu)
[![Upstream Release](https://img.shields.io/github/v/release/xgl34222220-ops/LuoShu?display_name=release&label=LuoShu%20上游版本)](https://github.com/xgl34222220-ops/LuoShu/releases/latest)
[![Build](https://github.com/shishui611-art/Ziyu/actions/workflows/build.yml/badge.svg)](https://github.com/shishui611-art/Ziyu/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-orange)](LICENSE)

[字域 Releases（本 fork）](https://github.com/shishui611-art/Ziyu/releases) · [使用教程](docs/USER_GUIDE.md) · [真机验证状态](docs/TEST_MATRIX.md) · [联系维护者（酷安）](https://www.coolapk.com/u/28601616)

</div>

> **Fork 说明：**本仓库是 [LuoShu 上游项目](https://github.com/xgl34222220-ops/LuoShu) 的独立 fork，项目名为**字域（Ziyu）**，由酷安 **[@柔性体](https://www.coolapk.com/u/28601616)** 维护，重点适配 ColorOS 17，同时兼容其他 Android 系统。上游源码中的版权与许可证声明继续保留。

## 项目简介

**字域**是一套面向 Root Android 设备的全局字体替换与复合方案。

它不是简单把一个字体文件复制到几十个系统路径，而是先读取当前设备真实字体配置，再根据中文、英文、数字的角色分别生成和映射字体负载，尽量兼顾覆盖范围、字体度量、系统稳定性和存储占用。

字域在本 fork 中从 **v1.0.0** 开始独立编号。这个编号代表字域 fork 的版本，不等同于 LuoShu 上游的版本；从 1.1.0 起，模块与 App 统一采用新编号，1.1.0 的 versionCode 为 11000。APK 包名改为 Ziyu，首次使用需要重新授予 Root；已有模块字体数据继续兼容。

## 主要能力

- **中文 / 英文 / 数字独立选择**：可以分别指定三类字体，也可以直接使用同一字体。
- **全局系统字重调节**：可预览、应用并恢复系统原值；字体自身的可变轴和多字重也可单独使用。
- **复合字体生成**：以中文字体为完整基底，将英文和数字目标字形合入同一字体，减少缺字回退和字体抢占。
- **设备自适应字体清单**：扫描当前 ROM 的实际字体目录、配置、字体槽、字重、TTC face 与字体度量，不依赖固定机型列表。
- **HyperOS / ColorOS 适配**：针对 OEM 字体路由、状态栏/系统 UI、英文数字槽和回退链提供额外处理。
- **Google 字体兼容**：设置中提供中文的「Google 字体兼容」页面，可检测、开启和恢复 GMS FontsProvider 组件状态，用于处理部分 Google 应用英数重新使用下载字体的问题。
- **挂载方式由用户选择**：可选择元模块自动挂载、OverlayFS、Magic Mount 或字域自挂载；明确选择会跨模块升级保留。各模式不会在失败时偷偷切换到其他模式，详情见[挂载说明](docs/MOUNT_FLOW.md)。
- **当前启动直接热切换**：已验证的字域物理自挂载支持在本次开机内从一套自定义字体切到另一套；其他路径会如实要求重启。热挂载不保证已打开应用立刻刷新缓存。
- **按证据报告应用结果**：区分已验证应用、部分槽位警告、待重启和失败；用户可忽略兼容警告，诊断信息仍保留。
- **详细诊断导出**：包含挂载来源、实现方式、分区目标、字体路径验证与必要的进程缓存证据；另有供用户直接执行的分区兼容报告脚本。
- **事务与回滚**：新字体完整生成并验证成功后才提交；生成失败、超时或内存不足时保留上一套可用负载。
- **字体缓存复用**：相同字体组合和设备契约可以复用已验证结果，减少重复生成。
- **原生 Android App**：模块 ZIP 内置 App，不使用 WebUI；正式版和 Debug 版均只发布可刷入模块 ZIP。

## 当前版本与验证状态

当前源码候选版本为 **字域 v1.3.0**；是否已经正式发布，以 [字域 Releases](https://github.com/shishui611-art/Ziyu/releases) 中的正式条目为准。上游 LuoShu 的 Releases 用于查看上游历史。真机验证状态见[测试矩阵](docs/TEST_MATRIX.md)，自动化通过不代表所有机型均完成验收。

Release 发布流程会验证 ZIP、内置 App 和 SHA-256，并自动同步更新清单。App 的“模块更新”会直接请求 GitHub Release API、Release 下载地址和 Raw 文件；国内网络可能无法检查或下载。接入国内可直连发布源并完成无代理实测前，不能承诺免代理更新。

设备适配仍以 [真机验证矩阵](docs/TEST_MATRIX.md) 为准。自动化构建通过不代表已经在所有 ROM、Root 管理器和机型上完成验证；遇到未列出的设备时，请先保留可卸载/恢复路径，再反馈诊断信息。

## 快速开始

1. 从 [字域 Releases](https://github.com/shishui611-art/Ziyu/releases) 下载正式模块 ZIP，并校验随附 SHA-256 文件。
2. 使用 Magisk / KernelSU / SukiSU Ultra / APatch 刷入 ZIP，按管理器提示重启，再从模块的“操作”入口安装内置 App。
3. 在 App 的“字体挂载方式”中明确选择模式。切换模式后，按弹窗选择立即重启验证或稍后重启。
4. 导入字体，选择中文、英文、数字及字重，提交应用。
5. 按任务结果处理：完成并等待重启时重启；若显示已热挂载，先查看挂载详情，界面仍旧时再考虑刷新缓存。

**包名与签名说明：**正式 App 包名为 `io.github.shishui611_art.ziyu`。签名配置齐全时，Debug 也会使用同一包名和正式证书，可覆盖正式 App 并保留数据；未配置正式签名的本地 Debug 使用 `.debug` 独立包，不能覆盖正式版。历史独立 Debug App 不会自动卸载。从旧包 `io.github.xgl34222220.ziyu` 更新时，Android 会把它作为新 App 安装；首次打开需重新授予 Root，旧 App 私有设置不会自动迁移。

> 不需要安装额外挂载模块，也不需要手工修改 `fonts.xml`。

## Google 字体兼容

部分 Google 应用会通过 Google Play 服务的 `FontsProvider` 获取下载字体，因此可能出现：

- 中文已经替换；
- 英文和数字一开始正常；
- 使用一段时间或重新打开 Google 商店后，英数又恢复成 Google 默认字体。

字域内置：

**设置 → Google 字体兼容**

页面提供：

- **重新检测**：只读取当前状态，不修改系统；
- **开启 Google 字体兼容**：停用当前 Android 用户的 GMS FontsProvider；
- **恢复原设置**：按照字域保存的原状态恢复组件。

该功能默认关闭，不会在安装、开机或升级时自动开启。

### 使用建议

1. 先正常应用字体并重启；
2. 如果 Google 商店等应用的英文数字仍反复恢复默认，再开启兼容；
3. 显示「已开启」后完整重启；
4. 检查 Google 商店、Chrome 等应用，以及放到后台再打开后的字体状态。

### 注意

Google 字体兼容会影响当前用户所有依赖 GMS 下载字体的应用，并不只作用于 Google 商店。它不会停用整个 Google Play 服务，也不会删除账户、App 数据或字体缓存。

**停用或卸载字域前，请先进入该页面点击「恢复原设置」，然后完整重启。**

## 字体工作方式

### 直接字体

当中文、英文、数字选择同一个字体时，字域优先使用快速路径，根据本机清单准备字体负载。

### 复合字体

当三类字体来源不同时：

```text
中文字体 ─┐
          ├─> 复合字体 ─> 本机字体槽 ─> systemless 挂载
英文字体 ─┤
数字字体 ─┘
```

中文字体负责完整正文基底，英文和数字只替换各自目标字符，尽量避免为了换英数破坏中文 fallback。

## 字体与格式支持

支持常见：

- TTF
- OTF
- TTC
- TrueType `glyf`
- CFF / CFF2
- Variable Font
- 多字重字体

可变字体会读取实际 `wght`、`wdth`、`opsz`、`slnt` 等设计轴；不存在的字重不会仅靠文件名伪装为可用。TTC 作为字体集合保留，不拆为多个字体文件；重复导入按内容哈希识别。

## 用户目录

```text
/sdcard/LuoShu/
├── fonts/      # 用户字体
├── import/     # 待导入字体模块 ZIP
└── reports/    # 脱敏诊断报告
```

也可以直接使用 App 的系统文件选择器导入字体。

## 安全设计

字域的基本原则是：**可以失败，但不能为了换字体先破坏当前可用系统。**

因此：

- 不直接写入 `/system`、`/product`、`/vendor` 等只读分区；
- 不覆盖整份原厂 `fonts.xml` / `font_fallback.xml`；
- 不执行导入字体 ZIP 中的第三方脚本；
- 图标、Emoji、符号、无关语言和高风险字体槽默认不参与普通替换；
- 新负载生成后会先进行格式、覆盖和契约检查；
- 切换失败时保留上一套有效字体；
- 卸载只处理字域自己的挂载和记录。

## 功能边界

字域主要管理 **Android 系统字体链路**。以下内容可能完全不经过系统字体，因此不保证被替换：

- App 自带字体文件；
- 游戏、阅读器等私有字体引擎；
- 输入法键帽等资源字体；
- 网页 CSS / WebFont；
- Canvas、SVG 路径或图片文字；
- 与字域同时覆盖同一字体路径的其他模块。

Google 字体兼容也不能替换 App 自己打包的字体或网页指定字体。

## ROM 兼容

字域不使用「机型白名单」作为主要覆盖依据，而是读取设备当前可见的字体分区和配置。

常见目标包括：

```text
/system/fonts
/system_ext/fonts
/product/fonts
/my_product/fonts
/my_region/fonts
/vendor/fonts
/odm/fonts
/oem/fonts
/mi_ext/fonts
/hw_product/fonts
```

不同 ROM 实际目录可能不同。HyperOS、ColorOS 等专项逻辑是对其特殊字体路由的增强，不代表其他 ROM 一律不支持。

真机验证情况请查看：[docs/TEST_MATRIX.md](docs/TEST_MATRIX.md)。

## 版本规则

字域 fork 的版本线从 **1.0.0** 开始，后续按项目策略递增：

```text
1.1.0 → 1.1.1 → 1.2.0 → ...
```

模块与 App 的 versionCode 均为 `major*10000 + minor*1000 + patch`。v1.3.0 为 13000。次版本限制为 0–9，修订号为 0–999。

当前版本号与是否已发布，以 [字域 Releases](https://github.com/shishui611-art/Ziyu/releases) 和模块信息为准；上游 LuoShu 版本号不会代替本 fork 的版本号。

## 从源码构建

正式版由 GitHub Actions 完成固定证书签名、测试和 ZIP 打包。不要把本地 Debug APK 当作正式版上传。

本地开发可构建 Debug App，再把本次 APK 显式交给模块打包脚本：

```sh
cd android-app
gradle --no-daemon :app:assembleDebug
cd ..
sh ./scripts/prepare_composite_runtime.sh
sh ./scripts/check.sh
LUOSHU_APP_APK=android-app/app/build/outputs/apk/debug/app-debug.apk \
LUOSHU_ALLOW_DEBUG_APP=1 sh ./scripts/build.sh
```

模块打包步骤需在配置好 Android SDK/NDK 和 Python 运行时的环境运行。配置正式签名时 Debug 可覆盖正式 App；未提供签名时 Debug 使用独立 `.debug` 包名，不能覆盖正式 App。正式 Release 使用固定证书构建，模块内置 App 必须与已签名构建产物保持一致。

## 问题反馈

提交 Issue 时建议提供：

- 手机型号；
- ROM 与 Android 版本；
- Magisk / KernelSU / SukiSU Ultra / APatch 版本；
- 使用的字体格式、字重和大致体积；
- 具体未覆盖的 App / 页面；
- 可复现步骤；
- 已检查隐私信息的字域诊断报告。

请不要上传没有授权的商业字体文件。

## 文档

- [文档索引与适用范围](docs/README.md)
- [完整使用教程](docs/USER_GUIDE.md)
- [Google 字体兼容中文说明](docs/GOOGLE_FONT_COMPATIBILITY_ZH.md)
- [真机验证矩阵](docs/TEST_MATRIX.md)
- [设备字体模板引擎](docs/DEVICE_FONT_TEMPLATE_ENGINE.md)
- [发布流程](docs/RELEASING.md)
- [挂载方式与验证](docs/MOUNT_FLOW.md)
- [热切换限制](docs/FONT_LIVE_SWITCH.md)
- [临时 Root 与软重启](docs/TEMP_ROOT.md)
- [字体问题与诊断日志](docs/FONT_DIAGNOSTICS.md)
- [性能优化说明](docs/SWITCH_PERFORMANCE.md)
- [模块更新说明](docs/MODULE_UPDATE.md)
- [第三方许可证](THIRD_PARTY_NOTICES.md)

## 许可证

字域源码采用 **GNU General Public License v3.0 only**（`GPL-3.0-only`）。

分发修改版本时，请遵守 GPLv3 关于源码提供、许可证保留和衍生作品许可的要求。第三方组件按各自许可证分发，详见 `THIRD_PARTY_NOTICES.md` 与 `licenses/`。

---

维护者：酷安 [@柔性体](https://www.coolapk.com/u/28601616)。欢迎反馈真机结果或提交改进建议。
