# Google 字体兼容

Google 应用（如 Google Play、Google App、Chrome、Gmail、YouTube）会通过 GMS 下载字体服务动态拉取并恢复 `Google Sans`，部分新版应用还会在 APK 内自带 `Google Sans`。

入口：**设置 → Google 字体兼容**（默认关闭）。

## 使用步骤

1. 先在字域应用自定义字体并重启手机。
2. 如 Google 应用反复恢复 `Google Sans`，打开本页点击「开启 Google 字体兼容」并确认。
3. 显示「已开启」后完整重启手机，再检查应用显示效果。

## 防恢复机制

- **双组件停用**：同时停用当前用户的 GMS `FontsProvider` 与 `UpdateSchedulerService`（原状态写入恢复记录）。
- **清理并只读封堵缓存**：清空当前用户 GMS（含 `user_de`）与 Play 商店的 `files/fonts` 缓存目录，并挂载模块自有的只读空目录封口，避免 GMS 后台自愈后重新写入 `Google Sans`。
- **开机自动加固**：开启后每次开机自动复检并重新停用被 GMS 更新复活的组件、补齐缓存封堵；GMS 刚更新完时也可在本页点击「重新加固」立即生效。
- **应用内置字体兜底**：若个别 Google 应用仍显示 APK 自带的 `Google Sans`，可在 LSPosed 启用字域模块并勾选对应应用，详见 [LSPosed 网页与 Google 字体](WEBVIEW_LSP.md)。

## 影响与恢复

- **账号与数据安全**：仅停用上述两个字体组件并封堵 `files/fonts` 缓存目录，不停用整个 Google Play 服务，不清除账户、应用数据或数据库。
- **影响范围**：影响当前 Android 用户下依赖 GMS 下载字体的应用（可能包含下载式表情字体）。
- **恢复与卸载**：点击「恢复原设置」会卸载缓存只读封口并按记录恢复原组件状态。**停用或卸载字域模块前，请先在此页点击「恢复原设置」并完整重启。**

## 参考来源

组件停用思路参考 [killgmsfont](https://github.com/MrCarb0n/killgmsfont)，字域独立实现可逆账本、只读空目录封口与开机加固。
