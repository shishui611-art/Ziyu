# 正式模块更新

- 进入主界面或返回前台时直接请求 GitHub `releases/latest` API；只接受正式 Release，不读取预发行版或未发布的 main 分支更新预告。国内网络可能无法访问该 API、Raw 文件或 Release 下载地址；当前版本没有国内镜像，不能承诺无代理可用。
- 读取 `/data/adb/modules/LuoShu/module.prop` 比较模块语义版本及版本代码，绝不使用 APK 版本作为是否更新的依据。已有 `/data/adb/modules_update/LuoShu/module.prop` 时提示完整重启，不重复安装。
- 新版弹窗提供“暂时跳过”与“更新模块”；跳过仅作用于当前进入周期。无更新或联网失败不弹启动提示，错误可在设置的“模块更新”页查看。
- 更新只下载 Release 中名称匹配的 `Ziyu-vX.Y.Z.zip`。需要同 Release 的 `.sha256`，核对下载大小、SHA-256、ZIP 根目录模块 ID `LuoShu`、版本及安装脚本、内置 APK。
- 校验后的 ZIP 复制到唯一 Root 临时文件，再校验一次 SHA-256。根据确认的管理器使用 Magisk `--install-module`、KernelSU / SukiSU `module install`、APatch `module install`，失败不切换管理器重试，不直接解压覆盖模块目录。
- 调用管理器前设置 `ZIYU_DEFER_APP_INSTALL=1`。本版模块的 `app_installer.sh flash` 保留首次启动补装标记，避免正在更新模块时替换正在运行的 App。重启后原有服务安装模块内置 App。
- 安装结束再次读取待生效模块身份。成功后用户可选择完整重启或稍后重启。下载失败、校验不符、管理器不明时停止并显示具体错误。最近的安装日志保存在 App 私有目录 `files/module-update/latest.log`。
- 尚未完成手机上的更新安装验收；联网元数据检查、编译与打包不代表实机更新成功。

## App、模块与 Debug 包关系

正式更新入口更新的是**完整模块 ZIP**；ZIP 内嵌同版本、正式签名的 App，安装模块时由现有模块安装流程更新 App。单独安装 APK 不会更新字体处理/挂载运行时。Release 资产为 `Ziyu-vX.Y.Z.zip` 和 SHA-256 文件，正式 APK 不单独发布。

正式签名环境齐全的 Debug 包沿用正式 applicationId 和证书，可覆盖安装正式 App；本地缺少正式签名时使用 `.debug` 独立包。签名不同的两个包不能相互覆盖，旧 `.debug` 包不会自动卸载。
