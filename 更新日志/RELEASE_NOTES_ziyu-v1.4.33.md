# 字域 v1.4.33（本地 Debug 候选）

修复系统更新后「字体完全没挂载、软重启怎么点都没用」的问题。

## 诊断里发生了什么

设备从 `C.168763c_fd34d9_fead8e` 更新到 `C.2dc2531-738a0b-74ae55`（构建指纹变化）。模块检测到变化后按设计进入 OTA 保护：

- `config/boot-safety-current-boot.conf`：`state=blocked`、`reason=ota-fingerprint-change`
- `config/mount-backend.conf`：`verification=failed`、`last_error=ota-fingerprint-change`、`selected_backend=unresolved`
- `config/boot-ota-pending.conf`：`scan_complete=0`（新系统还没做过受信任的原厂字体扫描）
- 日志：每次软重启后都是 `mounting blocked: ota-fingerprint-change; no payload activated`

保护本身是对的：加载状态下扫描会把用户字体当成「原厂」，所以必须先解锁挂载再扫。但**当时只有「设置 → 开机保护 → 重新扫描原厂字体」这一条恢复路径**，软重启入口不做这件事，于是每次软重启都回到同一块状态；字体入口则直接报「系统字体基准尚未核验」。这就是「应用不完全」和「软重启无效」的共同原因。

## 本版修复

- 新增 `common/boot_ota_recover.sh`：封装「重扫原厂字体」与「为当前系统重新准备字体负载」两步，带超时、日志和状态判断。
- `common/soft_reboot.sh`：检测到 OTA 保护时，先在**后台**完成重扫与负载重备，再自动执行软重启；调用本身立即返回，不会把界面卡住（App 侧软重启调用只有 15 秒超时）。
- `common/legacy_v14_4/font_switch_safe.sh`、`common/legacy_v14_4/mix_router.sh`、`common/font_mix_controller.sh`：遇到「需要重扫」时自动重扫一次并重试，不再让用户先手动去开机保护页。
- 原厂布局契约、源变化检查、输出校验、挂载验证、事务回滚以及 OTA 保护本身的判定语义全部保留；重扫仍只在挂载被阻止时进行。

## 验证

- 5 个改动的 shell 脚本通过 `sh -n` 语法检查。
- 打包后核对模块清单、版本、内外 APK SHA 一致与 ZIP 完整性（见 `package-verification.json`）。
- **未**在手机安装、刷入、重启或做显示对照；OTA 之后的实际重扫耗时、软重启后是否挂载成功待手机验收。诊断里 `boot-ota-guard.log` 未包含，无法确认旧版本重扫失败的具体位置，本版把重扫与重备都收敛到同一入口并写日志，便于下次诊断。

## 产物

- `Ziyu-App-v1.4.33-debug.apk` / `Ziyu-v1.4.33.zip`（内置同一 APK），SHA-256 与包完整性检查见交付目录 `package-verification.json`。
