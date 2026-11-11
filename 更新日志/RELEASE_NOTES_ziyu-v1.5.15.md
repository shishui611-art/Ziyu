# 字域 v1.5.15

本版只保留普通 Root 的完整重启模式，移除临时 Root 与软重启整条链路：

- 设置页删除「KernelSU 软重启 / 临时 Root 模式」开关；重启确认只提供完整重启。
- 移除模块内置的 `common/soft_reboot.sh`、`common/temporary_root_mode.sh`、`late-load.sh`、`emulated-soft-reboot.sh` 以及相关的 late-load 会话、软重启清理与安装时音量键选择 Root 类型。
- App 与模块的状态协议同步删除临时 Root 字段；应用字体、恢复默认字体和切换挂载方式后统一提示完整重启，并在重启后回到 App 查看实际挂载验证。

保留的流程：挂载后端选择与验证、开机保护、系统更新后的原厂字体重扫、失败回滚与诊断导出。Magisk、KernelSU、SukiSU Ultra、APatch 等管理器均按普通 Root 的完整重启方式使用。

本次完成本地 Debug 构建与包完整性核对；未运行完整测试套件，未安装、刷入或重启手机，完整重启后的挂载与字体效果仍待真机验收。
