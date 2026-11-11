# 字域 v1.5.23

本版按“字体应用尽量走原生桥”的要求，把 App 里剩余的字体应用相关入口统一收口到 `common/app_bridge.sh`。

## 什么是原生桥

`common/app_bridge.sh` 是字域原生 App（Kotlin/Compose）与模块 Shell 之间的唯一命令入口。App 通过一次 `su -c 'sh app_bridge.sh <命令> ...'` 发起请求，桥再按固定路由分发到内部脚本，并返回 typed key=value 或 JSON 给 App 解析。好处是：

- App 不直接拼接内部脚本路径，内部实现调整不影响 App；
- 每个命令只有一条受控入口，便于做参数校验、锁与超时；
- 握手/状态/字体应用使用同一套 Root 通道，减少重复 `su` 启动。

## 本版收口的入口

- 切换历史：`SwitchHistoryDialog` 原先直接执行 `system/bin/ziyu-history`，改为 `app_bridge.sh switch_history list|record-mix|restore <id>`。
- 字体导出：`FontTtfExport` 原先直接执行 `common/font_ttf_export.sh`，改为 `app_bridge.sh ttf_export <命令>`。
- 原厂字体重扫：首页两个入口原先直接执行 `common/font_manager.sh action stock_scan`，改为 `app_bridge.sh stock_scan`。
- 字体应用本身（`switch_start` / `switch_status` / `mix_start` / `mix_status`）原本就走原生桥，保持不变。

## 握手与状态

- 恢复两段式启动：先轻量握手（`app_handshake.sh probe-v2`，只读 module.prop / boot_id / active_font），首页立即点亮；随后再读完整状态刷新主页细节。
- “待验证”修复：管理器挂载在**本次开机刚发布**时不再无条件保持 pending，而是在 post-mount/service 阶段按内容（大小 + 抽样 sha256）核对 `/system/fonts` 与负载；核对通过就写 mounted/passed，不再一直显示待验证。

## 默认挂载

- 检测到 NoMount 元模块时，安装阶段把未指定或 auto 的挂载偏好落为 `manager`（元模块 / 根管理器挂载），不再自动切到自挂载。

## 桥新增路由

- `switch_history <list|record-mix|restore id>` → `system/bin/ziyu-history`（缺失时回退 `common/ziyu_history.sh`）。
- `ttf_export <命令>` → `common/font_ttf_export.sh`。

## 限制

- 本版只做了入口收口，不改变字体应用引擎逻辑；`switch_history`/`ttf_export` 的输出格式与原先直接调用一致。
- 未在手机上验收；切换历史、导出、重扫与字体应用需刷入后逐项验证。
