# Ziyu 外部挂载优先架构方案

日期：2026-10-06。状态：历史设计候选；包含已经取消的私有 NoMount 降级方案。当前实现以 [挂载流程](MOUNT_FLOW.md) 为准。

## 1. 目标与当前差距

目标：可接管 Ziyu 标准字体树的外部 provider 优先；确认没有外部 provider 时，才允许 Ziyu 的私有 NoMount 后端处理自身文件；NoMount 不可用时保留既有 legacy 兼容路径。

“私有 NoMount”指规则由 Ziyu 自己负责，仅覆盖自身文件，不要求维护第二份私有字体副本。

当前源码存在四个实际差距：

- `common/meta_mount_detection.sh` 把 NoMount 固定报告为 `nomount-not-integrated`，没有批准外部接管。
- `common/mount_backend_runtime.sh` 中 `preferred_backend=self` 可以挡住可用 Meta。
- `common/private_payload.sh` 在安装时把分区文件迁入 `.luoshu-payload`；`common/font_runtime_policy.sh` 和早期启动包装器仍会通过 bind 建立模块视图。
- 当前私有 NoMount CLI 查找依赖 Meta 目录；没有 Meta 时，不能假设还能取得它的 `bin/nm`。

因此本次实施把“标准树发布”与“外部优先选择器”作为同一交付门槛。只修改 `META_USABLE=1` 不构成完成。

本方案按最新要求调整原分期：原 Phase 1 的私有载荷后端扩展不再作为最终交付；原 Phase 3 的“仅提示”改为外部接管完成后的私有 fallback 实施任务，其正式启用仍需真实模块运行上下文验证。

## 2. 不变量

1. Ziyu 始终是普通模块，保持现有 `id=LuoShu`，不写 `metamodule=1/true`。
2. Ziyu 不枚举 `/data/adb/modules/*`。仅读取管理器声明的当前 provider、已知能力接口及自己的目录。
3. 外来 `skip_mount` 最高优先级；外部引擎对 Ziyu 的明确排除也不得被私有后端绕过。
4. 外部分支没有 Ziyu 主动挂载，没有私有 NoMount 探测/规则注入，没有模块视图 bind，不修改 provider 全局配置。
5. 对 provider 的权限错误、身份冲突、未知版本和读取失败，不能解释为“确认不存在”。未知时不自接管，状态显示“外部能力未确认”。
6. backend、provider、字体代次和发布布局在启动边界冻结。当前已经生效的 backend 不在 App/service 阶段热切。
7. 仅操作自身台账内的文件、控制标志、规则和 legacy 挂载；禁止 `nm clear all`、全局卸载和清空其他模块规则。
8. 不承诺 VFS 对所有检测手段不可见。“没有 Ziyu 传统字体挂载项”与“字体路径/文件内容不可观察”是不同结论。

## 3. 选择逻辑

```text
启动前完成候选字体树与 XML 的校验
        │
        ├─ 外来 skip_mount / provider 明确排除 Ziyu → 尊重禁用，不注入
        │
        ├─ 有可接管标准树的外部 provider → EXTERNAL_PROVIDER
        │
        ├─ 外部能力未知 → 不自接管，报告 unknown，不能宣称接管成功
        │
        └─ 确认没有外部 provider
               ├─ 私有 NoMount CLI + 内核接口 + 验证许可成立 → SELF_NOMOUNT
               ├─ 私有 NoMount 不可用、legacy 支持成立 → LEGACY_FALLBACK
               └─ 两者都不成立 → 保持默认字体，报告缺少挂载能力
```

`disabled`、`unknown`、`failed`、`default` 是健康/字体状态，不伪装成新的挂载引擎。

| 执行模式 | 自有 skip_mount | Ziyu 注入动作 | 实际负责人 |
| --- | --- | --- | --- |
| EXTERNAL_PROVIDER | 移除自身拥有的标志 | 无 | 有效 Meta 或管理器原生挂载 |
| SELF_NOMOUNT | 在 provider 扫描前创建双文件标志 | 仅自身 manifest 的 VFS 规则 | Ziyu |
| LEGACY_FALLBACK | 在 provider 扫描前创建双文件标志 | 既有 OverlayFS → bind 兼容事务 | Ziyu |

现代 KernelSU 无 Meta 时，不把“管理器兜底”当作可靠挂载能力；使用 legacy 的前提是 Ziyu 现有后端在该环境可用。

