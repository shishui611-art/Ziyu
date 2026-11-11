# 字域 v1.5.30

本版完成字库预览的三项优化（缺字缓存、sha256 去重、TTC faceIndex 接入）。

## 1. 缺字检测缓存与文案

- 预览用 Android 原生 `Paint.hasGlyph()` 逐字判断字体是否缺字；
- 结果按“字体来源（id/大小/时间/faceIndex/字重）+ 变化轴 + 文本 hash”做 LRU 缓存，滚动/重组不再重复查询；
- 缺字时预览右下角显示“缺字 N · 系统回退”，直接区分“字体真缺字”和“系统回退显示”。

## 2. sha256 预览缓存去重

- `preview_export` 返回源文件 sha256；App 把预览缓存文件命名为 `sha256-<hash>.<ext>`；
- 相同 sha 的字体（不同 id、不同导入时间）命中已有缓存，不再重复复制大字体；
- 缓存裁剪逻辑改为保留 sha 文件。

## 3. TTC faceIndex 接入

- 字库 FontItem 新增 `faceIndex`，从字库 JSON 的 `faceIndex` 字段读取（缺省 0）；
- 预览加载改用 `Typeface.Builder(file).setTtcIndex(faceIndex)`（faceIndex > 0 时），避免 TTC 总是落到 face 0；
- 说明：当前字库条目还没有写入 faceIndex（导入侧尚未记录），所以现有 TTC 仍按 face 0；导入时选择 face 并写入元数据后即可生效。

## 限制

- 未做手机验收；缓存去重、缺字标注、TTC 选面需刷入后验证。
- `Paint.hasGlyph` 基于系统 Skia 结果，不等同于完整 cmap 审计；TTC 自动选面依赖后续导入侧记录 faceIndex。
