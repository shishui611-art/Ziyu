# 字域 v1.5.12 版本说明

本版本新增「管理器挂载」模式：字体文件由字域发布到模块自身的 `system/` 树，模块只释放自己创建的 `skip_mount` 标记，不创建任何 bind/overlay 挂载，交由 KernelSU / APatch 等 Root 管理器在开机挂载阶段自动挂载。

## 新增

- 挂载方式新增「管理器挂载」选项（App 挂载设置第二项，也可写 `preferred_backend=manager`）。
- 该模式下 `post-fs-data` 只做发布与标记释放，`mount-backend.conf` 记录 `selected_backend=manager`；重启后 `post-mount` / `service` 比对 `/system/fonts` 可见文件与 `.ziyu-payload`：大小全部一致并抽样 3 个文件哈希一致才判定通过，否则报 `manager-route-verification-failed`。
- 只释放字域自有的 `skip_mount` / `skip_mountify`；其它模块或用户添加的标记不动。

## 说明与限制

- 管理器挂载依赖 Root 管理器真的会挂载模块树；首次选择后必须完整重启才可能生效，发布当次启动只记录待重启状态。
- 切回系统默认字体时会发布空负载并撤销回执，避免管理器继续挂旧字体。
- 本版本完成本地编译与包完整性检查；管理器挂载在真机上的实际挂载与路由校验、以及外部环境检测复测仍待执行。
