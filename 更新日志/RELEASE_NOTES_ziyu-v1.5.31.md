# 字域 v1.5.31

本版整理设置结构、修复彩色字体应用失败，并补上恢复默认/卸载的强制清理。

## 1. 设置拆分为独立菜单

- 新增“字体工具”：字体大小上限、其它语言槽位；
- 新增“临时 Root”：临时 Root/软重启、临时 Root 立即生效；
- “文件与配置位置”恢复为只管理存储位置；
- 新增设置统一写入 `config/tools/`（`font-import-limit.conf`、`slot-coverage-lang.conf`、`temporary-root-mode.conf`），开机自动迁移旧 `config/` 文件，读取保留旧路径兜底。

## 2. 彩色字体不再因防裁切失败

诊断日志显示：应用 `iOS26苹方中简by西风误了谁4.0` 时，因字体含 COLR/CBDT/sbix/SVG 彩色表，防裁切兼容路径直接抛错导致整个任务失败。

修复：切换前检测源字体是否彩色，是彩色则自动跳过防裁切继续应用（记录 `clipping skipped color font`），任务不再失败。

## 3. 应用链路大小上限统一

应用/合成/裁切/诊断/MTZ 的硬编码上限（128MB、256MB、384MB 等）全部改为跟随设置里的 512MB 滑块；补查了 `font_prepare_cache`、`font_clipping_compat`、`font_layout_diagnostic`、`font_zip_extract` 四处遗漏。

## 4. 恢复默认/卸载强制清理

新增 `common/restore_stock_factory.sh`：

- 清空 `.ziyu-payload` / `.ziyu-payload-next` / `.ziyu-retired`；
- 删除所有分区里的 `fonts` 目录与字体 XML（保留 `system/bin/ziyu*`）；
- 删除 provider/manager 发布回执、事务状态、待生效配置；
- 写 `active_font=default`。

接入：manager 默认分支、开机 default 残留清理、`uninstall.sh` 卸载前清理。确保恢复默认/卸载后回到原厂字体，不依赖发布阶段是否被跳过。

## 限制

- 未做手机验收；菜单显示、彩色字体应用、512MB 上限、恢复默认/卸载回原厂需刷入后验证。
- 彩色字体只是跳过防裁切，彩色字形本身不做轮廓适配。
- legacy physical-safe 物理替换文件的还原仍走运行时 default 路径。
