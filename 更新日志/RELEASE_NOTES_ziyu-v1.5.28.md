# 字域 v1.5.28

本版按你的要求，从 v1.5.14 备份把**临时 Root / late-load / 软重启**流程恢复回来。

## 恢复的文件（来自备份 `ziyu-v1.5.14-fullsnapshot`）

- `late-load.sh`：KernelSU late-load 时在元模块扫描前激活待生效负载，并写 `config/temporary-root-session.conf`。
- `emulated-soft-reboot.sh`：KernelSU 软重启前释放字域自己的挂载、要求重新选择后端。
- `common/soft_reboot.sh`：软重启请求脚本（`request` 等子命令）。
- `common/temporary_root_mode.sh`：判断当前是否处于临时 Root 会话（late-load 会话或手动开启标记）。

## 接线

- `app_bridge.sh` 新增：
  - `temporary_root get|set true|false`：查看/写入临时 Root 标记 `config/temporary-root-mode.conf`；
  - `soft_reboot request`：调用 `common/soft_reboot.sh`。
- `app_status.sh` 状态协议新增 `temporaryRootMode` 字段（字段数 33 → 34），App 可读取当前是否临时 Root。
- `post-fs-data.sh` 增加软重启失败保护：同一 boot_id 下上次软重启清理失败时，本次不激活负载，避免重复挂载。
- 设置 → 存储 新增“临时 Root / 软重启”卡片：开启/关闭临时 Root 标记，或直接请求软重启。

## 使用方式

1. 刷入 v1.5.28，完整重启一次；
2. 在 KernelSU 管理器里对该模块使用 late-load/软重启流程（或先打开设置里的“临时 Root”标记，再点“软重启”）；
3. 软重启后回到字域查看挂载验证；需要回到普通完整重启时，关闭临时 Root 标记即可。

## 限制

- late-load 是否生效取决于 KernelSU 管理器/内核是否支持并触发了该流程；仅开启标记不会让管理器自动执行 late-load。
- 软重启不是完整重启，部分系统服务可能保留旧状态；验证失败时请改用完整重启。
- 从备份恢复的是 v1.5.14 时期的实现，已按当前轻量启动/槽位改动做了接线，但**未在手机上验收**。
