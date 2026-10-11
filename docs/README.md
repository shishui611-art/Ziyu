# 文档索引

## 当前使用与行为说明

- [使用指南](USER_GUIDE.md)：安装、导入、挂载选择、切换与恢复。
- [挂载流程](MOUNT_FLOW.md)：四种用户选择的挂载模式、升级偏好和验证状态。
- [字体热切换](FONT_LIVE_SWITCH.md)：热切换前提、限制及需要重启的情况。
- [临时 Root 与软重启](TEMP_ROOT.md)：KernelSU / SukiSU Ultra 的适用条件和操作边界。
- [模块更新](MODULE_UPDATE.md)：模块 ZIP 与内置 App 的更新关系。
- [字体诊断](FONT_DIAGNOSTICS.md)、[挂载故障诊断](MOUNT_FAILURE_DIAGNOSIS.md)：日志字段和排查顺序。
- [ColorOS 适配](COLOROS17.md)：字体槽、桥接链接和部分应用结果。
- [测试矩阵](TEST_MATRIX.md)：自动化与真机验证状态；待测项目不代表已验收。
- [发布流程](RELEASING.md)：版本、签名和自动发布门禁。
- [版本记录](../更新日志/CHANGELOG.md)：历史版本变更；具体差异见对应发布说明。

## 文档适用范围

上列文档描述当前源码行为。带日期的设备研究、性能测量、故障复盘和 `superpowers/plans/` 设计计划记录其编写时的证据或候选方案；它们不是当前操作指南，也不能覆盖当前源码与上列说明。挂载行为以 `common/mount_backend_policy.sh`、`common/mount_backend_runtime.sh` 和 [挂载流程](MOUNT_FLOW.md) 为准；设备支持状态以 [测试矩阵](TEST_MATRIX.md) 为准。
