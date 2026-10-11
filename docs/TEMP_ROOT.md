# KernelSU 系列临时 Root 与软重启

此功能限定 KernelSU / SukiSU Ultra 系列。Magisk 和 APatch 不调用字域内置 `ksud soft-reboot`。

## 识别方式

检测到 KernelSU 系列本身不代表它处于临时 Root。官方 `late-load` 提供 `KSU_LATE_LOAD=1` / `KSU_RUNTIME_MODE=late-load`，字域在 `late-load.sh` 记录当前内核 boot ID，自动启用临时 Root 提示。其他 KernelSU 系列用户如果依赖软重启，可在 App「设置 → 安全与维护 → KernelSU 软重启」手动开启。普通 Root 用户不会仅因安装了 KernelSU/SukiSU 就自动进入临时 Root 模式。

## 字体生效

1. App 仍按原有流程生成下一次加载的私有字体负载，不直接改写正在挂载的字体源。
2. KernelSU `late-load` 在元模块挂载前运行 `late-load.sh`，激活待应用负载，然后用现有后端选择器发布字体。随后 `post-mount.sh` 与 `service.sh` 按原规则挂载和验证。
3. 支持 `ksud soft-reboot` 的 KernelSU / SukiSU Ultra 用户可在字域首页或挂载切换弹窗确认“立即软重启”，由内置 `common/soft_reboot.sh` 请求，也可从管理器执行。管理器按自身顺序执行清理钩子和模块重新加载，字域不在请求入口提前调用清理钩子。`emulated-soft-reboot.sh` 确认 NoMount 没有字域路由，回收本模块账本中的文件挂载、目录镜像及 lower，再撤销旧后端记录。失败时由 `post-fs-data.sh` 阻止新负载激活；钩子失败不等于管理器取消整个用户空间重启。
4. 回到 App 查看「挂载详情」。只有本次会话路由和字体加载验证成功才算生效；“模块安装成功”与“软重启已请求”都不算挂载成功。

`ksud soft-reboot` 是否存在取决于 KernelSU/SukiSU 版本和分支。内置入口检查本机安装的 ksud 是否接受 `soft-reboot --help`，不支持时显示具体原因。清理若发现遗留挂载、NoMount 字域路由或规则状态无法确认，会保留待应用负载并记录失败。NoMount 没有字域路由时，不会仅因检测到 NoMount 就拒绝负载激活。首次刷入模块后，模块脚本必须先由 Root 管理器加载一次。Magisk、APatch 不因该设置改变挂载选择。

## 内置脚本与日志（v1.2.29）

手机 Root 终端可执行：

```sh
# 仅查询接口，不重启
sh /data/adb/modules/LuoShu/common/soft_reboot.sh status
# 请求软重启
sh /data/adb/modules/LuoShu/common/soft_reboot.sh request
```

请求日志写入 `logs/soft-reboot.log`，清理结果写入 `config/temporary-root-soft-reboot.conf` 和 `logs/mount-backend.log`，诊断导出包含以上记录。返回成功只代表请求已发送，不代表重启或挂载完成。

清理钩子对 KernelSU 普通和临时 Root 软重启均生效；普通 KSU 不因此自动启用 App 的临时 Root 模式。普通卸载失败时，仅对当前启动账本及 mountinfo 确认属于字域的目标尝试 lazy unmount。

入口与流程依据：[KernelSU 管理器源码](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/util/KsuCli.kt#L454)、[软重启源码](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/soft_reboot.rs)。本次未在手机执行软重启，效果需设备验证。
