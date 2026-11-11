# 字域 v1.5.13 版本说明

本版本修复 v1.5.12「管理器挂载」一直显示"等待验证"的问题，并让该模式的失败原因可以直接看到。

## 修复

- 管理器挂载此前每次开机都会重新发布负载并改写回执里的 `boot_id`，导致 `post-mount` / `service` 永远判定"需要再重启一次"，用户重启多少次都停留在等待验证。
- 现在只有当负载清单（`config/font-payload-manifest.conf`）或所选字体变化时才重新发布；未变化时保留上一次回执，重启后立即执行 `/system/fonts` 路由校验。
- 记录恢复路径（重启后沿用本次启动记录）也补上管理器挂载的重新发布判断，切换字体后不会漏发布。

## 新增

- 管理器挂载校验失败原因：`manager-publication-missing`、`manager-payload-missing`、`manager-no-payload-fonts`、`manager-route-mismatch`，以及元模块接管模块挂载时的 `manager-mount-delegated-to-metamodule`。

## 真机观察（v1.5.12）

- `preferred_backend=manager` 已写入，但 `active_backend=none`、`verification=pending`。
- `/data/adb/metamodule -> /data/adb/modules/meta-nomount`，PID 1 挂载表没有 KSU/NoMount 挂载，`/system/fonts/DroidSans.ttf` 仍是 19 字节原厂符号链接：模块树已发布但未被管理器挂载，管理器挂载被 NoMount 元模块接管。
- 结论：要真正验证管理器挂载，需要先停用 NoMount 元模块（或移除 `/data/adb/metamodule` 后重启），否则该模式无法生效。

## 未完成

- 完成本地编译与包完整性检查；未安装到手机、未刷入模块。
- 元模块停用后的管理器挂载挂载效果、路由校验与外部检测复测待执行。
