# 字域 v1.5.11（本地 Debug，正式签名）

相比 v1.5.10：

- 修复自动模式回退自挂载后，后续软重启继续沿用自挂载。软重启不改变 kernel boot_id，恢复分支会把上一次的 `selected_backend=self` 原样恢复，post-fs-data 因此不再检测外部提供者、也不再发布，NoMount 扫描结果为空。
- 现在 post-fs-data 阶段若 `preferred_backend=auto` 且上次记录为自动回退（`fallback_used=1`），改为重新检测提供者、重新发布并释放字域自有 skip 标记；用户显式选择的 OverlayFS、Magic Mount 或字域自挂载仍按原记录恢复。
- 重新选择前先回收本次内核启动残留的自挂载 overlay/lower；回收未确认时保留自挂载并明确失败，不用残留挂载遮住外部后端。
- post-mount / service 阶段仍只恢复本次启动记录，不在晚期重新选择或发布。

真机观察（v1.5.10，2026-11-09 10:01 软重启）：post-fs-data 没有出现 `[PROVIDER-REPUBLISH]`，`mount-backend.conf` 恢复为 `selected=self`；NoMount 10:01:52 `VFS injection result=0`，10:01:56 起自挂载成功。与恢复分支直接沿用回退结果的路径一致。

更新模块后请完整重启一次（首次分支本来就会重新发布并释放标记），之后软重启也应保持外部后端。

真机复测（OnePlus PLK110 / ColorOS 17 / SukiSU Ultra + NoMount 1.87）：
- 完整重启后 `selected_backend=external`、`active_backend=external`、`fallback_used=0`、无自有 `skip_mount`。
- 随后两次软重启（kernel boot_id 不变）都记录了 `[PROVIDER-PUBLISH]` 重新发布，NoMount `VFS injection result=1`，post-mount 外部路由验证通过，未回退自挂载。
- 用户停用模块后再次软重启，NoMount `VFS injection result=0`，符合停用预期；已注入的字体在完整重启前仍可见。

原有挂载选择、PID 1 校验、失败回滚、防裁切与可变字重流程不变。
