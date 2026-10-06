# 字域 v1.1.16

- 修复 KernelSU / SukiSU 的 Mountify metamodule 误判。已激活并声明 metamodule 的 Mountify，只要提供 `metamount.sh`，即按元模块入口识别；这个脚本由 Root 管理器调用，不要求普通可执行权限。
- 保留 Mountify 配置停用、skip_mount 与 skip_mountify 排除检查。
- 设备日志显示当前活动字体为 `default`。选择并准备自定义字体后，完整重启才能验证自定义字体路由。

日常交付为 Debug APK 与包含同一 APK 的模块 ZIP。尚未完成实机更新验收。
