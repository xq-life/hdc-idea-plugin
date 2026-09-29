# HDC Wi-Fi — 独立验收报告（交付版，对照 UX-CONTRACT v1.7 / UX-SPEC v1）

> 作者：独立验证（verify-qa）。**本文件只做验收与举证，不改主代码。**
> 本版为**第 6 轮（交付版）**：先复核第 5 轮交付后的两个缺陷修复（A4.20 无结果文案、分组标题重建后焦点丢失），
> 再复核用户真机截图反馈的 U1–U6。第 1–5 轮的结论保留在 §3.1/§3.2 与本文档各表中。
> 基线：`src/main/**` = 当前磁盘内容（哈希见 §0.1），我未做任何修改；`src/test/**` 与 `docs/ux/ACCEPTANCE.md` 是我全部改动的范围。
> 测试规模：**20（第 1 轮前）→ 95 → 101 → 105 → 106 → 121**，`./gradlew clean buildPlugin test` 全绿。
>
> **交付结论**：**88 项 = 87 PASS / 0 FAIL / 0 PARTIAL / 1 MANUAL**，`OPEN -` / `RESIDUAL -` 前缀已清零。
> 第 3 轮残留 R1/R2/R3 成立修复；第 4 轮唯一 PARTIAL（A4.20 无结果文案）与 A9.4 的焦点隐患本轮均已修复：
> `emptyScanText()` 按「保存地址 × 自定义网段」四选一，`FOCUS_SECTION_KEY` + `findSectionHeader()` 让键盘焦点在重建后回到同一分组。
> 唯一 MANUAL 项 A3.1 是开关两态在 IDE 主题下的观感，必须 `runIde` 目视（§5）。
> 第 6 轮（本轮）复核契约 v1.7 的 **U1–U6**（用户真机截图反馈）：分割条白线、设备行被拉伸 412px、
> 工具栏不显示刷新间隔、开关两态不易区分、间隔菜单缺当前值、Disconnect 无背景、设置页英文。
> 7 项中 U1–U6 记为 6 个新验收项（A10.1–A10.6），全部 PASS；**这几项的最终观感仍需 `runIde`（§5 第 11–14 项）**。

## 0. 复现方式与环境

```shell
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew clean buildPlugin test   # BUILD SUCCESSFUL；tests=121 failures=0 errors=0 skipped=0
ls -l build/distributions/         # HDC Wi-Fi-1.1.0.zip
```

> `buildPlugin` **不依赖** `:test`：只跑 `buildPlugin` 不会执行测试；必须如上一行把 `test` 放进同一命令行（或先 `cleanTest`），否则 `:test` 可能被判定为 `UP-TO-DATE` 而看似"通过"。

单跑某一类（13 个测试类）：

```shell
./gradlew test --tests 'com.xq.hdcwifi.ContractConformanceTest'                  # 静态一致性（16）
./gradlew test --tests 'com.xq.hdcwifi.ContractRegressionTest'                  # 历次缺陷的契约形态（25）
./gradlew test --tests 'com.xq.hdcwifi.model.DeviceStateTest'                   # 四态 + 签名单射性（8）
./gradlew test --tests 'com.xq.hdcwifi.service.HdcScanCidrTest'                 # CIDR / 候选 / 告警（11）
./gradlew test --tests 'com.xq.hdcwifi.toolwindow.PanelInteractionContractTest' # 队列/抑制/扫描按钮状态机（12）
./gradlew test --tests 'com.xq.hdcwifi.toolwindow.PanelRenderingContractTest'    # 分割条/行高/按钮色块（10）
./gradlew test --tests 'com.xq.hdcwifi.settings.HdcSettingsStateTest'           # 安全默认（6）
```

辅助对象（都在 `src/test`）：`com.xq.hdcwifi.MainSources`（源码文本切片；找不到成员即 `check` 失败，不会静默跳过）、
`com.xq.hdcwifi.service.HdcServiceReflection`（反射调用 `HdcService` 的 private 纯函数与 `CandidateSet`；签名/成员名不符即 `error(...)`）。

环境事实（实测，不是假设）：

| 事实 | 值 | 影响 |
|---|---|---|
| 测试 JVM 是否初始化 IntelliJ 平台 | **否**，`ApplicationManager.getApplication() == null` | 无法构造 `HdcService.instance` / `HdcMainPanel` / `Project`，因此**服务注册、面板渲染、EDT 行为、焦点与读屏无法在自动化测试里断言**，只能源码级断言 + 人工目视 |
| `HdcService()` 构造 | 可用（构造器不依赖平台） | 反射可调用其 private 纯函数 |
| `deviceSignature` / `expandCidr` / `candidateTargets` / `localNetworkCidrs` | 可直接或反射调用（真实实现） | 签名单射性、CIDR、候选、告警语义可**动态**验证 |
| `HdcService.SCAN_CONCURRENCY` / `INTERFACE_ERROR` | `internal`，测试可直接引用 | 常量值可编译期断言 |
| 唯一可用的自动化 UI 证据 | 默认 LAF（本机为 Aqua）下的 Swing 绘制与像素采样 | 开关/四态/方向键/读屏/分割条/chip 的实际观感仍需人工；像素镜像只能证明**机制**，不能证明 IDE 主题下的最终颜色 |

**判定口径**：`PASS` = 有可复现证据；`FAIL` = 与契约冲突且有反例；`PARTIAL` = 机制成立但边界/文案不满足；`MANUAL` = 本环境无法自动化。`FAIL`/`PARTIAL` 全部在 §3 立项。

### 0.1 验证快照（被验证的确切代码版本）

本次结论对应的 `src/main/**` 内容（SHA-256，2026-09-29 15:3x 采集）。**若这些哈希变化，本报告所有 `文件:行` 引用与结论都要重新核对。**

| 文件 | SHA-256 |
|---|---|
| `toolwindow/HdcMainPanel.kt` | `31caa4ffb60ea4e2e890c716007f1ac168a186296d45978c9a7e216a57d36675` |
| `service/HdcService.kt` | `cc5c6b655e7026f22ed574b8fa9048da16352c9ff32af11bb6013b73fd349491` |
| `model/HdcDevice.kt` | `97206dbd0d4e49cf33693007735112613a821d427f88fecae6ec956174286f36` |
| `settings/HdcSettingsConfigurable.kt` | `eca7dc064022d52d689a2dea1ca1b3a7dbaf9d10c7aa951c9666531adda89f11` |
| `settings/HdcSettings.kt` | `09415ff62e7ba5d82944a6b04ccecbf86cf018680445e214087f07b46cb5790c` |
| `service/HdcCommandRunner.kt` | `0118ddee49fe382e781af1e480e560fe9e09f5c40dd4ed8ee792738b9e4a5e1b` |
| `service/HdcStream.kt` | `37401999aaf2e88ba7785ed0e890cf1651f8df9a493a0aca9a6b20f7666b5876` |
| `service/HdcTargetParser.kt` | `acd4a6e9c4ddb6e40d6316a6997b8636750283af71a77b7b35bc9a9c4bbf83de` |
| `service/HdcPathResolver.kt` | `a581b0cc11614656beac702424cc7a06614403bdc6b70c929beac8f0dc96bfe8` |
| `toolwindow/HdcToolWindowFactory.kt` | `4dc1412f11db43889a2551f7947238c80f2eefb551062501d223e68e001e8fd0` |
| `toolwindow/ConnectDeviceDialog.kt` | `d4a27895d4495190950a0b3f2936e8853492520c66601b0bbf72f03509e93d98` |
| `toolwindow/DeviceInfoPane.kt` | `b07ecc9c0192226e5e5f39089a7e6604efe5d52a0ada71d1fb4e2d5eb8c75d0c` |
| `toolwindow/DeviceToolsDialog.kt` | `231270a2ee472216d36a6a38a1672d5325c1555dd8a7eb84529e756e57aa2036` |
| `toolwindow/FilePane.kt` | `beacbbf9c98387a1842a59373b82ea1e412934d135be8e2323f332a5d736f1a5` |
| `toolwindow/LogPane.kt` | `13eb755a60a1cf9646b3b68f420d3d14a6fb8d7888fc5201ed56a00e1005750c` |
| `toolwindow/ShellPane.kt` | `03494c279156747a522a092c9e3f18a7d18ca0b471964793b9f52a5a7e639947` |
| `HdcNotifications.kt` | `45db5205802adf903e3434443f1ec0465b7e70819ea39d1de13d2dcb3676a4d3` |
| `Icons.kt` | `db898a1d998933eae9cc0841ce653d3e4238d96b06283b14d68f7a32c5734103` |

复核命令：`find src/main -name '*.kt' | sort | xargs shasum -a 256`

第 6 轮（本轮）变化的主代码是 **2 个文件**：`toolwindow/HdcMainPanel.kt`（U1–U5）与
`settings/HdcSettingsConfigurable.kt`（U6）；其余 **16 个哈希自第 5 轮未变**。我在本轮仍只改
`src/test/**` 与本文档。

---

## 1. 验收项总览

| 章节 | 项数 | PASS | FAIL | PARTIAL | MANUAL |
|---|---:|---:|---:|---:|---:|
| §0 颜色原则 | 1 | 1 | 0 | 0 | 0 |
| §1 连接状态模型 | 12 | 12 | 0 | 0 | 0 |
| §2 Connect/Disconnect 区分 | 6 | 6 | 0 | 0 | 0 |
| §3 自动刷新 | 11 | 10 | 0 | 0 | 1 |
| §4 可连接设备检测 | 20 | 20 | 0 | 0 | 0 |
| §5 自动连接 | 11 | 11 | 0 | 0 | 0 |
| §6 其他体验缺陷 | 6 | 6 | 0 | 0 | 0 |
| §8 修订裁决 | 5 | 5 | 0 | 0 | 0 |
| §9 可访问性 | 8 | 8 | 0 | 0 | 0 |
| §10 真机截图反馈（v1.7 U1–U6） | 6 | 6 | 0 | 0 | 0 |
| 历史缺陷回归（§8 v1.2） | 2 | 2 | 0 | 0 | 0 |
| **合计** | **88** | **87** | **0** | **0** | **1** |

演进：第 1 轮 76 项（65/4/5/2）→ 第 2 轮 80 项（74/1/3/2）→ 第 3 轮 80 项（76/0/3/1）→ 第 4 轮 81 项（79/0/1/1）→
第 5 轮 82 项（81/0/0/1）→ **第 6 轮（交付版）88 项（87/0/0/1）**。

第 6 轮新增（UX-CONTRACT §8 v1.7，用户真机截图反馈）：

