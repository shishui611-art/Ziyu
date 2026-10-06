# 字域 1.1.7 Debug

- 选择 Meta 挂载时自动补齐规则：字域在安装选择 Meta、App 内切换 Meta 或启动遇到 Overlay 默认路由时，自动为 Hybrid Mount 写入 `[rules.LuoShu] default_mode="vfs"`，普通用户无需接触 config.toml。
- 写入安全：先备份原配置为 config.toml.luoshu-backup，写入后用真实路由验证复核，验证不过自动还原；用户已有的字域专属规则（模块模式或路径规则）一律尊重、不改写。
- 卸载还原：卸载模块时，若 Hybrid 配置仍带字域写入标记则还原备份；用户后来自己改过的版本优先。
- 生效时机：写入只对下次启动生效，本次启动仍使用字域自挂载，挂载所有权保持清晰。
- 如实回退：Hybrid 运行时接口（runtime status / 按模块卸载）不可用时，照常回退自挂载并显示原因，不伪报 Meta 已接入。

模块和 App 同为 1.1.7 / 11007。模块 ID 与兼容路径继续为 LuoShu。本次本地 Debug 包用于用户实机测试，未经实机刷入验收，不发布 GitHub 正式版本。