## 4. 外部 provider 与私有 NoMount 分开检测

### 外部 provider 的判定

`mount_provider_detection.sh` 输出 `available / absent / unknown / excluded` 四种能力结果，并记录 provider 身份、版本、当前 boot ID、内容根目录和布局。

- KernelSU / SukiSU：有效的当前 Meta 身份、未停用/待移除、具有挂载 hook，以及该实现支持目标分区和当前发布布局。
- NoMount Meta：上述挂载基础设施成立即可进入外部计划。不要要求 Ziyu 先执行 `nm version`；上游可能到 `metamount.sh` 才加载 LKM。
- Hybrid / Hymo / Mountify / Meta OverlayFS / Magic Mount：按照各实现实际的模块扫描路径、排除规则和内容目录作适配，不按目录名称或模块名称猜测能力。
- Magisk：当前启动上下文确认管理器身份且原生 Magic Mount 路径成立，属于外部 provider。
- APatch / 老版 KernelSU / fork：根据该版本的实际原生/Meta 挂载能力归类，不仅凭产品名称。
- 当前 provider 读取失败或实现未适配：`unknown`，不启动私有规则或 legacy 注入。

安装时只能显示“计划交给外部”；启动后通过文件路由和哈希校验，才能显示“外部接管已生效”。

### 私有 NoMount 的判定

只在 `provider_state=absent` 时进入。需要：

1. Ziyu 自带固定版本、匹配架构的 CLI；建议位置 `common/bin/ziyu-nm`。保留许可证与来源版本记录。
2. 当前内核接口已加载或内置。Ziyu 不加载 LKM、不启用其他模块、不把自己变成 Meta。
3. 实际模块启动脚本上下文能够执行 `version`、`rule list --json`，并在一个自身临时诊断目录完成 `add → 读取校验 → del → 恢复校验`。
4. 检查通过后写入验证记录，关联 CLI 哈希、接口版本、内核标识和启动上下文。组件变化使记录失效。每次自接管前仍执行只读活性检查。
5. 无 CLI、无接口、验证记录不匹配或 JSON 协议不受支持：私有后端不可用，走已验证 legacy 或保持默认字体。

自带 CLI 不等于自带 provider；仅包含控制程序，不包含扫描其他模块、内核安装或 Meta 生命周期代码。

## 5. 一份字体载荷，按 provider 发布标准布局

生成器仍输出逻辑分区：`system`、`product`、`system_ext` 及 ROM 实际用到的 OEM 分区。发布器负责把逻辑目标映射到当前 provider 支持的实体模块路径。

Magisk/APatch 的标准基本布局示例：

```text
$CONTENT_ROOT/
└── system/
    ├── fonts/<Ziyu 生成字体>
    ├── etc/<合并后的配置>
    ├── product/{fonts,etc}/...
    └── system_ext/{fonts,etc}/...
```

根目录 `product/system_ext/vendor` 若由管理器创建为别名，遵从管理器语义，不再放第二份实体副本。

NoMount 的不同版本需要单独的布局 profile：核对过的上游 master 会转换 `system/product` 等嵌套路径；核对过的 ZQZCC/my 分支脚本对嵌套分区的转换较少，不能套用同一个假设。必要时按其支持的顶层分区目录发布。profile 记录版本/脚本特征，未知实现不伪装成已支持。

Meta OverlayFS 等使用独立内容目录的 provider，发布至该 provider 为 Ziyu 指定的内容根，不把 metadata 路径当作真实载荷路径，也不自行 mount 其镜像。

每个编译 manifest 记录：

```json
{
  "schema": "ziyu-compiled-payload-v1",
  "generation": "content-hash",
  "layout": "nested-system",
  "rom_identity": "ROM fingerprint + boot slot",
  "files": [{
    "target": "/product/etc/fonts_customization.xml",
    "source_relative": "system/product/etc/fonts_customization.xml",
    "sha256": "content-hash",
    "kind": "xml"
  }]
}
```

这是结构示意；实施时哈希必须为实际 SHA-256。target 显式记录，私有 NoMount 不再由 `source_relative` 猜目标分区。源文件必须解析到自身内容根内；跨目录链接、路径穿越、重复目标和缺少文件均拒绝提交。