| 项 | 判定 | 依据 |
|---|---|---|
| **A10.1** 分割条不再渲染成亮白粗线 | **PASS** | `HdcMainPanel.kt:97-104` 自绘分割条：`UIUtil.getPanelBackground()` 铺底 + `UIUtil.getBoundsColor()` 1px 中线；`PanelRenderingContractTest#the split divider is painted with theme colours instead of the laf background` |
| **A10.2** 单个设备行不再被拉伸 | **PASS** | `:936-938` `FixedHeightPanel.getMaximumSize()`；`:553` 设备行、`:431` 空态使用；`:409` 唯一可增长子组件是末尾 glue；含裸 `JPanel` 反例的 Swing 镜像测试 |
| **A10.3** 工具栏显示刷新间隔 + 开关两态三重差异 | **PASS** | `:880/883/884/885`；`:256-265` 工厂不再固定 30x28；`ContractConformanceTest` + `AutoRefreshToggleStyleTest`（4 例） |
| **A10.4** 间隔菜单先给当前值表头 | **PASS** | `:271`（禁用表头）→ `:274`（分隔符）→ `:275-285`（`REFRESH_INTERVALS` ✓ 列表） |
| **A10.5** Disconnect 有可辨识背景且与 Connect 不同 | **PASS** | `:583-612` 自绘圆角色块（`:618-621` 主题 error 混色，浅 0.16/深 0.34；`:624-629` `blend`）；像素镜像复算探针值 `#5E3335` |
| **A10.6** 设置页用户可见文案全部中文 | **PASS** | `HdcSettingsConfigurable.kt`（28 行含汉字）；正则不变量"所有控件/helper 字面量必含汉字" + 16 条中文文案断言 |

交付版改判（第 4 轮 → 第 5 轮）：

| 项 | 之前判定 | 交付版 | 依据 |
|---|---|---|---|
| A9.3 分组标题可访问性 | 第 3 轮 PARTIAL | **PASS**（第 4 轮） | `HdcMainPanel.kt:945-956` 新增 `SectionHeaderPanel` 覆写 `getAccessibleContext()` → `AccessibleRole.PUSH_BUTTON`（SPEC §9 :270 的"按钮**或** `PUSH_BUTTON` 组件"析取条件已满足） |
| A9.4 Left/Right 键 | 第 3 轮 PARTIAL | **PASS**（第 4 轮） | `:498-510` 拆为 `EXPAND_ACTION`（仅展开）/ `COLLAPSE_ACTION`（仅折叠），反方向 no-op，符合 §9 :270 |
| A4.19 枚举失败可感知 | 第 3 轮 PARTIAL | **PASS**（第 4 轮） | `HdcService.kt:159-166` 失败返回 `null`；`:137-157` 产出 `INTERFACE_ERROR` 告警；`:90-94` 候选择空 / `:117-122` 有告警且无结果 → `Result.failure`；`:123` 有结果则成功并携带告警；`HdcMainPanel.kt:779` 送控制台 |
| **A4.20** 无结果文案按来源区分 | 第 4 轮 PARTIAL | **PASS**（本轮） | `HdcMainPanel.kt:447-457` `emptyScanText()` 四选一 + `:394` 仍由 `scanCompleted` 把关；R5 反例（仅自定义网段误报本地网络）已消除 |
| **A9.8**（新增）重建后焦点保持 | — | **PASS**（本轮） | `:387-389` 在 `:390` `removeAll()` **之前**抓取 `focusOwner`（null 安全、限定在面板内），`:412-416` 同一 `invokeLater` 内 `findSectionHeader(...)?.requestFocusInWindow()` |
| D13 设置页文案 | 等价改写 | **等价改写（契约 v1.5 已记为收口）** | `HdcSettingsConfigurable.kt:166-171` |

- **FAIL 0 项**（连续第四轮无 FAIL；`OPEN -` / `RESIDUAL -` 前缀均已清零）。
- **PARTIAL 0 项**：第 4 轮唯一 PARTIAL（A4.20）已转 PASS。
- **MANUAL 1 项**：A3.1（开关两态在 IDE 主题下的实际观感）。
- **有自动化证据**：88 项中 **65 项**（§2 的 86 项里 63 项 + 2 项历史回归；121 个测试方法① 或 `grep`/源码断言直接覆盖）。
  ① 121 − 20（第 1 轮既有测试）= 101 个新增/改写的测试方法。
- **PASS 但需 `runIde` 目视复确认**：A10.1/A10.2/A10.3/A10.5 的最终观感（机制已自动化证明，见 §5 第 11–14 项）；
  这与 MANUAL 的区别是：MANUAL 项**整体**无法自动化，而这 4 项的不变量（绘制取值、最大值契约、两态属性差、像素环）已在测试 JVM 内断言。

---

## 2. 逐条验收（契约 → 证据 → 方法）

### §0 颜色原则

| ID | 契约要求 | 证据（文件:行） | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A0.1 | 禁用硬编码颜色，`Color(0x59A869)` 必须清除 | 全 `src/main` 无 `0x59A869`；唯一原始色值 `HdcMainPanel.kt:986` `JBColor(Color(0x2E7D32), Color(0x63B36B))` 是状态语义色且为双主题 | `ContractConformanceTest#removed legacy styling` | 是 | **PASS** |

### §1 连接状态模型

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A1.1 | 四态枚举互斥、可区分 | `model/HdcDevice.kt:3` | `DeviceStateTest#declares exactly the four contract states in order` | 是 | **PASS** |
| A1.2 | `withState` 保证状态与 `connected` 一致 | `HdcDevice.kt:45-49` | `DeviceStateTest#withState keeps exactly one state…` | 是 | **PASS** |
| A1.3 | CONNECTED = 实心圆 + `✓` + 成功绿 | `:571-573`(statusColor) `:959`(StatusIcon) `:978`(fillOval) `:981`(`"\u2713"`)；语义色定义 `:986` | 代码级 + 目视（§5 清单 2） | 部分 | **PASS** |
| A1.4 | CONNECTING = 旋转指示器，12fps，有动效 | `:141`(animationTimer) `:901-906`(仅 CONNECTING 时运行) `:999`(83ms≈12fps) `:987`(SPINNERS 帧) `:975`(当前帧) | `ContractConformanceTest#the spinner timer…`；连续旋转需目视 | 部分 | **PASS** |
| A1.5 | CONNECTING 文案为单字符省略号 `…` | `:566` `"Connecting\u2026"` | `ContractConformanceTest#a connecting row…` | 是 | **PASS** |
| A1.6 | FAILED = 红实心圆 + `!`（不靠颜色单独传达） | `:573`(getErrorForeground) `:981`(`"!"`) | 代码级 + 目视 | 部分 | **PASS** |
| A1.7 | FAILED tooltip 显示原始错误，状态持久到成功/重试/在线 | `:514`(构造 `failureTip`)、`:525-527`(状态行 tooltip)、`:557`(行容器 tooltip)、`:558`(图标 tooltip) 四处；仅成功(`:674`)与 list targets 在线(`:316`)清除；错误同时写 Console `:681` | 代码审查；`DeviceStateTest#withState carries the failure reason only when one is given` | 部分 | **PASS** |
| A1.8 | DISCONNECTED = 空心圆、次要前景色 | `:881`(drawOval) `:548`(getContextHelpForeground) | 代码级 + 目视 | 部分 | **PASS** |
| A1.9 | 状态文字独立成行，不复用 `details` | `:447-468 deviceRow` 内三个独立 `JBLabel`：`:495`(名称) `:499`(状态) `:503`(details) | 代码级 | 是（源码） | **PASS** |
| A1.10 | 状态与按钮语义一致：CONNECTED 只出现「断开」 | `:540` `AllIcons.Actions.Suspend else AllIcons.Actions.Execute`；`:520` Connected 行不渲染 Forget | `ContractConformanceTest#connect and disconnect differ in icon and colour` | 是 | **PASS** |
| A1.11 | CONNECTING 时按钮**可见但禁用**（不得替换成文本） | `:540` 按钮仍 `add(...)` + `.apply { isEnabled = !connecting }`；Forget(`:522`)/More(`:524`) 同样禁用（全文恰好 3 处） | `ContractConformanceTest#a connecting row keeps a visible but disabled action button` | 是 | **PASS** |
| A1.12 | 失败原因可从 UI 回溯 + Console 同时保留 | 同 A1.7（`:681` 写 Console，前缀 `Connection failed: `） | 代码级 | 部分 | **PASS** |

### §2 Connect / Disconnect 按钮区分

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A2.1 | Connect 图标 = 运行/接入类 | `:540` `AllIcons.Actions.Execute` | 同 A1.10 | 是 | **PASS** |
| A2.2 | Disconnect 图标 = 停止/挂起类 | `:540` `AllIcons.Actions.Suspend` | 同上 | 是 | **PASS** |
| A2.3 | Disconnect 前景**不得为绿色** | `:604` `foreground = if (secondary) UIUtil.getLabelForeground() else SUCCESS`；`secondary = online`（`:515`） | `ContractConformanceTest#connect and disconnect differ…` | 是 | **PASS** |
| A2.4 | 文案 `Connect` / `Disconnect` | `:513` | 同上 | 是 | **PASS** |
| A2.5 | 两者都是 flat 但不可看起来一样 | `:605` `isContentAreaFilled = false`（两者共用）；差异来自图标 + 前景色 | 代码级 + 目视 | 部分 | **PASS** |
| A2.6 | Forget 用 `General.Remove`，不得用 `Actions.GC` | `:547` `AllIcons.General.Remove`；`Actions.GC` 仅出现在 `:144`（Clear command output，语义正确） | `ContractConformanceTest#forget uses the remove icon and never the gc icon` | 是 | **PASS** |

### §3 自动刷新

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A3.1 | 工具栏可开关，带两态可视差异 | `:205-208` `autoButton = toggleButton(`；`:257` `JToggleButton(icon).apply {`；`:261` `isContentAreaFilled = true` / `isBorderPainted = true`（与 flat 图标按钮 `:229-230` 相反） | `AutoRefreshToggleStyleTest`（默认 LAF 下 on/off 像素确实不同）+ 目视 | 部分 | **MANUAL**（见 §5 清单 1） |
| A3.2 | tooltip 显示当前间隔，文案精确 | `:271` `"Auto-refresh: Off"` / `"Auto-refresh: Every ${seconds}s"` | `ContractConformanceTest#auto refresh keeps the contract intervals…` | 是 | **PASS** |
| A3.3 | 快捷间隔 Off/5/10/30/60 | `:991` `listOf(0, 5, 10, 30, 60)`；菜单 `:275-285` `REFRESH_INTERVALS.forEach`；Off 点击启用 10s `:187`（`DEFAULT_REFRESH_SECONDS=10`，`:906`） | 同上 | 是 | **PASS** |
| A3.4 | 与设置页双向一致 | 设置按钮关闭后重读并 `configureAutoRefresh()` `:214-218`；`apply()` 写回 `HdcSettingsConfigurable.kt:193-201` | 代码审查 | 部分 | **PASS** |
| A3.5 | 窗口不可见时跳过 tick | `:292` `(!manual && !isShowing && hasBeenShown)`；`:894` `if (isShowing)` | 代码审查（面板不可实例化） | 否 | **PASS** |
| A3.6 | 恢复可见时立即补刷 | `:155-161`：`if (showing && !wasShowing) refreshDevices(manual = false)`，随后 `wasShowing = showing`；`hasBeenShown` 只用于"首次显示前不发刷新"。面板实例由 `HdcToolWindowFactory.kt:11-15` 每工具窗只建一次，故该分支是唯一补刷点 | `ContractRegressionTest#becoming visible again always triggers one refresh` | 是 | **PASS** |
| A3.7 | 显示 `Last updated HH:mm:ss`，失败不更新 | `:220-224` 初始 `Last updated -`；`:316` 成功才 `SimpleDateFormat("HH:mm:ss")`；失败分支 `:324-332` 不更新 | 代码审查 | 否 | **PASS** |
| A3.8 | 无变化不重建 UI；**签名必须是恒等映射** | `:353-354` 签名 = `deviceSignature(groups) + ":$scanStatus:$availableExpanded:$connectedExpanded:$previousExpanded"`；`HdcDevice.kt:57-73` 三组固定 + `GROUP_SEPARATOR`/`FIELD_SEPARATOR` + 失败原因前缀（`:70`：`null` → `NO_REASON`，非 null → `HAS_REASON` + 值，`:77-78`） | `DeviceStateTest`（8 例，含 **15 个单字段变体两两不等的单射性**）、`ContractRegressionTest#different device sets…` / `#a device moving between groups…` / `#a null and an empty failure reason are different identities` | 是 | **PASS** |
| A3.9 | 必须重建时保留滚动位置 | `:384` 记录 `verticalScrollBar.value`，`:412` `invokeLater` 恢复 | 代码审查 | 否 | **PASS** |
| A3.10 | 详情 TTL ≥60s，不得每 tick 全量 `param get` | `:1003` `DETAIL_TTL_MS = 60_000L`；`:791` 仅缺失详情且过 TTL 才请求；服务层缓存 `HdcService.kt:287-322` | 代码审查 | 部分 | **PASS** |
| A3.11 | `autoRefreshSeconds` 仍为权威值，范围 `0..3600` | `settings/HdcSettings.kt:56-59` `coerceIn(0, 3600)` | `HdcSettingsStateTest#the custom cidr is trimmed and the refresh interval is clamped` | 是 | **PASS** |

