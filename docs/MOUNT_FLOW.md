# 挂载方式与故障排查

## 1. 挂载策略与启动流程

字域在离线准备字体负载并校验通过后提交到下一启动代次，按用户在 App 中保存的挂载模式执行，不在安装器阶段或运行时静默改写用户偏好。

### 四种模式

1. **元模块自动挂载（`auto`）**：优先使用当前启动已识别的元模块或管理器原生挂载（NoMount、Mountify、Hybrid Mount、meta-overlayfs、Magisk / APatch 原生挂载）；若提供者缺失、被排除或发布/路由校验失败，自动尝试字域自挂载（无法识别管理器时回退 Magic Mount / bind）。
2. **字域自挂载（`self_mount`）**：跳过元模块，优先 OverlayFS，不满足条件时使用文件 bind；遇 ROM 别名冲突时在 `/data/adb/luoshu/self-mount/work/` 建立目录镜像 bind。
3. **Magic Mount（`magic`）**：跳过元模块，仅使用文件或目录镜像 bind。
4. **OverlayFS（`overlayfs`）**：仅使用只读多 lower 层 OverlayFS，失败直接记录原因并回滚。

### 启动阶段分工

| 阶段 | 执行内容 |
| --- | --- |
| `post-fs-data` | 检查停用/删除/外部 `skip_mount` 标记，激活待应用代次，识别提供者，向扫描目录发布字体负载并锁定本轮后端。APatch 环境由 `ziyu-apatch-pre-mount.sh` 在扫描前完成准备。 |
| `post-mount` | 执行手动自挂载，或对元模块挂载进行首轮路由核验；NoMount 处于加载窗口时延后到 `service` 复核。 |
| `service` / `boot-completed` | 在 PID 1 命名空间核对实际字体哈希与系统路由；自动模式确认外部挂载失败时尝试自挂载；同一次启动内 `boot_id` 与负载清单未变化时直接复用已通过的验证报告。 |

### 原厂只读字库与覆盖兼容

生成组合字体与扫描原厂槽位时，`common/stock_readonly_view.py` 会自动跳过叠加在 `/system/fonts` 上的 Mountify / OverlayFS / Magic Mount 覆盖层，直接从底层只读块设备分区（`erofs` / 只读 `ext4`，对齐设备块大小）读取原厂字库，避免已挂载自定义字体时无法补齐原厂集合。

## 2. 内核软重启与 PID 回收

- **适用条件**：兼容的 FinaSU 内置 VFS（NoMount）环境，或开启「设置 → 临时 Root」并由管理器执行软重启。
- **执行流程**：
  1. `emulated-soft-reboot.sh` 在 Android 停止前登记本轮软重启请求。
  2. 系统框架停止（`zygote` / `system_server` 退出，兼容 ColorOS `zygote_tango` 转译进程）后，`post-fs-data` 仅按账本清理字域自有 NoMount 规则（兼容已替换模块路径的 `(deleted)` 标记），单次全量回读校验确认后发布新负载。
  3. 字体 `post-mount` 完成后、Android 尚未重启前，调用原生 `pid_wrap` 工具短暂调整 `pid_max` 回收后续 PID 分配区间（最多 256 次 / 200 ms 预算，附独立恢复看护进程），降低重启后高 PID 风险。
- **失败处理**：若旧字体挂载或规则清理未通过校验，保留待应用负载并在首页提供「普通重启」。
- **相关日志**：`logs/soft-reboot.log`、`config/temporary-root-soft-reboot.conf`、`config/pid-wrap-last.conf`。

## 3. 常见问题排查

### 查看挂载状态

打开首页「挂载详情」，重点核对 4 项：
- **Root 管理器与提供者**：当前识别到的管理器和元模块版本。
- **实际实现方式**：外部提供者、OverlayFS、文件 bind 或目录镜像 bind。
- **验证状态**：
  - `已应用`：PID 1 路由与内容校验全部通过。
  - `部分应用`：主要槽位生效，个别路径保留原厂并列出原因（可在设置页忽略提醒，原始日志保留）。
  - `待重启`：新负载已生成，尚未经过重启激活。
  - `失败`：查看错误原因并导出诊断报告。

### 某些 App 字体没变化

1. 打开 **设置 → 检查应用字体**，选择目标 App（先打开显示异常的页面再切回字域），点击「检查字体」或使用「批量检查」查看进程实际映射的字体路径。
2. 按检查结果处理：
   - **Google Play / Chrome 等英数回退默认**：打开 [Google 字体兼容](GOOGLE_FONT_COMPATIBILITY_ZH.md) 开启并重启。
   - **App 内网页仍为原字体**：可启用 [LSPosed 网页字体](WEBVIEW_LSP.md) 并勾选对应 App。
   - **App 自带字体或独立渲染引擎**（如部分游戏、阅读器、输入法皮肤）：此类文本不走 Android 系统字体，系统模块无法覆盖。
   - **目标 App 位于 Root 隐藏/排除列表**：在 Root 管理器确认该应用是否被隔离于模块挂载之外。

### ColorOS 数字或锁屏时钟异常

- 通用文字默认保留原有字形比例，仅专用数字字体应用宽度适配；如需保持原厂锁屏时钟字体，保持「设置 → 字体工具 → 保留锁屏时钟字体」开启。

### OverlayFS 模式失败

- OverlayFS 为独立严格模式，内核或文件系统不支持时会报错并回滚。请切回「元模块自动挂载」或「字域自挂载」并重启。

## 4. 导出诊断与分区报告

- **App 一键导出**：进入「设置 → 日志与诊断」（或字体刷写日志页）点击导出，报告保存在 `/sdcard/ziyu/reports/Ziyu-diagnostic-<时间>.txt`，包含挂载状态、后端日志、PID 1 挂载表、软重启与槽位覆盖记录。分享前建议复核设备信息。
- **独立分区兼容脚本**：将模块内 `common/font_partition_compat_report.sh` 复制到 `/sdcard/Download/` 并在 Root Shell 运行：

```sh
su -c 'sh /sdcard/Download/font_partition_compat_report.sh'
```

脚本会在同目录生成只读报告 `font-partition-compat-<时间>.log`，提交反馈时一并附上即可。
