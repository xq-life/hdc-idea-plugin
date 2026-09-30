# HDC Wi-Fi 1.1.0 Marketplace 提交记录

## 提交信息

- 插件：`HDC Wi-Fi`
- Plugin XML ID：`com.xq.hdcwifi`
- Marketplace Plugin ID：`34637`
- Marketplace Update ID：`1182296`
- 版本：`1.1.0`
- 渠道：Stable
- 作者 / Vendor：`xq-life`
- 许可证：MIT
- 兼容范围：`233.0 — 261.*`
- 提交时间：2026-09-29 18:15:09（Asia/Shanghai）
- 当前状态：等待 JetBrains Marketplace 审核（`approve=false`、`listed=false`、`hidden=false`）
- 编辑页：https://plugins.jetbrains.com/plugin/34637-hdc-wi-fi/edit
- 待审版本页：https://plugins.jetbrains.com/plugin/34637-hdc-wi-fi/versions/stable/1182296

## 本地签名制品

- 文件：`build/distributions/HDC Wi-Fi-1.1.0-signed.zip`
- 大小：277,630 bytes
- SHA-256：`5a92a7aab7916b527f6b6b1e82e6f0e0b50b84b8395e4b2f926e8e1dad2f76ea`
- `verifyPlugin`：通过
- `verifyPluginSignature`：通过
- ZIP 完整性：通过

## Marketplace 保存的制品

Marketplace 会再次签名 / 重压缩插件，因此服务器制品与本地签名包的外层 ZIP 字节不一致，这是预期行为。

- 文件：`HDC_Wi-Fi-1.1.0-signed.zip`
- 大小：282,984 bytes
- SHA-256：`a21b75c00312e98b148f90bfd2b889ca3cbbad344ab5a0da012e973e792b5118`

已直接解析服务器制品的内层 JAR，并确认：

- `plugin.xml` 版本为 `1.1.0`；
- vendor URL 为 `https://github.com/xq-life`；
- 连接弹窗标题、设备 IP 标签、提示语与错误文案均为中文；
- 动作词 `Connect` 按产品约定保留英文。

## 自动化验证

- `148 tests completed`
- `0 failed`
- `0 errors`
- `0 skipped`
- 本地 `runPluginVerifier`（Verifier 1.409）：
  - `IC-2023.3` → `Compatible`；
  - 自动枚举首次因 JetBrains 已下架的 `IC-2025.2.6` 下载失败，已改为显式版本列表；
  - `IC-2025.3` 平台扫描长时间无进一步输出，已停止该额外任务；
  - Marketplace 服务器已为待审制品生成完整兼容矩阵：IDEA `2023.3 — 2026.1.5`，Android Studio `Jellyfish | 2023.3.1 — Quail 4 | 2026.1.4 Patch 1`，并覆盖 CLion、Rider、WebStorm、PyCharm 等对应平台版本。

## 审核后动作

审核通过后检查公开页、图标、描述、版本兼容范围和下载文件；若需再次上传，必须先提升 `pluginVersion`，Marketplace 不接受相同版本号的重复制品。
