# 字域 v1.5.88

- 修复恢复系统默认字体后，重启阶段因空负载触发槽位扩展导致 `provider-default-payload-cleanup-failed` 的问题；同时避免发布提交阶段为未暂存分区建出空目录。
- 修复跳过开机保护或临时 Root 软重启成功后，遗留的 `boot-safety-current-boot.conf` 导致状态长期显示 `boot-safety-blocked` 的问题；同时避免模块替换后软重启恢复脚本读取缺失状态文件报错。
- 修复 `/sdcard/LuoShu` 迁移至 `/sdcard/ziyu` 时，因目标已存在自动生成的系统默认字体缓存文件（如 `fonts/SystemDefaultDigit.conf`）反复中断迁移的问题。
- 修复只读原厂视图（`stock_readonly_view.py`）在 4K 块大小 dm-verity 设备上挂载 EROFS 返回 `Errno 5`（EIO）的问题，对齐回环设备块大小以恢复原厂字体读取与符号链接路由校验。

本轮改动已完成语法检查与打包完整性核验，真机实测待验证。
