# 字域 v1.5.45

## 修复：安装直接失败

现象（KernelSU/FinaSU 安装日志）：

```
[4/4] 挂载状态
字域数据目录初始化失败
- Error: Failed to install module script
```

原因：`customize.sh` 在安装阶段调用 `ziyu_adb_storage`，而它要求 `common/python/bin/ziyu-python` **可执行**；管理器解压 zip 时不保留可执行位，`customize.sh` 又没有补权限，于是直接 `abort`。

修复：

1. `customize.sh` 调用前补 `chmod 0755 ziyu-python`；
2. `common/adb_storage.sh` 自身在二进制存在但不可执行时也补一次；
3. 安装阶段失败**不再中断安装**，只提示「首次开机时完成」——开机阶段本来就会执行同一步（真机日志中开机均为 `complete`）。

## 修复：字体一直不生效（关键）

现象（诊断报告）：连续 13 次「字体启动证据不足」，跨多次重启，字体始终没被验证为生效。

根因：`post-fs-data.sh` 开头的「外部 skip_mount 检查」把**本模块目录内**、没有归属标记的 `skip_mount` 判成外部模块留下的标记，于是**直接退出整个挂载阶段**：

```
[挂载结果] 状态=已跳过 … 原因=foreign-skip-mount
```

而那个 `skip_mount` 是字域旧版（自挂载时代）自己留下的。后果是挂载后端根本没运行，因此 `mount-backend.conf` 里 `root_manager=unknown`、`meta_engine=none`，字体从未挂上，每次开机累积一次失败。

修复：本模块目录内的 `skip_mount` / `skip_mountify` 一律认领为字域所有（没有第三方会在别人的模块目录里创建这类标记）。认领后：

- 外部标记判定为假 → post-fs-data 正常继续；
- 管理器/元模块模式下，挂载后端会释放自有标记，让元模块正常挂载 → 字体生效。

## 精简：临时 Root 只保留内核软重启

- 移除「立即生效 / 撤销 / 状态」（现场 bind 挂载）。
- 移除「框架软重启」（`setprop ctl.restart zygote` 在部分机型会触发整机重启）。
- 删除 `common/ziyu_live.sh`、`common/framework_restart.sh`、根目录 `live.sh`。

现在流程只有一条：**应用字体 → 内核软重启（向 KernelSU 发 soft-reboot）→ 开机阶段挂载生效**。

## 补全：多机型识别

按多证据规则识别 10 家：HyperOS、ColorOS、OriginOS、Flyme、荣耀 MagicOS、华为、红魔/努比亚、三星 One UI、Nothing、AOSP。

- 小米要求「强属性 + 品牌/厂商目录/小米专有字体」双证据，避免类原生被误判成 HyperOS 而读错槽位清单；
- 只有品牌属性不算数，必须再有厂商目录实证；
- 兜底改为 aosp（原来错误地默认 coloros）。

模拟验证 17 个场景全部通过（含类原生残留 MIUI 属性应判 aosp）。真机仅验证 OnePlus → coloros；Nothing 无独立槽位清单，回落 `aosp.list` + 目录扫描。

## 说明

- 本次为本地 Debug 交付：完成编译与包完整性检查，未安装、刷入或重启手机。
- 安装失败与 skip_mount 两项修复尚待真机刷入验证。
