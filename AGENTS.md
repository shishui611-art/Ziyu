# 字域项目协作规范

## 源码修改与本地交付

- `module.prop` 是模块、App 与制品的唯一版本来源；模块 ZIP 必须内置本次构建的同版本 APK。
- 每次完成源码修改后，默认构建可安装 APK 和包含同一 APK 的模块 ZIP，写入新的 `dist/<北京时间时间戳>-<用途>/` 目录并保留旧交付物。
- 优先使用已有正式签名配置；未提供时构建 Debug APK 并明确标注。不得创建、替换或提交正式签名密钥，不得把未签名 APK 当作正式版。
- 打包后核对 applicationId、版本名/code、签名证书、模块清单、ZIP 完整性，以及独立 APK 与内置 APK 的 SHA-256 一致。
- 不因打包自动安装 APK、刷入模块或发布到网络；这些动作需由用户明确要求。
- 不自行添加或运行测试，除非用户要求，或用户明确要求的发布工作流包含其规定的自动化检查。分别报告构建、包完整性检查和手机验收范围。

## 正式发布

- 发布版本必须同步更新 `module.prop`、`config/version_notes.conf`、`更新日志/CHANGELOG.md` 和版本对应的 `更新日志/RELEASE_NOTES_ziyu-vX.Y.Z.md`。
- Release notes 应总结新增、修复和优化，并基于上一个正式版本对比；历史设备观察不能写成当前版本真机验收。
- 用户明确授权发布后，按 `docs/RELEASING.md` 推送 `main` 并等待 Release workflow；正式版本使用固定签名，ZIP 中 App 与已验证签名构建产物字节一致。
- 设备矩阵只依据实际验收证据更新；待测时保留待测状态。正式发布的版本范围授权只允许通过 `config/stable_release_authorization.conf` 一次性绑定具体版本。

## 实现约束

- 模块 Shell 需兼容 Android `/system/bin/sh` 和设备自带 BusyBox/Toybox，不能假设桌面 GNU 工具齐备。
- Root 命令应校验输入与路径，记录事务状态，并在失败时验证回滚结果。
- 字体只写入字域管理的 systemless 负载；不得直接修改只读系统分区或覆盖整份原厂字体 XML。
- 挂载模式须遵循用户所选项。外部提供者、自挂载、OverlayFS 和 Magic Mount 的失败不能悄悄转换为另一模式。
- 报告字体应用状态时区分挂载、路由、部分槽位 warning、进程缓存和整体失败。
- 字体处理或缓存优化必须保留源变化检查、输出校验、挂载验证和事务回滚条件。
- 新增第三方代码或运行文件时登记来源/许可证，并更新 `THIRD_PARTY_NOTICES.md`、`licenses/` 与模块打包清单。

## 开发环境

- Android 构建使用 JDK 17、Android SDK API 37 与 Gradle 9.5。
- 模块包构建需提供与仓库脚本相符的 Python/ARM64 字体运行时环境；新增运行文件必须进入 `scripts/module_payload_manifest.txt`。
- 正式签名只经受控环境变量提供；不要把密钥、密码、解密材料、本机凭据路径或设备诊断导出提交到仓库。
