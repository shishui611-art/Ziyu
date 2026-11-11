# 字域 v1.5.24

本版继续移植 `custom-font-switcher` 的设备槽位发现，并把首页槽位口径改成设备真实槽位。

## 设备槽位发现

- 新增 `common/device_slot_discovery.py`：
  - 解析 `/system/etc/fonts.xml`、`/system_ext/etc/fonts.xml`、`/product/etc/fonts.xml` 等文件中的 `<family>` / `<font>` 条目，拿到设备真正声明的字体文件、family、字重、语言；
  - 合并 `common/slot_targets/*.list` 里的机型清单，补上 fonts.xml 没列出但设备上存在的槽位；
  - 输出缓存 `config/device-slots.list`（含 cjk/digit/latin/lang 计数），fonts.xml 更新后自动重建。
- 槽位分类：`clock/digit/DIN/Number` → 数字；`zh-Hans/zh-Hant/CJK/Hans/Hant/Hei/Song/Kai/Fang/OPPO Sans/MiSans/SysFont/SysSans/SourceHan` → 中文；日韩阿拉伯等 → 其它语言（默认不覆盖，可在 `config/slot_coverage_lang.conf` 打开）。
- 槽位覆盖计划改为按设备槽位表生成：按 fonts.xml 的字重（400/500/700…）挑选对应的 Bold/Medium/Light/Regular 负载文件，缺字重时回退 Regular。
- 首页“槽位覆盖”统计改为设备口径：总数、中文、数字来自设备槽位表，不再是“清单与本机文件的交集”。

## 原生桥与握手（澄清后的实现）

- “原生桥”指模块调用 Android 原生 HarfBuzz 引擎做可变字体实例化：`common/font-native-instance`（HarfBuzz 15.0.0）+ `common/font_native_instance.py`。字体应用仍保持**原生优先**：可变字体先走 HarfBuzz helper，失败/不支持才回退 FontTools。
- “握手”指 App 与模块握手：App 先用 `app_handshake.sh probe-v2` 确认模块身份与当前字体，随后读完整状态刷新主页；这让首屏不必等完整状态脚本。

## 限制

- 设备槽位表来自 fonts.xml 与机型清单，个别 ROM 用运行时动态字体或私有目录时可能漏项；漏项会退回机型清单。
- 覆盖计划仍只补 `/system/fonts` 中真实存在的文件，不会凭空创建不存在的槽位。
- 未在手机上验收；槽位数量、覆盖范围与首页显示需刷入后核对。
