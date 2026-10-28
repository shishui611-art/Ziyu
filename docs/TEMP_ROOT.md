# KernelSU 系列临时 Root 与软重启

此功能限定 KernelSU / SukiSU Ultra 系列。Magisk 和 APatch 不调用字域内置 `ksud soft-reboot`。

## 识别方式

检测到 KernelSU 系列本身不代表它处于临时 Root。官方 `late-load` 提供 `KSU_LATE_LOAD=1` / `KSU_RUNTIME_MODE=late-load`，字域在 `late-load.sh` 记录当前内核 boot ID，自动启用临时 Root 提示。其他 KernelSU 系列用户如果依赖软重启，可在 App「设置 → 安全与维护 → KernelSU 软重启」手动开启。普通 Root 用户不会仅因安装了 KernelSU/SukiSU 就自动进入临时 Root 模式。

## 字体生效

1. App 仍按原有流程生成下一次加载的私有字体负载，不直接改写正在挂载的字体源。
2. KernelSU `late-load` 在元模块挂载前运行 `late-load.sh`，激活待应用负载，然后用现有后端选择器发布字体。随后 `post-mount.sh` 与 `service.sh` 按原规则挂载和验证。
3. 支持 `ksud soft-reboot` 的 KernelSU / SukiSU Ultra 用户可在字域首页或挂载切换弹窗确认“立即软重启”，由内置 `common/soft_reboot.sh` 请求，也可从管理器执行。管理器按自身顺序执行清理钩子和模块重新加载，字域不在请求入口提前调用清理钩子。SukiSU 的 `emulated-soft-reboot.sh` 阶段先于 `stop`，遇到 NoMount 时只记录同次启动待清理状态，不写 provider 规则。`stop` 完成后，`post-fs-data.sh` 才重跑清理 hook；确认 zygote 已停止、PID 1 namespace 匹配且本模块字体事务锁可用后，逐条核对虚拟/真实路径和模块分区映射，只清理字域拥有的规则并复核旧路由已消失，再回收本模块账本中的文件挂载、目录镜像及 lower，随后撤销旧后端记录。清理或复核失败会恢复原规则并阻止新负载激活；钩子失败不等于管理器取消整个用户空间重启。该顺序覆盖 KernelSU 管理的 soft-reboot 与 NoMount metamount 重铺流程；任意 root/ADB 命令或第三方后台脚本直接改写 NoMount 规则不受字域锁约束，检测到不一致时流程会失败关闭。
4. 回到 App 查看「挂载详情」。只有本次会话路由和字体加载验证成功才算生效；“模块安装成功”与“软重启已请求”都不算挂载成功。

`ksud soft-reboot` 是否存在取决于 KernelSU/SukiSU 版本和分支。内置入口检查本机安装的 ksud 是否接受 `soft-reboot --help`，不支持时显示具体原因。清理若发现遗留挂载、字域旧路由仍存在或规则状态无法确认，会保留待应用负载并记录失败。NoMount 没有字域路由时，不会仅因检测到 NoMount 就拒绝负载激活。首次刷入模块后，模块脚本必须先由 Root 管理器加载一次。Magisk、APatch 不因该设置改变挂载选择。

v1.4.50 针对 SukiSU Ultra 4.2.1 + NoMount v2.0.0 (484) 增加了受管软重启中的局部旧规则清理；在该组合中，NoMount 缺少 `emulated-soft-reboot.sh` 时，既有 22 组字域外部规则未被旧救援 ledger 清理。新路径经 `scripts/mount_nomount_backend_test.sh` 覆盖 pre-stop 延迟、post-stop 规则清理、其他模块规则保留、锁忙拒绝、失败回滚及 namespace/目标不匹配拒绝。设备规则快照只读规划确认目标，没有在手机实际删除规则或运行新 hook；任意外部 root/ADB rule 写入不受字域锁保护，真实软重启仍待设备验收。

## 内置脚本与日志（v1.2.29）

手机 Root 终端可执行：

```sh
# 仅查询接口，不重启
sh /data/adb/modules/ziyu/common/soft_reboot.sh status
# 请求软重启
sh /data/adb/modules/ziyu/common/soft_reboot.sh request
```

请求日志写入 `logs/soft-reboot.log`，清理结果写入 `config/temporary-root-soft-reboot.conf` 和 `logs/mount-backend.log`，诊断导出包含以上记录。返回成功只代表请求已发送，不代表重启或挂载完成。

清理钩子对 KernelSU 普通和临时 Root 软重启均生效；普通 KSU 不因此自动启用 App 的临时 Root 模式。普通卸载失败时，仅对当前启动账本及 mountinfo 确认属于字域的目标尝试 lazy unmount。

入口与流程依据：[KernelSU 管理器源码](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/util/KsuCli.kt#L454)、[软重启源码](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/soft_reboot.rs)。新 hook 的手机软重启与字体生效效果仍需设备验证。
