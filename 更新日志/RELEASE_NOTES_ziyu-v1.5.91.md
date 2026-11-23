# 字域 v1.5.91

- 修复在 Mountify、OverlayFS、Magic Mount 或自挂载覆盖 `/system/fonts` 环境下，应用组合字体报 `组合字体原厂集合逐面生成或契约校验失败`（`原厂字体资料未能补齐（1 处）`）的问题：`common/stock_readonly_view.py` 现过滤覆盖挂载层并直接从底层只读原厂块设备（支持 `erofs` 与只读 `ext4`，拒绝 `loop` 镜像与目录级覆盖挂载）创建隔离只读视图读取原厂 `NotoSansCJK-Regular.ttc` 等字体。
- 同步修复同一根因引起的扩展槽位原厂比对全缺（`slot-coverage` `missing/unavailable`）与活动覆盖状态下扫描原厂字库失败的问题。
- 优化 `common/stock_font_snapshot.py` 按需扩展快照逻辑，跳过当前设备不存在且未请求的可选 OEM 分区，减少多余的只读块设备 loop 挂载与卸载。
- 包含 `v1.5.90` 的 NoMount 软重启修复与耗时优化、以及 `v1.5.89` 的主题商店与自定义图标功能。

本轮使用既有正式签名（`CN=Ziyu Release`）构建本地 Debug 变体，已完成脚本语法/离线挂载解析校验与打包完整性核验，Mountify/OverlayFS 真机应用组合字体表现待手机实测。
