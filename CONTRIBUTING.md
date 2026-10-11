# 贡献指南

感谢参与字域开发。当前用户界面是原生 Android App；模块中不使用 WebUI。

## 提交前

- 基于最新 `main` 创建工作分支。
- 一个 PR 尽量只处理一类问题，并在 PR 中说明验证范围。
- Shell 脚本必须兼容 Android `/system/bin/sh`；不要依赖设备未必具备的 GNU 参数。
- 不提交商业字体、ROM 提取字体、用户诊断报告、设备备份、临时脚本或其他无权公开的文件。
- 不替换 Emoji、图标、符号字体，不直接写入 `/system`，不覆盖完整 `fonts.xml` / `font_fallback.xml`。
- 不在刷写、`post-fs-data` 或开机关键阶段执行大型字体生成任务。
- 新增 Root 命令时校验参数与路径，并考虑事务回滚和真实状态验证。
- 挂载流程必须遵循用户在 App 中选的模式；未确认的外部提供者不能当作可用，失败不能暗中切换模式。
- 修改 App 与模块交互时，区分 Root 授权、当前 boot 后端、挂载实现、字体路径验证、部分 warning 与进程缓存。

## 本地构建和检查

环境要求：JDK 17、Android SDK API 37、Gradle 9.5，以及模块打包所需的 Python/字体运行时。

```sh
gradle --no-daemon :app:assembleDebug
sh ./scripts/prepare_composite_runtime.sh
sh ./scripts/check.sh
```

需要模块 ZIP 时，应将本次构建 APK 显式传给 `scripts/build.sh`，并核对 APK 包名/版本、ZIP 文件清单、SHA-256 和 ZIP 内 APK 字节一致性。正式发布使用固定签名的 Release workflow。

## 字体功能回归

涉及字体或挂载时，回归应覆盖适用的字体类型与挂载路径：

- TrueType `glyf`、CFF/CFF2、TTC 多 face 和可变字体；
- 中文、英文、数字角色覆盖、实际字重和特殊字体保护；
- 首次生成、缓存命中、缓存失效、源文件修改与失败事务；
- 外部提供者、字域自挂载、Magic Mount 与 OverlayFS 的选择、验证和回滚；
- ColorOS / HyperOS 字体槽、部分映射 warning、无目标成功时的失败判定；
- 符合条件的热切换、软重启请求、完整重启路径、恢复原厂和卸载清理。

字体负载必须先在暂存目录准备并验证，再替换活动负载。测试结果应记录系统、Root 管理器、选用模式、版本和证据；没有真机证据的项保持待测。

## 文档与许可证

新增或改变用户流程时同步更新 README/使用教程和相应的挂载、更新、诊断说明。新增第三方代码或运行文件时说明来源和版本，在 `THIRD_PARTY_NOTICES.md` 登记完整许可证，并更新模块打包清单。

提交内容按 **GPL-3.0-only** 发布。不要提交许可证不兼容的代码或未经授权的字体。

## PR 描述

说明改动目的、主要实现、风险和回滚方法；区分静态检查、自动化工作流与真机验收，并注明是否改变模块路径、缓存或版本号。
