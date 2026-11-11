# 字域 v1.5.33

## 修复：NoMount 开机挂载失败

现象：刷入后开机，`mount-backend.log` 显示

```
route mismatch ok=0 total=15; metamodule owns module mounting
verification=failed reason=manager-mount-delegated-to-metamodule
```

NoMount `boot.log`：

```
VFS missing target: ziyu/mi_ext
VFS injection completed: result=1
```

原因：`ziyu_provider_payload_publish` 会为 `ziyu_private_partitions` 里的**每个分区**创建目标目录，
即使本代负载里没有该分区。于是模块根目录出现 `mi_ext`、`my_*`、`oplus_*` 等空目录。
NoMount 把它们当成待注入分区，遇到设备不存在的 `mi_ext` 目标后直接中止**全部** VFS injection，
结果字域一条路由都没挂上。

修复：

- 发布时只为「负载里真实存在」或「模块里已有旧内容需要回收」的分区建目录；
- `post-fs-data.sh` 与 `restore_stock_factory.sh` 清理已有的空分区目录。

## 限制

未做手机验收；开机挂载恢复需刷入本包并重启后验证 `mount-backend.log` 的
`route verified ok=15 total=15`。
