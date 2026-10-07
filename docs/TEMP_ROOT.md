# KernelSU 临时 Root 与软重启

## 识别方式

检测到 KernelSU 本身不代表它是临时 Root。官方 `late-load` 提供 `KSU_LATE_LOAD=1` / `KSU_RUNTIME_MODE=late-load`，字域在 `late-load.sh` 记录当前内核 boot ID，自动启用临时 Root 提示。其他 KSU 用户如果只能软重启，可以在 App「设置 → 安全与维护 → KernelSU 软重启」手动开启。普通 KSU 不自动改用软重启。

## 字体生效

1. App 仍按原有流程生成下一次加载的私有字体负载，不直接改写正在挂载的字体源。
2. KernelSU `late-load` 在元模块挂载前运行 `late-load.sh`，激活待应用负载，然后用现有后端选择器发布字体。随后 `post-mount.sh` 与 `service.sh` 按原规则挂载和验证。
3. 对支持 `ksud soft-reboot` 的 KSU，用户在管理器内执行软重启。KernelSU 会重新运行模块的 `post-fs-data.sh`、元模块挂载及 `post-mount.sh`。字域的 `emulated-soft-reboot.sh` 先回收自身挂载并撤销本次内核启动的旧后端选择，下一阶段重新选择和验证。回收失败则阻止激活新负载。
4. 回到 App 查看「挂载详情」。只有本次会话路由和字体加载验证成功才算生效；“模块安装成功”与“软重启已请求”都不算挂载成功。

`ksud soft-reboot` 是否存在取决于 KernelSU 版本和分支。若管理器没有软重启功能，本版不会自动改用完整重启。软重启前若仍发现旧模块挂载，或 NoMount 的 VFS 规则无法确认清理，则保留待应用负载并报告失败，避免误报生效。首次刷入模块后，模块脚本必须先由 KernelSU 加载一次。普通 Magisk、APatch 与常规完整开机的挂载选择不因该设置改变。
