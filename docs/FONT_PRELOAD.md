# 应用字体缓存预加载

此组件用于尝试处理一种字体读取时序：Zygote 或应用可能已记录字体文件，之后应用被排除时挂载发生变化，延迟读取可能遇到不同内容并显示方框。这里的“应用排除”是对目标应用的策略，与 Root 管理器是否排除字域模块本身的挂载是两件事。仅凭安装组件或收到回调不能确认某个应用的字体显示问题已解决。

## 安装与路径

主模块的当前目录为 `/data/adb/modules/ziyu`；独立原生库配套模块 ID 为 `ziyu-font-preload`，当前目录为 `/data/adb/modules/ziyu-font-preload`。管理器会多显示一个「字域字体预加载（实验性）」模块，但不会多安装一个 App。升级待生效时，管理器可能暂存到对应的 `/data/adb/modules_update/` 目录。旧的 `ZiyuFontPreload` 标识只用于兼容和迁移，不是当前目录或模块 ID。

安装器从主模块内的 `common/font_preload_module` 部署该配套模块。它只有 Zygisk 原生库，没有字体挂载负载或启动脚本。主模块不在根目录放置 `zygisk/`，因此即使没有可用 Zygisk，主模块已有的字体挂载流程仍可运行。

组件不会自动安装或启用 Zygisk，不会改写 Magisk、APatch、KernelSU 或 Zygisk Next 的排除设置，也不会停用其他模块。配套模块的停用和移除选择会在主模块普通升级时保留。主模块停用或卸载后，组件不会继续提供字体清单；卸载只给所有权校验通过的配套模块写入管理器标记，由管理器回收。

需要已有 Zygisk 实际执行该组件的应用回调，并完成一次完整重启。FontLoader 的使用说明建议将启用字体定制的目标应用移出 DenyList；Magisk 当前源码在目标同时标记为 `ProcessOnDenyList` 和 `DenyListEnforced` 时不加载 Zygisk 模块。是否调整目标应用的排除设置由用户自行决定，字域不会更改该设置。其他策略若阻止组件回调，预加载无法绕过此限制；如果回调前字体已经不可见，组件也无法补回挂载。OEM 独立字体缓存或应用自带字体同样可能绕过 Android 系统字体缓存。

## 执行路径

主模块只在字体挂载校验完成后准备预加载代际。`common/font_preload_state.py` 使用 schema v2 清单，绑定当前 boot ID、模块身份、后端状态、挂载路由证明以及负载和文件的完整 stat 身份；不读取或哈希字体内容。预加载沿用挂载结果，不能改变字体生成、输出校验、源变化检查、挂载验证或事务回滚结果。

清单只列出本次挂载证明确认的字体。路径按已验证目标和解析结果生成；XML 声明、分区/System 别名和解析出的路径，只有在源文件与 PID 1 可见文件身份符合清单条件时才纳入。它不会把 `/data/fonts` 作为来源。私有负载所有权、路径边界、主模块身份、停用/移除状态、boot ID 和代际仍逐次核验。

原生组件按清单顺序读取路径，不再扫描字体目录。路径在中文、拉丁字母和数字类别间交错排序，避免大量指向同一文件的别名先占满清单，使常见字符类别被挤出。上限为 192 条路径、单字体 128 MiB、逻辑路径映射总量 512 MiB；单项不可用或触及上限时跳过并记入遗漏，其他已确认路径仍可继续处理。超限策略不是常驻内存占用的测量。

预加载分别核对私有源文件身份和 PID 1 命名空间中可见文件身份。这样能够适配提供者复制文件或 Overlay 后可见 inode 与私有源不同的情况；判断依据仍是当前已验证挂载所对应的可见文件，而不是放宽来源归属或挂载验证。准备前后会重新核对代际身份，变化或没有可用字体时整组跳过。

Root companion 将清单传完后立即关闭这条 socket。应用回调完成 JNI 字体缓存调用后，另开连接发送回执，避免字体 I/O 超过 socket 的 150 ms 接收超时而丢失结果。普通回执与 `PROCESS_ON_DENYLIST` 回执按 ABI、代际分别保存。

## 诊断和限制

诊断可导出以下记录：

- 普通回调：`config/font-preload-arm64-v8a.conf`、`config/font-preload-armeabi-v7a.conf`；
- DenyList 回调：`config/font-preload-arm64-v8a-denylist.conf`、`config/font-preload-armeabi-v7a-denylist.conf`；
- 当前清单代际：`config/font-preload-generation.conf`。

`callback-observed` 只表示记录到了回调；`process_on_denylist` 标记该次回调的进程状态；`invoked` 只统计 JNI 调用未抛异常的数量，不证明任何具体字形已在屏幕上正确显示。`catalog_omitted`、`failed`、`skipped` 与 `method_unavailable` 用于解释清单遗漏和调用结果。没有记录或包内存在 `.so` 都不能单独作为显示成功的证据。logcat 标签为 `ZiyuFontPreload`。

当前提供 ARM64 和 ARM32 库。强制策略阻止 Zygisk 回调、挂载提前撤销、非 ARM ABI、OEM 使用独立字体管线、应用自带字体以及已运行进程保留的旧字体缓存，都可能使预加载无效或不适用。它只尝试为进程预热仍可见的字域字体，不保证排除应用恢复原厂字体。修改后应重新打开目标应用；已运行应用需先退出并重新启动。

截至 v1.4.19，本次源码迭代没有完成手机安装、刷入、重启和目标应用显示对照，也没有同条件耗时实测；十项集合回归检查不构成预加载真机验收。不能据编译或 JNI 调用数量宣称所有 APatch/Magisk 排除场景已解决，或宣称实际提速比例。设备验收状态见[真机验证矩阵](TEST_MATRIX.md)。

## 来源与构建

预加载实现按字域 GPL-3.0-only 授权。字体缓存调用思路参考 [FontLoader 的说明与实现](https://github.com/JingMatrix/FontLoader)，其 Usage 建议目标应用退出 DenyList；Magisk 的 [`zygisk_should_load_module`](https://github.com/topjohnwu/Magisk/blob/master/native/src/core/zygisk/daemon.rs) 展示了强制 DenyList 状态下模块加载受限的条件。字域自行实现清单、身份校验、socket 和回执逻辑，没有复制这两个项目的源码，也不会自动更改目标应用的排除设置。Zygisk API v4 固定公共头文件的来源与许可证见 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) 和 [`Zygisk-API-LICENSE.txt`](../licenses/Zygisk-API-LICENSE.txt)。

使用 Android NDK 执行 `python scripts/build_font_preload.py --ndk <NDK目录>`。脚本核对固定头文件、源码、构建配方和两种 ABI 库的哈希；打包器执行 `--verify`，未重编译、库缺失或哈希不匹配时会拒绝打包。配套模块版本从主 `module.prop` 派生。
