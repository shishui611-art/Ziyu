# 发布字域

`module.prop` 是模块、原生 App 与产物名称的唯一版本源。修改版本后，验证工作流会编译原生 App、运行模块检查并生成测试模块；它不会自动创建测试版 Release。

## 字域 fork 正式版本编号

字域 fork 使用独立的 `ziyu-v*` 标签。当前 1.1 系列每次交付新包都递增修订号：`1.1.0 → 1.1.1 → 1.1.2 → …`。正式版、测试版和 Debug 版都遵循这条规则；同一源码版本的模块与 App 保持一致，Debug App 仅在显示版本后增加 `-debug`，例如模块 `v1.1.1` 对应 App `1.1.1` 或 `1.1.1-debug`。

`module.prop` 写入 `versionSeries=ziyu`。字域的模块与 App 共用升级编号：`major*10000 + minor*1000 + patch`。`1.1.0 = 11000`，`1.1.1 = 11001`，`1.1.2 = 11002`；Debug 与正式 App 同一源码版本的编号相同。次版本小于 10，修订号小于 1000。LuoShu 上游历史系列的编号不参与字域的新包编号。

`release_version_policy.py` 统一校验编号和系列。每次交付先同步 `module.prop`、`config/version_notes.conf` 与当前发布说明，再重新编译 App。Gradle 从 `module.prop` 读取版本；测试打包脚本还会核对构建元数据中的实际 APK 版本与文件内容，拒绝将旧 APK 标为新版本。模块 ID、数据路径和同一 App 包名的签名不得随修订号改变。

字域首发标签为 **ziyu-v1.0.0**，发布说明保存在 `更新日志/RELEASE_NOTES_ziyu-v1.0.0.md`；Release 只发布可刷入的模块 ZIP 与校验文件，App 随 ZIP 内置。GitHub 正式发布显式标为 Latest，在线更新依据递增 versionCode，而不是比较不同项目的显示版本字符串。

`config/stable_version_policy.json` 的 `currentStable` 与线上更新清单只在正式发布完成后推进；本地 Debug 构建不宣称已发布。只有明确发布请求才创建 Release，不删除旧版本或标签。

## 首次配置固定 App 签名

在仓库 `Settings → Secrets and variables → Actions` 添加：

- `LUOSHU_KEYSTORE_BASE64`：JKS/PKCS12 文件的 Base64 内容；
- `LUOSHU_KEYSTORE_PASSWORD`：密钥库密码；
- `LUOSHU_KEY_ALIAS`：签名别名；
- `LUOSHU_KEY_PASSWORD`：签名私钥密码。

密钥库和密码不可提交到仓库。本 fork 使用独立的字域签名密钥，正式 App 必须长期使用同一把密钥，否则 Android 会拒绝覆盖安装。证书 SHA-256 固定为 `ebfd6167fe727ab3ada7dd5cf44dc3d3c345812fab4f6d5699f69ea572688468`；发布工作流会精确校验此指纹并要求只有一个 signer。该签名与 LuoShu 上游不同，已安装的上游 App 不能直接覆盖安装此 fork；安装前需卸载上游 App，卸载可能清除 App 本地数据。必须备份原始签名库和密码；遗失后无法为现有字域安装续签更新。

## 候选版本门禁

1. 基于最后一个干净候选基线建立独立分支，不从已废弃实验分支继续打补丁。
2. 验证源码、角色覆盖、原生 App 编译和单元测试、单模块包构建及成品检查。
3. 执行复合字体烟雾测试，生成可解压的模块 ZIP；内置 App 与已签名构建产物字节一致，不包含 webroot。
4. 按 `docs/TEST_MATRIX.md` 完成真机回归，将时间和证据写入 `docs/device_validation.json`；未验证项目保持待测。
5. 出现黑屏、SystemUI 重启、批量闪退或乱码，停止发布并恢复可用模块包。

## 发布步骤

1. 整理发布分支，使用上述正式编号，确认 `更新日志/` 内存在同名发布说明。
2. 稳定版不含 Alpha、Beta、RC，不含 prerelease 标记；提高 versionCode，保持 module.prop 为唯一版本源。
3. 默认要求最低真机矩阵有证据。维护者明确授权某一版本在矩阵仍待测时正式发布，可以使用既有 `config/stable_release_authorization.conf`，必须绑定该版本，不得写成长期通用豁免，也不得把待测记录改成通过。
4. 合并 main 后，Publish signed release 重新运行源码检查、App lint / 单元测试、固定签名、证书、单模块成品和发布门禁，再创建 GitHub Release。
5. 已有 Tag 或 Release 不覆盖；修订内容使用新版本。预发行必须有 prerelease 标记；正式版本更新正式和预览通道。
6. 检查 Release 仅包含模块 ZIP 与 ZIP 的 SHA-256，ZIP 内置 APK 与签名构建产物一致；再核对在线更新元数据与真实下载地址。

正式版和 Debug 版 Release 均只发布 `Ziyu-<版本>.zip` 与对应 `.sha256` 文件；APK 只构建并嵌入模块，不作为单独的 Release 附件发布。模块必须内置相同签名 App。

## v4.4.4 一次性版本清理

本次授权时间为 2026-09-13T02:32:33Z。发布前最近六项是 v4.3.2-Beta1、v4.3.1-Beta1、v4.3.0、v4.2.0、v4.1.0、v4.0.0；其中 **v4.0.0 永不进入本次删除名单**。前五项和该授权时间之前已发布的全部预发行版，在 v4.4.4 安装包与两条更新元数据就绪后删除。

清理脚本固定本次范围，重跑不得向更早正式版滑动；不删除标签、分支、源码历史或草稿，不删除之后新发布的版本。保留清理审计报告。此删除授权仅用于本次，不自动推广为今后无限重复的清理任务；正式版本编号规则持续生效。