### §4 可连接设备检测（扫描）

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A4.1 | 工具栏新增 `Scan for devices`（搜索图标） | `:195` `AllIcons.Actions.Search` | 代码审查 | 是（源码） | **PASS** |
| A4.2 | HDC 不可用或扫描中时按钮禁用；**扫描进行中必须始终可取消** | `:834-838` `setScanAvailable` 只记录 `hdcAvailable`；`:843-853` `refreshScanButton()` 单一推导点：`scanning = scanHandle != null`、`isEnabled = hdcAvailable \|\| scanning`、tooltip 三分支（`scanning` 优先）、`accessibleName = tooltip`。四处状态迁移都重推导：`:660`（扫描结束）、`:731`（开始）、`:741`（取消）、`:766`（可用性变化） | `ContractRegressionTest#the scan button reflects hdc availability` / `#a running scan stays cancellable even when hdc disappears` / `#the scan button has a single derivation point`；`PanelInteractionContractTest#availability is only recorded…` | 是 | **PASS** |
| A4.3 | 默认不扫描，须显式点击 | 全文无自动 `startScan()` 调用 | `grep -n startScan` | 是 | **PASS** |
| A4.4 | 扫描过程可取消 | `:196` 第二次点击即 `cancelScan()`；`:804-814`；`HdcService.kt:27-33` `cancel()`。取消后按钮状态由 `HdcMainPanel.kt:770` 重推导，不会停在 "Cancel scan" | 代码审查 + `ContractRegressionTest#a cancelled scan…` | 部分 | **PASS** |
| A4.5 | 候选来源 a：已保存设备 host 始终包含 | `HdcService.kt:137-147`（`linkedSetOf` 保证顺序：保存地址排在最前） | `HdcScanCidrTest#candidate targets always include saved addresses…` | 是 | **PASS** |
| A4.6 | 候选来源 b：本机 IPv4 所在 /24（仅扫描时枚举） | `HdcService.kt:159-166` | `ContractRegressionTest#local network ranges are well formed on this machine` | 是 | **PASS** |
| A4.7 | 候选来源 c：自定义 CIDR | `HdcService.kt:148`；设置项 `HdcSettingsConfigurable.kt:175` | `HdcScanCidrTest` | 是 | **PASS** |
| A4.8 | `(host,port)` 去重，保存地址端口优先 | `HdcService.kt:139-145`：`savedPort` 取自身端口、非法回落 `defaultPort`，与派生候选一起放入 `linkedSetOf<Pair<String,Int>>` | `ContractRegressionTest#a saved address keeps its own port…`；`HdcScanCidrTest` | 是 | **PASS** |
| A4.9 | 探测端口为 `settings.defaultPort`（工具窗不覆盖） | `HdcMainPanel.kt:756` `port = settings.defaultPort` | 同上 | 是 | **PASS** |
| A4.10 | 单 host 超时 300ms 量级 | `HdcMainPanel.kt:1000` `SCAN_TIMEOUT_MS = 300`；`HdcService.kt:108` `socket.connect(java.net.InetSocketAddress(host, candidatePort), timeoutMs)` | `ContractConformanceTest#scan concurrency…` | 是 | **PASS** |
| A4.11 | 并发上限 32 的可验证常量 | `HdcService.kt:447` `internal const val SCAN_CONCURRENCY = 32`；`:97` `Semaphore(SCAN_CONCURRENCY)` | `ContractConformanceTest#scan concurrency and cidr cap…` | 是 | **PASS** |
| A4.12 | 进度 `Scanned n/N` | `HdcMainPanel.kt:691`；`HdcService.kt:116` | 代码审查 | 部分 | **PASS** |
| A4.13 | 结束给出结果或明确空态；**只有真正跑完的扫描才能声称「未找到」** | `HdcMainPanel.kt:776` `scanCompleted = true` 位于 `onSuccess` 内（全文件唯一赋值）；`:713-717` 有结果→工具栏 `Found N device(s).`（4s 消失）、零结果→交给组空态 `:371`（`emptyScanText()`）；`:725` 失败只给 8s 错误提示，不置 `scanCompleted`。服务侧新失败路径（`:94` 候选择空、`:121` 有告警且无结果）同样不置 | `ContractRegressionTest#only a successful scan may claim the network was empty` / `#scan feedback is transient…` / `#a cancelled scan…` / `#a scan with no candidates at all fails…` | 是 | **PASS** |
| A4.14 | 结果分组 `Available on network (N)` + Connect 主操作 | `:391`；Connect 复用 `:513` 主按钮样式 | 代码审查 | 部分 | **PASS** |
| A4.15 | **不得自动把扫描结果写入已保存设备** | `startScan`（`:749-802`）**0 处** `rememberDevice`/`rememberHost`，也不写 `states`；全文件仅 `:316`（list targets）与 `:674`（连接成功后）会保存 | `ContractConformanceTest#the scan path never writes a discovered device into the saved list` | 是 | **PASS** |
| A4.16 | 扫描不修改连接状态；Connected 不重复列出 | `:357` `filter { it !in onlineAddresses && it !in saved }`；`startScan` 不写 `states` | 代码审查 | 部分 | **PASS** |
| A4.17 | 网络枚举容错：**不抛异常**、不刷堆栈 | `HdcService.kt:159-166`：枚举入口整体在 `runCatching{…}` 内、失败以 `null` 表达（不再伪造空表）；`:152` 每条派生 CIDR 另有 `runCatching { expandCidr(cidr) }.getOrNull()`；用户 CIDR 仍显式报错（`:148`） | `ContractRegressionTest#an unreadable interface list is reported…`；`HdcScanCidrTest#an unreadable interface list becomes a warning…`；另动态调用确认不抛 | 是 | **PASS** |
| A4.18 | 取消扫描不得让 Available 声称「未找到设备」 | `:804-814` `cancelScan()` 不写 `scanCompleted`；组空态只读它（`:394`） | `ContractRegressionTest#a cancelled scan does not claim…` | 是 | **PASS** |
| A4.19 | 枚举/空候选**可感知**、写 Console 一次、不打印堆栈、用户不卡死 | `HdcService.kt:90-94` 候选择空 → `Result.failure`（此前该路径**回调永不触发 → 按钮永久停在 Cancel scan**，本轮顺带修复）；`:117-122` 有告警且无结果 → `Result.failure(INTERFACE_ERROR)`；`:123` 有结果 → `success` 携带告警；`HdcMainPanel.kt:779` `scan.description?.let { appendConsole(it) }`（`description` 由死字段变为唯一消费点），`:795` 失败分支 `appendConsole` + 8s 提示 | `ContractRegressionTest#a scan with no candidates…` / `#a warning plus no results is a failure…` / `#a scan result can carry a warning and the panel writes it to the console`；`HdcScanCidrTest#an unreadable interface list becomes a warning…` | 是 | **PASS** |
| A4.20 | SPEC §7 :207-213 要求**按候选来源**给出不同无结果文案 | `HdcMainPanel.kt:447-457` `emptyScanText()` 按 `savedDevices().isNotEmpty()` / `customScanCidr.isNotBlank()` 四选一，`:394` Available 空态 = `if (scanCompleted) emptyScanText() else "Scan to find devices on your network."`。四句互异且事实正确：无保存无自定义 → 只提 local networks；**仅自定义网段 → "local networks or the custom range"，不再谎称只查了本地网络**（R5 反例消除）；保存 + 自定义 → 三者并列 | `ContractRegressionTest#a completed scan says which sources it actually covered` | 是 | **PASS** |

### §5 自动连接

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A5.1 | 两项默认 `false`（安全默认） | `HdcSettings.kt:18-19` | `HdcSettingsStateTest#new state fields default to the safe values` | 是 | **PASS** |
| A5.2 | Settings 可配 + 人话说明 | `HdcSettingsConfigurable.kt:172-174`（说明与 SPEC §8 逐字一致） | `ContractConformanceTest#settings expose and compare all three new options` | 是 | **PASS** |
| A5.3 | 自动连接串行（同刻一台） | `:537,540-552` `connectionInFlight` + 队列 | 代码审查 | 部分 | **PASS** |
| A5.4 | 同一设备每会话自动尝试一次 | `:692-695` `autoAttempted` 过滤 + 入队即标记 | 代码审查 | 部分 | **PASS** |
| A5.5 | 失败不弹通知轰炸 | `:681` 只写 `states` + Console；自动连接路径无 `HdcNotifications` 调用 | 代码审查 | 是（源码） | **PASS** |
| A5.6 | 自动与手动共用同一入口 | `:577` 唯一 `requestConnection` | 代码审查 | 部分 | **PASS** |
| A5.7 | 手动请求优先于未开始的自动项；**不得被静默丢弃** | `:580-592` 忙分支按来源分流：MANUAL → `autoQueue.removeAll { it.first == address }` + `addFirst`（提升，`:584-585`）；AUTO → `any{…}` 则 `return`，否则 `addLast`（`:665`）。两条路径都不可能留下重复项 | `ContractRegressionTest#a manual connect promotes an address that is already queued`；`PanelInteractionContractTest#neither queue branch can produce a duplicate entry` | 是 | **PASS** |
| A5.8 | 用户手动 Disconnect 后不得被自动重连 | 抑制三处：`:713` disconnect 先写（早于异步回调）、`:649` 非 MANUAL 直接 return、`:700-708` `runNextAuto`，其中 `:705` 对已抑制条目 `continue` | `PanelInteractionContractTest#a manual disconnect is recorded before…` / `#an automatic target is refused while it is suppressed` / `#the automatic queue skips suppressed entries…` | 是（源码） | **PASS** |
| A5.9 | 单台失败不停止队列 | `:615` 无条件 `runNextAuto()` | 代码审查 | 否 | **PASS** |
| A5.10 | 自动连接走四态状态机，用户可见 | `:665` CONNECTING → `:674` CONNECTED / `:680` FAILED | 代码审查 | 部分 | **PASS** |
| A5.11 | 已连接的 address 不重复入队 | `:188` 构造期只置 `savedAutoConnectPending`；`:319-322` 在**首次 list targets 成功分支内**才 `enqueueAuto(...)`（此时 `connected` 已填充），配合 `:692` 的 `it !in connected` 去重 | `PanelInteractionContractTest#saved auto connect waits for the first successful target listing` | 是（源码） | **PASS** |

