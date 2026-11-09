# CI 签名密钥配置

CI 构建与产物说明见仓库根 [README.md](../../README.md) 的「CI 自动构建」「Known Limitations」。本页说明签名配置及手动 ARM64 Release 产物构建。

## 需要配置的 GitHub Secrets

仓库 `Settings → Secrets and variables → Actions → New repository secret`，添加 4 个：

| Secret 名称 | 取值 |
|---|---|
| `AICODE_KEYSTORE_BASE64` | `app/aicode.jks` 文件的 base64 编码（见下） |
| `AICODE_KEYSTORE_PASSWORD` | keystore 的 `storePassword` |
| `AICODE_KEY_ALIAS` | 签名 key 的 `keyAlias` |
| `AICODE_KEY_PASSWORD` | key 的 `keyPassword`（通常与 keystore 口令相同） |

`GITHUB_TOKEN` 由 Actions 自动注入，无需手动配置。

## 生成 keystore 的 base64

仓库根目录用 PowerShell 复制到剪贴板：

```powershell
[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes("app\aicode.jks")) | Set-Clipboard
```

粘贴到 `AICODE_KEYSTORE_BASE64`。

若还没有 keystore，先用 keytool 生成（别名/口令自定，回填到对应 Secret）：

```powershell
keytool -genkeypair -v -keystore app\aicode.jks -alias aicode -keyalg RSA -keysize 2048 -validity 10000
```

> `aicode.jks` 与 `keystore.properties` 都在 `.gitignore` 里，不会入仓库，本地签名不受影响。

## 手动 ARM64 Release 产物

在 Actions 选择 `ARM64 Release APK`，仅从 `master` 手动运行。该工作流不创建 Tag 或 GitHub Release，仅上传 `arm64-release.apk`、版本元数据、SHA256SUMS、公钥证书摘要及 R8 映射，保留 14 天。

此入口从上述四个 Secrets 恢复 PKCS#12 签名库，并在 runner 转为 JKS 供 Gradle 使用。签名材料不进入 artifact，构建禁用配置缓存。工作流会校验证书指纹与固定签名一致、APK 只包含 arm64-v8a、包名为 `com.aharou.agent` 且不可调试；缺失签名时立即失败，不生成临时替代签名。

请长期备份签名库及口令。换用不同签名不能直接覆盖安装旧正式版；不要为了出一次包重新生成签名。签名变更也需显式更新工作流中的公开证书指纹校验。