同一时刻只保留一份活动字体实体载荷。生成 staging、升级迁移和短期回滚允许临时旧代次；保留引用的代次不能提前删除。优先硬链接自身不可变字体文件，链接不支持时在 staging 复制，提交后回收未引用代次。

不得发布字体目录 `.replace` 或无关 whiteout，防止遮掉 ROM 保留字体。系统字体链接、中文/英文/数字路由及可变字体轴由既有路由校验器验证。

## 6. XML bake 与 OTA

复用 `font_config_overlay.py` 的合并、解析和引用检查，不复制一套字体配置算法。配置文件名单复用 `font_config_partitions.sh` 的实际分区发现，不把“11 个文件 × 3 分区”当作固定数量。

```text
干净 ROM 配置 + 已选择字体/字重
  → 源文件指纹（路径、size、mtime、SHA-256、ROM identity）
  → fonts/XML 一起生成 staging
  → XML parse + 引用字体存在 + 字体哈希/目标覆盖校验
  → publish journal 准备
  → provider 扫描前发布完整代次
  → 最后提交 generation/manifest
```

指纹文件放在 `.ziyu-state/font-config-sources.json`，活动输出台账放在 `.ziyu-state/compiled-payload.json`。

必须确认读到的是干净 ROM 源，不能把当前 provider 已替换的 XML 再当原厂配置。现有 `font_config_capture_original()` 的关键词过滤不足以独立证明 ROM 代次；增加当前输出哈希识别、原始快照所属 ROM 标识及启动阶段记录。无法获得匹配当前 ROM 的干净源时，停止该候选代次。

字体计算主要在 App apply 阶段完成；启动阶段以检查指纹、完成待提交代次和少量 XML 合并为主。配置未变化不重新启动完整编译链。

OTA 后源指纹变化：在已证明先于 provider 扫描的 hook 中重新 bake；失败时撤下自身不匹配当前 ROM 的字体配置输出，恢复默认配置并报告，不能继续挂旧 ROM 的整文件 XML。

多个分区目录的发布不是一次 `rename` 就能全部原子完成：通过 journal、备份映射、每步完成记录和最终 manifest commit 实现可恢复事务。中断后，在 provider 扫描前完成恢复，不能暴露 fonts/XML 混合代次。

若 provider 在模块 hook 前就 snapshot：采用 apply 时预编译及已确认的下一启动发布边界。仅靠 apply 预编译不能保证首次 OTA 启动安全；没有可验证的扫描前失效处理时，该 provider 的动态 XML/OTA 支持不通过发布门槛。

## 7. 启动与切换

### 启动前置顺序

```text
外来 skip_mount 闸门（先检查，不发布新代次）
  → 恢复未完成发布事务（被禁用时撤销未提交候选，禁止推进新代次）
  → 准备 default/撤销/待生效字体候选
  → 当前 provider 判定
  → 校验 ROM 指纹并准备标准树
  → 冻结 boot ID、provider、generation、backend
  → 设置/移除自身拥有的控制标志
  → 由已选引擎处理
```

外来标志存在时，允许只做自身未提交 staging 的清理与状态记录，不发布新的活动字体代次；自身旧规则/挂载清理失败时明确报告失败，不通过另一后端绕过。

### EXTERNAL_PROVIDER

- 启动早期移除 Ziyu 自己创建的 skip 标志，保留外来标志。
- 跨重启旧 NoMount 台账只清磁盘记录；不得拿旧台账对当前外部 provider 执行 `rule del`。
- 禁用所有旧模块视图 bind、动态字体补挂、Meta 文件热同步和 service 自动自挂载入口。
- post-mount/service 只校验路由、文件哈希及报告状态。外部接管失败不在同次启动切入私有后端。

### SELF_NOMOUNT

- 必须确认无 provider，且私有验证许可成立；先创建 `skip_mount` + `.ziyu_skip_mount_owned`。
- 按同一个 compiled manifest 对自身文件逐条 apply/verify/commit。
- 规则台账保留 boot ID、generation、target、source 和提交状态。添加前发现已有目标规则时停止，不覆盖。
- 规则台账并非内核规则所有权令牌；同 target/source 仍可能来自另一引擎。只有当前启动明确由 Ziyu 私有事务创建、没有外部接管且核对一致的规则才允许删除。
- 失败且确认 clean 后，可在该次尚未提交的启动事务里尝试 legacy；dirty failure 停止，不叠加另一后端。

