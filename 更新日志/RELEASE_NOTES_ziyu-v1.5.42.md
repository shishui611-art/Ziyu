# 字域 v1.5.42

## 槽位探测对齐 custom-font-switcher

1. 新增 `common/slot_discovery_cfs.sh`：纯 shell 全覆盖槽位探测，移植 custom-font-switcher 的
   「四路来源（fonts.xml + 机型清单 + 目录扫描）+ 角色分类 + 保护名单」思路，代码独立实现。

2. 覆盖口径对齐对方模块（本机 100 槽，逐名一致）：
   - 目录扫描只收 ≥32KB 字体，跳过衬线/等宽/手写/时钟/数学/音乐/emoji/符号；
   - XML 家族只认 zh/und-latn/en，其它语言（阿拉伯/天城文/日韩等）走目录扫描的角色分类；
   - protect.list、粗细调节族（supportWeightAdjustment）、兜底字体一律不替换。

3. `slot_coverage.sh` 的 `ziyu_slot_discover` 优先走新 shell 探测（classifier=3），
   Python 保守版（device_slot_discovery.py，classifier=2）降级为回退。

4. 设备实测：OPPO Find X8（ColorOS 17）上 100 槽与 custom-font-switcher v1.5.3 的
   `device.list` 逐名一致（100 = 100，双向 diff 为空）。

## 说明

- 本次为本地 Debug 交付：完成编译与包完整性检查，未安装、刷入或重启手机。
- 挂载侧仍为字域现有多后端，本次仅移植槽位探测（Step 1）。
