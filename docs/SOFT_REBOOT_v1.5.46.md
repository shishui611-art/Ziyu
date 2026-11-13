# 软重启与字体激活

## 原因与区别

本次核对的本地 `custom-font-switcher-main/module/live.sh` 来自 v1.5.2.2。它依赖 `slots.map`，先将字体 bind 到 init 挂载命名空间，再重启 zygote。字域使用经过校验的待激活负载和独立事务，不能直接沿用该计划文件或只重启 zygote。

只执行 `setprop ctl.restart zygote` 不运行字域的负载激活和元模块重载，也不能证明另一个命名空间或应用中的字体已改变。

本地参考脚本 `_mount()` / `_umount()` 使用 `$NS _to ...`，其中 `NS="nsenter -t 1 -m --"`，`_to` 是 Shell 函数。需要跨命名空间时，实际尝试让 nsenter exec `_to` 可执行文件，而不是调用当前 Shell 函数；通常会执行失败。正确调用顺序应由当前 Shell 的超时包装器执行实际的 nsenter 和 mount/umount 程序，或在目标 Shell 中明确定义函数。字域没有复制此路径，而是沿用自身事务挂载和管理器重载。

对方脚本还以 inode 相同判断部分路径已挂载，并在 su 命名空间解析目标；VFS 重定向或符号链接、私有命名空间下这些结果不等于 init 内的字节校验。此处为源码分析，尚无用户手机现场证据，不能把每一处未替换都归为同一原因。

## 本次修正

- KernelSU 的 emulated-soft-reboot 在 Android stop 前运行。字域该阶段只记录本轮请求；系统停下后，在 post-fs-data 接着清理字域旧规则，再允许既有事务激活、发布新负载。
- 旧实现对 NoMount 写入 `nomount-cleanup-pending` 后没有续接调用，且引用了缺失的清理/窗口函数。现补齐该路径，要求 init 挂载命名空间、系统未完成启动且 zygote/system_server 已停止。
- NoMount 只删除明确引用字域发布树、目标属于 Android 分区的精确规则，保留 Suite public/private 属性；逐项读回，失败尝试恢复旧规则并保留计划。禁止全局 clear、替换用户后端或在普通状态读取时卸载。
- 旧自挂载、live 事务、Mountify 清理由既有所有权与回滚逻辑处理；清理未确认则不激活新负载。KernelSU 可能仍继续启动，最终结果必须查看路由验证，不能把请求已接收当作字体已成功。
- 临时 Root 页使用管理器软重启，保留两次确认并解析结果，不展示原始 JSON。不恢复直接 kill system_server 的兜底。

## PID 功能

参考 [soft_restart_fix](https://github.com/youfeng11/soft_restart_fix/tree/a6748afcbed704a0e85089fed868381e8b4a5e4e) v1.2.2（Apache-2.0）。移植的是编号分配器回绕思路，**不清空已有进程的 PPID**，不改变 seccomp。

在本次管理器软重启的字体 post-mount 完成、Android 尚未启动时，原生工具暂时降低 pid_max，最多尝试 256 次 fork/wait、循环时间预算 200 ms，无无限消耗 PID 的兜底、不绑定大核。已观察到低位则直接退出。父进程恢复并读回 pid_max，独立看护进程在管道关闭/约一秒超时后尝试恢复，保留失败状态；这不是对内核故障、断电或两个恢复进程同时被杀的绝对保证。单次系统调用仍受内核调度约束，预算不代表真机耗时承诺。

它只影响后续分配；后续其他模块、线程和系统进程仍可能消耗编号，所以不能保证 zygote PID 必定小于某个阈值，也不能保证通过任何检测。普通冷启动不执行此工具。字体挂载结果与 PID 结果独立。

日志：`logs/soft-reboot.log`；状态：`config/temporary-root-soft-reboot.conf`、`config/pid-wrap-last.conf`；失败规则计划：`.ziyu-state/nomount-provider-reload.plan`。诊断导出已包含这些文件。

## v1.5.47：ColorOS Tango 修正

用户手机的 v1.5.46 已通过只读 ADB 确认：选择为 Google 和音，新负载状态 prepared；激活记录/挂载记录仍为保时捷；本轮软重启记录一直停在 cleanup-pending。KernelSU 日志显示已执行 post-fs-data，而字域新一轮 boot-profile 未开始，与 post-stop 检查提前退出一致。`zygote` 名称命中的 PID 7342 实际执行 `/system_ext/bin/tango_translator`，init 服务为 zygote_tango；该转译服务在字域检查的 zygote/system_server 停止窗口中仍运行。

v1.5.47 使用 init 的 zygote/zygote64/zygote32/zygote_secondary/zygote_next 服务状态，以及 system_server 和 zygote64 实际进程判断主框架是否停下；不再要求 Tango 的同名转译进程消失。主框架停止中最多允许八次 250 ms 间隔的等待，其他前提失败直接记录原因。不是取消停止检查，也不强制停止厂商转译服务。NoMount 规则清理和 PID 阶段共用同一检查。

主页待重启由实际待激活记录决定。新负载未激活时必须保留提示；不能用请求成功、相同 boot_id 或旧字体校验通过来隐藏它。临时 Root 确认按钮调用 soft_reboot request；普通 Root 仍走完整重启。两者显示文字和执行入口一致。

已存在的转译进程可能持有旧字体 mmap；旧负载继续留在 retired 目录以保护映射，新版仍需分别验收普通应用和转译应用，不能据此承诺整个设备所有进程立即重建字体缓存。未运行新版软重启、规则删除或 PID 修改；只读故障证据不等于新版设备验收。

## 验证边界

本次未在手机执行软重启、PID 工具或 NoMount 规则修改；没有同条件耗时结论，也没有确认所有应用都改用新字体。应用自带字体、排除策略和其他命名空间仍需分别取证。字库验收基准为酷安 53620096 r1 原文（未修改）；本次未完成语料和手机挂载验收。

轻量启动原有取舍保持：未重新开启开机自动 OTA 扫描/保护暂停或发布时全量字体校验。
