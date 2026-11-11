# 挂载失败诊断

本文按当前源码说明如何收集和阅读证据。请优先使用 App 导出的诊断报告；不要公开粘贴包含设备标识、启动 ID、私有路径或用户字体名称的原始日志。

## 先确认 App 选择

在首页“字体挂载方式”查看保存的偏好，再打开“挂载详情”。选择变更只在重启后验证。一次启动的状态以与当前 boot ID 匹配的 `config/mount-backend.conf` 为准；过期状态或 `/data/adb` 目录存在不能证明当前挂载成功。

可关注字段：

| 字段 | 说明 |
| --- | --- |
| `preferred_backend` | 用户选择的模式：`auto`、`self_mount`、`magic` 或 `overlayfs` |
| `selected_backend` | 当前启动的调度分支：外部提供者、字域自挂载或无挂载 |
| `active_backend` | 本次启动实际激活的后端；`none` 表示尚无可确认活动后端 |
| `provider_id` / `meta_version` | 当前启动检测到的元模块/管理器提供者身份与版本 |
| `verification` | 后端验证状态：成功、部分、失败、待验证等 |
| `last_error` / `mount_warning` | 失败原因或未应用目标列表 |

## 按用户模式分析

NoMount 的 nomount_kernel_usable 需要区分探测时机：v1.3.7 的早期扫描记录 unknown，post-mount/service 的实际 nm version 探测才写 1 或 0。旧版启动记录中的 0 可能是早期默认值，不能据此认定内核不支持。驱动加载成功仍不等于字体规则生效，最终以 PID 1 路由为准。post-mount 未就绪只转交 service 复核，service 确认失败才走自动自挂载回退。

- **自动挂载 (`auto`)**：优先使用当前启动可识别的外部提供者。提供者缺失/状态未知、元模块排除字域、外部负载发布/回执/字域自有排除标记处理异常或 PID 1 路由校验失败时，会尝试字域自挂载，并再次验证字体文件哈希和路由；回退也失败时检查 `fallback_reason`、`last_error` 及回滚记录。下一次启动会清理上一启动中归字域所有的排除标记，再尝试识别外部提供者。用户在字域模块目录设置的外部 `skip_mount`、停用或删除标记仍会停止挂载。
- **字域自挂载 (`self_mount`)**：跳过元模块；OverlayFS 失败且属于允许的内核挂载拒绝时可用逐文件 bind，别名冲突时尝试目录镜像 bind。日志若显示复制、SELinux 标签、空间或 lower 准备失败，不能归因成“内核不支持 OverlayFS”。
- **Magic Mount (`magic`)**：由字域执行 bind/目录镜像策略，失败不切换成外部提供者或另一偏好。
- **OverlayFS (`overlayfs`)**：只使用 OverlayFS；设备不支持时应显示明确失败，不会静默回退 bind。

检查 `logs/mount-backend.log` 中用户偏好、提供者检测、选择结果、阶段及原因，再对照 `logs/mount-diagnostics.log` 的命令、返回码、挂载参数、文件系统、空间、SELinux 与内核快照。若日志没有目标命令/内核错误，不能猜测为某个内核参数问题。

## 区分挂载失败和字体路径警告

后端挂载、字体文件内容、字体 XML/原厂链接路由和 Android 进程映射是不同层次：

1. 先确认当前启动后端验证是否为 `passed` 或 `partial`。
2. 查看 `config/mount-backend-verification.json`、`config/font-apply-result.conf` 和字体加载报告中已确认、未确认路径及原因。
3. `partial` 表示至少部分目标有验证证据；未确认目标保留原厂字体并显示 warning。它不等同于完全挂载失败。
4. `failed` 包含没有可证明成功目标、源负载错误、配置不完整或安全回滚未确认等情况。忽略 warning 只收起提示，不改变实际状态或原始日志。
5. 路径确认成功不代表 SystemUI/应用进程已刷新缓存；必要时单独检查进程映射并由用户确认支持的刷新方式。

ColorOS 的 SysFont 桥接链接只读取受限位置的链接元数据；确认结果要求链接链回到已知字体分区槽、角色匹配且读取前后身份稳定。不能仅凭文件名相同或字体哈希相同确认加载路径。

## 失败后的回滚

检查 `config/self-mount.conf`、`config/self-mount-required.conf`、`/data/adb/luoshu/self-mount/mounts.list` 和 `logs/self-mount.log`。每个字域挂载都应有自身账本记录。若错误中含 `rollback-failed`、`cleanup`、`residual` 或 `unconfirmed`，先停止反复提交，保留证据并按 Root 管理器的停用/恢复路径操作；不要手工全局卸载或清理其他模块目录。

