# 当前字体挂载流程

## 当前启动的热切换

v1.2.40 对已经验证的物理自挂载接入连续字体热挂载，保留本次启动确定的挂载方式。
字体生成仍先提交下一启动队列，再复制独立字体代、创建独立工作目录并在 PID 1 挂载校验。
失败恢复旧字体；元模块等不支持运行时更新的后端仍保留重启队列。
成功显示“已热挂载”，部分界面可能需要重新打开应用或手动软重启刷新缓存。
当前启动使用过的字体代和工作文件保留到内核完整重启。
详细流程与限制见 [字体热切换](FONT_LIVE_SWITCH.md)。

## 启动时只做一次选择

1. `post-fs-data.sh` 先检查 `disable`、`remove` 和外来的 `skip_mount` / `skip_mountify`，被排除时不激活待应用字体。
2. 激活已经准备好的字体代次，统一运行 `common/mount_backend_runtime.sh`。
3. App 可选四种挂载模式：OverlayFS、Magic Mount、元模块自动挂载、字域自挂载。模式写入 `config/mount-backend-preference.conf`，重启后生效；刷入新版本模块时保留用户已选模式。
4. 元模块自动挂载模式只在本次启动确认外部提供者可用时发布 `.luoshu-payload` 到其扫描目录并等待验证。提供者缺失或状态不确定、发布失败、路由验证失败时明确记录失败，不自动改为自挂载。
5. Magic Mount 模式跳过元模块，使用字域 bind。字域自挂载模式跳过元模块，优先 OverlayFS，不可用时使用 bind。逐文件 bind 在挂载前检测别名冲突；同一 ROM 实体需要不同负载时，使用保留原厂文件的字体目录镜像 bind，让逻辑别名保留各自的字体字节和度量。单独选择 OverlayFS 时只尝试 OverlayFS，不支持则报错。所有手动模式仍执行 PID 1 路由校验与事务回滚。
6. 字域被禁用 / 删除、存在外部 `skip_mount`，或没有选择自定义字体时，不进行自挂载。恢复系统默认字体时，如果扫描目录有字域此前发布的凭据，启动阶段会先发布当前无自定义字体的负载，撤回旧字体；撤回失败会明确记为失败，不伪报恢复完成。无法识别 Root 管理器时也会尝试早期自挂载，再由 service 阶段复核。

刷写终端只显示一行挂载状态：系统默认字体显示“未执行”，自定义字体显示“待重启验证”，显式排除显示“已跳过”。真实挂载发生在 Root 管理器的启动阶段；详细后端、实现方式和验证原因在 App「挂载详情」及 `logs/mount-backend.log` 查看。终端不把“模块 ZIP 安装成功”当作挂载成功。

同一次启动的提供者身份、用户选择和实际后端写入 `config/mount-backend.conf`，后续阶段使用该记录。App 保存新选择不热切换正在运行的后端；重启后由启动阶段执行选择。失败不改写 `config/mount-backend-preference.conf`，也不跨后端接管。

目录镜像在 `/data/adb/luoshu/self-mount/work/<分区>-fonts` 准备。先复制原厂目录，再删除负载覆盖项的同名符号链接并写入独立字体文件。按照本机原厂目录及字体复制 SELinux 上下文，在覆盖系统目录前校验负载内容，然后只读 bind 挂载整个目录。`self-mount-required.conf` 中以 `mirror` 记录该实现，挂载详情显示目录镜像 bind。没有 NoMount CLI 调用，也不写入系统分区。

目录镜像额外使用 /data 空间，约为原厂字体目录和本次负载副本大小；不会把全部字体放进 tmpfs。复制、标签处理、挂载或 PID 1 内容验证失败时按原有账本逆序卸载，确认卸载后删除镜像。清理失败保留账本，不报告成功。原厂 lower 先记录，字体目录目标后记录，卸载顺序为字体目标、原厂 lower、镜像文件。

v1.2.26 / v1.2.27 的自挂载失败自动改为 auto / NoMount 救援已移除。旧版本字域自行创建的 NoMount 规则仍按 `.ziyu-state/nomount-rules.current` 账本清理，自挂载启动前先确认这些规则回收成功，再移除旧救援标记；不删除没有账本归属的规则。旧版本已改写的 auto 偏好需要用户重新选择，程序不推测旧意图。

