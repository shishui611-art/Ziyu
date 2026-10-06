# 字域 1.1.9 Debug

- 全引擎降级接入：meta-overlayfs、mountify、magic-mount 与 Hybrid 统一按"降级接入"策略处理——引擎能挂载字域负载即接入，不再因缺少按模块卸载接口回退自挂载。
- 回滚能力如实标注：接入时显示"无按模块卸载接口；验证失败需重启恢复"（Meta 挂载重启即清空）；仅 Hybrid 纯 VFS + 完整 runtime 接口显示"可在线回退"。
- 拒绝条件不变：字域被排除/黑名单/停用/待删除、Hybrid 配置不可解析时，仍回退自挂载。
- 用户实机确认：1.1.8 已在 ColorOS 17 + SukiSU Ultra + Hybrid Mount 环境成功走通元模块挂载。

模块和 App 同为 1.1.9 / 11009。本次本地 Debug 包用于用户实机测试，未经实机刷入验收，不发布 GitHub 正式版本。
