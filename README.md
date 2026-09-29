# HDC Wi-Fi

HDC Wi-Fi 是一个 IntelliJ Platform 插件，用于通过 Wi-Fi 管理 HarmonyOS 与 OpenHarmony 设备。它把 `hdc` 命令行工具包装成一套可视化工作流。

## 功能

- 按 IP 地址和端口连接设备，并保留最近使用的主机记录
- 查看已连接的 USB 设备，并把 Wi-Fi 设备按「可用 / 已连接 / 之前连接过」分组
- 在类 ADB Wi-Fi 风格的面板中连接、断开、遗忘、刷新与查看设备
- 按需扫描本地网络，发现可连接的设备
- 一眼看清每台设备的状态：已连接、连接中、失败、未连接
- 在工具栏开关自动刷新并选择间隔（关闭 / 5s / 10s / 30s / 60s）。按钮本身就会显示当前设置——轮询中显示绿色加粗的 `30s`，关闭时显示灰色的 `关闭`，不必展开菜单就能读到状态
- 可选地自动连接已保存或新发现的设备；串行执行，默认关闭
- 展开或折叠设备分组，并在内置控制台中查看实际执行的 HDC 命令
- 查看常见的设备与系统属性
- 对选中目标执行 shell 命令
- 向设备发送文件、从设备接收文件
- 对 `hilog` 输出进行流式查看、过滤、停止、重启与清空
- 配置 HDC 可执行文件，或使用 SDK / PATH 自动检测

## 设备状态

每个设备行处于四种状态之一，同时用独立的图形和文字标签表示：

| 状态 | 指示符 | 含义 |
| --- | --- | --- |
| 已连接 | 带对勾的实心圆 | 目标在线且可用 |
| 连接中 | 动态圆环 | 正在尝试连接；该行的按钮处于禁用状态 |
| 失败 | 带 `!` 的实心圆 | 尝试失败；把鼠标悬停在行上可看到底层错误 |
| 未连接 | 空心圆 | 已保存或已发现，但当前不在线 |

**Connect** 是主操作；**Disconnect** 有意做成带背景色的次要操作，两者不会混淆——Connect 是平面绿字，Disconnect 是圆角 error 色块，悬停与按下时提亮，禁用时色块一并淡化。

> 界面为中文，但按钮与菜单项上的**动作词**（`Connect`、`Disconnect`、`Forget device`、`Scan for devices`、`Cancel scan`、`Copy address`、`Device tools...`）保留英文。

## 在网络中查找设备

点击工具窗口工具栏上的 **Scan for devices**。扫描会通过 TCP 探测你已保存的主机、本地 `/24` 网段，以及（若已配置）自定义 CIDR 网段，然后把结果列在 **网络上的可用设备** 分组下。扫描只在你要它跑的时候运行，并发数有上限，并且可以在同一个工具栏按钮上取消。

如果你的设备不在本地子网内，请在 **Settings > Tools > HDC Wi-Fi** 中设置自定义网段；每个 IPv4 网段每次扫描最多 4096 个地址。

进度显示在 **最后更新** 旁边，并在扫描结束后几秒自动消失。什么都没扫到时界面会明确说明；扫描根本无法运行时（例如读不到本机网络接口）则会报告该原因——因此空列表绝不会被误认成"网络里没有设备"。

**已连接设备**、**之前连接过的设备** 与 **网络上的可用设备** 三个分组标题无需鼠标即可操作：点击可折叠分组，或让标题获得焦点后按 `Space` / `Enter` 切换、按 `Right` 展开、按 `Left` 折叠。焦点会停留在你正在使用的标题上，因此连续按键无需再按 Tab 找回。

## 环境要求

- IntelliJ IDEA 或其他基于 IntelliJ 的 IDE，build 233 或更高
- 包含 `hdc` 可执行文件的 HarmonyOS 或 OpenHarmony SDK
- 目标设备已启用无线调试（用于 Wi-Fi 连接）

## 从磁盘安装

1. 用 `./gradlew buildPlugin` 构建插件。
2. 在 IDE 中打开 **Settings > Plugins**。
3. 选择 **Install Plugin from Disk**，并选中 `build/distributions/` 下的 ZIP。
4. 按提示重启 IDE。

## 配置 HDC

打开 **Settings > Tools > HDC Wi-Fi**。选择 `hdc` 可执行文件，或将路径留空以从常见的 DevEco Studio、HarmonyOS、OpenHarmony SDK 位置以及 `PATH` 中自动检测。可用 **测试** 确认该可执行文件能否运行。

## 连接设备

1. 在设备上启用无线调试，并记下它的 IP 地址与端口。
2. 打开 **HDC Wi-Fi** 工具窗口。
3. 点击 **Connect**，分别填入主机与端口，或直接填 `IP:Port`，然后确认。
4. 选中已连接的目标，即可使用 Info、Shell、Files、Log 各标签页。

## 自动连接

两个选项都在 **Settings > Tools > HDC Wi-Fi** 下，且**默认关闭**：

- **自动连接扫描发现的设备** —— 扫描结束后，连接扫描到的设备。
- **打开工具窗口时自动连接已保存的设备** —— 启动时重连你已知的设备。

自动连接严格串行（一次一台设备），每台设备每个会话最多尝试一次，并且复用与手动点击完全相同的代码路径——所以你看到的始终是正常的「连接中 → 已连接 / 失败」状态流转。失败会显示在设备行和输出控制台中，而不是弹出通知。显式断开某台设备会抑制为它排队的所有自动重连。

## 构建与验证

```shell
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

可安装的压缩包输出到 `build/distributions/`。

## 发布到 Marketplace

Gradle 构建从 `INTELLIJ_PUBLISH_TOKEN` 读取 JetBrains Marketplace 令牌：

```shell
INTELLIJ_PUBLISH_TOKEN=... ./gradlew publishPlugin
```

发布前请更新版本号与更新说明，核对 vendor 与仓库 URL，并运行上面的完整验证命令。