### §6 其他体验缺陷

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A6.1 | 设备行自适应高度，无固定 76px | `:513-562 deviceRow` 无 `maximumSize`；`grep scale(76)` → 0 命中 | 是 | 是 | **PASS** |
| A6.2 | 分组折叠用平台图标，无 `⌃`/`⌄` | `:485` `ArrowDown`/`ArrowRight`；全 `src/main` 无 `⌃`(U+2303)/`⌄`(U+2304) | `ContractConformanceTest#removed legacy styling…` | 是 | **PASS** |
| A6.3 | `Connected` 为空显示克制空态 | `:400` `"No connected devices."` | 代码审查 | 是（源码） | **PASS** |
| A6.4 | 计数为 0 不显示 `(0)`，计数与行数一致 | `:472` `if (count > 0) "$title ($count)" else title`；`:430` 用 `items.isEmpty()` | `ContractConformanceTest#the device row…` | 是 | **PASS** |
| A6.5 | 工具窗不得硬编码中文到 UI | `HdcMainPanel.kt` 汉字扫描 → **0 命中**（A6.5 的适用范围自本轮起收窄为工具窗：设置页中文是用户明确要求，由 A10.6 覆盖） | 是 | 是 | **PASS** |
| A6.6 | 分组标题始终存在（即使为空） | `:391/366/372` 三次无条件 `addGroup`；`:430` 标题先加，再判断空态 | 代码审查 | 是（源码） | **PASS** |

### §8 修订裁决（v1.1–v1.5）

| ID | 裁决要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A8.1 | 扫描能力落在服务层，并发上限为可验证常量 | `HdcService.kt:77-129`；面板只做会话编排（`HdcMainPanel.kt:639-692`） | `ContractConformanceTest#scan concurrency and cidr cap…` | 是 | **PASS** |
| A8.2 | 允许自绘状态指示器；颜色取自主题；Timer 必须在 `dispose()` 停止 | `:959-983` `StatusIcon`；`:919-928` `dispose()` 停止**全部 3 个** Timer + 取消扫描 | `ContractConformanceTest#the spinner timer…` + `PanelInteractionContractTest#dispose stops every timer…` | 是 | **PASS** |
| A8.3 | CIDR 支持非 /24 前缀；上限 4096；非法/超限明确报错 | `HdcService.kt:168-181`；错误文案与 SPEC §5.2 逐字一致（`:173,:177`） | `HdcScanCidrTest`（11 例） | 是 | **PASS** |
| A8.4 | v1.2 两处历史修复不得回退 | `HdcService.kt:35-36` `@Service` + `class HdcService`；全 `src/main` 无 `EOF` 行 | `ContractConformanceTest#service annotation…` / `#no stray EOF marker…` | 是 | **PASS** |
| A8.5 | 扫描必须用**有界**池 | `HdcService.kt:47-49` 固定 32 池（daemon，`hdc-scan`）+ `:100` `scanExecutor.submit` + `:429` 释放 | `ContractConformanceTest#scanning runs on its own bounded pool…` | 是 | **PASS** |

### §9 可访问性

| ID | 契约要求 | 证据 | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A9.1 | 控件 `isFocusable = true` | `:231`（图标按钮）`:263`（toggle）`:478`（分组标题）`:606`（Connect/Disconnect） | `grep -n isFocusable` | 是 | **PASS** |
| A9.2 | icon-only 按钮同时有 tooltip + accessible name | `:243`、`:259`、`:437`；扫描按钮由 `:851` 动态同步 | 代码审查 + `ContractRegressionTest#the scan button has a single derivation point` | 是（源码） | **PASS** |
| A9.3 | 分组标题用可聚焦按钮**或**带 `AccessibleRole.PUSH_BUTTON` 的组件 | `:945-956` `SectionHeaderPanel : JPanel(BorderLayout())` 覆写 `getAccessibleContext()`，在 `AccessibleJPanel` 子类里返回 `AccessibleRole.PUSH_BUTTON`；`sectionHeader`（`:429`）用它构造并保持 `isFocusable = true`（`:478`）、`accessibleName = text`（`:481`） | `ContractRegressionTest#the section header announces itself as a push button` | 是（源码） | **PASS** |
| A9.4 | Space/Enter 切换，**Left 折叠、Right 展开** | `:498-503` `VK_RIGHT` → `EXPAND_ACTION`，体内 `if (!expanded) toggle()`；`:504-510` `VK_LEFT` → `COLLAPSE_ACTION`，体内 `if (expanded) toggle()`；`:492-494` Space/Enter 仍为 `TOGGLE_ACTION`；三个 action 名互不相同（`:992-994`）。反方向为 no-op，符合 §9 :270 | `ContractRegressionTest#section headers are keyboard operable from space enter left and right` / `#left only collapses and right only expands` | 是（源码） | **PASS** |
| A9.5 | 四态不靠颜色单独区分（形状 + 英文文案） | `:564-569` 四段文案；`:974-983` 四种几何 | 代码级 + 目视 | 部分 | **PASS** |
| A9.6 | auto-refresh 开关不只靠颜色；菜单当前项带 `✓` | `:277` `val checked = if (seconds == current) "\u2713 " else ""`（当前项前缀 ✓，`:271` 另有禁用表头）；`:880` `isSelected` | 代码审查 | 是（源码） | **PASS** |
| A9.7 | 状态相关的 accessible name 与当前语义一致 | `:843-853`：tooltip 与 `scanButton.accessibleContext.accessibleName` 由同一个 `tooltip` 变量写出；全文各仅 1 处赋值（`ContractRegressionTest#the scan button has a single derivation point` 断言） | `PanelInteractionContractTest#availability is only recorded…` | 是（源码） | **PASS** |
| A9.8 | 键盘激活分组标题后**焦点保持**在同一分组（连续键盘操作不掉焦，第 4 轮 ⚠️） | `:482` `putClientProperty(FOCUS_SECTION_KEY, title)`；`:387-389` 在 `:390 devicesPanel.removeAll()` **之前**抓取 `focusOwner`，用 `?.takeIf { SwingUtilities.isDescendingFrom(it, devicesPanel) }` 保证 null 安全且只认面板内的焦点；`:412-416` 在同一 `invokeLater` 里 `findSectionHeader(devicesPanel, focusedSection)?.requestFocusInWindow()`；`:460-466` 递归查找；键字面量全文仅 1 处（`:997`）；`:378` 签名未变时提前返回，不动焦点 | `ContractRegressionTest#a rebuilt section header gets keyboard focus back` | 是（源码） | **PASS** |

### §10 真机截图反馈（UX-CONTRACT §8 v1.7：U1–U6）

| ID | 要求 | 证据（文件:行） | 方法 | 自动化 | 结论 |
|---|---|---|---|---|---|
| A10.1 | 分割条不得取 LAF 的 `SplitPane.background`（深色主题下渲染成亮白粗线） | `HdcMainPanel.kt:97-104`：`setUI` 装 `BasicSplitPaneUI` 子类，`createDefaultDivider()` 返回的 `BasicSplitPaneDivider` 覆写 `paint()`：`:100` `UIUtil.getPanelBackground()` 铺 `fillRect(0, 0, width, height)`，`:102` `UIUtil.getBoundsColor()` 画 `fillRect(0, height / 2, width, 1)` 发丝线；抓取区仍为 `:91` 的 5px | `PanelRenderingContractTest#the split divider is painted with theme colours instead of the laf background` / `#the divider ui keeps the basic behaviour it does not override`（恰好 2 个 override，`installDefaults`/`installUI` 未覆写 → 拖拽不受影响） | 是（源码） | **PASS**（观感见 §5-11） |
| A10.2 | 单个设备行/空态不得吃掉 `BoxLayout` 的富余空间 | `:936-938` `FixedHeightPanel.getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)`；`:553` 设备行改为 `FixedHeightPanel(BorderLayout(JBUI.scale(8), 0))`，`:431` 空态同样包进 `FixedHeightPanel`；`:409` 末尾 `Box.createVerticalGlue()` 是 `devicesPanel` 唯一可增长子组件；标题仍为 `:476` 的显式 30px | `PanelRenderingContractTest#device rows and empty states are fixed height panels` / `#only the trailing glue may grow inside the device list` / `#the section header keeps its own fixed maximum height` / `#a fixed height panel refuses the slack that a plain panel swallows`（含裸 `JPanel` 反例） | 是（源码 + Swing 行为镜像） | **PASS**（观感见 §5-12） |
| A10.3 | 工具栏须显示自动刷新间隔，且开关两态差异**不止**选中态 | `:883` `autoButton.text = if (on) "${seconds}s" else "Off"`；`:880` `isSelected`；`:884` `foreground`（`SUCCESS` vs `UIUtil.getContextHelpForeground()`）；`:885` `font`（BOLD/PLAIN）；`:886-890` tooltip 整句；`:256-265` `toggleButton()` 改为 `margin = JBUI.insets(2, 6)` 且不再设 `preferredSize` | `ContractConformanceTest#the auto refresh control states the interval and differs by more than selection`；Swing 侧 `AutoRefreshToggleStyleTest#the on and off states differ by text, colour, font and selection` / `#a toggle that carries text sizes itself to fit the content` / `#selected and unselected toggle states do not paint identically` | 是（源码 + Swing 镜像） | **PASS**（观感见 §5-12） |
| A10.4 | 间隔菜单须先给出当前值 | `:271` 禁用表头 `Auto-refresh: Off` / `Auto-refresh: ${current}s` → `:274` `addSeparator()` → `:275-285` `REFRESH_INTERVALS` 列表（`:277` 当前项带 `\u2713 `） | `ContractConformanceTest#the refresh menu states the current value before the interval list`（表头存在、`isEnabled = false`、顺序 header < separator < list、✓ 逻辑保留） | 是（源码） | **PASS** |
| A10.5 | 已连接设备的 Disconnect 须有可辨识背景，与 Connect 区分 | `:583-612` `actionButton` 的匿名 `JButton` 子类：`:590` `chip.color = disconnectBackground()` + `:591` `fillRoundRect`（圆角 8）→ `:593-596` hover/pressed 用 error 半透明提亮（34/72）→ `:611` 之后才 `super.paintComponent(g)`；`:618-621` `disconnectBackground()` 取 `UIUtil.getPanelBackground()` + `UIUtil.getErrorForeground()`，浅色 0.16 / 深色 0.34；`:624-629` `blend`；`:605-608` `isContentAreaFilled/isBorderPainted/isOpaque = false`（LAF 无关） | `PanelRenderingContractTest#the disconnect chip is self painted with a themed error tint` / `#the disconnect tint follows the theme instead of a fixed colour` / `#the disconnect chip paints a ring where the connect button stays flat`（像素级：`#1E1F22` + `#DB5C5C` @0.34 = `#5E3335`，与 Connect 的外圈像素不同）/ `#the chip painting does not disturb the row action buttons` | 是（源码 + 像素镜像） | **PASS**（观感见 §5-13） |
| A10.6 | 设置页用户可见文案全部中文（仅保留产品名与标识符） | `HdcSettingsConfigurable.kt:42,43`（复选框）`:74,83,102`（按钮）`:64`（`getDisplayName()` = `"HDC Wi-Fi"`）`:86,93,96,104,108,115,118,120`（状态/异常）`:143,144,148-150,157,158,161,163,166-168,172,174,175,176`（分组/行/说明）`:195`（校验异常）；非中文剩余仅 `"HDC Wi-Fi"`、`"hdc-detect"`/`"hdc-test"`、`"hdc"`（默认可执行名）、`"-v"`（命令参数）、`"0"`（spinner 格式） | `ContractConformanceTest#the settings ui is chinese and keeps only product and thread identifiers`（正则不变量：控件/helper 的每个非空字面量必含汉字 + 16 条中文文案 + 例外项仍在） | 是（源码，正则不变量） | **PASS** |