KernelSU 软重启由 App 确认后通过内置 `common/soft_reboot.sh` 请求本机 ksud。清理钩子按账本回收旧字域规则、文件挂载及目录镜像，确认遗留目标已卸载后撤销旧后端记录。不能确认清理时记录失败并阻止新负载激活；不把钩子失败当作 KernelSU 已取消整个用户空间重启。请求与清理日志均附入诊断导出。恢复默认字体、禁用启动与卸载脚本同样保留旧账本清理能力。

安装核心拒绝迁移当前活动字体时，外层安装器会先清理生成的兼容脚本，再调用 Root 管理器的中止流程。该保护不会把迁移错误伪装成安装成功，也不会留下临时脚本影响之后的启动。

## 各阶段职责

| 阶段 | 职责 |
| --- | --- |
| post-fs-data | 激活代次、识别提供者、发布扫描目录、固定选择 |
| post-mount | KernelSU / SukiSU / APatch 执行用户选择的自挂载，或验证元模块挂载 |
| service / boot-completed | 复核已有选择；不改写用户偏好，不跨后端接管；独立字体部分生效写 partial，全部未生效或安全异常写 failed |
| App 准备字体 | 写待应用负载；已验证物理自挂载尝试独立字体代热挂载；元模块等待重启 |

KernelSU 开机后加载时由 `late-load.sh` 代替 `post-fs-data.sh` 完成早期选择。启用临时 Root 软重启流程时，`emulated-soft-reboot.sh` 先回收字域自己的旧挂载并撤销本次内核启动的旧后端记录，随后 KernelSU 重新执行 `post-fs-data.sh`、元模块挂载、`post-mount.sh` 和验证。具体识别与限制见 `docs/TEMP_ROOT.md`。

Magisk、APatch 的原生模块挂载作为外部提供者处理。其他已识别的提供者包括 Hybrid Mount、Mountify、Magic Mount、meta-overlayfs 和 NoMount。KernelSU / SukiSU 当前选中的 Mountify 以有效的 `metamount.sh` 作为元模块入口，不要求该脚本具有可执行位；Magisk 独立 Mountify 仍需可执行 boot / service hook。未经确认的提供者保持未知状态。

## 目录与验证

- `.luoshu-payload` 是字体负载的来源，脚本直接读取它。加载辅助脚本不创建模块目录的 bind 视图。
- 如果升级中断后 `.luoshu-payload` 不存在，外部提供者发布器只会在 `config/font-payload-manifest.conf` 校验通过后，临时从模块根目录的旧分区树发布字体；校验失败会记录具体原因并拒绝发布，不会把不完整负载交给 NoMount 或其他元模块。成功发布会在凭据中记录来源。
- 普通提供者使用 `system/<partition>` 布局；NoMount 使用顶层分区布局；meta-overlayfs 使用其已就绪的内容目录。字域不会替提供者挂载内容镜像。
- 发布扫描目录使用临时目录和恢复记录，替换失败可恢复上一代目录；替换字体或恢复系统默认字体时，旧字体不会留在元模块扫描目录中。
- 验证读取 PID 1 的文件，检查来源、哈希和字体 XML 引用。NoMount 不要求存在 OverlayFS / bind 的 mountinfo 项。
- Universal 字体负载仍检查部署身份和字体契约；外部模式使用本次启动的提供者记录，不要求字域的自挂载事务记录。
- 自挂载失败时逆序清理本模块记录的目标。卸载失败保留目标列表并报告失败，不能显示“已完整回滚”。

## 兼容与状态

主页挂载设置允许选择 OverlayFS、Magic Mount、元模块自动挂载或字域自挂载；界面同时展示 Root 管理器、具体元模块及版本、实现方式、实际后端、验证结果和原因。手动模式只控制字域自挂载策略，不修改元模块配置；新选择保存成功后弹出是否重启验证，可立即重启或稍后重启；KernelSU / SukiSU Ultra 上本机 `ksud` 支持时，可确认后由内置脚本请求软重启。旧 App 的 `meta` / `self` 设置命令仍可调用，统一转换为 `auto`。

`auto` 是外部提供者选择，不等于“发现外部挂载失败后自动回退”。自动选择只有在当前启动检测为 `available` 时才选择 external；`absent`、`unknown`、`excluded` 等状态不会被冒充成可用提供者。需要绕过元模块时，用户应明确选择 OverlayFS、Magic Mount 或字域自挂载。

提供者身份读取本次启动记录；NoMount / Hybrid VFS 使用启动配置，字域自挂载使用同启动事务记录，其他方式显示 PID 1 字体路径的 mountinfo 观察值。观察值可能包含其他模块，不把提供者名称当作生效证据。日志导出默认附带 `config/mount-backend.conf`、`config/mount-backend-verification.json` 和 `logs/mount-backend.log`；详情查询不修改元模块配置。

