# FinaSU / Folk Mount 适配记录

## 上游依据

2026-11-04 读取官方仓库 `LyraVoid/FinaSU`，固定提交 `a839ce2d742f0f1450a3aad20bdc14bc060da840`。以下是行为协议依据，未复制上游实现或打包上游二进制：

- [Folk Mount 文档](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/website/docs/builtin/folk-mount.md)
- [模块开发](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/website/docs/developer/module-development.md)
- [内置 NoMount](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/website/docs/builtin/nomount.md)
- [Root 身份与 CLI](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/su.rs)、[CLI 能力](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/cli.rs)
- [启动顺序](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/init_event.rs)
- [挂载执行与启动记录](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/magic_mount.rs)
- [NoMount 负载路由](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/nomount_plan.rs)、[外部驱动让位规则](https://github.com/LyraVoid/FinaSU/blob/a839ce2d742f0f1450a3aad20bdc14bc060da840/userspace/ksud/src/nomount.rs)

## 实际缺口与修改

FinaSU 的 `su -v` 仍带 `:KernelSU`，版本名来自 Git tag，并不保证带 FinaSU 字样。旧检测因此可能给出 KernelSU 身份；没有活动元模块时进入“外部后端缺失”的自挂载流程，绕过内置引擎。

1. 仅在 KernelSU 家族身份路径中，对已安装的标准 ksud 路径执行有 2 秒上限的 `--help` 查询，以明确的 Folk Mount 能力描述识别 FinaSU；不根据共享目录或固定管理器包名猜身份。该身份可复用本次启动的字域记录。快速 App 握手只读已存在记录，不增加 Python、驱动探测或常驻进程。
2. 启动前读取 `mount_mode`，区分 auto/builtin/metamodule/nomount/off。auto 使用启用的活动元模块，否则选择内置；builtin 忽略元模块；nomount 遇到上游定义的外部驱动所有者时让位；metamodule 不假装内置可用；off 不由自动策略绕过。
3. 继续使用既有事务发布器：真实 system 目录、规则文件和不可变字体硬链接，不能使用 system 根符号链接。保留摘要、源校验、发布 journal、回滚和 skip 标记归属校验；不重写原厂 XML，也不新增字体目录镜像。
4. 由 FinaSU 原生启动流程扫描并挂载。post-mount 后只读其 `mount_runtime`，单次打开读取各字段，校验 boot_id 与结果，再根据实际 provider 区分 Folk Mount、内置 NoMount 或元模块。记录 configured 模式只用于诊断，不用于冒充本次生效引擎。
5. FinaSU 自身的 NoMount → builtin 回退、驱动让位与启动保护结果按真实记录处理。先提交待验证的实际后端，再做原有 PID 1 字节、XML 与中英数字字体路由校验，全部通过才写成功。内置 VFS 不要求不存在的 mountinfo 条目，但仍保留文件内容与路由校验；Universal 路径还核对 FinaSU 本次 VFS 记录。
6. FinaSU 自动模式的后端不可用、关闭或失败不转到字域自挂载，不在状态读取或失败处理中全局卸载、加载驱动、重新执行提供者。用户在字域主动选择的自挂载模式保留，并统一在 post-mount 阶段执行。
7. App 的模块更新安装器允许 FinaSU 调用现有 ksud module install；安装、升级仍依赖用户原有操作，不自动刷入。本次新增自有运行文件 `common/finasu_mount.sh` 已加入打包清单。

上游 `mount_plan.rs` 的 builtin 分区重映射列表只有 vendor、system_ext、product、odm、oem（另含 system 本身）；不能把 my_product 等独立分区伪装为 system 子目录。适配器按实际负载检查其分区范围，不能支持时保留源负载并说明具体分区，启动发布失败时只恢复字域自有 skip 标记，避免上次公共树继续被扫描；不手工给提供者加挂载。内置 NoMount 的 nested 重映射范围不同，按官方 `nomount_plan.rs` 单独判断。由 NoMount 回退到 builtin 后仍会检查实际后端能力，不能把回退写成完整应用成功。

## 故障记录卡片

复用 `ZiyuCard`、页面调色板、卡片透明度及布局间距；标题、状态与三个操作位于同一卡片。说明和按钮缩短，FlowRow 允许窄屏/大字号换行。保留真实错误、采集上限、导出与分享前的隐私确认，未改变采集范围或生命周期。

## 验证边界

- 本轮为源码检查、Shell/Python 语法编译、Android 编译/vital lint 与 APK/ZIP 完整性检查；不是运行测试套件。
- FinaSU 设备尚未验收，能力描述变动、旧版协议、启动阶段预算、引擎回退、App Profile/VFS 例外、源字体缺字、Universal 动态目标及其他提供者组合均需实机验证。
- FinaSU 临时 Root 软重启的旧挂载清理尚未接入，不开放新的 App 软重启入口，不宣称支持。原生提供者重载不由字域手工替代。
- 无实际耗时与温度对照，本轮减少重复文件读取不作为实测提速结论。
- 默认字重修正沿用 `PREVIEW_DEFAULT_WEIGHT_v1.4.94.md`；不改写源字体、系统字重或连续变化数据。
- 固定字库基准为完整 `coolapk-53620096-r1`，SHA-256 `e45b90d6244268251d69d9b95d0ae5f36df4b0174b8b6dce280236be2db30bd2`。本轮未运行字库覆盖、实际字体挂载、显示、连续字重与裁切验收。
- 未安装、刷入、重启、上传或发布；NoMount Suite 的 App JSON 故障未确认修复，RootService/Binder 迁移未实施。