> A10.2 与 A6.1「设备行自适应高度、不得固定 76px」不冲突：A6.1 反对的是**固定像素高度**，A10.2 收紧的是**最大高度**（= `preferredSize.height`，仍由内容决定）。两者断言同时成立。

### 历史缺陷回归（§8 v1.2）

| ID | 缺陷 | 回归证据 | 结论 |
|---|---|---|---|
| R1 | `@Service` 误挂 `HdcScanProgress` | `ContractConformanceTest#service annotation is attached to a class everywhere, and to HdcService specifically` | **PASS** |
| R2 | 文件尾泄漏字面量 `EOF` | `ContractConformanceTest#no stray EOF marker leaked into any main source` | **PASS** |

> R1 的**运行时**版本（`service<HdcService>()` 不抛 `ServiceNotFoundException`）需要 IntelliJ 平台，本测试 JVM 无法执行（见 §0），故按"注解挂载点"做源码级断言 —— 这正是当初坏掉的地方。

---

## 3. 缺陷复核与残留

### 3.1 历代缺陷的总复核（交付版）

| 缺陷 | 结论 | 证据 |
|---|---|---|
| D1 / D1a 签名碰撞 | **已修复** | `HdcDevice.kt:57-73`；15 变体单射性测试 |
| D2 保存端口丢弃 | **已修复** | `HdcService.kt:139-145` |
| D3 扫描反馈 | **已修复** | `HdcMainPanel.kt:209-214/707-720` |
| D4 恢复可见补刷 | **已修复** | `:155-161` |
| D5 扫描可用性 | **已修复** | `:764-783` |
| D6 线程风暴 | **已修复（未压测）** | `HdcService.kt:47-49/100/406` |
| D7 取消不关 socket | **未修复（已批准偏差）** | `HdcService.kt:27-33`，契约 §8 明确记录 |
| D8 重复入队 | **已修复** | `:544-549` |
| D9 抑制失效 | **已修复** | `:539/595/603` |
| D10 早入队 | **已修复** | `:169/293-295` |
| D11 枚举容错 | **已修复** | `HdcService.kt:152/159-166` |
| D12 文案（句号） | **已修复** | `:383`；扫描无结果文案见 A4.20（`emptyScanText()` 四选一） |
| D13 设置页说明 | **等价改写**（契约 v1.5 明确记为收口） | `HdcSettingsConfigurable.kt:166-171` + `:165-170` |
| D14 线程/EDT | **已修复（两处均 daemon）** | `HdcSettingsConfigurable.kt:99,123` |

### 3.2 第 2 轮发现项（N1–N7）的复核

| 项 | 声明 | 复核结论 | 证据 |
|---|---|---|---|
| N1 | `scanCompleted` 只在 `onSuccess` | **成立（终版不变）** | `HdcMainPanel.kt:776` 全文唯一赋值；服务侧新失败路径（`HdcService.kt:94/121`）不经过它 |
| N2 | 扫描中始终可取消 | **成立** | `:846` `isEnabled = hdcAvailable \|\| scanning`；`:847` tooltip 先判 `scanning`；4 处迁移都重推导 |
| N3 | accessible name 随状态更新 | **成立** | `:851`；全文各仅 1 处赋值 |
| N4 | 手动提升而非吞掉 | **成立** | `:584-585`（顺序已断言）；AUTO 分支 `:665` 保持去重 |
| N5 | `hdc-test` 线程 daemon | **成立** | `HdcSettingsConfigurable.kt:123` |
| N6 | 枚举异常被兜住 | **成立** | `HdcService.kt:159-166`；不抛异常（动态确认） |
| N7 | 签名区分 `null` / `""` | **成立** | `HdcDevice.kt:70,77-78`；15 变体两两不等（含 marker 字符串本身作为真实 reason 的对抗用例） |

### 3.3 与 UX-CONTRACT §8 修订记录的一致性核对

契约 v1.3（`UX-CONTRACT.md:141-162`）、v1.4（`:196`）、v1.5（`:182-201`）、v1.6（`:197-209`）由 Lead 撰写。逐条对照：

| 契约声明 | 复核 | 说明 |
|---|---|---|
| v1.3：D1–D6、D8–D10 已修 | **一致** | 见 §3.1 |
| v1.3：D7 明确不修（已知限制） | **一致** | 代码保持原样；该裁决正式覆盖 SPEC §5.1 |
| v1.3：D11/D12/D14 已修 | **一致** | 本轮无回退 |
| v1.4：N1–N7 已修 | **一致** | 见 §3.2，逐条有源码证据 + 契约形态测试 |
| v1.4：A9.4「补 VK_LEFT / VK_RIGHT 键绑定」 | **一致** | 键绑定确已补齐（当时判 PARTIAL 是因为方向语义，已由 v1.5 修复） |
| v1.4：「本轮修复后应无 FAIL」 | **成立** | 第 3 轮即 0 FAIL；终版仍 0 FAIL |
| v1.5：R1 补 `PUSH_BUTTON` 角色 | **一致** | `HdcMainPanel.kt:945-956`；静态可证 |
| v1.5：R2 拆 `EXPAND_ACTION`/`COLLAPSE_ACTION`，反方向 no-op | **一致** | `:498-510` |
| v1.5：R3 失败返回 `null`、空候选/告警无结果一律 `Result.failure(INTERFACE_ERROR)` | **一致** | `HdcService.kt:90-94/117-122/159-166` |
| v1.5：R4（D13）「以信息量更大的等价说明收口」 | **一致** | `HdcSettingsConfigurable.kt:166-171` |
| v1.5：`description` 由死字段变为送达控制台 | **一致** | `HdcMainPanel.kt:779` 是唯一消费者 |
| v1.5 未声明 SPEC §7 的按来源无结果文案 | **一致** | 第 4 轮记为缺口（A4.20）；v1.6 已声明修复 |
| v1.6：`emptyScanText()`「按有无保存地址 × 有无自定义网段四选一，与 SPEC §7 的来源语义对齐」 | **一致** | `HdcMainPanel.kt:447-457` 四臂齐全且互异；`:394` 仍由 `scanCompleted` 把关；语义对齐（非逐字，见 §3.4 INFO） |
| v1.6：标题写 `FOCUS_SECTION_KEY`、渲染前记录焦点所属分组、重建后 `findSectionHeader` 找回并 `requestFocusInWindow()` | **一致** | `:482` / `:387-389` / `:415` / `:460-466`，顺序与限定条件逐条相符（§3.4） |
| v1.6：「预期 81 项 = 81 PASS / 0 FAIL / 0 PARTIAL / 1 MANUAL」 | **数字有出入（已更正）** | 81 PASS + 1 MANUAL 意味着 **82 项**；第 5 轮实际新增 A9.8（焦点保持）后为 **82 项 = 81 PASS / 0 FAIL / 0 PARTIAL / 1 MANUAL**，PASS/FAIL/PARTIAL/MANUAL 四项与契约预期一致，仅"项数"写法少算 1 |
| v1.7：U1 裸 `JSplitPane` 分割条取 LAF 背景 → 自装 `BasicSplitPaneUI`＋`UIUtil` 主题色 | **一致** | `HdcMainPanel.kt:97-104`；`paint()` 内无 `UIManager`、无 `getBackground()`（测试断言）；`:91` 的 5px 抓取区未变 |
| v1.7：U2 `BoxLayout` 富余空间 + 新增 `FixedHeightPanel`、设备行与空态都改用它 | **一致** | `:936-938`、`:553`、`:431`；裸 `JPanel` 反例由 `PanelRenderingContractTest#a fixed height panel refuses the slack that a plain panel swallows` 复现 |
| v1.7：U3 `autoButton.text` 两态 + `foreground`/`font` 差异 + `toggleButton()` 不再固定尺寸 | **一致** | `:880-885`、`:256-265` |
| v1.7：U4 菜单先加禁用表头，再分隔符，再 `REFRESH_INTERVALS` 的 ✓ 列表 | **一致** | `:271-286` |
| v1.7：U5 自绘圆角色块（浅色 0.16 / 深色 0.34）、深色探针 `#5E3335` | **一致** | `:583-629`；独立像素镜像复算 `blend(#1E1F22, #DB5C5C, 0.34) == #5E3335`，与 Lead 的探针值一致 |
| v1.7：U6 设置页全部中文化，仅留产品名与线程名等标识符 | **一致** | 28 行含汉字；非中文剩余仅为产品名 `HDC Wi-Fi`、线程名 `hdc-detect`/`hdc-test`、默认可执行名 `"hdc"`、命令参数 `"-v"`、spinner 格式 `"0"`（均属全局规则允许保留的标识符/命令） |
| v1.7 文末「验收状态（终版）：82 项…、`106` 个测试全绿」 | **时点真值，现已过期** | 该数字是 v1.6 时点的真值；登记 U1–U6（A10.1–A10.6）后为 **88 项 = 87 PASS / 0 FAIL / 0 PARTIAL / 1 MANUAL**、**121** 个测试全绿。契约文本不改，以本报告为准 |
| v1.7 文末「契约签订后共进行 4 轮独立验证…累计 19 个真实缺陷」 | **计数已过期** | 实际已进行 **6 轮**独立验证；缺陷累计口径见 §3.1–§3.4（19 + R5 + 焦点丢失 + U1/U2/U5 三处真机截图界面缺陷） |

### 3.4 残留项：已全部闭合

**已闭合**：R1（A9.3）、R2（A9.4）、R3（A4.19）、R4/D13 等价收口、R5（A4.20 无结果文案）、A9.4 ⚠️ 焦点丢失，以及第 6 轮的 **U1–U6**。六轮下来累计的 `OPEN -` / `RESIDUAL -` 断言已全部翻转为契约形态（当前 0 条）。

