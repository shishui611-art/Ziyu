# 字域 v1.5.35

## 补查修复

- `post-fs-data.sh` 清理 `.ziyu-payload` 空分区目录改用 `rmdir` 循环：Android `find` 不保证支持 `-delete`，旧写法在部分 ROM 上会静默失效；
- 发布、恢复默认、开机三处空分区目录清理逻辑保持一致，覆盖：

  - `.ziyu-payload/<partition>`（空负载目录）
  - 模块根 `<partition>`（被 NoMount 误判为待注入分区）
  - 恢复默认时同路径清理

## 已知仍保留

- `stock_font_snapshot` 的 384MB 快照预算、诊断输出的 8/12MB 上限属于内部预算，不跟随字体滑块；
- NoMount 的 `VFS missing target` 判断只看目录是否存在，第三方模块若残留空分区目录也会中止注入，本版只处理字域自身目录。

## 限制

未做手机验收；需重启后验证 `route verified ok=15 total=15`。