### 已运行系统中的 provider/字体变化

App 或 service 仅写 pending 计划和原因，准备下一代次；不移除当前活动规则，不改会影响外部热加载的有效 skip 标志，不更换活动源文件。下一启动在扫描前消费计划并重新检测实际 provider。pending 不能覆盖最新的人为禁用意图。

因此旧方案的“运行中发现 provider 后立即删除 skip_mount”调整为“扫描前启动边界删除”，避免外部引擎热加载造成同次启动双后端。

## 8. UI 与旧偏好迁移

取消安装界面的“音量上 Meta / 音量下 self”作为全局强制选择。

旧 `preferred_backend=self` 迁移为 `fallback_preference=auto`，另存历史值便于诊断；不能继续压过外部 provider。确有需要保留的兼容开关只控制“无 provider 时是否禁用私有 NoMount”，且下次重启生效。

展示四个独立事实：当前 provider、计划 backend、实际 backend、验证结果/待重启原因。例如：

```text
外部挂载提供者：NoMount Meta
计划：交给外部提供者
实际：等待完整重启
私有 NoMount：未启动（外部提供者优先）
```

启动校验通过后显示“外部 NoMount Meta 已接管”，不再显示 `nomount-not-integrated`。无 provider、私有验证未通过时显示“当前使用 legacy 兼容后端”，不把泛称 `self` 显示成零挂载已经生效。

## 9. 实施与验收

顺序：能力选择与标准树/XML → 启动发布及旧版迁移 → 外部 NoMount 真机验收 → 私有 NoMount 许可与 CLI → 其他 provider 矩阵及发布。

优先实测用户当前 SukiSU Ultra + NoMount 环境。其他环境逐项验收：KernelSU + NoMount、KernelSU + Hybrid/Hymo、APatch 原生/Meta、Magisk、无 provider + 内置 NoMount、无 provider + 无 NoMount。不能把官方生命周期文档当作所有 fork/provider 已实测的证据。

外部接管验收必须同时满足：

1. 标准输出含所选 fonts 与本机新鲜 XML，目标覆盖完整。
2. 没有 Ziyu 自有 skip 标志、Ziyu 私有规则台账或模块视图 bind。
3. Ziyu 全部生命周期与 App apply 路径没有调用自身 `nm`、mount 或补挂；允许 provider 本身创建规则/挂载。
4. PID-1/系统实际可见字体路由和哈希闭合；NoMount 模式不因缺少传统 mount 项判失败。
5. 重启切换、恢复默认、卸载、OTA、升级保留字体均正确，无外来控制标志或其他模块规则被改动。

后续交付使用新的版本号。包内 CLI 带来的实际体积必须测量；Ziyu 1.2.20 自动化实测完整模块 ZIP 为 11,805,683 字节（约 11.26 MiB），原 11.25 MiB 门禁会多拦 9,203 字节。App 更新器下载上限为 128 MiB，因此正式包和 Debug 包门禁统一调整到 11.5 MiB，保留完整 Python/FontTools 和 ARM64 WOFF2 运行时，不为压体积删除必需依赖。

## 10. 来源与证据边界

- [KernelSU Meta 文档](https://kernelsu.org/guide/metamodule.html)：普通模块与 Meta 分工、post-fs-data/metamount 顺序、独立内容目录。
- [Magisk 开发指南](https://topjohnwu.github.io/Magisk/guides.html)：system 嵌套分区标准布局与启动前修改模块文件的阶段。
- [APatch 模块指南](https://apatch.dev/apm-guide.html)：普通模块布局与启动 hook；原生能力仍需按实际版本确认。
- [NoMount 上游 metamount.sh](https://raw.githubusercontent.com/maxsteeel/nomount/master/module/metamount.sh)：bin/nm、跳过 skip_mount、驱动加载时机、嵌套分区转换。
- [ZQZCC/my metamount.sh](https://raw.githubusercontent.com/ZQZCC/NoMount/my/module/metamount.sh)：fork 的分区转换差异。
- [NoMount 上游说明](https://github.com/maxsteeel/nomount)：VFS 重定向、CLI 接口与重启后规则失效。

上述源码/文档核对日期为 2026-10-06；用户实际安装的 provider 版本及执行时序，必须在实施验收时另行核对。
