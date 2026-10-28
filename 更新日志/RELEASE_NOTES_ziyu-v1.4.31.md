# 字域 v1.4.31（本地 Debug 候选）

本版只增加**诊断明细**，不改变字形替换行为与安全判定：把“被保留原厂”的字形原因写进逐面证明与兼容槽位报告，便于核对字重混排是否与保留字形有关。

## 改动

- `common/composite_collection_build.py`：`replace_face()` 的预检不再只累计 `retainedUnsafeSlots`，而是同时记录最多 16 条明细——字形名、码位、触发原因（`hhea-minRightSideBearing`、`frame-xMax`、`vmtx-*`、`xMin-bearing-diff=N` 等）、宽度、侧距、轮廓边界。
- `common/font_slot_compat.py`：兼容槽位报告新增 `unsafeGlyphs` 字段，直接带出这些明细。
- 安全判定条件逐条拆开写，但**判定结果与之前完全一致**（原 `unsafe |= ...` 的每一项都保留），布局契约、源变化检查、输出校验、逐面证明与回滚不变。

## 用这批明细查到的结论（v1.4.30 遗留的 2 个保留字形）

设备导出字体对照（简体 Hans、繁体 Hant 各保留 2 个）：

| 字符 | 字形 | 宽度 | 侧距 | 轮廓 | 触发原因 |
|---|---|---|---|---|---|
| U+0334 COMBINING TILDE OVERLAY | uni0334 | 0 | -257 | -259..258 | `hhea-minRightSideBearing`（rsb -260 < -198） |
| U+0335 COMBINING SHORT STROKE OVERLAY | uni0335 | 0 | -282 | -282..282 | `hhea-minRightSideBearing`（rsb -282 < -198） |

两者都是**零宽组合符号**（叠加在别的字符上用的波浪线/短横线），轮廓横跨原点，所以算出的右边距必然是大负数，低于原厂 `hhea.minRightSideBearing=-198`。这是原厂声明的度量边界，不是取整误差，也不参与汉字排版，因此保留原厂是正确行为；v1.4.30 的 1 单位修正不适用于它们（U+0334 的 -1 偏差已被修正，仍差 62 单位）。

## 验证

- `scripts/composite_collection_build_test.py` 11 项测试通过（含 1 单位修正测试）。
- 电脑端复跑设备导出字体：简体替换 27,950 / 保留 2、繁体 13,242 / 保留 2，契约 PASS；新增明细字段与实测一致。

## 产物与范围

- Android Debug APK + 内置同一 APK 的模块 ZIP，包完整性检查见 `package-verification.json`。
- 未在手机安装、刷入、重启或做显示对照；手机上的诊断展示（明细是否出现在报告里）待验收。
