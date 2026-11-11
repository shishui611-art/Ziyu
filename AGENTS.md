# 字域项目协作规范

## 源码修改与本地交付

- **出包必须先问、先获允许（2026-11-10 起，用户明确要求）**：完成代码分析或改动后，不得自行编译 APK/打包 ZIP。必须先向用户列出疑问点和待确认方案，得到用户明确允许后才能执行构建、打包与验证；未获允许前只交付分析、改动说明或补丁建议。
- `module.prop` 是模块、App 与制品的唯一版本来源；模块 ZIP 必须内置本次构建的同版本 APK。
- 每次源码修改交付都必须递增版本，不得复用上一交付版本号。用户所说“每次加 0.01”在当前三段版本格式中指修订号加 1，例如 v1.4.0 → v1.4.1；versionCode 按 major*10000 + minor*1000 + patch 同步递增。Debug 本地交付也须同步版本提示、CHANGELOG 和对应版本说明。
- 经用户允许后，构建可安装 APK 和包含同一 APK 的模块 ZIP，写入新的 `dist/<北京时间时间戳>-<用途>/` 目录并保留旧交付物。
- 优先使用已有正式签名配置；未提供时构建 Debug APK 并明确标注。不得创建、替换或提交正式签名密钥，不得把未签名 APK 当作正式版。
- 打包后核对 applicationId、版本名/code、签名证书、模块清单、ZIP 完整性，以及独立 APK 与内置 APK 的 SHA-256 一致。
- 不因打包自动安装 APK、刷入模块或发布到网络；这些动作需由用户明确要求。
- 不自行添加或运行测试，除非用户要求，或用户明确要求的发布工作流包含其规定的自动化检查。分别报告构建、包完整性检查和手机验收范围。

## 正式发布

- 发布版本必须同步更新 `module.prop`、`config/version_notes.conf`、`更新日志/CHANGELOG.md` 和版本对应的 `更新日志/RELEASE_NOTES_ziyu-vX.Y.Z.md`。
- Release notes 应总结新增、修复和优化，并基于上一个正式版本对比；历史设备观察不能写成当前版本真机验收。
- 用户明确授权发布后，按 `docs/RELEASING.md` 推送 `main` 并等待 Release workflow；正式版本使用固定签名，ZIP 中 App 与已验证签名构建产物字节一致。
- 设备矩阵只依据实际验收证据更新；待测时保留待测状态。正式发布的版本范围授权只允许通过 `config/stable_release_authorization.conf` 一次性绑定具体版本。

## 界面与文案

- 设置、预览和弹窗使用简短、直白的中文；优先说明能做什么、如何操作及实际结果，辅助说明默认不超过一句。按钮直接写动作，如“导入”“应用”“保存”。
- 每个功能默认用一句话说明“做什么、什么时候用”；详细教程和技术原理维护在 GitHub 仓库文档，界面按需提供“使用说明”入口。基本操作应在界面内完成，不要求用户先联网读文档；必要的错误、影响范围、重启要求和确认不可省略。
- 日常界面不展示设计意图、实现过程、内部协议、算法及装饰性英文。技术细节、字体内部名称、字符覆盖统计和路径规则按需展开，或放入详情与日志。
- 同一信息只显示一次：不重复标题与正文，不在输入框外再完整复述同一路径；成功结果不追加无关说明。单文件导入不显示无效的前后翻页按钮。
- 预览突出字体样例，名称与说明使用次级字号；长名称可省略并提供完整名称入口。窄屏与大字号须调整布局，弹窗内容可滚动，操作按钮保持可达。
- 使用普通用户能理解的名称，不直接展示“候选槽位”等内部术语；真实错误原因、重启要求、影响范围和删除等必要确认不得省略，待验证或未生效不得写成成功。
- 后续界面修改默认检查重复信息、冗长说明、长名称和控件间距，不再将上述内容留作用户反复提醒。

## 实现约束

- 修复必须针对实际故障原因和执行路径。新增错误提示、诊断字段或提前拦截只能作为辅助，不能代替实际问题的修复，也不能据此声称已修好。交付时说明实际改正的行为、仍存在的限制和未完成的设备验证。
- 修复同时关注现有功能兼容、资源消耗和切换字体速度；优先减少重复复制、重复哈希及无必要等待，保留安全契约。性能改善只能依据同条件实测报告，不能从编译通过直接推断。
- 挂载提供者适配只允许在用户请求的管理器重载阶段执行；逐项证明提供者身份、挂载来源、命名空间和清理结果。不得在普通状态读取或路由校验失败时全局卸载。提供者原生重载流程仍由管理器调度。

