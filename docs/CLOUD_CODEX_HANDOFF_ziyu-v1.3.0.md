# 云端 Codex 交接：字域 v1.3.0 正式发布

更新日期：2026-10-11

## 用户授权与目标

用户明确要求：将当前工作副本的改动推到 GitHub 仓库 `main`，由仓库自动化创建并发布正式版 `ziyu-v1.3.0`，并使使用说明、仓库文档和当前源码行为一致。该授权包括提交、推送和 GitHub Release 发布；不包括安装 App、刷入模块或在其他网站上传文件。

发布说明要比较 `ziyu-v1.2.0`，分列 v1.3.0 的新增、修复和优化。主发布说明已写在 `更新日志/RELEASE_NOTES_ziyu-v1.3.0.md`，总变更记录在 `更新日志/CHANGELOG.md`。

## 工作副本状态

- 仓库：`https://github.com/shishui611-art/Ziyu`
- 当前目录：本仓库的 `Ziyu-miuix-detail-fix` 工作副本。
- 当前分支：`codex/mount-provider-first`。
- 本地基线：`499f3923aff56f511144af208f12c554eb180c10`。
- 上次 fetch 时远端 `origin/main`：`a5455d35763187aa03678d8ecc18a5f94b5fcb8e`，是本地基线之后的一条 README 文档提交。合并时必须保留该提交和其中仍有效的说明；不要 force-push。
- 源码、配置和多份文档已修改，但尚未提交、合并或推送。继续前先运行 `git status` 与 `git diff`，不要 reset、checkout 覆盖或丢弃现有工作。
- `module.prop` 已设为 `version=v1.3.0`、`versionCode=13000`。不存在 `ziyu-v1.3.0` tag 的结论来自上次检查；推送前重新确认。

## 已完成的整理

- 当前挂载、用户指南、模块更新、软重启、热切换、诊断、ColorOS、测试矩阵及发布流程文档已按源码更新；新增 `docs/README.md` 作为当前文档索引，并将旧设计方案标为历史候选。
- 现行行为文档说明四种用户挂载选择，不允许失败时静默换模式；区分挂载、字体路由、进程加载证据；解释仅受支持的 KernelSU / SukiSU Ultra 软重启和有限条件热切换。
- 发布说明归纳了挂载选择与升级保留、部分应用告警、分区兼容报告、桥接路径校验、OverlayFS/事务修复、字体处理和 App 调用优化。真机验证矩阵仍真实标为待测。
- `config/stable_release_authorization.conf` 精确授权 `v1.3.0` 在矩阵待测时进入正式发布门禁。该例外是版本绑定的一次性授权，不得改成全局豁免，也不得把矩阵改成已通过。
- `config/stable_version_policy.json` 仍保留已发布 stable `v1.2.20`，并将 `v1.3.0` 设为下一 stable；成功发布后由自动 metadata sync 流程更新。
- 历史 CHANGELOG 中 v1.2.26 的 NoMount 推荐已注明为当时建议、现已过时。带日期的诊断/研究资料应保留为历史记录，不能改写成当前真机验收结论。
- `git diff --check` 已无空白错误；仅有 Windows 行尾提示。

## 不要提交的本机文件

工作树中存在此前设备只读诊断和临时脚本：未跟踪目录 `diagnostics/`、`diag3_tmp.sh`、`diag4_tmp.sh`、`diag5_tmp.sh`、`diag6_tmp.sh`。不要添加到 Git 或发布包；诊断材料可能含设备标识或本机数据，临时脚本也不是发布源码。

另有未跟踪的 `更新日志/RELEASE_NOTES_ziyu-v1.2.22.md` 至 `.v1.2.51.md`（部分版本缺号）。这些不是当前 v1.3.0 交付必需文件，尚未逐份核实来源、准确性和隐私。不要用 `git add -A` 一并提交；如认为需要纳入历史，先逐份审阅、去除敏感信息并确认与对应版本一致。已跟踪的 `CHANGELOG.md` 及 v1.3.0 发布说明足以描述本次发布。

本机正式签名材料位于仓库之外；不要复制、输出或提交密钥、DPAPI 文件、`transfer-once.json`、密码或其路径内容。云端正式发布应使用 GitHub Actions 已配置的正式签名 Secrets。

## 继续步骤

1. 重新 fetch `origin/main` 和 tags，确认 main 是否又前进、`ziyu-v1.3.0` tag / Release 是否已存在；若存在，先停止创建同名不可变发布并报告状态。
2. 检查所有已跟踪源码变更和计划提交的新增运行文件，核对 `scripts/module_payload_manifest.txt` 覆盖本次新增运行文件。排除上节的诊断/临时文件与未经审阅的历史发布说明。确认没有密钥或设备私密材料。
3. 核对 `module.prop`、版本说明、发布说明、稳定版策略和版本专用授权均指向 `v1.3.0`；不要把 `update.json` 的已发布版本提前手工改成未发布版本。
4. 使用常规提交记录当前完整源码/文档改动。将最新 `origin/main` 合入当前提交（或用等价的非破坏方式保留双方历史），解决 README 冲突时保留当前更新版并保留远端 TTC 与 `/my_region/fonts` 信息。禁止 force-push。
5. 推送合并后的提交到 `origin main`。`.github/workflows/release.yml` 监听 main 上的版本/发布文件变更，成功后会运行源码检查、Android Lint/单元测试、固定证书签名、模块 ZIP/APK 一致性和发布门禁，再创建 `ziyu-v1.3.0` 正式 GitHub Release。不要手工 dispatch 该工作流；它需要由 main 推送创建 tag。
6. 等待并检查 GitHub Actions 结果。失败时读取失败步骤并修复可修复问题，再提交推送修复；不要把本地构建成功说成正式发布成功。确认正式 Release 已创建且含预期 ZIP/SHA256；确认 metadata sync 完成。
7. 按仓库 `AGENTS.md` 生成当前源码的安装包交付：最新 APK 和内置同一 APK 的模块 ZIP 放入新的时间戳目录，保留既有交付物。核对包名、版本号、APK 哈希与 ZIP 内嵌 APK 哈希、模块清单和 ZIP 完整性。签名配置不可用时只能生成并标注 Debug 包，不能假称正式包。
8. 最终回复列出 main 提交、Release 链接、时间戳本地 APK/ZIP 链接、构建与自动化检查范围，并明确四组设备矩阵仍待真机验证、没有刷机/安装验收。

## 自动发布行为和验证边界

正式发布工作流在成功后会按 `module.prop` 自动创建 `ziyu-v1.3.0` tag 和 stable Release；Release 资产以工作流实际输出为准。该工作流会读取版本专用待测授权并如实警告设备矩阵尚未完成。真机矩阵 `docs/device_validation.json` 当前四组均为 `pending`，不能将历史用户日志、脚本输出或 CI 通过改写成 v1.3.0 全机型验收。

工作中途曾启动本机 `:app:assembleRelease`，但工具调用被会话中断，尚无该命令的最终输出。本机检查时存在 Java/Gradle 进程且没有检测到新 APK 或模块 ZIP，故构建是否完成未知。若继续使用同一本机环境，先检查这些进程和输出文件，避免重复 Gradle 构建；云端可按 GitHub Actions 结果和远端正式产物验证。
