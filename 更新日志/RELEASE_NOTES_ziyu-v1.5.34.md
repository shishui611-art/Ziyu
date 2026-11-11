# 字域 v1.5.34

## 修复：NoMount 仍报 `VFS missing target: ziyu/mi_ext`

v1.5.33 只修了“发布时创建空目录”，但 `.ziyu-payload` 里本身就带着
`mi_ext`、`my_*`、`oplus_*` 等**空分区目录**；发布仍会为它们建目标目录，
模块根又出现空目录，NoMount 再次中止全部 VFS injection。

修复：

- 发布时只处理「负载目录里有内容」或「模块里已有旧内容要回收」的分区，空负载目录直接跳过；
- `post-fs-data.sh`：开机先删 `.ziyu-payload` 和模块根里的空分区目录；
- `restore_stock_factory.sh`：恢复默认时同样清理空负载分区目录。

设备侧已手动清掉当前的空负载分区目录（payload 现在只剩 `system`），
刷入本包后重启可验证 `route verified ok=15 total=15`。

## 限制

未做手机验收；开机挂载需重启后验证。
