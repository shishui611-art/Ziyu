# 字域 v1.5.19

本版针对 v1.5.18 实测中最大的字域进程来源做优化，判定结果不变：

- `boot-ota-guard hook` 在同一 `boot_id` 内只执行一次。此前根 `post-fs-data` 与挂载运行时会各调用一次，同一判定重复做两遍；现在第二次直接复用第一次的返回码（0/10/12），标记写在 `config/boot-ota-guard-hook.conf`，换启动后自动失效。
- `_zbg_value`、`_zbg_boot_id`、`_zbg_fingerprint`、`_zbg_boot_completed` 从 `sed | head | tr`、`cat | tr`、`getprop | tr` 管道改为 shell 内建读取和参数展开；`_zbg_candidates`/`_zbg_read_candidates` 的 `sed` 路径去掉；`_zbg_load_state` 从 6 次命令替换改为一次循环读全。
- 21 处 `printf | cut -d '|' -f1` 改为参数展开 `${var%%|*}`；`sha256sum | awk` 改为 `sha256sum` 后参数展开。
- 挂载运行时增加 `mount-guard` / `mount-detect` / `mount-manager-apply` 分段打点，便于下一轮区分 491 个 PID 中检测、发布、状态写入各占多少。

对照：v1.5.18 首次开机 `logs/boot-profile.log` 显示字域 `post-fs-data` 自身创建 779 个 PID，其中 ota-guard 169、mount-runtime 491。本版预计 ota-guard 降到约 1/2~1/3，实际值待下次重启读取。

本次完成本地 Debug 构建与包完整性核对；未运行完整测试套件，未安装、刷入或重启手机。