**顺带修复（第 3 轮未被发现的缺陷）**：候选择空时 `candidates.map { … }` 产生空 futures → `done == candidates.size` 永不成立 → **`callback` 永不调用**，面板 `scanHandle` 永远非 null，按钮永久停在 `Cancel scan`、`scanCompleted` 永不置位。第 3 轮的 `getOrDefault(emptyList())` 恰好使"枚举失败"落入该路径，所以那条卡死是可达的。本轮 `HdcService.kt:90-94` 的失败快路径同时消灭了它，已由 `ContractRegressionTest#a scan with no candidates at all fails instead of succeeding with nothing` 钉住。

#### R5 · 已修复（原 LOW）· 无结果文案不区分候选来源（A4.20）
- 原反例：设置里只填自定义网段 `10.99.0.0/29`（无保存设备、本机网段无命中）→ 扫描成功且零结果 → Available 组显示 "No devices found on local networks."，属**事实错误**。
- 现证据：`HdcMainPanel.kt:447-457` 四选一，仅自定义网段时输出 "No devices found on local networks or the custom range."（`:394` 仍由 `scanCompleted` 把关）；`ContractRegressionTest#a completed scan says which sources it actually covered` 断言四句互异、且自定义分支**不等于** local-only 那句。
- 遗留 INFO（不扣分）：实现按**来源类别**枚举（local networks / saved addresses / custom range），未逐字采用 SPEC :207-213 的 `{network}/24`、`{cidr}` 插值文案，也未使用 `No saved devices are reachable.` —— 与 SPEC 的**语义**对齐且不泄露 CIDR 到分组标题；契约 v1.6 亦以"与 SPEC §7 的来源语义对齐"表述，故记为等价收口（同 D13 先例）。
- 遗留 INFO（不扣分）：若本机接口枚举失败但存在保存地址/自定义网段（R3 的"部分成功"路径），空态文案仍会提到 local networks；该情形的告警会同时写入 Console 与工具栏（`HdcService.kt:117-123` → `HdcMainPanel.kt:776/779`），用户可感知扫描不完整。

#### U1–U6 · 已修复（契约 v1.7，用户真机截图反馈）
- 逐条证据见 §2 的 §10 表（A10.1–A10.6）；本轮**无新增残留**。
- **INFO（不扣分）· 禁用态色块**：自绘 chip 不读 `isEnabled`（`:583-612`），连接进行中 Disconnect 被判禁用时文字变灰但色块仍满强度。禁用态下 `model.isRollover`/`isPressed` 恒为 false，不会误报 hover；属观感细节，记为 INFO。
- **INFO（不扣分）· 界面语言不一致**：工具窗 `HdcMainPanel.kt` 仍是全英文，设置页 `HdcSettingsConfigurable.kt` 已中文。用户只要求设置页中文化（契约 v1.7 U6），故不判缺陷；是否统一见 §7 的 P4 项。
- **INFO（不扣分）· A6.5 的适用范围收窄**：原「全 `src/main` 无中文」已按用户要求收窄为「工具窗无中文」，设置页中文由 A10.6 覆盖。

#### 焦点丢失 · 已修复（原 A9.4 ⚠️）· 重建分组标题后焦点不保
- 原问题：每次按键触发 `render(force = true)` → `devicesPanel.removeAll()` 重建标题，全 main 无 `requestFocusInWindow()`（0 命中），键盘用户每按一键都要重新 Tab。
- 现证据：`:482` 写入 `FOCUS_SECTION_KEY`；`:387-389` 在 `:390 removeAll()` 前抓取 `focusOwner`；`:412-416` 在既有 `invokeLater`（与滚动条恢复同一批）内 `findSectionHeader(...)?.requestFocusInWindow()`；`:460-466` 按同一键递归查找。
- 抗回归：`focusOwner)` 后用 `?.takeIf`（`focusOwner` 可为 null，全文无 `!!`）；抓取限定 `SwingUtilities.isDescendingFrom(it, devicesPanel)`，工具栏按钮的焦点不会被误抢回分组标题；`:378` 签名早退**先于**抓取，无变化的刷新不动焦点；`:415` 的 deferred 块内无 `render(` 调用，无重入；`requestFocusInWindow` 全文恰好 1 处。以上全部由 `ContractRegressionTest#a rebuilt section header gets keyboard focus back` 断言。

---

## 4. 资源与 EDT 审计结论（可执行的核对方法）

| 检查项 | 方法 | 结论 |
|---|---|---|
| Swing 变更是否都在 EDT | `grep -rn "invokeLater\|invokeAndWait" src/main/`：`HdcService.kt:457-462` 统一转投；面板所有 `render()/appendConsole()` 的触发源都是这些回调、`Timer.actionPerformed`、`HierarchyListener` 或面板 `init` | **PASS**（代码级；无自动化断言手段） |
| 阻塞 IO 是否在 daemon 线程 | `grep -rn "isDaemon" src/main/`：`HdcService.kt:39,48`（两个池）、`HdcCommandRunner.kt:68`、`HdcStream.kt:42`、`HdcSettingsConfigurable.kt:99,123` → **全部 daemon，无例外** | **PASS** |
| `javax.swing.Timer` 是否都在 `dispose()` 停止 | `grep -c "javax.swing.Timer(" HdcMainPanel.kt` → **3**（`:141` 动画、`:822` 扫描提示、`:894` 自动刷新）；`:919-928` `dispose()` 三个都停；`PanelInteractionContractTest` 断言恰好 3 个 | **PASS** |
| `animationTimer` 是否只在有 `CONNECTING` 时运行 | `:901-906`；`:374` 每次 `render()` 先调 `syncAnimationTimer()`（在签名早退之前） | **PASS** |
| 扫描线程是否有界 | `HdcService.kt:47-49` 固定 32 池，`:100` 提交，`:429` 释放 | **PASS**（未压测，见 §6.4） |
| 扫描按钮状态是否只有一个写入点 | `ContractRegressionTest#the scan button has a single derivation point`：`isEnabled` / `toolTipText` / `accessibleName` 各恰好 1 处赋值，且都在 `refreshScanButton()`；`setScanAvailable` 体内不含 `scanButton.` | **PASS** |
| 重建后焦点是否真的能找回 | `render()`：`:378` 签名早退 → `:387-389` 抓取（null 安全 + 面板内限定）→ `:390` `removeAll()` → `:412-416` `invokeLater` 内滚动条 + 焦点恢复（`:415`）；`:415` 块内无 `render()` → 无重入；`requestFocusInWindow` 全文 1 处 | **PASS** |
| `emptyScanText()` 是否引入新的 EDT 开销 | 只读 `settings.savedDevices()` / `customScanCidr`（纯内存 getter，`HdcSettings.kt:86-88`）；`render()` 经 `groups()`（`HdcMainPanel.kt:341-342`）本就在调它，无新增 IO/变更 | **PASS** |
| 分割条自绘是否影响拖拽 / 事件 | `HdcMainPanel.kt:97-104` 只覆写 `createDefaultDivider()` 与 `paint()`（`PanelRenderingContractTest#the divider ui keeps the basic behaviour it does not override` 断言恰好 2 个 override、未覆写 `installDefaults`/`installUI`/`mousePressed`）；拖拽由 `BasicSplitPaneDivider` 自身的监听器承担，与 `paint` 无关；`paint` 仅 2 次 `fillRect` | **PASS** |
| 行高收紧后滚动是否受影响 | `devicesPanel`（`BoxLayout.Y_AXIS`）的富余空间只由 `:409` 的末尾 glue 吸收；内容超过视口时 glue 高度自然为 0，`JScrollPane` 行为不变；组标题仍固定 `:476` 的 30px、不被卷入 | **PASS** |
| 自绘 chip 是否影响按钮行为 / 禁用态 | `actionButton` 仍 `addActionListener { action() }`（`:612`）；`isEnabled = !connecting` 的 3 处写入未变（`:544/548/550`，由 `PanelRenderingContractTest#the chip painting does not disturb the row action buttons` 计数断言）；禁用态色块仍绘制，记为 INFO（§3.4） | **PASS** |
| 工具栏按钮变宽是否破坏布局 | `autoButton` 加在同一工具栏（`:205-209`），`toggleButton()` 去掉固定尺寸后由内容决定宽度；本机 LAF 下 `"30s"` 与 `"Off"` 均能完整显示（`AutoRefreshToggleStyleTest#a toggle that carries text sizes itself to fit the content`） | **PASS**（观感见 §5-12） |
| 队列是否可能重复 | `:544-549` 两分支都先清后加或先查后加；`PanelInteractionContractTest#neither queue branch can produce a duplicate entry` | **PASS** |
| 扫描失败是否会卡住按钮 | `HdcService.kt:90-94` 空候选快失败、`:117-123` 完成即回调（两条失败路径都回调）→ `HdcMainPanel.kt:657-660` 归零 `scanHandle` 并重推导按钮 | **PASS** |
| Socket / Process / Stream 是否关闭 | `HdcService.kt:107` `Socket().use`；`HdcCommandRunner.kt:72-87`；`HdcStream.kt:46-56`；`HdcService.kt:372-381 stopAllStreams()` + `:429 dispose()` | **PASS**（取消时 socket 见 D7） |
| 契约要求的清理项是否真的没了 | A0.1、A6.1、A6.2、A2.6：`0x59A869` / `⌃` / `⌄` / `scale(76)` 全 0 命中；`Actions.GC` 仅剩 `:144` | **PASS** |

---

## 5. 必须人工目视的清单（本环境无法自动化）

前置：`./gradlew runIde` → 打开 HDC Wi-Fi 工具窗。

