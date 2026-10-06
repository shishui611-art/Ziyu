# 字域 1.1.10 Debug

- 免 adb 完整诊断包：字体挂载验证失败、Meta 回滚无法确认时，自动生成一份诊断包（每 Boot 每原因至多一份），写入模块 logs/diagnostics 并尽力复制到 /sdcard/Ziyu/reports，用文件管理器直接发给别人即可。
- 诊断包内容：模块版本、设备系统信息、实时 Root 管理器与元模块检测、mount-backend / 挂载偏好 / 验证状态等配置、mount-backend.log 与字体日志尾部、命名空间内字体挂载快照。
- App 手动触发：诊断页新增模块侧完整诊断包入口（bridge action diagnostic_bundle）；原有的脱敏导出保留。
- 帮助别人排障时：让对方装好新版后重启一次，直接把 /sdcard/Ziyu/reports/ 下最新的 diag-*.txt 发回。

模块和 App 同为 1.1.10 / 11010。本次本地 Debug 包用于用户实机测试，未经实机刷入验收，不发布 GitHub 正式版本。
