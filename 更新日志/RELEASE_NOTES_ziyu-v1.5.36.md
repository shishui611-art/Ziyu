# 字域 v1.5.36

## 补查遗漏：nested-system 空目录

设备检查发现，manager 的 `nested-system` 发布会在模块里留下：

```
system/product  system/system_ext  system/vendor  system/mi_ext
system/my_*  system/oplus_*  ...
```

这些空目录不触发 NoMount 顶层分区检查，但属于脏状态，也会被其他管理器/provider 扫描。

修复：

- `post-fs-data.sh` 与 `restore_stock_factory.sh` 在清理顶层空分区目录后，一并 `rmdir` 空的 `system/<partition>`；
- 与 v1.5.34 的“发布跳过空负载分区”配合，后续不再产生此类空目录。

设备侧已清理当前嵌套空目录。
