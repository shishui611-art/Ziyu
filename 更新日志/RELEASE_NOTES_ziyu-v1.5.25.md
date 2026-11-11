# 字域 v1.5.25

本版修复设备槽位发现“能发现但应用不到槽位”的两个实际故障。

## 实测结果（OnePlus PLK110 / ColorOS 17）

在设备上直接运行发现脚本：

- 设备槽位表 **323 个**：中文 46、数字 5、英文 192、其它语言 80（默认不覆盖其它语言）。
- 覆盖计划 **152 个槽位**（跳过其它语言 + 只保留 `/system/fonts` 中真实存在的目标）。
- 之前计划为 **0 / 19**，所以发布树一直只有 21 个文件，看起来“应用不到槽位”。

## 根因一：Android mksh 的 `${var%%|*}` 解析

槽位缓存每行是 `文件名|类别|字重|family`。计划脚本原来用 `${entry%%|*}` 拆字段，在 Android 的 mksh 里竖线模式没有按预期截断（实测 `${e%%|*}` 得到空串、`${e#*|}` 得到完整串），导致每个槽位的文件名为空、全部被跳过。

修复：统一改成 `IFS='|' read -r name category weight family` 拆字段，已实测计划从 0 变成 152。

## 根因二：状态把 manager 通过误判为待验证

后端状态里 `verification=passed`、`active_backend=manager` 时，App 状态脚本只把 `self|meta|external` 当作已挂载，`manager` 落到 `pending`，首页因此一直显示“待验证”。

修复：`passed` 分支把 `manager` 也映射为 `mounted`，并写 `verified / PASS`，首页应显示“模块已连接”。

## 使用步骤

1. 刷入 v1.5.25 ZIP + APK，完整重启；
2. 打开 App → 首页槽位应显示设备口径（约 323 / 中文 46 / 数字 5）；
3. **重新应用一次字体**，再完整重启；
4. 第二次重启时发布阶段会按 152 个槽位扩展发布树；`logs/slot-coverage.log` 会记录 `expanded=… total=152`，`/system/fonts` 中被替换的文件数会明显增加。

## 关于“兼容引擎”

当前设备上 `config/font_runtime_legacy_v14_4.conf` 仍存在（`core=physical-safe-v1`），所以 App 会走物理兼容切换路径，界面提示“兼容引擎”。这不是故障，而是旧版迁移留下的运行时标记；要切到标准/原生引擎需要删除该标记并完整重启（会用 v2.2.7/v4 运行时），属于下一轮可选项，需先验证标准引擎在当前 NoMount 环境下的挂载效果。
