# 字域 v1.5.26

本版修两件事：槽位统计口径、字体大小上限可配置。

## 1. 槽位为什么扫出 300 多

之前设备槽位发现把 `fonts.xml` 里**每一个** `<font>` 都算了进去：Noto 各语种字体（阿拉伯、泰、孟加拉、亚美尼亚……）、装饰字体（DancingScript、ComingSoon 等）都被当成“槽位”，再加上机型清单，合计 323 个。实际系统 UI 只使用“文本家族”（sans-serif / serif / monospace 系列 + 中文家族）里的字体。

本版改为只统计这些文本家族：

- 中文 `SysFont/SysSans/OPPOSans/MiSans/SourceHan` 等
- 英文 `Roboto/GoogleSans/SourceSans/OPPOSans-Oplus` 等
- 数字/时钟 `AndroidClock/DINPro/Digits`

装饰字体和各语种 Noto 归入“其它语言”，默认不覆盖、不计入总数。效果（本机实测口径）：

| 口径 | 之前 | 现在 |
|---|---|---|
| 设备槽位（文本家族） | 323（含装饰/脚本字体） | **约 113**（中文 46 / 数字 5 / 英文 62） |
| 可覆盖计划 | 152（含装饰字体） | 约 100+（只含真实存在且属于文本家族的槽位） |

首页“槽位覆盖”总数现在显示文本家族数，不再把 200 多个脚本字体算进去。

## 2. 字体大小上限可配置

- 设置 → 存储 新增“字体大小上限”滑块：**32–512 MB**，默认 256 MB。
- 配置持久化在 `config/font-import-limit.conf`，模块侧 `font_import.sh` / `native_import.sh` 与 App 导入入口都读取这个值；App 仍保留 512 MB 绝对硬上限，实际生效上限由滑块决定。
- 超过上限时错误信息会显示当前配置的 MB 数值（不再固定写 256）。

## 3. 系统字体导入修复 + 临时 Root 入口

- 修复 `common/system_font_library.sh` 的参数拼接错误（`--destination` 被当成命令执行），现在可在设备上成功导出：实测生成 `SystemDefaultCjk / SystemDefaultLatin / SystemDefaultDigit` 三个字体库条目。
- 设置 → 存储 新增：
  - “系统字体”卡片：一键把 ROM 原厂中文/英文/数字字体导入字体库；
  - “临时 Root 立即生效”卡片：立即生效 / 撤销 / 状态，走 `app_bridge.sh live …`，不重启把当前负载挂到 init 命名空间。
- 说明：模块根目录的 `live.sh` 和 `common/ziyu_live.sh` 一直随包提供；之前只是没有 App 入口，现在补上了。

## 限制

- 槽位发现仍以设备 `fonts.xml` + 机型清单为准；某些 ROM 用私有目录/运行时字体时可能漏项。
- 字体越大，应用任务耗时与内存占用越高；512 MB 只是允许上限，不建议日常使用超大字体。
- 未在手机上验收；滑块写入、覆盖数量与字体实际生效需刷入后验证。
