# Ziyu 三套原生设计体系

## 用户规格与边界
用户授权全局 UI 重构。共享原有 Route、ViewModel、仓储、持久化键及业务回调；不修改字体处理、挂载、Root、更新与备份事务。Material 3、MIUIX 0.9.3、COUI 1.1.0 各自提供真正的主题、控件、弹层和交互。原生主题拥有颜色与文字样式；MaterialTheme 仅作为旧调用点语义数据桥，不让非 Material 主题渲染 Material 控件。

## 组件契约
保留页面业务内容，ui/design 提供 ZiyuText/Icon/Surface/Button/TextButton/OutlinedButton/FilledTonalButton/IconButton/Checkbox/RadioButton/Switch/Slider/TextField/OutlinedTextField/Progress/Divider/ListItem；参数兼容现有使用方式。ui/theme/ZiyuCard 独立分发原生卡片。所有派发均根据 LocalUiStyle，使用三个库自身的组件和反馈。弹层以原生 OverlayDialog/OverlayBottomSheet/OverlayListPopup 实现，保留回调、关闭、键盘和滚动。导航与标签页由主代理整合。

## Task 1: 原生主题和令牌
负责 ZiyuTheme.kt、ZiyuLayoutTokens.kt、新主题令牌文件。安装 MiuixTheme/COUITheme，提供三套独立语义色彩、原生字体与尺寸，AMOLED/Monet/种子色/暗色设置兼容。原生 popup host 在根部安装。缓存 controller 只依赖颜色设置。不要改动作、页面、组件。

## Task 2: 基础组件
负责新 ui/design/ZiyuActions.kt、ZiyuInputs.kt、ZiyuText.kt、ZiyuProgress.kt、ZiyuLists.kt。按上述兼容契约实现真正原生分发，映射 enabled/error/selection/a11y/interactionSource；标签和输入不丢失。不要修改主题、Card、弹层、页面导入；主代理迁移调用点。

## Task 3: 弹层和交互
负责新 ui/design/ZiyuDialogs.kt、ZiyuSheets.kt、ZiyuMenus.kt、ZiyuMotion.kt。AlertDialog/ModalBottomSheet/DropdownMenu 契约兼容，非 Material 使用库自身 Overlay 组件，原生 dismiss/drag/IME/back。动画避免非活跃状态无限刷新。不改主题、基础组件、调用点。

## Task 4: 页面和导航集成
主代理负责原生 Card/Surface、标题栏、导航、标签页，迁移全部活动包控件导入。清除 COUI 组合页复用 MIUIX 设计标识，保留语义页面共享；不复制业务逻辑。动作状态由原 Route 维持。检查所有独立页面、空/载入/错误状态和业务回调；保持排序搜索、导入导出、预览、调节、更新、日志、任务、备份路径。

## Task 5: 集成与交付
编译修复实际依赖 API，审阅全差异。按 AGENTS 不新增或运行测试。构建新 Debug APK 和同 APK 的模块 ZIP；校验版本、签名、清单、ZIP 与哈希。不安装/刷机/发布。文档记录迁移表、未进行真机和动画性能验收。

## 全局约束与审查
代理文件互斥；共享 LocalUiStyle 与 LocalMiuixTokens 兼容。允许第三库的主题数据桥，禁止非 Material 组件实际渲染 Material。所有业务函数和存储保持。无额外依赖，无复制第三方源代码。编译/打包检查不替代手机验收。
