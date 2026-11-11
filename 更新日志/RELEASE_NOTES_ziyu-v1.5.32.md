# 字域 v1.5.32

## 1. 修复“字体工具 / 临时 Root”点击闪退

新菜单页在 LazyColumn 的 item 里又套了 `verticalScroll`，测量时高度约束为无限，直接抛
`IllegalStateException` 导致闪退。已移除页面内的嵌套滚动，由设置页自身的 LazyColumn 负责滚动。

## 2. 补齐剩余 128MB 硬上限

- `font_slot_compat.py`：`MAX_FONT_BYTES` 跟随 512MB 滑块；
- `composite_collection_build.py`：stock/output/mix 源上限跟随滑块；
- `stock_font_snapshot.py`：`MAX_FILE` 跟随滑块。

设备实测 512MB 配置下 `MAX_FONT_BYTES=536870912`，154,222,212 字节（147MB）的
`Google和音Rounded85%VF-VF(1)` 已允许通过大小检查。

## 限制

未做手机验收；新菜单点击、147MB 字体应用需刷入后验证。
