# Ziyu 外部挂载优先 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> 当前会话不自动委派。执行时优先在现有会话逐任务实施；保留工作区已有改动，不创建丢失未提交改动的 checkout。

**Goal:** 外部 provider 可接管时完全放权，确认无 provider 时才使用 Ziyu 私有 NoMount，并保留已验证 legacy 兼容事务。

**Architecture:** 把当前字体生成链的输出发布为 provider 支持的标准树；显式 target/source manifest 同时服务外部接管校验与私有 fallback。能力检测与引擎选择分离，启动前冻结代次，所有后续变化通过 pending + boot ID。

**Tech Stack:** Android BusyBox ash、现有 Python/FontTools/XML 工具、Kotlin App、Root 管理器模块生命周期、固定版本 NoMount CLI。

**Spec:** `docs/MOUNT_PROVIDER_FIRST_DESIGN.md`。

## Global Constraints

- Ziyu 始终是普通模块，保持现有 `id=LuoShu`，不写 `metamodule=1/true`。
- Ziyu 不枚举 `/data/adb/modules/*`。
- 外来 skip_mount 和 provider 对 Ziyu 的明确排除不能被 fallback 绕过。
- 外部能力 unknown 不能映射为 absent。
- EXTERNAL_PROVIDER 不执行 Ziyu 的 nm、mount、模块视图 bind 或热补挂。
- 一份活动字体实体载荷；staging/短期回滚代次只能在无引用后回收。
- provider/backend/字体/布局切换通过 pending + boot ID；不热切已提交 backend。
- 不执行全局 NoMount 清理、不加载驱动、不修改外部 provider 全局配置。
- 本文件是未来实施计划，本次仅新增文档，不改变 v1.1.14 运行代码或包。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| 新增 common/mount_provider_detection.sh | 只读探测 external 能力、身份、布局与内容根 |
| 新增 common/mount_backend_policy.sh | 无副作用的优先级选择 |
| 新增 common/font_config_bake.py / .sh | ROM 指纹、现有合并器适配、候选代次校验 |
| 新增 common/compiled_payload.py / .sh | logical target→source 映射、发布 journal、恢复 |
| 修改 common/mount_backend_runtime.sh | 调度三个执行分支和启动冻结，不保留旧热 failover |
| 修改 common/font_runtime_policy.sh、common/private_payload.sh | 抽离 import 时主动 bind；旧布局只作迁移入口 |
| 修改 common/next_boot_payload.sh、common/universal_next_boot.sh | 统一候选代次激活与发布事务 |
| 修改 common/font_config_runtime.sh、common/font_config_partitions.sh | 复用合并/发现，输出指向 staging，识别干净 ROM 源 |
| 修改 common/mount_nomount_backend.sh、common/nomount_rule_json.py | 自身 compiled manifest 与 CLI/协议版本校验 |
| 新增 common/nomount_capability.sh、common/bin/ziyu-nm | 私有后端验证许可与固定版本 CLI，仅 ARM64 首版 |
| 修改 common/install_ui.sh、customize.sh、common/mount_backend_preferences.sh | 自动 external 优先及旧偏好迁移 |
| 修改 post-fs-data.sh、.luoshu-runtime/core/post-fs-data.sh、service.sh 等 hook | 在发布边界调度，不建立 private view |
| 修改 App 状态模型、ZiyuViewModel.kt、ui/settings/SettingsHubScreen.kt | planned/active/health/pending 分开展示 |
| 修改 scripts/module_payload_manifest.txt、scripts/check.sh、打包与声明文件 | 显式新增运行文件、协议与许可证、发布检查 |

## Task 1: 外部能力判定与不可覆盖的优先级

**Files:** 新建 provider detection/policy 与 `scripts/mount_provider_detection_test.sh`、`scripts/mount_backend_policy_test.sh`；扩展现有 Meta 检测测试。保留旧 Meta 检测给迁移路径，避免它在新 external 路径调用 nm。

**Interfaces:** `ziyu_mount_provider_detect "$MODDIR"` 导出 PROVIDER_STATE、PROVIDER_ID、PROVIDER_LAYOUT、PROVIDER_CONTENT_ROOT、PROVIDER_REASON。`ziyu_mount_select foreign provider private_verified legacy_supported fallback_preference` 打印模式；除 state 外无写操作。

- [ ] 加入以下最小策略与断言，先验证缺少新策略时失败：

