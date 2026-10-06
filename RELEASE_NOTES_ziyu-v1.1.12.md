# 字域 1.1.12 Debug

- 诊断细分：magic-mount 不可用时，日志与安装界面按检查点分别显示 magic-mount-not-active（元模块软链接未指向它）、magic-mount-not-metamodule（缺 metamodule 属性）、magic-mount-runner-unavailable（缺运行器二进制）、magic-mount-module-excluded（外来 skip_mount 标记），不再统一显示笼统的 backend-not-active-or-runtime-unavailable。
- mountify 同步拆分：缺运行器显示 mountify-runtime-unavailable，被排除显示 mountify-module-excluded。
- 纯可观测性改动：判定逻辑、检查顺序、挂载行为与回退路径完全不变。

模块和 App 同为 1.1.12 / 11012。本次本地 Debug 包用于用户实机测试，未经实机刷入验收，不发布 GitHub 正式版本。