1. **3.1 / A10.3 自动刷新开关两态与间隔显示**：默认 Off → 按钮显示 `Off`、灰色常规字重、未选中外观、tooltip 为 `Auto-refresh is off. Click to refresh every 10s.`；点击一次 → 按钮显示 `10s`、绿色加粗、明显选中态、tooltip 为 `Auto-refresh is on: every 10s. Click to turn it off.`；再点回 Off。
2. **1.3/1.4/1.6/1.8 四态视觉**：制造四种状态，核对形状/颜色/文案；悬停 Failed 行确认 tooltip 为 `Connection failed: {原始错误}`；确认 CONNECTING 的旋转连续、不闪整行。
3. **2.x Connect vs Disconnect**：Disconnect 非绿色、图标为 Suspend；Connect 为 Execute 与成功绿。
4. **9.3 键盘与读屏**：Tab 顺序应为 Add → Scan → Console toggle → Refresh → Auto-refresh → 下拉箭头 → Settings → 各分组标题 → 行内按钮；用读屏或 `AccessibleRole` 检查器确认标题被播报为**按钮**（`SectionHeaderPanel` 的角色覆写是否被 IntelliJ 的 a11y 桥接透出，是本节唯一无法静态证明的点）。
5. **9.4 方向键语义 + 9.8 焦点保持**：展开态按 **Right** 应无动作，折叠态按 **Left** 应无动作；展开态按 Left 折叠、折叠态按 Right 展开；Space/Enter 仍切换。**并确认按一次键后焦点仍在该分组标题上**（源码已实现 `findSectionHeader(...)?.requestFocusInWindow()` 于 `invokeLater` 内，但"焦点是否真的回到新实例"只有真实窗口能验证）；另确认按工具栏按钮触发刷新时焦点**不会**被抢到分组标题（`:388` 的 `isDescendingFrom` 限定）。
6. **4.2 扫描可用性**：把 hdc 路径指向不存在的文件 → 扫描按钮禁用且 tooltip 为 `HDC is not available. Configure it in Settings.`；修正后刷新 → 恢复可用。
7. **4.2b 扫描中取消（N2 的核心，需构造）**：开始扫描一个较大网段 → 扫描中让 hdc 不可用（改设置路径或移走二进制）→ 确认按钮**仍可点**、tooltip 仍为 `Cancel scan`、点击后能真正停下。
8. **4.19 枚举失败路径（需受限环境）**：在无法枚举网络接口的环境下扫描 → 应看到 `Unable to read local network interfaces…` 的 8s 错误提示、Console 一行、且 Available 组**不出现**"No devices found"；若同时有保存设备且能扫到 → Console 应出现该告警而扫描显示 `Found N device(s).`。
9. **3.6 恢复可见补刷**：关闭自动刷新，隐藏工具窗 → 再显示，`Last updated HH:mm:ss` 应立即更新。
10. **4.13/4.18 扫描提示生命期**：有设备 → `Found N device(s).` 约 4s 后消失；空网段 → 组内 `No devices found on local networks.`；扫描中取消 → `Scan cancelled.`，**不得**出现"未找到"空态。填非法 CIDR → 8s 错误提示，且 8 秒后组内不应出现"未找到设备"。
11. **A10.1 分割条白线（深色主题）**：在 Darcula / New UI 下确认设备列表与控制台之间只有一条与主题背景一致的发丝线，**无** `(230,232,230)` 亮白粗条；鼠标移到分割条上光标应变抓取形，**拖拽仍有效**、5px 抓取区仍在（源码已证 `paint` 与拖拽无关，此处只看观感）。
12. **A10.2 行高 + A10.4 菜单表头**：只连接 1 台设备并把窗口拉高 → 该行高度应约等于其内容高度（不再出现 ~412px 的行、组间不再有巨大空白），多出的空间留在列表底部；三组标题都保持约 30px；把列表滚到底，最后一行不被裁切。点开刷新按钮的下拉：第一行应是**灰色不可点**的当前值表头（`Auto-refresh: Off` 或 `Auto-refresh: 30s`），随后分隔符，之后才是带 ✓ 的选项列表。
13. **A10.5 Disconnect 色块**：Connect（绿色文字、平面）与已连接行的 Disconnect（圆角 error 色块）应一眼可分；鼠标悬停与按下时色块应变亮；连接进行中按钮禁用时，确认仅文字变灰、色块仍可辨识（§3.4 INFO）。
14. **A10.6 设置页中文**：`Settings → HDC Wi-Fi` 的分组名、行标签、两个复选框、三个按钮（浏览…/自动检测/测试）、状态文案与说明应全部为中文，中文在 IDE 字体下无截断；确认产品名 `HDC Wi-Fi` 与错误信息里的路径/命令仍为英文原文。

---

## 6. 未覆盖 / 不确定

1. **运行时服务注册（历史 R1 的运行时验证）**：本测试 JVM 无 IntelliJ 平台，无法执行 `service<HdcService>()`；只有源码级断言。
2. **面板行为基本没有行为级测试**：`ContractRegressionTest`(25)/`PanelInteractionContractTest`(12)/`ContractConformanceTest`(16)/`PanelRenderingContractTest`(10) 共 **63 例**，其中 **61 例是源码断言**，2 例是 Swing 行为/像素镜像（`PanelRenderingContractTest` 的布局松弛分配与 Disconnect chip 像素）。镜像测试运行在本机默认 LAF（Aqua）下，只能证明**机制**，不能证明 IDE 主题下的最终颜色/尺寸；"R1–R3、A4.20、焦点保持、U1–U6 已修"的结论同样建立在此之上，真正的观感确认在 §5 清单。
3. **EDT 正确性**没有自动化断言（需平台测试框架 + `isDispatchThread` 违规检测）。
4. **D6 未做压力实测**（并发 32 的实际表现）。
5. **R3 的异常触发未在本机构造出反例**（需无法枚举接口的环境）；"失败返回 null → INTERFACE_ERROR → failure"是源码可证 + 反射动态可证，"面板显示"需人工。
6. **扫描从未端到端实跑**：`expandCidr`/`candidateTargets`/`localNetworkCidrs` 走反射直接调用（真实实现，但绕过 `submit` + `invokeLater` 编排）。
7. **`deviceInfo` 的 `param get` 次数**未实测（仅有 TTL 源码断言）。
8. **颜色/主题对比度（高对比/暗色主题）**未目视。
9. **`AccessibleRole.PUSH_BUTTON` 的实际透出**依赖 IntelliJ 的 a11y 桥接（§5 第 4 项）；`getAccessibleContext()` 覆写只在源码层可证。
12. **真机截图的原始测量值来自用户**：U1 的 `(230,232,230)`/y=635..639、U2 的 412px 与 y≈362 均为用户截图测量，本机无法复现该主题；我只能证明修复机制（源码取值 + Swing 镜像 + 像素复算），最终观感见 §5 第 11–13 项。
13. **`UIUtil.getPanelBackground()`/`getBoundsColor()`/`getErrorForeground()`/`SUCCESS` 在真实主题下的取值**未在本机渲染（测试 JVM 不初始化 IntelliJ 平台）；U5 的像素镜像用探针色对 `blend` 做算术复算，不调用 `UIUtil`。
10. **设置页开启 `autoConnectSaved` 后无需重开工具窗即生效**：当前只在面板构造时读取（`:188`）；契约措辞是"工具窗打开时"，故不判缺陷，仅记录。
11. **`deviceSignature` 的理论碰撞**：字段间用 `FIELD_SEPARATOR` 分隔，若某个 hdc 返回值本身包含 `\u0000F\u0000` 仍可构造碰撞；该字符不可能出现在 hdc 输出中（`NO_REASON`/`HAS_REASON` 前缀已覆盖 marker 本身被当作 reason 的对抗用例）。

---

## 7. 待处理项（交付版）

| 优先级 | 项 | 理由 | 成本 |
|---|---|---|---|
| P4 | D13 补 SPEC §8 逐字文案 | 契约 v1.5 已接受等价改写，可作为可选项 | 一行 |
| P4 | A4.20 文案若要与 SPEC 逐字一致 | 现为按来源类别枚举；契约 v1.6 已按"来源语义对齐"收口 | 一行 |
| P4 | 工具窗文案是否也中文化 | 设置页已中文而工具窗仍英文（A10.6 只覆盖设置页）；统一语言需用户决定 | 中（约 40 处文案） |
| P4 | 禁用态 Disconnect 的色块强度 | 连接中禁用时色块不随 `isEnabled` 变化（§3.4 INFO-1） | 一行 |
| — | D7 取消不关闭 socket | Lead 已声明接受（契约 §8） | — |

**无 P0–P3 待办**：本次交付的 FAIL/PARTIAL 均为 0；唯一 MANUAL（A3.1）只需目视确认，
另有 A10.1/A10.2/A10.3/A10.5 四项机制已自动化、最终观感待 `runIde`（§5 第 11–14 项）。
> 说明：§5 的目视清单与 §0/§2 的部分 `文件:行` 引用写于第 6 轮时点，当时工具窗还是英文；本轮基线里
> 工具窗文案已中文化（与 §2 A10.6 的“设置页中文”合并为全中文界面）。§5 的**核对动作**仍然有效，
> 但其中引用的英文样例文字已过期，请以实际界面为准；行号引用以 §0.1 与本轮 §8.6 的哈希为准。

---

## 8. 第 7 轮（QA 收尾复核，2026-09-29）

> 背景：上一位独立验证者在 provider 故障前留下 `139 tests completed, 4 failed`，并把
> `ContractRegressionTest.kt` 的一条断言写到一半（`match.groupValues[5]` 缺 `!!`）；实现者已做最小语法
> 修补（`val found = match ?: return@forEach`），未改断言意图。本轮由**新的独立验证者**接手：
> 独立复核那 4 条失败、逐条修正测试、新增图标契约测试。
> **本轮只改 `src/test/**` 与本文档，`src/main/**` 一个字节未动**（本轮开始与结束时的 `src/main` 哈希一致，见 §8.6）。

### 8.1 构建与制品

```shell
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew clean buildPlugin test
```

| 项 | 值 |
|---|---|
| 结果 | `BUILD SUCCESSFUL` |
| 用例 | **148 tests completed, 0 failed, 0 errors, 0 skipped**（基线 139/4） |
| 制品 | `build/distributions/HDC Wi-Fi-1.1.0.zip` |
| 字节数 | **274348** bytes |
| 时间戳 | **2026-09-29 17:51:37** |
| SHA-256 | `3cad7bee5bb383e1667f726f9445357f292ddbd2eb4e5a94ed4c4bfcc18a811f` |

用例增量：`139 → 148` = 新增 `IconContractTest`（7）+ 两条反例控制（`ContractConformanceTest` 16→17、
`PanelLocalizationContractTest` 4→5）。

### 8.2 4 处失败的复核结论：**同意"全部是测试自身的问题"**，无生产 bug

| # | 失败（`file:line`） | 判定 | 独立证据 |
|---|---|---|---|
| 2.1 | `ContractConformanceTest.kt:311` `the setter must trim its input` | **测试锚点指错对象** | `MainSources.bodyOf(state, "var scanPorts: String")` 命中的是 `HdcSettings.kt:21` 的 **`State` 字段** `var scanPorts: String = ""`（其后紧邻 `override fun getState()`，切片止于 `:34`），里面根本没有 setter；真正的属性在 `HdcSettings.kt:81-83`，`set(value) { myState.scanPorts = value.trim() }` 本身是对的 |
| 2.2 | `AutoRefreshToggleStyleTest.kt:129` `ArrayIndexOutOfBoundsException` | **测试镜像尺寸不一致** | 生产的 `toggleButton()`（`HdcMainPanel.kt:261-270`）本轮已去掉 `preferredSize = Dimension(30, 28)`，只留 `margin = JBUI.insets(2, 6)`；测试却在 `button()` 里给每个按钮 `size = preferredSize`，`关闭` 与 `30s` 的自然宽度不同 → `countDifferentPixels` 用较短图的尺寸遍历较长图 → 越界。生产侧无异常 |
| 2.3 | `PanelLocalizationContractTest.kt:54` 报两个“英文 UI 文案” | **提取器把模板碎片当文案** | 被点名的两项是 ` }}]，` 与 ` }}]`，来自 `HdcMainPanel.kt:763-764` 的嵌套模板 `"...ifBlank { "无" }}]，"`；`STRING_LITERAL` 平铺扫描在**内层引号**处截断，产出不含任何字母的标点尾巴。白名单不该为它们开口子 |
| 2.4 | `PanelRenderingContractTest.kt:242` `assertNotEquals` 失败，`Actual: rgb(52,38,40)` | **反例控制没有真正进入 rollover** | `chip()` 用 `model.isRollover = true` 强制悬停，但 **`DefaultButtonModel.setRollover(true)` 在按钮 `isEnabled == false` 时是 no-op**（JDK 21 实测：`disabled→rollover = false`、`rollover→disabled = false`、`enabled→rollover = true`）。因此 guard 版与 unguard 版渲染出同一像素；`rgb(52,38,40) = #342628` 正是 `blend(#1E1F22, #5E3335, 0.35)`，即**禁用态淡化本身是对的** |