```sh
ziyu_mount_select() {
    [ "$1" != true ] || { printf 'disabled\n'; return; }
    case "$2" in
        available|unknown) printf 'external\n'; return ;;
        excluded) printf 'disabled\n'; return ;;
        absent) ;;
        *) printf 'none\n'; return ;;
    esac
    if [ "$3" = true ] && [ "$5" != legacy ]; then
        printf 'self_nomount\n'
    elif [ "$4" = true ]; then
        printf 'legacy_fallback\n'
    else
        printf 'none\n'
    fi
}

# scripts/mount_backend_policy_test.sh 中 source 实现后执行：
test "$(ziyu_mount_select false available true true legacy)" = external
test "$(ziyu_mount_select false unknown true true auto)" = external
test "$(ziyu_mount_select false absent true true auto)" = self_nomount
test "$(ziyu_mount_select false absent false true auto)" = legacy_fallback
test "$(ziyu_mount_select true available true true auto)" = disabled
test "$(ziyu_mount_select false excluded true true auto)" = disabled
```

- [ ] provider fixture 覆盖有效 Meta、停用 Meta、无 Meta、活跃 Magisk 原生、APatch 已验证原生、读取失败、多个身份冲突和被排除的 Ziyu。unknown 必须保留。
- [ ] NoMount fixture 在 early hook 返回 `nm version` 失败、但 metamount 有有效后续加载路径时，仍计划 external；检测器不调用 nm。
- [ ] Run: `bash scripts/mount_backend_policy_test.sh` 与 `bash scripts/mount_provider_detection_test.sh`。Expected: 上述所有选择成立，探测 spy 中 nm/mount 调用为 0。
- [ ] 检查 `git diff`，保证检测不扫描其他模块、不写 provider 配置。

## Task 2: 编译 manifest、布局 profile 和新鲜 XML

**Files:** 新增 bake/compiled Python 工具及对应 shell 薄封装；修改既有 font config runtime/partitions。新建 `scripts/font_config_bake_test.py` 和 `scripts/compiled_payload_layout_test.py`。

**Interfaces:** `source_fingerprint(path: Path) -> dict` 返回 path/size/mtime_ns/sha256；`published_relative(target: str, layout: str) -> str` 支持 nested-system/top-level-partitions；`bake_generation(input_root: Path, output_root: Path, rom_sources: list[dict], layout: str, rom_identity: str) -> dict` 返回 spec 的 compiled manifest。bake 通过现有合并器处理 XML，失败不修改 output_root 之外的文件。

- [ ] 写入下列边界测试；接口缺失时应失败：

```python
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from compiled_payload import published_relative
from font_config_bake import source_fingerprint

class PublishedLayoutTest(unittest.TestCase):
    def test_product_mapping_is_explicit(self):
        self.assertEqual(published_relative('/product/etc/fonts.xml', 'nested-system'),
                         'system/product/etc/fonts.xml')
        self.assertEqual(published_relative('/product/etc/fonts.xml', 'top-level-partitions'),
                         'product/etc/fonts.xml')

    def test_same_size_and_mtime_cannot_hide_source_change(self):
        import os
        with TemporaryDirectory() as tmp:
            p = Path(tmp) / 'fonts.xml'
            p.write_bytes(b'<a/>')
            stamp = p.stat()
            old = source_fingerprint(p)
            p.write_bytes(b'<b/>')
            os.utime(p, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            self.assertNotEqual(old['sha256'], source_fingerprint(p)['sha256'])
```

- [ ] 输入分区复用既有发现结果；为 NoMount upstream、ZQZCC/my、Magisk/APatch 和独立内容目录分别定义 profile。未知版本输出 unknown，不承诺所有布局兼容。
- [ ] 新增干净源检查：当前输出哈希相同的 XML 不能作为 ROM 源；原始快照 rom_identity 不匹配则拒绝。坏 XML、缺失字体引用、重复 target、源目录逃逸、未支持 OEM target 都阻止 commit。
- [ ] 修改字体生成入口，让新布局写入显式 staging；既有 `.luoshu-payload-next` 与 Universal generation 作为迁移输入，不再把活动树作为可写临时目录。
- [ ] Run: `PYTHONPATH=common python3 scripts/compiled_payload_layout_test.py` 与 `PYTHONPATH=common python3 scripts/font_config_bake_test.py`；Expected: 布局正确、源变化可检测、失败不改活动代次。
- [ ] 复用现有 XML、分区与字体路由测试确认语言/数字/字重保留，不从最小 XML fixture 推导全 ROM 覆盖。

