# 字域发布流程

## 1. 版本与制品

- `module.prop` 是模块、App 与发布文件名的唯一版本来源。
- `versionCode` 规则：`major*10000 + minor*1000 + patch`（如 `v1.6.0` → `16000`，正式 tag 为 `ziyu-v1.6.0`）。
- 正式 GitHub Release 仅发布 `Ziyu-vX.Y.Z.zip` 与 `Ziyu-vX.Y.Z.zip.sha256`（内含同版本正式签名 App，ZIP 体积上限 14 MiB / 14,680,064 字节；`docs/`、`更新日志/`、`README.md` 留在仓库不打入模块包）。

## 2. 签名规则

- 正式包与本地 Debug 包统一使用固定正式签名（`CN=Ziyu Release`，证书 SHA-256 `ebfd6167fe727ab3ada7dd5cf44dc3d3c345812fab4f6d5699f69ea572688468`），应用包名均为 `io.github.shishui611_art.ziyu`，支持无损覆盖保留数据。
- 签名材料仅通过受控环境变量注入：`ZIYU_KEYSTORE_FILE`、`ZIYU_KEYSTORE_PASSWORD`、`ZIYU_KEY_ALIAS`、`ZIYU_KEY_PASSWORD`（CI 从 Actions Secrets 注入）。严禁将密钥、密码、解密材料或本机路径提交到仓库。

## 3. 发布前准备

1. 同步更新以下 4 处文件：
   - `module.prop`（`version`、`versionCode`）
   - `config/version_notes.conf`
   - `更新日志/CHANGELOG.md`
   - `更新日志/RELEASE_NOTES_ziyu-vX.Y.Z.md`（相对上一正式版分列新增、修复、优化及待测项）
2. 检查 `scripts/module_payload_manifest.txt` 是否包含全部新增运行文件，核对 `THIRD_PARTY_NOTICES.md` 与 `licenses/`。
3. 如设备矩阵（[`TEST_MATRIX.md`](TEST_MATRIX.md)）仍有待测项且已获维护者单版本发布授权，更新 `config/stable_release_authorization.conf` 绑定当前版本。
4. 确认工作树干净，不含诊断日志、临时构建物或签名文件。

## 4. 打包与自动发布

### 本地正式打包

```sh
# 1. 通过环境变量提供正式签名后构建 Release APK
gradle -p android-app --no-daemon :app:assembleRelease

# 2. 打包模块 ZIP（含清单元件、原生工具与体积门禁）
ZIYU_APP_APK=android-app/app/build/outputs/apk/release/app-release.apk \
ZIYU_APP_PACKAGE=io.github.shishui611_art.ziyu \
ZIYU_APP_VERSION_CODE=16000 \
ZIYU_APP_VERSION_NAME=1.6.0 \
  sh ./scripts/build_debug_fast.sh
```

打包后核对：`aapt2` 包名与版本、`apksigner verify --print-certs` 证书指纹、独立 APK 与 ZIP 内 `bundled/Ziyu-App.apk` 的 SHA-256 一致、ZIP 完整性与体积上限。

### GitHub 自动发布

1. 推送版本提交到 `main` 后，`.github/workflows/release.yml` 自动运行源码回归、Android Lint/单元测试、正式签名构建、ZIP 打包与就绪检查，通过后创建不可变 tag 并发布 Release。
2. Release 成功后，`sync-update-metadata.yml` 自动同步 `update.json`、`update-prerelease.json` 与 `config/stable_version_policy.json`（发布前请勿提前修改 `update.json`）。
