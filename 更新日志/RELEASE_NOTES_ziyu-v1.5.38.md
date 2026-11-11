# 字域 v1.5.38

## 排查用：槽位覆盖扩展默认关闭

现象：应用字体后重启，`system_server` 出现

```
watchdog: Blocked in handler on main thread (main) for 66s
```

然后重启、卡在开机动画；默认字体 + 模块启用则能正常开机。

怀疑点：槽位覆盖扩展把用户字体（35MB/147MB、以及硬链接）写进 30 个槽位，
包括 `NotoSansCJK-Regular.ttc` 等 TTC/其它语种槽位；ColorOS 开机字体服务解析这些槽位时
可能阻塞主线程超过 60 秒。

本版改动：

- `zsiyu_slot_coverage_expand` 加总开关，**默认关闭**；
- 只有 `config/tools/slot-coverage-enabled.conf` 写入 `enabled=1` 时才启用扩展；
- 其它发布/挂载逻辑不变。

验证方法：刷入后应用字体并重启。

- 如果能正常开机 → 确认是槽位扩展导致，再逐步放开安全槽位（跳过 TTC/其它语种）；
- 如果仍卡开机 → 说明问题不在扩展，需要继续缩小到字体文件本身（VF/TTC 内容）。

## 限制

未做手机验收；开机验证需实机重启。
