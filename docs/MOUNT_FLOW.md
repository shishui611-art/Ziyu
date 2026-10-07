# 当前字体挂载流程

## 启动时只做一次选择

1. `post-fs-data.sh` 先检查 `disable`、`remove` 和外来的 `skip_mount` / `skip_mountify`，被排除时不激活待应用字体。
2. 激活已经准备好的字体代次，统一运行 `common/mount_backend_runtime.sh`。
3. 提供者可用：从 `.luoshu-payload` 复制普通文件到提供者扫描目录，清理字域自己持有的跳过标记，等待提供者接管。
4. 没有可用提供者、提供者状态不明确、提供者排除本模块，或发布 / 最终路由验证失败：保留字域自己的跳过标记，并通过唯一的自挂载实现尝试 OverlayFS；不支持时对已有目标文件使用 bind。
5. 字域被禁用 / 删除、存在外部 `skip_mount`，或没有选择自定义字体时，不进行自挂载。无法识别 Root 管理器时也会尝试早期自挂载，再由 service 阶段复核。

刷写终端只显示一行挂载状态：系统默认字体显示“未执行”，自定义字体显示“待重启验证”，显式排除显示“已跳过”。真实挂载发生在 Root 管理器的启动阶段；详细后端、实现方式和验证原因在 App「挂载详情」及 `logs/mount-backend.log` 查看。终端不把“模块 ZIP 安装成功”当作挂载成功。

同一次启动的提供者身份、路径和后端选择写入 `config/mount-backend.conf`，后续阶段使用该记录。自挂载回退会写入 `fallback_used` 与 `fallback_reason`；如果发现外部路线无效，字域只叠加自己的 OverlayFS / bind 路由，不卸载或改写外部提供者已经建立的挂载。自挂载事务失败后记录失败与回滚结果，不将其报告为成功。

## 各阶段职责

| 阶段 | 职责 |
| --- | --- |
| post-fs-data | 激活代次、识别提供者、发布扫描目录、固定选择 |
| post-mount | KernelSU / SukiSU / APatch 执行自挂载或在外部路由验证失败时切换自挂载 |
| service / boot-completed | 复核已有选择；发现回退挂载被后续层覆盖时，允许一次自挂载重试 |
| App 准备字体 | 写待应用负载，不更新正在使用的提供者目录 |

KernelSU 开机后加载时由 `late-load.sh` 代替 `post-fs-data.sh` 完成早期选择。启用临时 Root 软重启流程时，`emulated-soft-reboot.sh` 先回收字域自己的旧挂载并撤销本次内核启动的旧后端记录，随后 KernelSU 重新执行 `post-fs-data.sh`、元模块挂载、`post-mount.sh` 和验证。具体识别与限制见 `docs/TEMP_ROOT.md`。

Magisk、APatch 的原生模块挂载作为外部提供者处理。其他已识别的提供者包括 Hybrid Mount、Mountify、Magic Mount、meta-overlayfs 和 NoMount。KernelSU / SukiSU 当前选中的 Mountify 以有效的 `metamount.sh` 作为元模块入口，不要求该脚本具有可执行位；Magisk 独立 Mountify 仍需可执行 boot / service hook。未经确认的提供者保持未知状态。

## 目录与验证

- `.luoshu-payload` 是字体负载的来源，脚本直接读取它。加载辅助脚本不创建模块目录的 bind 视图。
- 普通提供者使用 `system/<partition>` 布局；NoMount 使用顶层分区布局；meta-overlayfs 使用其已就绪的内容目录。字域不会替提供者挂载内容镜像。
- 发布扫描目录使用临时目录和恢复记录，替换失败可恢复上一代目录；移除的字体不会留在旧扫描目录中。
- 验证读取 PID 1 的文件，检查来源、哈希和字体 XML 引用。NoMount 不要求存在 OverlayFS / bind 的 mountinfo 项。
- Universal 字体负载仍检查部署身份和字体契约；外部模式使用本次启动的提供者记录，不要求字域的自挂载事务记录。
- 自挂载失败时逆序清理本模块记录的目标。卸载失败保留目标列表并报告失败，不能显示“已完整回滚”。

## 兼容与状态

App 展示自动选择、Root 管理器、具体元模块及版本、挂载机制、判断依据、实际后端、验证结果和原因。提供者身份读取本次启动记录；NoMount / Hybrid VFS 使用启动配置，字域自挂载使用同启动事务记录，其他方式显示 PID 1 字体路径的 mountinfo 观察值。观察值可能包含其他模块，不把提供者名称当作生效证据。详情查询不修改元模块配置。旧 App 的 `meta` / `self` 设置命令仍可调用，统一转换为 `auto`，不影响正在运行的挂载。

挂载状态记录使用 `ziyu-mount-backend-v3`；Root 检测、字体加载验证、字体负载复用和 Universal 运行验证均接受 v3，并仍验证 boot ID、后端状态和字体路由。

运行时不再创建私有 NoMount 规则。保留旧规则清理文件和命令接口用于升级兼容。

这是当前源码的行为说明；自挂载成功与否仍以本次启动的路由验证和设备验收为准。
