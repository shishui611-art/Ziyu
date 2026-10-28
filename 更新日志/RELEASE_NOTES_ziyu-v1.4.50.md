# 字域 v1.4.50

本版以 GitHub 当前正式版 v1.4.2 为对比基线。主模块与 App 版本为 v1.4.50，versionCode 14050。源码版本不代表 Release 资产已经发布；下载时请以[字域 Releases](https://github.com/shishui611-art/Ziyu/releases)页面实际提供的模块 ZIP 和 SHA-256 文件为准。

## 新增

- 相较 v1.4.2，后续版本陆续加入系统字号、粗细与 DPI 调整，主题预览、字体任务通知与实时活动，运行应用字体来源诊断、字体导出，以及可选的 LSPosed WebView 字体兼容。
- 已保存组合可对符合条件且有可信 OEM 快照的 TTC/OTC 目标集合逐 face 生成。专用面、彩色面、缺字或不安全依赖保留原厂；源变化、布局契约、输出和事务回滚检查继续执行。

## 修复

- Material 浅色主题下，普通卡片和可点击卡片现在都会收到计算后的默认描边。此前描边虽已计算，但 Material 分支没有把它交给卡片组件，浅色卡片会与页面背景融在一起。默认边框为 1 dp，颜色混合 `outlineVariant` 与 `outline`；调用方传入显式边框时仍按显式值绘制。
- App 覆盖更新或进程重启后，应用监督器此前会把从磁盘恢复的 SUCCESS/FAILED/CANCELLED 导入状态再次当作新事件发布通知。现在恢复状态标为历史记录，不重新发布已结束任务通知；导入历史仍保留，真实任务完成、失败、暂停和恢复通知不变。
- 修复 SukiSU Ultra 4.2.1 + NoMount v2.0.0 (484) 环境中，软重启脚本缺少 `emulated-soft-reboot.sh` 且旧救援 ledger 未清除 22 组指向字域 `system` 字体负载的外部规则，导致检查到旧负载仍被路由并阻止待激活字体生效的问题。设备诊断中当前生效字体与待激活负载身份不同，证实这是实际旧路由冲突，不只是状态提示错误。
- pre-stop 的 `emulated-soft-reboot.sh` 只写入同次启动的清理待办，不会在 Android 服务停止前改写 NoMount 规则。SukiSU `stop` 完成后，post-fs-data hook 在 zygote/zygote_secondary 均已停止、无 zygote 进程、PID 1 namespace 匹配且字体事务锁可用时，按 virtual/real 路径和模块分区映射执行局部清理，再确认字域路由已消失并继续原有激活流程。失败时恢复原规则并阻止后续激活；不清空 NoMount 全局规则，也不接管其 metamount。该边界覆盖 KernelSU 管理的重载顺序，不约束任意 root/ADB 命令或第三方后台脚本直接写 NoMount 规则。
- 后续版本修复了组合字体集合中的共享字形冲突、静态字重引擎选择、默认 UVS 字形误保留、边界取整误差，以及多项界面和任务状态问题。无法安全替换的字形仍按槽位保留原厂，不因优化跳过源变化、输出、挂载或回滚验证。

## 优化

- 字体处理加入有界并行、轮廓与来源缓存复用及 HarfBuzz 原生桥。v1.4.38 将静态 TrueType 字形解码、复合字形分解与缩放用于部分槽位路径；原厂几何参考、落盘输出校验、设备槽位实例化、可变字体实例化、Google 字体裁剪和 CFF2 导出也在适用条件下优先使用原生工具。无法处理时沿用已有 FontTools 路径。
- 原生路径不是完整原生字体流水线：CFF/CFF2 曲线转换与原厂排版契约尚未整体原生化；TTC 供体、小槽位、点匹配复合字形等仍可能使用现有实现。缓存不足时不先生成再丢弃。源码和工具链优化不能单独作为手机端提速结论。

- 覆盖模块升级时，如果旧版软重启钩子已留下同次启动的 NoMount 清理失败记录，新 post-fs-data 会先重跑新版完整清理并确认就绪，再激活待应用字体；失败仍保留原有阻断条件。

## 验证范围

- v1.4.38 的验证摘要记录了 32 项测试和通用编译器检查通过。同一供体、相同简繁体槽位的 Windows 桌面测量为 90.6056 秒 → 70.3628 秒；44,278 个字形的全部字体表与完整文件字节一致。该桌面测量不代表手机耗时。
- 手机上的临时 ARM64 工具检查覆盖可变字体实例化、裁剪及 51,815 字形缓存，输出与 Windows 结果核对一致。这是工具和输出一致性检查，不是完整字体应用、刷入、重启或视觉验收。
- v1.4.50 Kotlin 编译与 Debug assemble 通过。Windows Gradle worker 有 ClassNotFound 问题；使用 Gradle 导出的原始测试 classpath 直接运行 `NativeImportNotificationSpecTest` 和 `NativeImportQueueStoreTest`，11 项通过。`scripts/mount_nomount_backend_test.sh` 已通过 pre-stop 不写 provider、post-stop 清理、其他模块规则保留、锁忙拒绝、失败回滚及 namespace/目标不匹配拒绝检查。
- 真实用户规则快照仅经 ADB 只读规划，确认 22 组字域规则将被处理；手机规则没有实际删除，也没有刷入或运行新 hook。SukiSU/NoMount 下新 hook 的实际软重启、完整字体应用和视觉验收仍待完成。Windows Gradle worker 问题需由正式 CI 再验证，正式检查结果以 Release 工作流为准；不宣称本机已恢复成功或手机字体处理已提速。

正式安装仍应使用 Release 中的完整模块 ZIP；单独 APK 不会更新模块运行时。正式签名和包内 APK 一致性以发布工作流验证结果为准。