## Task 3: 启动前发布事务与 v1.1.14 迁移

**Files:** compiled_payload.py/.sh、private_payload.sh、font_runtime_policy.sh、两个 next-boot helper、physical_payload_manifest.sh。新建 `scripts/compiled_payload_publish_test.py`。

**Interfaces:** `publish_generation(module: Path, stage: Path, manifest: dict, boot_id: str) -> dict`；`recover_publish(module: Path, boot_id: str) -> dict`。仅发布 manifest 所属 fonts/XML；journal 指向自身已核对的内容根，最后写 generation commit。

- [ ] 增加故障注入测试：在每个 rename/文件提交之后模拟异常，调用 recover 后必须是完整旧代次或完整新代次；不能混合 fonts/XML。
- [ ] 在 migration fixture 保留 `system/bin/luoshud` 和一个非 font 文件；发布/恢复后其哈希保持相同。
- [ ] 迁移旧私有载荷时，用清单确定自身字体/XML，不移动或清空整棵 system；旧来源在提交成功且无引用后才回收。
- [ ] 把原 import 时 `luoshu_private_mount_module_view` 副作用去掉；新外部分支不调用该函数。保留明确的旧布局迁移工具入口。
- [ ] 恢复默认字体也走待提交代次，只撤自身发布清单中的 fonts/XML；不热改当前活动源。
- [ ] Run: `PYTHONPATH=common python3 scripts/compiled_payload_publish_test.py`。Expected: 全部断点可恢复、无清单外删除、默认恢复与旧布局迁移幂等。

## Task 4: external 启动闭环与只观测的晚期 hook

**Files:** mount_backend_runtime.sh、post-fs-data.sh、.luoshu-runtime/core/post-fs-data.sh、post-mount.sh、service.sh、动态补挂和 Meta 热同步入口；扩展 mount_backend_orchestration_test.sh 与 service_mount_truth_test.sh。

**Interfaces:** 新状态 schema 使用 `ziyu-mount-backend-v2`，字段包括 boot_id、provider_state/id/layout、planned_backend、active_backend、generation、verification、pending_reason。旧 reader 只读兼容；新状态不能被旧 reader 当作本启动已验证状态。

- [ ] 将“外来 skip 闸门→journal 恢复→候选准备→provider 判定→bake 校验及发布→freeze→标志准备”移到所有主动 mount/规则入口之前；外来 skip 存在时只撤销未提交候选、不推进新代次；对应 root identity reader 识别新 schema。
- [ ] external fixture 装入会把所有调用写入 spy 文件的 nm、mount、umount 和 dynamic apply 函数；贯穿安装、post-fs-data、post-mount、service、App apply 后 spy 必须为空。
- [ ] external 校验失败只记录失败和下次启动计划；取消 `_lbr_meta_failover` 在 external 分支的同启动自挂载行为，也取消修改 Hybrid 全局/模块路由来强制 VFS 的行为。
- [ ] 自身旧规则台账跨 boot 时只清磁盘，不执行 del；已提交 external 模式下不尝试根据 target/source 删规则。
- [ ] Run: `bash scripts/mount_backend_orchestration_test.sh` 和 `bash scripts/service_mount_truth_test.sh`。Expected: 外部零主动注入、旧 self 偏好无法覆盖外部、晚期 provider 变化只 pending、同启动 backend 冻结。
- [ ] 在 SukiSU Ultra + NoMount 记录扫描前标准树发布完成、provider 真正扫描时间及最终 target/source；先验收 external 后再做私有后端许可。

## Task 5: 无 provider 时的私有 NoMount

**Files:** nomount_capability.sh、固定版本 common/bin/ziyu-nm、THIRD_PARTY_NOTICES.md、对应许可证、mount_nomount_backend.sh、nomount_rule_json.py。扩展 mount_nomount_backend_test.sh。

**Interfaces:** `ziyu_nomount_capability_probe "$MODDIR"` 仅在 confirmed absent 分支调用；验证状态包括 CLI 哈希、接口版本、内核标识和上下文。`luoshu_nomount_apply` 消费 compiled manifest 的显式 target/source。退出码 0=成功，1=clean failure，2=dirty failure。

