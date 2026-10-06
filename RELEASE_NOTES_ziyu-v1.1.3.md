# 字域 1.1.3 Debug 测试版

这是本地构建的 Debug 测试包，供验证字体挂载路径使用，不属于正式版。

## 本次改动

- 统一识别 Magisk、KernelSU、SukiSU Ultra、APatch 与 unknown，并按 Root 管理器的启动 hook 选择自挂载时机。
- 区分元模块是否安装、启用和真正可用；任何时刻只允许一个挂载后端处于生效状态。
- Hybrid Mount 的纯 VFS 规则和运行时卸载能力通过检测后才优先尝试 Meta；挂载验证失败时先卸载并确认状态，再回退到 Ziyu 自挂载。
- 从 Android PID 1 主命名空间验证 XML、CJK 中文字体、Latin 和 Digit 路由；验证或回滚状态无法确认时失败关闭。
- Mountify、Meta OverlayFS、Magic Mount RS 等尚无已验证 Ziyu 单模块运行时撤销流程的后端，不会被强行叠挂；本版会走自挂载路径。

## 版本

- 模块：1.1.3 / 11003。
- App：1.1.3；Debug App：1.1.3-debug / 11003。
- 挂载流程有自动化测试覆盖，但尚未在多 Root 管理器及元模块组合的真机上验证。
