# 临时 Root 重启选择与 PPID 核验

字体准备完成且需要重启时，临时 Root 模式会显示底部选择框，提供普通重启、KernelSU 内核软重启和稍后操作。普通重启与内核软重启都会保留用户确认步骤。

内核软重启由 KernelSU 管理模块重载。字域先等待 Android 的真实 zygote 和 system_server 停止，完成字体挂载准备，再在服务重启前执行有界 PID 分配回绕。PID 工具恢复原 `pid_max` 并回报 `wrapped` 或 `already-low` 后，启动流程继续。

v1.5.60 修正了清理阶段的资料传递。系统启动完成后，后台核验通过 app_process 可执行文件识别真实 zygote，成功条件为 PID 回绕成功、当前 zygote PID 小于 5000。旧 PID 保留作诊断资料，允许回绕后复用相同编号。应用进程由新 zygote 派生，其 PPID 指向这个 zygote PID。工具状态、失败原因和 PID 值保存在 `config/temporary-root-soft-reboot.conf`、`config/pid-wrap-last.conf` 与 `logs/soft-reboot.log`。首页刷新也会重新核对本次启动尚待核对或失败的记录。

PID 调整和 PPID 核验属于 KernelSU 内核软重启路径。普通重启沿用 Android 与 Root 管理器的开机流程。此次改动完成后，仍需在目标手机上实测 KernelSU 重载顺序、字体挂载和新 zygote PID。