- [ ] 固定 CLI 源码版本/提交、构建来源和许可证，不把 NoMount 全量 Meta 目录装进 Ziyu；第一版只发布已验证 ARM64 CLI，其他架构走兼容提示。
- [ ] 在自身临时诊断目录使用两个小文件：原始内容 A、替换内容 B，先检查无目标规则，执行 add、读取目标为 B、del、读取目标恢复 A；失败保留可清理的自身台账并停止启用。CLI 哈希/接口/内核变化撤销许可。
- [ ] mock 覆盖：外部 available/unknown 时 capability probe 调用数 0；absent + 验证成立才 apply；已有 target 冲突不覆盖；第 N 条失败只回滚自己已提交项；删除失败不进 legacy；嵌套分区 target 不被误写成 /system/product。
- [ ] 台账与 backend freeze 共同约束删除资格；不把相同 source/target 当成内核所有权令牌。
- [ ] Run: `bash scripts/mount_nomount_backend_test.sh`。Expected: 事务/冲突/dirty failure 均满足上述断言。
- [ ] 在无外部 provider 的真实 NoMount 接口环境完成模块上下文闭环后开放许可。没有该设备证据时，候选包仍能 external 接管，私有功能保持未验证状态。

## Task 6: 安装/App 状态和偏好迁移

**Files:** install_ui.sh、customize.sh、mount_backend_preferences.sh、app_bridge.sh、ZiyuViewModel.kt、SettingsHubScreen.kt 和相应现有状态测试。

**Interfaces:** App 状态包含 providerName/providerState/plannedBackend/activeBackend/verification/pendingReason/fallbackPreference；fallbackPreference 仅 auto/legacy，仅影响 absent 分支。

- [ ] 删除音量键强制 Meta/self 的全局策略；旧 self 保存为历史诊断值并迁移成自动优先，external 继续胜出。
- [ ] 安装显示“计划交给 NoMount Meta，重启后验证”；active 状态必须来自本 boot 的成功校验，不能把 READY 当作已生效。
- [ ] 私有未验证、无接口、legacy、unknown、被排除分别展示实际原因；支持恢复默认和下次启动切换，但不暗示即时生效。
- [ ] 运行现有 preferences/status 回归，并以 Kotlin 单元测试覆盖 v1/v2 状态解析；Expected: 不混淆 planned 与 active，self 不再压过 available/unknown provider。
- [ ] 重新构建 App；版本仍从 module.prop 读取，不另写 APK 版本常量。

## Task 7: provider 时序、OTA 与交付门槛

**Files:** docs/TEST_MATRIX.md、发布说明、新版 module.prop/version_notes、scripts/module_payload_manifest.txt、scripts/check.sh、打包脚本。

- [ ] 记录每个实际 provider 的身份/版本/profile/扫描时刻。先验证用户设备；随后验证 KernelSU + NoMount、Hybrid/Hymo、APatch 原生/Meta、Magisk 以及两种无 provider 环境。
- [ ] 用 ROM XML 指纹变化与 boot slot 变化模拟 OTA；在实机确认干净源获取和扫描前撤下过期配置。提前 snapshot 且无失效保护的实现，不标动态 XML/OTA 已支持。
- [ ] 验证 external↔private 跨重启切换、手工 skip、provider 排除、升级保留字体、恢复默认、卸载；external 的卸载只撤 Ziyu 输出/自身状态，外部已生效规则留给 provider 生命周期或重启。
- [ ] 所有新运行文件列入显式 manifest，包内无 metamodule 声明、全局扫描脚本或默认强开私有 NoMount 配置。
- [ ] 运行完整源检查和 App 构建；若宿主 mock 与真机语义不一致，修复或明确隔离其适用范围，不能将忽略失败记录成全部通过。
- [ ] 新版本号生成 APK + 模块 ZIP + SHA-256 +体积报告。核对嵌入 APK 包名/版本/哈希、执行权限、manifest target/source 和禁止目录。旧 v1.1.14 包不覆盖。
- [ ] 报告做了什么、哪些环境验收通过、哪些尚未取得真机证据以及是否偏离 spec；没有完成标准树和 external 零主动注入闭环，不能称为本架构完成。

## 实施检查点

1. Task 1–3：逻辑与编译/迁移可验证；生产行为尚未切换。
2. Task 4：完成用户 NoMount Meta 外部接管，满足主要诉求。
3. Task 5–6：确认无 provider 的私有 fallback，状态与策略一致。
4. Task 7：按已取得证据发布支持矩阵与新的交付包。

不为本计划预设工时或宣称五种环境均已验证；实际硬件与 provider 版本会决定验收时间。