- 模块 Shell 需兼容 Android `/system/bin/sh` 和设备自带 BusyBox/Toybox，不能假设桌面 GNU 工具齐备。
- Root 命令应校验输入与路径，记录事务状态，并在失败时验证回滚结果。
- 字体只写入字域管理的 systemless 负载；不得直接修改只读系统分区或覆盖整份原厂字体 XML。
- 挂载模式须遵循用户所选项。外部提供者、自挂载、OverlayFS 和 Magic Mount 的失败不能悄悄转换为另一模式。
- 报告字体应用状态时区分挂载、路由、部分槽位 warning、进程缓存和整体失败。
- 字体处理或缓存优化必须保留源变化检查、输出校验、挂载验证和事务回滚条件。
- 防裁切必须保留可变字体的连续字重调整，不得以固定默认字重、离散字重替代或删除变化数据作为兼容/提速方案；不支持的格式应保留现有字体并如实报告限制，不得静态化后宣称已支持。
- 新增第三方代码或运行文件时登记来源/许可证，并更新 `THIRD_PARTY_NOTICES.md`、`licenses/` 与模块打包清单。

## 固定字库验收基准

- 每次字体字库覆盖与挂载测试，以用户指定的酷安文章 https://www.coolapk.com/feed/53620096 为基准，遵循 `docs/FONT_TEST_BASELINE.md`。已收到的用户原文固定为 `docs/font-test-baseline/coolapk-53620096-r1.txt`，摘要与索引见同目录清单；原样保留，不用自编文字替代，也不要求所有字体支持全部私用区符号。
- 使用同一份固定语料对比源字体、生成字体、系统实际字体路由和目标应用显示；分别记录缺字、未覆盖、挂载失败、缓存/自带字体及裁切，不能仅凭界面显示或单个码位存在宣称挂载成功。
- 每次报告附基准版本、字体摘要、系统/Root/挂载后端、本次启动标识、具体失败字符/序列及未完成项。待取得正文或待手机测试时明确标为待验证。

## 开发环境

- Android 构建使用 JDK 17、Android SDK API 37 与 Gradle 9.5。
- 本机 JDK 17 为 `E:\Android\openjdk\jdk-17.0.8.101-hotspot`；SDK 为 `F:\Program Files\AndroidSDK`。Gradle 9.5 位于用户 Gradle wrapper 缓存，本副本没有 `gradlew.bat`，用缓存中的 `gradle.bat -p android-app` 构建。
- 本机 Python 使用 Conda 环境 `F:\ProgramData\anaconda3\envs\env\python.exe`。本地打包用 Git Bash 和 `scripts/build_debug_fast.sh`，不运行完整测试脚本；独立包与 ZIP 内 APK 完整性仍必须检查。
- 模块包构建需提供与仓库脚本相符的 Python/ARM64 字体运行时环境；新增运行文件必须进入 `scripts/module_payload_manifest.txt`。
- 正式签名只经受控环境变量提供；不要把密钥、密码、解密材料、本机凭据路径或设备诊断导出提交到仓库。本机存在用户提供的签名材料时，优先用它构建 Debug 变体，使 Debug 包可覆盖已安装正式版，避免同包名不同签名形成两个 App；签名材料位置与口令不入库。

## 已知环境问题与快速处理

- **Git Bash 的 `python3` 打不开项目路径**：Git Bash 里 `python3` 可能是 WindowsApps stub 或原生 Windows Python，收到 `/c/...` 会报 `can't open file 'C:\c\...'`。快速处理：在 PATH 前置一个包装脚本，用 `cygpath -m` 把每个 `/<盘符>/...` 参数转成 `C:/...` 再 `exec` 真实 `python.exe`；不要为此修改项目内脚本。`scripts/version.sh` 是被 source 的脚本，单独执行会报 `return: can only return from a function or sourced script`，应通过 `build_debug_fast.sh` 间接使用。
- **`aapt.exe` 遇中文路径**：会报 `Illegal byte sequence` 并读不到 APK。改用 `aapt2 dump packagename` / `aapt2 dump badging` 与 `apksigner verify --print-certs` 校验包名、版本和证书；必要时先复制到纯英文临时目录。
- **本地打包所需环境变量**：运行 `scripts/build_debug_fast.sh` 前设置 `ZIYU_BUILD_OUT`（Git Bash 路径）、`ZIYU_APP_APK`、`ZIYU_APP_PACKAGE`、`ZIYU_APP_VERSION_CODE`、`ZIYU_APP_VERSION_NAME`；构建日志中的 `BUILD SUCCESSFUL` 和 APK 时间戳都要核对。
- **Gradle 增量构建含 R8 约 3–4 分钟**：用后台任务等待完成通知，不要重复启动或用轮询代替等待；APK 位于 `android-app/app/build/outputs/apk/debug/app-debug.apk`。
- **只读 ADB 诊断优先看证据链**：`su -c` 读取模块 `config/` 与 `logs/`；挂载方式用 `/proc/1/mountinfo` 和 `/proc/self/mountinfo` 对照，NoMount 看 `/data/adb/nomount/.runtime/boot.log`，内容一致性用 `sha256sum` 比较 `/system/fonts` 与 `.ziyu-payload`。KernelSU 软重启日志在 `/data/adb/ksu/log/logcat.old.log`，注意软重启不改变 kernel boot_id，不能把它当作同一次启动。