### Mountify 与提供者重扫

Mountify v2.0.4 的扫描脚本使用 `/dev/mountify_single_instance` 阻止重复扫描；其 `boot-completed.sh` 会释放标记并删除 /dev 日志，但不卸载字体 OverlayFS。新负载部署成功也不能证明现有提供者层已被替换，必须复核 PID 1 的实际文件。

完整重启后的启动阶段，若 PID 1 路由验证失败且提供者为 Mountify，字域会按 PID 1 挂载来源建立只读清理计划：先确认提供者身份、安装脚本协议、PID 1 命名空间、lowerdir 来源和挂载父子关系，只对确认属于 Mountify 的 OverlayFS 层按子到父顺序逐层卸载，核对挂载 ID 已消失，再释放已确认的扫描标记。随后由 Root 管理器按原顺序继续启动阶段并重新扫描其启用模块。字域不在普通查询或路由校验失败时全局卸载共享层，不调用 metamount.sh、不改提供者启动计数，也不改用户所选模式。

路径越界、不识别的文件系统、其他来源的子挂载、命名空间不符或卸载残留都会中止激活并保留队列。启动计数未复位时保留 Mountify 的防启动循环保护。模块更新后由新的 post-fs-data 再复核一次。`config/provider-reload-check.conf` 和 `.ziyu-state/provider-reload.started` 记录实查结果及开始时的清理计划。删除 /dev 日志后仍通过 PID 1 挂载来源建立计划，不依赖已被提供者删掉的日志。

`external-route-verification-failed` 仍须对照发布回执和清单：发布目录校验通过但 PID 1 哈希不同，说明提供者没有呈现这一批文件；`provider-published-payload-invalid` 表示发布树本身不一致。不能仅凭版本号认定具体设备一定命中了扫描锁问题。

偏好改成自挂载或恢复默认字体时，同样在完整重启后的启动阶段处理旧提供者层；恢复默认时先清理旧层，再发布空字体负载，避免露出旧字体。不要静默叠加另一模式；源码已实现适配不代表已完成对应手机验收。

协议依据：[Mountify 扫描脚本](https://github.com/backslashxx/mountify/blob/master/module/post-fs-data.sh)。报告中的 `activeFont`、`bootId`、`checkedAt` 和 `payloadManifestSha256` 用于区分同一内核启动中的不同字体批次。

## 导出给维护者

### 原厂快照与 Vivo 部分界面未变化

v1.3.3 在 post-fs-data 中、发布下一套字体前，把已验证的 ROM/lower/mirror 字体文件复制到模块的 `config/stock-font-snapshot/`。不为读取快照新建挂载，也不把活动覆盖层中的可读字体当作原厂。清单与系统构建身份绑定，编译读取时验证内容 SHA-256；同一系统和同一目标集合复用快照，升级迁移保留。单文件上限 128 MiB、快照内容上限 384 MiB；不可验证或超出限制的目标保留缺失记录，不宣称捕获完整。

首次刷入后需要进入一次原厂目录可验证的启动阶段再捕获快照。`logs/stock-font-snapshot.log` 中 `complete`/`partial`/`deferred` 与清单里的 `missing` 用于判断实际捕获结果。通用编译的其他格式、字重、轴或样式限制仍独立存在，不能由快照存在推断整份编译成功。

Vivo 兼容生成不再只使用三个固定中文名称。系统属性/品牌或可信库存识别为 OriginOS 后，按清单中度量和字符覆盖有效的普通 UI 槽位生成；保留 Emoji、图标、等宽、斜体、时钟和集合容器的保护。按字重实例化，变量容器沿用既有保留策略，相同来源/角色/度量的输出在单次生成中复用。

分类报告递归查看 Vivo/iQOO、OPPO/一加/真我的专有目录、字体子目录、嵌套 XML 和 PID 1 视图。模块库存的候选探针同样记录 OEM 目录；位于现有物理运行契约之外的目录只作为候选证据，不直接部署。新增目录发现与实际可部署槽位仍需分别核对。

1. 在 App“字体刷写 → 日志”导出 `Ziyu-diagnostic-<时间>.txt`。
2. 如果要调查机型分区，在模块 `common/font_partition_compat_report.sh` 运行，只读日志会写在脚本同目录。
3. 说明选用模式、Root 管理器版本、Android/ROM 版本、重启方式、预期效果和实际现象。
4. 分享前检查并遮盖个人字体名、启动 ID、设备标识和私有路径。

相关说明：[挂载流程](MOUNT_FLOW.md)、[使用教程](USER_GUIDE.md)、[设备验证矩阵](TEST_MATRIX.md)。