第 3 点还顺带发现：`PanelRenderingContractTest.kt:229-232` 那条 "a disabled chip must not light up on hover"
在修复前**同样是空转**——它比较的两张图都没有真正 hover。本轮一并修好。

### 8.3 逐条修复与反回归能力

| # | 修法 | 回退什么会让它失败 |
|---|---|---|
| 2.1 | 锚点改为**带 getter 的属性头** `"var scanPorts: String\n        get() = myState.scanPorts"`（`State` 字段不可能匹配到它），仍断言整句 `set(value) { myState.scanPorts = value.trim() }`；并新增反例控制 `the scan ports trim assertion catches a setter that stopped trimming`，在**内存副本**上把 setter 改回 `myState.scanPorts = value` 后断言提取结果不再满足 | 把 `HdcSettings.kt:83` 的 `value.trim()` 改回 `value` → 反例控制断言失败（`assertFalse`），主断言也失败。**未**改成检查 `State` 字段 |
| 2.2 | 镜像属性集逐项对齐生产 `toggleButton()`（`toolTipText`/`accessibleName`/`margin`/`isContentAreaFilled`/`isBorderPainted`/`isFocusable`；图标因 `AllIcons` 需要平台而无法镜像，已在注释说明）；两态先 `setSize` 到同一 canvas（78×29）再逐像素比较；断言从 `> 0` 收紧为 `>= 50`（单个抗锯齿像素不算“看得出区别”） | 去掉 `isSelected`/`foreground`/`font` 任一态差异，或把 `isContentAreaFilled` 改回 `false` 使选中态不可见 → 差异像素数跌破 50（实测 **1263**）；生产侧对应的源码断言在 `ContractConformanceTest#the auto refresh control states the interval and differs by more than selection` |
| 2.3 | `literalsIn()` 只放行**含至少一个字母**（`\p{L}`）的片段：纯标点/空白尾巴被跳过，含拉丁字母的英文片段仍会被抓；白名单**未**新增任何条目。并新增反例控制 `the invariant still catches a genuine english literal`：把 `"刷新设备列表"` 换成 `"Refresh device list"` 后必须报出且恰好报出它 | 把任意 tooltip 改回英文 → 反例控制/不变量失败；把过滤器放宽成“全部放行” → 反例控制失败。已用一次性 dump 核对**全部 52 条被提取字面量**：除白名单 12 项外全部含汉字，无真实英文文案被漏放行，§4 白名单仍有效 |
| 2.4 | `chip()` 改为覆写模型：`model = object : DefaultButtonModel() { override fun isRollover(): Boolean = rollover }`（在禁用态下唯一能表达 hover 的方式），guard/unguard 两版都真正进入 rollover | 去掉生产的 `isEnabled && (model.isRollover \|\| model.isPressed)` 守卫 → `PanelRenderingContractTest#the disabled disconnect chip fades and never brightens on hover` 的源码断言（`:193-200`）失败；若只把镜像的 `guardEnabledCheck` 默认值翻成 `false` → “stays faded”断言失败 |

2.4 的实测像素（`x=1, y=16`，本轮探针，探针已移除）：

| 场景 | 像素 |
|---|---|
| 禁用态 + 真实 rollover + **保留**守卫 | `rgb(52,38,40)` = `#342628`（淡化值，与 `disabledChip` 常量相等） |
| 禁用态 + 真实 rollover + **去掉**守卫 | `rgb(74,45,47)`（变亮，反例成立） |
| 启用态 chip | `rgb(94,51,53)` = `#5E3335` |

> 说明：由于 `DefaultButtonModel` 在禁用时拒绝 rollover，真实用户操作里禁用按钮其实**永远不会**进入 hover，
> 生产守卫因此是纵深防御而非唯一防线（真正的第一道防线是 Swing 模型本身）。这不改变结论——守卫存在、
> 可观测，且本轮已让“去掉守卫就变亮”这件事真的可被观测。

### 8.4 图标契约测试（新增 `IconContractTest`，7 例）

用户提供位图 logo，实现者描摹为 4 个 SVG；本轮**独立**验证（不改 `plugin.xml` / `Icons.kt`）：

| 契约 | 结论 | 依据 |
|---|---|---|
| 4 个文件存在、合法 XML、根 `svg`、带 `xmlns`/`viewBox`/数字 `width`+`height` | **PASS** | `all four traced icons exist and are standalone svg documents` |
| `hdc.svg`/`hdc_dark.svg` = 13×13；`pluginIcon*.svg` = 40×40 | **PASS** | `the tool window icon is 13px and the plugin logo is 40px` |
| 无内嵌位图：无 `<image>`、无 `base64`、无 `data:` | **PASS** | `no icon embeds a bitmap the platform renderer cannot draw` |
| 无纯白填充（`#FFFFFF`/`#FFF`/`white`/`rgb(255,255,255)`，含 `style="fill:…"`） | **PASS** | `no icon paints a pure white fill that would glow under the dark theme` |
| `_dark` 与浅色**不同**且**逐通道不更暗**、整体更亮 | **PASS** | `the dark variants are recolours of the same shapes, never darker`；浅 `#38A6D3/#4DE5EA/#2B7DBE` → 深 `#60B8DC/#71EAEE/#5597CB`，逐通道严格变大 |
| `plugin.xml` toolWindow 的 `icon` 仍为 `com.xq.hdcwifi.Icons.HDC`；`Icons.kt` 仍为 `/icons/hdc.svg`；plugin.xml 不自行声明 `pluginIcon` | **PASS** | `the tool window still loads the traced icon and only through Icons` |
| 每个 SVG 至少一条 `<path>`，`d` 非空，`fill-rule="evenodd"` | **PASS** | `every icon is drawn with non-empty even-odd paths` |

平台约定已对照 **2023.3 平台源码**复核（`ideaIC-2023.3-sources.jar`）：

* `PluginLogo.kt:47-48,145` `getPluginIconFileName(light)` 只认 `META-INF/pluginIcon.svg` /
  `META-INF/pluginIcon_dark.svg` —— 即自动发现，无需（也无处）在 `plugin.xml` 声明；
* `ImageDescriptor.kt:77-101` `addFileNameVariant` 会生成 `_dark`、`@2x_dark`、`_dark@2x`、`@2x`
  变体并在缺失时回落非深色版 —— 即 `IconLoader.getIcon("/icons/hdc.svg")` 会在深色主题下自动
  解析到 `/icons/hdc_dark.svg`。

几何一致性补充：`hdc.svg` 与 `pluginIcon.svg` 除 `width`/`height` 外**逐字节相同**（`diff` 验证），
两组 `_dark` 也只改 `fill` 值；即两个尺寸共用同一份描摹，不存在两份会漂移的路径数据。

### 8.5 新发现问题

| 级别 | 项 | 说明 |
|---|---|---|
| **INFO** | `ZzSwingProbeTest` / `ZzProbeTest` 无任何断言 | 3 条用例只 `println("PROBE …")`，**永远不会失败**。它们是前几轮的诊断探针（本轮 2.4 的 rollover no-op 结论正是用同类手法实测的），保留有价值，但不构成本轮 148 中的有效覆盖；若要计入通过率需先给它们加断言 |
| **INFO** | 提取器看不见嵌套模板的内层字面量 | `"…${x.ifBlank { "无" }}"` 中内层 `"无"` 落在两次匹配的引号之间，不被提取；但外层英文片段（含字母）一定会被抓，故不变量对“英文文案回归”仍然可靠 |
| **INFO** | `allowedEnglish` 未含全部 §4 线程名 | 白名单有 `hdc-detect`/`hdc-test`，没有 `hdc-test-read`/`hdc-probe`/`hdc-worker`。它们出现在 `Thread(...)` 参数而非 widget sink，当前不会被提取，故不构成违规；若日后扩展提取范围需同步补齐 |
| **INFO** | 本文档 §5 目视清单的文案样例属于第 6 轮时点 | 工具窗现已中文化（第 7 轮 U6/§4 白名单），§5 第 1–10 项的英文示例（如 `Auto-refresh is off. Click to refresh every 10s.`）已过期；观感核对项本身仍有效，以实际界面为准 |
| **INFO** | §2 A6.5（“工具窗不得硬编码中文”，记 PASS “0 命中”）已与当前实现相反 | 基线里 `HdcMainPanel.kt` 的用户可见文案已是中文，`PanelLocalizationContractTest` 正是按“描述性文案必须含汉字、仅动作词白名单保持英文”在守；同理 `docs/ux/UX-CONTRACT.md:112`「不得引入中文硬编码到 UI」也是旧条款。用户后续明确要求中文界面，故以 §4 白名单 + 该测试为准，判 **INFO（文档口径需更新）**，不是生产缺陷；按纪律本轮不改 `UX-CONTRACT.md` |

本轮**未发现 BLOCKER / MAJOR**。既有约束全部复核通过：`SCAN_CONCURRENCY = 64`、
`MAX_SCAN_PORTS = 6`、`PROGRESS_INTERVAL_MS = 200L`、`PROBE_TIMEOUT_MS = 15_000L` 均有测试钉住；
`networkCidr` 的 `joinToString(".") { … } + "/$prefixLength"` 修复形态未回退（`HdcService.kt:235-239`），
并有“reflect 直调生产方法”级别的 `HdcScanCidrTest` 覆盖。

### 8.6 本轮验证快照（`src/main/**`，本轮未修改）

```shell
shasum -a 256 <files>
```

| 文件 | SHA-256 |
|---|---|
| `toolwindow/HdcMainPanel.kt` | `4573cfed54a01a9931e105f7df8b6238360cb643fef502a681f972790c46043b` |
| `settings/HdcSettings.kt` | `ab4ee9a5cb160ba720885f810581faf4e162b208f78acb29da40e42ebe7a26d6` |
| `Icons.kt` | `db898a1d998933eae9cc0841ce653d3e4238d96b06283b14d68f7a32c5734103` |
| `META-INF/plugin.xml` | `e70c1702aea9e86d0961653d3f092b3b38eb82ed07195ed850821873cdbd7d8d` |
| `icons/hdc.svg` | `6f868660fd06a7d5ae38030aab814e685ee3285a755836b7791f96b013695a65` |
| `icons/hdc_dark.svg` | `cd0d9683b08569b5fd03ec1a2756033d1f361d19cbc054bde0f21f6a0e1138fa` |
| `META-INF/pluginIcon.svg` | `cbf4208192d410336f54f136ee28b4594892dca28cb0768bfed49a62bb88d3de` |
| `META-INF/pluginIcon_dark.svg` | `faf955d7d8b4703e2c672fdf458be49c149d2e40f089c730beee268edebe1192` |

本轮改动的测试文件：`ContractConformanceTest.kt`、`AutoRefreshToggleStyleTest.kt`、
`PanelLocalizationContractTest.kt`、`PanelRenderingContractTest.kt`，新增 `IconContractTest.kt`。