## 开机 PID 控制方法（2026-11 实测，v1.5.20~v1.5.22）

字域在 Android 17 / ColorOS + KernelSU(SukiSU) + NoMount 元模块设备上的开机 PID 目标：`zygote64` 的 PID 小于 5000，同时保持字体挂载可用。

### 指标与采集

- 每次改动后完整重启，读 `/proc/uptime`、`pidof zygote64`、`pidof system_server`。
- ksud 阶段耗时看 `/data/adb/ksu/log/dmesg.log` 里 `exec 11 (/data/adb/ksud post-fs-data)` 的 `waiting took X seconds`，以及 zygote 启动行。
- 字域自身用 `common/boot_profile.sh` 在 `post-fs-data` 打点，日志 `logs/boot-profile.log`，记录 `stage / pid / delta / uptime`。
- 元模块阶段看 `/data/adb/nomount/.runtime/boot.log`（VFS injection started/completed、script suppressed、synced-files）。

### 已确认的因果链（不要靠猜）

1. zygote 的 PID ≈ 开机到 zygote 创建前的任务总数；`post-fs-data` 越久，ColorOS 在等待期间启动的服务/线程越多。实测每多等约 1 秒，zygote PID 约多 400~500。
2. 字域自身只创建几百个 PID（v1.5.19 实测 505，v1.5.20 实测 325），但把 ksud 阶段拉长到 11~16 秒时，zygote PID 会到 5900~7358。
3. 去掉开机自动保护/快照/发布校验后，字域自身阶段约为 0.14 秒；剩余时间在 NoMount 元模块。
4. NoMount 的“脚本接管”会 `find` 模块目录下所有 `*.sh` 并逐个 stat/检查。字域风险最大：曾一次包含 170 个 `.sh`（全部模块 258 个），仅这一步就占约 5 秒。把运行库移入 `data/`（NoMount 会裁剪该目录）并用 `common -> data/common` 相对符号链接保持路径后，扫描对象降到十余个，ksud 阶段 6.5 秒 → 2.6 秒，zygote PID 5821 → 4311。

### 操作规则

- 优先减少开机阶段执行的外部命令与重复判定：能用 shell 内建就不开进程；同一 boot_id 内同一判定只跑一次；无待激活负载时直接跳过事务恢复/激活。
- 大块运行库、脚本目录不要直接放在模块根下：放进 `data/` 或被元模块裁剪、不跟随符号链接的路径。新增脚本目录时先在目标设备确认元模块的扫描/裁剪规则。
- 保留 `boot-profile.log` 的 `dur=` 阶段耗时；每轮改动至少对比：ksud 阶段耗时、zygote PID、字域自身 delta、真机字体是否仍生效（`/system/fonts` 与负载 `sha256sum` 对比）。
- 不得只凭编译通过或静态计数宣称加速；必须以同一台设备、同条件完整重启的数据为准。
- 轻量启动会关闭自动 OTA/开机保护暂停与发布时全量校验（用户明确要求的取舍），交付说明中必须写明该限制；需要恢复自动保护时应作为可配置项重新引入，不能默认开启。

## 原生字体实例化桥（HarfBuzz）

- 可变字体的字重固化优先走 `common/font-native-instance`（HarfBuzz 15.0.0 静态链接，构建记录见 `common/font_native_instance_build.json`）+ `common/font_native_instance.py`；只有格式/角色不支持或 helper 失败时才回退 FontTools。修改字体应用路径时必须保持“原生优先、失败回退”的顺序，并在交付说明写明是否真机验证。
