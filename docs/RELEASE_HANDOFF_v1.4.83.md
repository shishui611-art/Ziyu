# v1.4.83 正式发布接力

记录日期：2026-11-02（北京时间）。目标：继续构建并发布正式版。用户已授权发布，无需再次索取发布许可。

## 远端检查结果

- 仓库：shishui611-art/Ziyu；main 源提交为 `5539dbb982d822bb7b323620793183f558f706fe`，候选 v1.4.82。
- 待修复工作流：https://github.com/shishui611-art/Ziyu/actions/runs/41450212167 ，attempt 1。
- prepare 与 Android Lint、单元测试、固定证书签名成功；source 失败；package/publish 跳过。
- source job 125293425851 的注释定位到 `scripts/universal_font_deployment_bridge_test.sh`。
- APK 制品 `ziyu-signed-apk-41450212167-1` 属于旧提交和旧版本，不可用于 v1.4.83。
- 最近已发布稳定版为 ziyu-v1.4.70。没有把 update.json 提前指向候选版。

## 本次修复

1. 部署与挂载测试夹具补入真实依赖 `legacy_identity.sh`，未修改生产运行脚本。
2. Google 字体兼容集成夹具从当前 module.prop 读取真实模块身份，修正统一目录 `/data/adb/ziyu/google-font-fallback` 的断言；保留原恢复与只读检查。
3. 按 AGENTS.md 将新交付版本递增为 v1.4.83/14083，同步版本提示、单版本稳定发布授权、CHANGELOG 和 Release notes。

## 已验证与边界

- `universal_font_deployment_bridge_test.sh`、`universal_mount_runtime_test.sh` 通过。
- 紧随其后的字库、原厂度量、符号链接、系统字体库、HyperOS CJK 路由与 Google fallback 检查通过。
- 修正后的 `google_font_fallback_integration_test.py` 12 项通过。
- `release_version_policy_test.py` 16 项、`sync_update_metadata_test.py` 与 diff 空白检查通过。
- 后续 `google_font_provider_lifecycle_test.py` 的取消测试在此容器超时，未声明通过。诊断发现 `os.getpid()` 为 5，而 `/proc/self/stat` 记录 PID 为 19051，说明 PID 视图不一致；生产监督器按 /proc 进程身份核对，因此须在正常 GitHub runner 重验，不能删减断言绕过。
- 未运行完整 source receipt、未构建本次正式签名 APK/ZIP、未做手机验收；设备矩阵仍待测。

## 连接恢复后的动作

1. 先重新核对 origin/main，避免覆盖同时发生的修改；当前本地提交以 5539dbb 为基础。
2. 推送本次提交到 main，按既有 release.yml 触发 v1.4.83 正式发布。旧 run 不能检查新源码，不能用 rerun 掩盖版本差异；保留既有缓存策略。
3. 接续观察失败任务，完整源码、Android、签名和包检查全部成功后才发布。不要向不存在的 tag dispatch。
4. 检查 tag 对应提交、Release 的 ZIP 与 sha256、内置 APK 和本次签名构建的一致性。
5. 等待 Sync online update metadata 完成并检查线上清单。

当前发布阻塞是 GitHub 写入认证不可用：无已连接 GitHub 工具，Git dry-run push 返回无法读取 Username；浏览器插件连接也超时。本地修复已保存，待连接 GitHub 后接续，不要重新拉起旧 v1.4.82 工作流或重复此前修复。
