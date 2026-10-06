# 字域 1.1.13 Debug

- 识别 NoMount 元模块：NoMount 是内核级 VFS 路径重定向框架（KernelSU/APatch 生态），以零挂载方式服务模块文件。字域现在能检测它并区分三种状态：未激活（nomount-not-active）、缺 nm 命令行（nomount-cli-unavailable）、已激活但字域尚未接入（nomount-not-integrated）。
- 如实报告：NoMount 的自动注入只覆盖标准模块 system 目录；字域的字体负载在私有目录，因此当前版本仍使用字域自挂载，字体功能不受影响。安装界面会明确显示这一点。
- 后续路线：通过 `nm rule add <目标> <负载文件>` 在开机时为每个字体目标建立注入规则、用 `nm rule del` 回滚——待实机验证 nm 接口后接入。

模块和 App 同为 1.1.13 / 11013。本次本地 Debug 包用于用户实机测试，未经实机刷入验收，不发布 GitHub 正式版本。
