# 挂载失败诊断

本文按当前 v1.3.0 源码说明如何收集和阅读证据。请优先使用 App 导出的诊断报告；不要公开粘贴包含设备标识、启动 ID、私有路径或用户字体名称的原始日志。

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

- **元模块自动挂载 (`auto`)**：只有提供者被当前启动检测为 `available` 才交给它发布字体负载。`absent`、`unknown`、`excluded` 或路由验证失败会记录失败；不会自动改为自挂载。确认提供者、扫描目录和管理器日志后再判断。
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

软重启另检查 `logs/soft-reboot.log` 和 `config/temporary-root-soft-reboot.conf`。请求成功只表示 `ksud` 接受请求，不代表重新加载、后端挂载或字体效果已经通过。

## 导出给维护者

1. 在 App“字体刷写 → 日志”导出 `Ziyu-diagnostic-<时间>.txt`。
2. 如果要调查机型分区，在模块 `common/font_partition_compat_report.sh` 运行，只读日志会写在脚本同目录。
3. 说明选用模式、Root 管理器版本、Android/ROM 版本、重启方式、预期效果和实际现象。
4. 分享前检查并遮盖个人字体名、启动 ID、设备标识和私有路径。

相关说明：[挂载流程](MOUNT_FLOW.md)、[使用教程](USER_GUIDE.md)、[设备验证矩阵](TEST_MATRIX.md)。
