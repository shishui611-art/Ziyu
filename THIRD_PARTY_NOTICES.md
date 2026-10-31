# 第三方组件与许可证

`common/stock_readonly_view.py` 为字域原创 GPL-3.0-only 运行文件，许可证见 [Ziyu-GPL-3.0.txt](licenses/Ziyu-GPL-3.0.txt)。它调用设备已有的 Linux 命名空间、loop 与只读 EROFS 挂载接口及系统 losetup，未引入或打包新的第三方库、分区镜像或字体。


## Xposed API 82（仅用于编译）

`android-app/xposed-api/api-82.jar` 来源为 [Xposed 官方 Maven](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)，SHA-256 为 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`，许可证为 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。它只作为 compileOnly 使用，不打包到 App；实际 API 由用户安装并启用的兼容框架提供。WebView 字体处理由字域自行实现。

## HarfBuzz 15.0.0 原生字体处理器

- 来源：[HarfBuzz 15.0.0 官方源码](https://github.com/harfbuzz/harfbuzz/tree/15.0.0)，原始源码包 SHA-256 为 `fe4b8bad7afbbae75d61b2095c267c98f4ed04fe4a117680130ed253d2732efe`。
- `native/font-instance/instance.cpp`、`native/font-instance/glyph-cache.hh`、`native/font-instance/cff-cache.hh` 和构建脚本是字域 GPL-3.0-only 代码；`common/font-native-instance` 静态包含 HarfBuzz，许可证见 [`licenses/HarfBuzz-COPYING.txt`](licenses/HarfBuzz-COPYING.txt) 和 [Microsoft USE MIT 许可](licenses/HarfBuzz-Microsoft-USE-MIT.txt)。构建脚本固定 Android ARM64 API 28、NDK 与二进制哈希记录，打包前校验来源。
- 原生实例优先覆盖已支持的 TrueType 与 CFF2 可变字体和各角色供体；不支持的表、结果校验失败及运行不可用会走现有 FontTools。手机运行与性能仍须实测。

`common/font_ttf_export.{py,sh}` 与 `FontTtfExport.kt` 为本项目原创 GPL-3.0-only 代码，调用已打包 FontTools 的字体集合、变轴实例与轮廓转换 API，未新增第三方依赖或字体数据。转换文件仅由用户主动导出。轮廓模板复用同样使用现有 FontTools API，无新增原生库；运行文件列入模块清单，项目许可证见 `licenses/Ziyu-GPL-3.0.txt`（随主许可证原文）。

字域当前源码采用 GPL-3.0-only，详见根目录 [`LICENSE`](LICENSE)。历史 MIT 发行版本的许可证文本保存在 [`licenses/LuoShu-MIT-HISTORICAL.txt`](licenses/LuoShu-MIT-HISTORICAL.txt)。

发布包会包含或在构建过程中引入以下第三方组件。它们不因被字域打包而改变原许可证。

## LuoShu 3.0.0 功能移植

- 来源：[`xgl34222220-ops/LuoShu`](https://github.com/xgl34222220-ops/LuoShu)，正式标签 `refactor-v3.0.0`，固定提交 `3e7e19b9f2b0899a326d0ce337602c006b90f233`。
- 移植内容：组合字体平顶探针、中文比例与基线校正、英数微调算法和参数边界，以及西文路由优先诊断、符号链接路由说明。
- 字域适配：保留当前组合保存与应用流程、字形兼容和错误报告；微调接入三种界面风格、方案备份及任务参数；实例缓存沿用字域实现并补充同源英数复用。
- 许可证：GPL-3.0-only。上游此标签的许可证文本保存在 [`licenses/LuoShu-v3.0.0-GPL-3.0.txt`](licenses/LuoShu-v3.0.0-GPL-3.0.txt)。完整范围和当前验证状态见 [`docs/UPSTREAM_V3_PORT.md`](docs/UPSTREAM_V3_PORT.md)。

## 字体预加载公共接口

- `native/font-preload/zygisk.hpp` 原样取自 `topjohnwu/zygisk-module-sample` 提交 `7bb941ac8edfcffd1d23761e401c45ca95409dc1` 的 `module/jni/zygisk.hpp`（Zygisk API v4）；版权 John "topjohnwu" Wu，原宽松许可证保存在文件头及 `licenses/Zygisk-API-LICENSE.txt`。
- `native/font-preload/preload.cpp`、`common/font_preload_state.py`、构建脚本和安装生命周期脚本为字域 GPL-3.0-only 代码。字体缓存调用思路参考 [JingMatrix/FontLoader](https://github.com/JingMatrix/FontLoader) 的说明；其 Usage 建议将字体定制目标应用移出 DenyList。回调资格边界参考 [Magisk 的 `zygisk_should_load_module`](https://github.com/topjohnwu/Magisk/blob/master/native/src/core/zygisk/daemon.rs)：当目标同时处于 `ProcessOnDenyList` 和 `DenyListEnforced` 状态时，该函数不加载 Zygisk 模块。字域不会自动修改排除设置；这些参考只用于说明字体预热原理与回调限制，未复制 FontLoader 或 Magisk 的源码实现。
- `common/font_preload_module/zygisk/*.so` 由上述源码使用 Android NDK r28c 编译，未链接外置 `libc++_shared.so`。源码及库哈希记录在同目录 `build.json`，模块清单明确包含该组件和许可证。

## 挂载实现参考

`common/device_font_template_base.py` 的按需 PostScript 名称索引及字体表懒加载移植自 LuoShu 提交 [`852297117606ca37436b999b6307fd598742bca7`](https://github.com/xgl34222220-ops/LuoShu/commit/852297117606ca37436b999b6307fd598742bca7)，保持 GPL-3.0-only；许可证完整文本沿用 `licenses/LuoShu-v3.0.0-GPL-3.0.txt`。它仅改变采集读取时机，原有解析、哈希和验证流程保留。

`common/font_structure.py` 中 v1.3.12 新增的 SFNT/TTC 目录读取及 `common/font_check.sh` 的组合源接入为字域自行编写的 GPL-3.0-only 代码，使用 Python 标准库，未引入第三方解析代码。FontTools 的既有完整验证仍按其原许可证使用。

新增 `common/mount_provider_reload.sh` 是字域自行编写的 GPL-3.0-only 代码，依据安装版本的可读证据检查软重启兼容性。协议参考 [Mountify 扫描脚本](https://github.com/backslashxx/mountify/blob/master/module/post-fs-data.sh)（Unlicense）和 [KernelSU v3.3.0 阶段执行逻辑](https://github.com/tiann/KernelSU/blob/v3.3.0/userspace/ksud/src/init_event.rs)；未引入或执行上述项目的第三方脚本副本。

## 原创状态读取代码

`common/mount_backend_state.sh` 为字域自行编写的状态读取辅助代码，按仓库 GPL-3.0-only 授权，完整许可证为根目录 `LICENSE`；未引入第三方状态解析实现。

## CPython

- 用途：在 Android ARM64 设备上运行离线复合字体构建器。
- 来源：Python 官方 Android ARM64 发行包。
- 许可证：Python Software Foundation License 及其历史许可条款。
- 完整文本：[`licenses/CPython-LICENSE.txt`](licenses/CPython-LICENSE.txt)。

字域只进行打包精简：删除测试、开发头文件、IDLE、ensurepip 和运行时不需要的工具，不修改解释器核心许可。

## FontTools

- 用途：读取、转换、写入和验证 OpenType 字体。
- 许可证：MIT License。
- 完整文本：[`licenses/FontTools-LICENSE.txt`](licenses/FontTools-LICENSE.txt)。
- FontTools 上游附带的外部字体项目声明：[`licenses/FontTools-LICENSE.external.txt`](licenses/FontTools-LICENSE.external.txt)。

发布运行时不会使用 FontTools 测试字体作为字域内置字体，但仍保留上游外部声明以便完整追溯。

## Google WOFF2 与 Brotli

- 用途：在 Android ARM64 设备上离线解码 WOFF2，生成可由 Android 字体栈使用和进一步校验的 SFNT/TTC。
- 来源：`google/woff2` 固定提交 `fb9c3379f2605b10f3e8f1d9636664ab5576775c`；其 Brotli 依赖固定提交 `533843e3546cd24c8344eaa899c6b0b681c8d222`。
- 许可证：两者均为 MIT License。
- 完整文本：`licenses/WOFF2-LICENSE.txt` 与 `licenses/Brotli-LICENSE.txt`。

字域只打包交叉编译后的 `woff2_decompress` 解码工具，不使用它修改原始字体文件；转换结果仍会经过字域自己的 SFNT 与字体表校验。

## Miuix 与 AndroidLiquidGlass

- 用途：Android 13 及以上悬浮底栏的背景取样、RuntimeShader 模糊与折射、边缘高光和平滑连续圆角；MIUIx 英数微调使用 `miuix-ui-android:0.9.3` 的官方 Slider。
- 来源：[`compose-miuix-ui/miuix`](https://github.com/compose-miuix-ui/miuix)；折射透镜按其官方示例改写，该示例注明源自 [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)。
- 许可证：Apache License 2.0。
- 完整文本：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。

字域修改了示例的包名、配色、分层、尺寸和导航状态接入；底层库及原示例的许可证与署名不变。

## COUI

- 用途：COUI 风格的主题、导航组件与英数微调 Slider，依赖 `io.github.suqi8.coui.kmp:coui-ui-android:1.1.0`。
- 来源：[`suqi8/coui`](https://github.com/suqi8/coui)。组件直接调用库 API，未复制组件实现。
- 许可证：Apache License 2.0。完整文本：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。

## KernelSU 悬浮底栏

- 用途：MIUIx 风格下的悬浮导航栏，包括拖动切换、弹性动画、选中高亮与液态玻璃绘制。
- 来源：[`tiann/KernelSU`](https://github.com/tiann/KernelSU)，固定提交 [`0ff54fab4e741d958daf3048d7711644c40eca79`](https://github.com/tiann/KernelSU/tree/0ff54fab4e741d958daf3048d7711644c40eca79)。移植文件位于 `android-app/app/src/main/java/io/github/xgl34222220/ziyu/ui/navigation/kernelsu/`。
- 许可证：GNU General Public License v3.0。完整文本：[`LICENSE`](LICENSE)。

字域调整了包名、主题和系统版本能力检测，并将组件接入现有字体管理页面。`liquid/` 子目录中的折射与背景辅助代码仍按各文件标注，基于 Miuix 与 AndroidLiquidGlass 示例并采用 Apache License 2.0。

## 用户字体与第三方模块

`common/apatch_boot_prepare.sh` 为字域原创的 APatch 启动准备适配代码，按仓库 GPL-3.0-only 授权（完整文本见 `LICENSE` 及 `licenses/Ziyu-APatch-boot-GPL-3.0.txt`）。实现参照 APatch 的 common post-fs-data → modules_update → metamount → module post-fs-data 调度契约，未复制 APatch 或 NoMount 源码，也不接管提供者原生挂载或清理。

v1.3.3 新增的 `common/font_scan_profile.py` 和 `common/stock_font_snapshot.py` 为本项目原创运行代码，按仓库 GPL-3.0-only 授权，未复制第三方模块实现。快照只在用户设备上保存该设备已验证的原厂文件，不随模块 ZIP 分发。

字域不会在仓库或发布包中附带商业字体。以下内容不受字域 GPL-3.0-only 授权：

- 用户自行放入当前默认目录 `/sdcard/ziyu/fonts/` 的字体；升级兼容期间也可能仍从旧目录 `/sdcard/LuoShu/fonts/` 读取；
- 从其他模块 ZIP 中读取的字体；
- ROM、厂商、Google 或应用自带字体；
- 使用这些字体生成的复合输出中由原字体权利人拥有的字形数据。

使用者必须自行确认字体的使用、修改和再分发权限。不要在 Issue、PR、Release 或其他公开渠道上传无授权字体。


## LuoShu main 2026-10-13 分项移植（字域 v1.4.2）

- 来源：[LuoShu main `cd90150bb6bfa8081c639e684d421c317e5d436a`](https://github.com/xgl34222220-ops/LuoShu/tree/cd90150bb6bfa8081c639e684d421c317e5d436a)，GPL-3.0-only；完整许可证见 `licenses/LuoShu-main-20261013-GPL-3.0.txt`。
- 按字体真实声明读取轴、隐藏轴标记、轴名与范围校验、成功元数据缓存及相关回归源自 `4dead61b`；字域保留自己的字体预览与三种主题控件，并增加读取期间源变化核对。
- 本次 ColorOS 覆盖、App 筛选与轮询/前台服务、只读应用字体来源审计的参考分别为上游 PR #294、#289、#283/#293。实现按字域现有原厂快照、挂载、热切换、任务及三主题设置路径适配。
- `common/composite_collection_build.py`、`composite_collection_contract.py` 及 Shell 入口参考上游组合字体集合方向，基于字域现有组合输出、fontTools 与可信原厂快照实现逐面构建/证明校验；代码按本仓库 GPL-3.0-only 授权，字体数据的权利仍属于原权利人。
- `common/boot_ota_guard.sh` 及设置入口为按字域现有启动、任务监督、切换事务和挂载验证路径编写的启动/OTA 保护适配，按本仓库 GPL-3.0-only 授权；不复制或接管 Root 管理器挂载实现。
- 完整移植范围、验收证据与手机待测项见本次版本说明和 `docs/UPSTREAM_PORT_20261018.md`；不把上游未发布 main 或其历史设备观察当作字域当前版本手机验收。

## ziyu v1.4.8 运行文件

`common/user_storage.{sh,py}`、`common/user_storage_dispatch.sh`、`common/full_backup.{sh,py}`、`common/identity_migration.sh` 与 Android 背景导入为项目自有实现，沿用项目许可证；依赖现有 CPython 的 fcntl/hashlib 与 Android ImageDecoder，未引入第三方二进制。保留的 LuoShu 前缀状态、文件与来源注释用于旧负载恢复及许可证溯源，不代表模块管理器 ID。

## ziyu v1.4.10 字形兼容修正

`common/composite_collection_build.py`、`font_slot_compat.py` 内的原厂组合字形部件隔离、变化映射复制及回读契约为本项目基于现有 FontTools API 编写的修改，沿用 GPL-3.0-only；使用已有运行库，未引入新第三方文件或二进制。原厂字体仅用于设备上本次离线兼容编译，不随模块分发。

## ziyu v1.4.11 本地显示配置

`common/display_config_storage.{sh,py}`、`display_typography.sh` 的本地配置读写、迁移与内部镜像，以及 `font_axis_info.py` 的元数据缓存为本项目自有实现，沿用 GPL-3.0-only。使用已有 CPython 标准库和 Android 设置接口，未增加第三方依赖、密钥或后台常驻服务。新增运行文件列入模块打包清单。

## ziyu v1.4.12 SukiSU 双主题适配

v1.4.13 的通知授权状态、独立预览通知与 `ui/navigation/PredictiveBack.kt` 手势状态管理为项目自有实现，沿用 GPL-3.0-only。使用现有 Android/AndroidX Compose API，没有引入新第三方库、运行二进制或签名密钥。主题交互继续基于以下固定 SukiSU 参考适配，已有来源声明和许可保持。

来源：[SukiSU-Ultra/SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra)，固定提交 `df84920b20a925fd1135530f6e403361ad494d7b`，获取日期 2026-10-24。参考 `manager/app/src/main/java/com/sukisu/ultra/ui/theme/{Theme,MiuixTheme,MaterialTheme,ThemeExt,Shape,Colors}.kt`、`ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`、`ui/component/material/TonalCard.kt` 与 `ui/component/bottombar/BottomBarMaterial.kt`。

上游 manager 使用 GPL-3.0-or-later；本次适配选择 GPL 第 3 版，修改沿用本仓库 GPL-3.0-only。许可证全文保存在 `licenses/SukiSU-Ultra-GPL-3.0.txt` 并进入模块清单。适配涉及调色枚举/2025 支持范围、主题色数值、Expressive 圆角/主题与按压反馈、明暗预览布局、Material 导航的实体和液态样式；保留字域自己的内容、业务状态、控件桥和低版本兼容路径。未复制上游图标插画、应用标识、Root 管理或内核代码。已有玻璃导航的 KernelSU/Miuix/Apache 来源说明继续保留。

`common/font_verity.{sh,py}` 是项目自有 Linux fs-verity ioctl 封装，UAPI 参数参考 Android NDK `<linux/fsverity.h>`；不引入额外库或证书。新增用户存储、备份与标识迁移同为项目自有代码，保留项目许可证。

`common/font_parallel.py` 与 Android `NotificationAccess.kt` 为项目自有实现，沿用项目许可证。并行处理使用已有 CPython 的 os/signal/json，通知使用 Android 系统 API，未引入第三方库、厂商 SDK、签名密钥或驻留服务。

v1.4.38 的 `common/font_native_glyphs.py`、原生字形缓存协议和 TrueType 分解/缩放代码为本项目原创 GPL-3.0-only 实现；`font_native_instance.py` 的字符裁剪使用上述已固定 HarfBuzz。没有新增外部库或随包字体；运行文件已列入模块清单。

v1.4.53 的 `common/font_native_cff.py`、原生 Type 2 写入器、边界计算和缓存协议为本项目原创 GPL-3.0-only 实现；CFF/CFF2 供体读取使用上述固定版本 HarfBuzz draw API。没有新增外部库。
