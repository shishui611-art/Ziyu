# 临时 Root 可见性排查（v1.4.102）

## 本次结论

本次已按用户请求，只读核对当前连接手机的春秋 4.6.2 主进程和两个辅助进程：应用自身 Seccomp 为 2，父进程 PID 超过 5000；父进程实际为系统 zygote64。因此按用户提供的两项判据，当前状态命中父 PID 一项，不命中 Seccomp 为 0 一项。具体采样值与启动标识仅保留在本地交付报告，不提交用户设备信息。
父 PID 大于阈值不能单独证明父进程被替换或发生注入，也不能据此认定由字域导致。用户提供的排查项目将此现象与热/软重启联系，但本次没有做重启前后对照，也不把 PID 阈值作为所有 Android 设备的绝对判据。字域字体模块不能承诺可靠隐藏该项系统进程状态。

## 已核对的代码

- 字域 `common/temporary_root_mode.sh` 只读取本模块配置和本次 boot_id，不改应用的父进程或 seccomp。
- `common/soft_reboot.sh` 检查并请求已安装管理器的 `ksud soft-reboot`；不自己创建替代 zygote，不直接创建应用进程。
- 当前 KernelSU `userspace/ksud/src/soft_reboot.rs` 调用 Android stop/start 和模块阶段，应用进程重建属于 Root 管理器/系统的执行路径。
- 旧独立预加载模块已退役；现有清理只对所有权文件完整匹配的字域旧组件设置移除标记，不动其他模块。

因此，仅隐藏包名、改名字、删日志或隐藏 /data/adb 文件不能被作为这项进程状态问题已解决的证据。不同检测条目可能检查不同来源，需要以实际条目和当前进程证据区分。
完整重启可作为与软重启对比的诊断操作，临时 Root 会因此丢失并可能需要重新获取；本次没有在设备执行重启或更改 Root 框架。

## 处理范围与待确认

本次不改 Root 管理器、内核、检测器和全局进程策略，不关闭安全过滤来试图消除提示。字域继续保留字体应用、真实状态报告和临时 Root 下的已有兼容流程。
要进一步处理该项，需 Root 实现方依据该设备上的目标应用 PPID、父进程/zygote 启动时间与 Seccomp 状态确认。Shell 自己的 /proc/self/status 不是目标 App 的证据，不能拿 Root shell 的输出冒充检测 App 的状态。
本次只读采样不等于目标检测原生函数的内部跟踪，也不覆盖其他用户手机或历史启动。没有加入声称已经通过检测的开关或隐藏保证。

## 来源

- 用户提供的排查项目：[answer_zh.md，Found ksu/免解设备](https://github.com/mingzun09/Chunqiu-Detector-Problem-solution/blob/main/language/answer_zh.md#found-ksu免解设备)，本次按项目原文读取。
- [KernelSU 官方 soft_reboot.rs](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/soft_reboot.rs)，本次读取 blob `d9eb492bf637ae36456fbd77577aa85c8a7ff122`。
- 字域上述本地运行脚本与 `docs/TEMP_ROOT.md`。
