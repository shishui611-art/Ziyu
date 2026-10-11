# 字域发布流程

## 版本和制品

`module.prop` 是模块、App 与发布文件名的唯一版本来源。字域 versionCode 规则为 `major*10000 + minor*1000 + patch`；v1.3.0 使用 versionCode 13000，正式 tag 为 `ziyu-v1.3.0`。模块 ZIP 内必须包含与本次 App 构建字节相同的 APK。

正式 Release 仅发布：

```text
Ziyu-vX.Y.Z.zip
Ziyu-vX.Y.Z.zip.sha256
```

APK 随模块 ZIP 内置，不作为正式 Release 单独附件。用户下载模块 ZIP 后由现有安装流程更新 App；单独安装 APK 不更新字体处理或挂载运行时。

## 签名

正式发布必须使用仓库已有固定签名。CI 从受保护的 Actions Secrets 注入签名数据，并校验证书指纹；不得把密钥、密码、导出数据或本机凭据路径写入仓库。签名配置缺失、证书指纹不符、APK 包名不符或 ZIP 内 APK 与 Release 构建产物不一致均阻断发布，不替换正式密钥、不回退旧 ZIP。

正式 applicationId 为 `io.github.shishui611_art.ziyu`。提供正式签名配置的 Debug 构建可用同一包名和证书覆盖正式 App，显示版本带 `-debug`；无签名配置的本地 Debug 使用 `io.github.shishui611_art.ziyu.debug`，不能覆盖正式版。独立 `.debug` 旧包不自动删除。

## 自动发布

正式稳定发布通过推送到 `main` 的版本提交启动 `.github/workflows/release.yml`。工作流从 `module.prop` 取版本；正式 tag 不存在时，工作流在源码检查、App Lint/单元测试、固定签名、ZIP/哈希和发布就绪检查完成后创建不可变 tag 并发布 Release。不要对尚不存在的标签手动 dispatch；`workflow_dispatch` 仅用于工作流要求的已存在 immutable tag。

当前设备矩阵见 [`TEST_MATRIX.md`](TEST_MATRIX.md)。矩阵没有记录的机型必须保留“待测”。维护者明确授权特定稳定版本在矩阵待测时发布，可将 `config/stable_release_authorization.conf` 绑定到该版本，填写 `scope=stable-release` 与 `allowPendingDeviceMatrix=true`；只能是单版本授权，不能修改矩阵伪造通过。就绪报告会标出待测 warning。

发布成功后 `sync-update-metadata.yml` 根据实际 GitHub Release 同步更新清单、备用预发行通道和 `config/stable_version_policy.json`。发布前不得预先把 `update.json` 指向未发布版本。

## 每次发布前核对

1. 同步 `module.prop`、`config/version_notes.conf`、主变更日志与 `更新日志/RELEASE_NOTES_ziyu-vX.Y.Z.md`。
2. 发布说明分列新增、修复和优化，并按上一个稳定版本说明用户可见变化；不要将历史设备观察描述为本次真机验收。
3. 在目标发布分支检查工作树和 diff；不提交诊断导出、设备标识、scratch 文件、签名材料或临时构建产物。
4. 推送 `main` 后等待自动 Release workflow 结束，检查其源提交、tag、Release 附件、ZIP SHA-256 与内置 APK 一致性。
5. 确认后续更新元数据 workflow 完成，且正式更新清单只指向已成功发布的正式 Release。

自动编译和 ZIP 完整性不能替代真机测试。发布回复应分别说明源码/CI、签名/打包、设备矩阵和在线更新元数据状态。
