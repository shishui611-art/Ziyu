# 真机验证矩阵

本表记录各系统与 Root 管理器组合的真机实测进度，数据同步于 [`device_validation.json`](device_validation.json)。

## 当前进度

以下核心组合截至 `v1.6.0` 仍待完成完整真机验收（包含安装/升级、挂载模式切换、字体替换与酷安字库显示、软重启/完整重启、恢复默认与卸载）：

| 系统 | Root 管理器 | 验证项目 | 状态 |
| --- | --- | --- | --- |
| ColorOS 16+ / Android 16–17 | KernelSU / SukiSU Ultra / FinaSU | 安装升级、挂载模式、组合字体、软重启、恢复与卸载 | 待测 |
| HyperOS 3 / Android 16 | KernelSU / SukiSU Ultra | 安装升级、挂载模式、字体显示、恢复与卸载 | 待测 |
| 通用 Android | Magisk | 安装升级、自动挂载与自挂载、字体显示、恢复与卸载 | 待测 |
| 通用 Android | APatch | 安装升级、预挂载准备、字体显示、恢复与卸载 | 待测 |
| vivo / iQOO / OriginOS | KernelSU / Magisk / APatch | 分区扫描、原厂快照、字体切换、恢复与卸载 | 待测 |

> 历史局部观察（2026-10-14，OnePlus PJZ110 / Android 17，APatch 115357 + NoMount v2.1.0）：`v1.4.1` 升级重启及 14 个 PID 1 目标路由校验通过；尚未覆盖后续版本、换字、恢复与卸载，矩阵保持待测。

## 验收与提交记录要求

提交真机验收记录时请提供：

1. **基础环境**：手机型号、ROM 构建号、Android 版本、Root 管理器及版本、模块版本、所选挂载模式与本次 `boot_id`。
2. **功能核对**：应用自定义字体（含组合/Emoji）、核验挂载详情、切换另一字体、恢复默认字体、卸载清理。
3. **字库核对**：按 [字库固定测试基准](FONT_TEST_BASELINE.md) 使用 `coolapk-53620096-r1` 语料核对缺字、路由与裁切。
4. **证据附件**：App 导出的脱敏诊断报告或对应截图。
