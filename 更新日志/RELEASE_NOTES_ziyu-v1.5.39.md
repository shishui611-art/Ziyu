# 字域 v1.5.39

## 安全槽位扩展

在 v1.5.38“全部关闭扩展”验证开机正常后，重新开启扩展，但只处理安全槽位：

- 跳过 `.ttc` / `.TTC` 合集槽位（`NotoSansCJK-Regular.ttc` 等，最可疑的卡开机来源）；
- 跳过 Emoji / 彩色 / 时钟等特殊字形槽位（文件名含 `emoji`、`color`、`clock`）；
- 跳过 `lang` 其它语种槽位（需 `slot-coverage-lang` 显式开启）；
- 只扩展设备上真实存在、分类为中文/数字/英文的槽位。

设备实测计划：**27 个安全槽位**，过滤后 TTC/Emoji/彩色/时钟数量为 0。

默认开启；`config/tools/slot-coverage-enabled.conf` 写 `enabled=0` 可整体关闭。

## 验证

需要刷入后应用字体 + 重启，确认不再出现 `system_server watchdog` 卡开机；
如果仍卡，则问题不在槽位扩展本身，需进一步收窄到具体槽位或字体内容。
