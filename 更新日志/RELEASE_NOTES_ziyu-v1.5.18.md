# 字域 v1.5.18

本版新增开机阶段测量，并说明上一版优化的实际覆盖范围：

- `post-fs-data` 新增 `logs/boot-profile.log`：用 `/proc/loadavg` 最后分配的 PID 与 `/proc/uptime` 记录本模块自身在 zygote 启动前创建的任务数，以及 storage、ota-guard、stock-snapshot、transaction、live-retire、activation、mount-runtime 各阶段的分段增量。统计只使用 shell 内建与 `/proc` 读取，不额外派生进程。
- 该日志用于回答“减少进程创建能不能降低 zygote PID”：zygote 的 PID 等于本次内核启动在它之前创建的任务数，本模块每少派生一个进程，zygote PID 就少 1。手机重启一次后即可看到本模块的实际占比。
- 说明：v1.5.16 的批量 `chmod` 修改位于 v2.2.7 兼容运行时（`.ziyu-runtime/compat/v227/post-fs-data.sh`）。本机当前使用 legacy `physical-safe-v1` 运行时，`post-fs-data` 会跳过该脚本，因此这次改动没有改变本机的 zygote PID（实测 6495 → 6503，属正常波动）。
- 本版不改字体应用、挂载与验证逻辑。

本次完成本地 Debug 构建与包完整性核对；未运行完整测试套件，未安装、刷入或重启手机。手机端 zygote PID 与开机耗时的对比待重启后读取 `logs/boot-profile.log`。
