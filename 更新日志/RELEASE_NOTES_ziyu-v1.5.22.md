# 字域 v1.5.22

本版把 `custom-font-switcher v1.5.1.3` 的槽位覆盖与临时 Root 流程引入字域，并优化 App 握手与首页槽位展示。

## 槽位覆盖

- 引入对方的 ROM 槽位清单（`common/slot_targets/*.list`，含 ColorOS 119 个槽位、HyperOS、OriginOS、Flyme 等），仅作为“设备存在哪些字体文件”的事实数据使用。
- 发布字体负载时，字域会按当前 ROM 清单把已选字体硬链接扩展到 staging 字体树中所有真实存在的槽位：按槽位名匹配 Bold/SemiBold/Medium/Light/Thin/VF 字重，找不到对应字重时回退到 Regular。
- 只补充 `/system/fonts` 里真实存在的槽位；不存在的目标不生成规则，避免 NoMount 的 `VFS missing target`。
- 发布只在字体负载变化的那次开机执行，稳态启动不重复扩展；扩展结果写入 `config/slot-coverage.conf`。

## 临时 Root 立即生效

- 新增模块根目录 `live.sh`（与对方模块入口位置一致）和 `common/ziyu_live.sh`：
  - `sh live.sh mount`：按槽位覆盖计划把当前负载用 `mount --bind` 挂到 init 命名空间（su 与 init 不同命名空间时自动用 `nsenter -t 1 -m`），不重启即可生效；
  - `sh live.sh unmount`：撤销本流程挂载；
  - `sh live.sh status`：查看当前字体、本次启动挂载槽位数、命名空间。
- 只挂载并记录成功项；记录文件 `config/live-mount.list` 跨启动会失效提示。
- 已打开的应用可能仍使用旧字体缓存，需要重新打开应用；本版不提供软重启。

## 首页槽位统计

- 首页在“当前字体 / 字体库”卡片下新增“槽位覆盖”卡片，显示**中文字体槽位数**与**数字槽位数**及总数。
- 统计由发布阶段写入 `config/slot-coverage.conf`，状态协议新增 `slotTotal / slotCjk / slotDigit / slotRom` 字段，App 与模块版本必须匹配。

## App 握手优化

- 启动时先直接读取完整状态（状态脚本本身能区分 Root 未授权/模块未安装），成功就跳过单独的握手 `su` 进程；失败才回退握手以给出准确错误。
- 状态协议字段数 29 → 33（新增槽位字段）；旧 App 或旧模块会提示“模块状态未读取完整”。

## 限制

- 槽位映射是启发式：只按槽位文件名的字重/用途分类，不读取字体内部字形覆盖；对缺少目标字重的字体，会用 Regular 顶替。
- 字体是否真正覆盖中文/数字，仍取决于所选字体本身；统计展示的是“覆盖到的槽位数量”，不是缺字检测。
- 本版未在手机上验收；`live.sh` 的挂载/撤销、槽位扩展的实际效果与 App 首页显示需刷入后验证。
