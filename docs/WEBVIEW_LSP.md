# LSPosed 网页与 Google 字体

字域内置可选的 LSPosed 模块入口（Xposed API 82），对用户在 LSPosed 勾选的目标 App 同时提供两项能力：

1. **系统 WebView 网页字体**：页面加载完成后为正文可见文字应用系统 `sans-serif`（自动避开图标字体、私用区符号、代码块、SVG 与画布）。
2. **拦截 Google Sans 内置与下载字体**：拦截目标 App 对 `Google Sans` / `Product Sans` 的 GMS 字体查询、`Resources.getFont`、`Typeface` 资源/文件加载，按原字重与斜体转回系统无衬线字体（自动排除 `Code`、`Mono`、图标、Emoji 与时钟字体）。

## 开启步骤

1. 在 LSPosed 管理器中启用「字域」模块。
2. 手动勾选需要处理的 **目标 App**（例如 Google Play、Google App、Gmail、Chrome、YouTube 等；系统框架无需勾选）。
3. 强行停止并重新打开目标 App 即可生效。

## 停用与恢复

如个别应用或网页图标显示异常，在 LSPosed 中取消勾选对应 App（或关闭字域模块）并重启该 App 即可立即恢复。