挂载状态记录使用 `ziyu-mount-backend-v3`；Root 检测、字体加载验证、字体负载复用和 Universal 运行验证均接受 v3，并仍验证 boot ID、后端状态和字体路由。

元模块路径只发布扫描目录并验证。字域自挂载不创建 NoMount 规则；仅为旧版本遗留规则保留账本清理与百分号路径解析修复。

这是当前源码的行为说明；自挂载成功与否仍以本次启动的路由验证和设备验收为准。

## 部分应用与兼容验证（v1.2.36）

保留原厂 XML 的 physical-safe 字体负载按独立字体目录处理。所有挂载立即登记到同一账本，每个目录记录开始位置，失败只回退该位置之后的挂载，再继续其余分区；失败目录必须确认清理，清理未确认仍停止事务。逐文件 bind 可跳过无法挂载的独立目标，但共用 ROM 目标且内容冲突时仍先使用目录镜像，不能任意挑一个字体覆盖共用目标。用户明确选择 OverlayFS 时不切换 bind 或元模块。

最终验证使用库存扫描相同的动态 OEM 分区名单，支持跨分区相对子目录 XML 引用，接受有原厂度量及格式凭据的 hyperos-physical 槽位。源负载完整性检查不放宽。生成器的隐藏 .font 硬链接源只验证源完整性；被运行时符号链接引用的 backing store 仍要求可见。

只有存在 PID 1 文件哈希、读取权限和 XML/原厂直连槽位路由证据的字体才算已应用。部分槽位未应用时记录 verification=partial、active_backend 和 mount_warning，保留已验证的字体并显示位置警告。没有任何可证明的字体应用、源负载损坏、XML 依赖不完整或回退未确认仍显示失败。不能用“负载准备完成”代替实际应用证据。

元模块初期不可用时，post-mount/service 只读重新检测并核对实际字体；不晚期发布字体树，不执行另一个挂载后端。Meta OverlayFS 支持 MODULE_CONTENT_DIR 中已挂载的内容镜像，分项记录 active/metamodule/skip_ok/hook/image 检测条件。

config/font-apply-result.conf 和 mount-backend-verification.json 保存应用数量、未应用目标及警告；font-mount-warnings.conf 记录本次启动失败目录。导出报告补齐当前 boot ID、检测条件、库存来源及字体挂载信息。忽略警告只改变通知与设置页提示，实际 partial 状态及诊断证据仍保留。

v1.2.37 补齐中断恢复：挂载不再等待分区结束后合并账本；提交前写 preparing，避免沿用旧 mounted 状态。回退以原子替换方式保留此前已提交部分和未清理目标；再次执行先检查当前挂载归属，不直接卸载归属未确认的目标。后续启动钩子从当前物理负载配置重新读取部分应用策略，避免把部分 bind 的正常警告当成整体失败。只有字体目录的 bind 验证允许部分匹配，XML 与 OverlayFS 目录内容仍按原约束验证。

## OverlayFS 参数与诊断（v1.2.30）

OverlayFS 使用只读多 lower 层。根据本机公开的 OverlayFS 参数入口增加 index/metacopy/nfs_export 关闭与 redirect_dir=nofollow 组合；检测到 Android 旧 override_creds 入口时增加对应组合，保留基本参数。明确选择 OverlayFS 时不切换其他后端。

捕获原目录的 bind 立即入账。传播隔离兼容 Toybox 的 -o private 调用和 util-linux 的 --make-private，再通过 mountinfo 校验。准备失败停止事务；回滚后仍能看到私有负载或工作目录挂载、或者挂载表不可读时保留工作目录并报告未确认。

logs/mount-diagnostics.log 保留每步命令、退出码及原始输出，记录本机文件系统、空间、SELinux 标签、命名空间和 OverlayFS 参数；关键失败追加相关内核历史快照。诊断导出包含该日志及上一份尾部、self-mount.log、两份挂载表、self-mount-required.conf 和 /data/adb/luoshu/self-mount 下账本。组件验证失败明确指向具体文件，不再只留下 visibility-mismatch。日志缺失、空文件、不可读分别说明。独立分区检测脚本只读收集这些证据，不尝试挂载。

语法和包检查不能证明手机兼容；失败后导出本次日志，先区分 lower 准备、参数拒绝、内容或 PID 1 不匹配、回滚失败，再结合内核错误判断。
