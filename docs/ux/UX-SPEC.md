# HDC Wi-Fi — 交互与视觉规格 (v1)

## 1. 适用范围与决策原则

本规格细化 [UX-CONTRACT.md](UX-CONTRACT.md)。契约第 1–6 节优先；本文件未重述的行为仍按契约执行。所有界面文字使用英文，所有非状态颜色使用 IntelliJ Platform 主题色。状态语义色仅用于状态指示与状态文字，按钮使用主题 action/link 或常规前景色，不写死 RGB。

本规格以工具窗的三组设备清单为唯一设备状态视图；Console 继续保留原始 HDC 命令与错误输出，不能代替行内状态或失败原因。

## 2. 信息架构

### 2.1 工具栏

工具栏从左至右排列如下；同一组内相邻按钮间距为 `JBUI.scale(2)`，组与组之间插入 `JBUI.scale(6)` 的固定空白。所有图标按钮尺寸为 `30 × 28` logical px，保留可聚焦性。

| 组 | 顺序 | 控件 | 行为 |
|---|---:|---|---|
| 设备 | 1 | Add device | 打开已有的手动连接对话框。 |
| 设备 | 2 | Scan for devices | 启动按需发现；扫描期间改为 Cancel scan。 |
| 视图 | 3 | Show or hide command output | 显示或隐藏 Console。 |
| 连接状态 | 4 | Refresh devices | 请求一次 `list targets`；进行中禁用。 |
| 连接状态 | 5 | Auto-refresh split button | 主区域切换开/关；下拉菜单选择 `Off / 5s / 10s / 30s / 60s`。 |
| 设置 | 6 | HDC Wi-Fi settings | 打开 Settings > Tools > HDC Wi-Fi；关闭对话框后重新读取设置并重新配置刷新。 |

选择此顺序的理由：新增扫描紧邻“添加设备”，而刷新和自动刷新作为同一类状态更新操作相邻。

Auto-refresh 的 split button 主区域不改变已选间隔：当前为 Off 时点击启用 `10s`；当前为任意非零间隔时点击设为 Off。菜单选择任一非 Off 值即启用并写入 `autoRefreshSeconds`；选择 Off 即禁用并写入 `0`。工具窗快捷菜单只暴露五个契约档位；设置页的 `0..3600` 秒数值仍是权威值，设置为非档位值时工具栏显示启用且 tooltip 使用实际秒数，菜单中不额外伪造选中项。

### 2.2 设备分组与默认展开状态

设备区始终按下列顺序渲染，分组标题始终存在（即使为空），计数仅在大于 0 时显示为 `Title (N)`：

1. `Available on network`：仅保留最近一次已完成、未取消扫描的可达候选；默认展开。扫描未曾成功完成时仍显示此组的扫描引导空态。
2. `Connected`：来自最新一次 `list targets` 和本会话连接成功回调的并集；默认展开。
3. `Previously connected`：已保存但当前不在 Connected 的设备；默认展开。

折叠状态按分组在当前工具窗实例内保持，刷新、扫描进度更新和详情更新都不得重置。标题全行可点击和可用键盘激活；其右端仅显示箭头图标，不再显示 `⌃` 或 `⌄` 字符。计数为零时标题不显示 `(0)`。

空态（展开时显示在标题下，左缩进与设备标题对齐）：

| 分组 | 条件 | 文案 |
|---|---|---|
| Available on network | 从未完成扫描 | `Scan to find devices on your network.` |
| Available on network | 扫描完成且无结果 | 使用第 5.2 节按来源生成的具体空态。 |
| Connected | 无已连接设备 | `No connected devices.` |
| Previously connected | 无已保存设备 | `No previously connected devices.` |

扫描中，Available 分组的空态位置显示扫描进度行而不是普通空态。连接中的设备始终在其原归属组内：已保存设备在 Previously connected，扫描发现但尚未成功连接的设备在 Available on network。连接成功后，设备立刻进入 Connected 且从 Available/Previously connected 中移除；连接失败则返回原归属组并持久显示 `Failed`。

## 3. 设备行规格

### 3.1 通用几何与内容

每一行采用三列：左侧状态区、可增长的文本区、右侧操作区。行边距为上/下 `8`、左/右 `12` logical px；状态区宽 `20`；状态区与文本区间距 `8`；文本区与操作区间距至少 `12`；按钮间距 `4`。行不设置固定 `maximumSize` 高度，文本区按内容计算首选高度，长名称和详情换行，操作区保持右对齐并垂直居中。

文本区固定三行，顺序不可变：

* 第一行：粗体主标题 `device.name`，过长时省略并设置完整名称 tooltip。
* 第二行：状态图标语义对应的英文状态文字；不能复用详情文字。
* 第三行：`device.details`；地址必须在此行可见。过长时换行，完整值作为 tooltip。

右侧主按钮总是含图标和文字；Forget 和 More 都是图标按钮。`CONNECTING` 的主按钮仍渲染，但禁用。手动发起连接、扫描后自动连接、启动自动连接都使用同一个四态状态存储与同一个连接入口。

### 3.2 四态线框

以下 `●`、`○` 和 `◌` 表示状态指示的视觉形状，不是代码中直接显示的文本字符；实际图标见第 4 节。`[ ]` 表示按钮，`×` 表示禁用。

#### DISCONNECTED

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  ○   Pixel Pad                                                             │
│      Not connected                                  [▶ Connect] [−] [⋯]    │
│      HarmonyOS device - 192.168.1.32:5555                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

`[−]` 是 Forget；仅对已保存设备显示。Available 的未保存扫描结果不显示 Forget。`[⋯]` 是 More actions。

#### CONNECTING

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  ◌   Pixel Pad                                                             │
│      Connecting…                                   [▶ Connect]× [−] [⋯]   │
│      HarmonyOS device - 192.168.1.32:5555                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

左侧 `◌` 为持续旋转的进度指示器；主按钮可见但禁用，Forget 和 More 也禁用，避免连接中删除或重复触发同一目标。

#### CONNECTED

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  ●   Pixel Pad                                                             │
│      Connected                                      [Ⅱ Disconnect] [⋯]    │
│      HarmonyOS 5.0.0 (API 12) - 192.168.1.32:5555                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

Connected 行绝不显示 Connect 或 Forget。Disconnect 为中性色次操作；More 保留 Device tools、Copy address 和 Disconnect 菜单项。

#### FAILED

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  ●!  Pixel Pad                                                            │
│      Failed                                        [▶ Connect] [−] [⋯]     │
│      HarmonyOS device - 192.168.1.32:5555                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

`●!` 表示红色实心圆并附带错误语义，不靠红色单独传达失败；状态文字为 `Failed`。整个状态行、状态图标和行容器均设置同一失败 tooltip（第 4.3 节模板）。用户点击 Connect 即重试；失败状态仅在连接成功、设备被明确 Forget、或该地址已由 `list targets` 确认为在线时清除。

## 4. 视觉与图标

### 4.1 颜色、形状与动效

| 状态 | 形状与动效 | 文字 | 色彩来源 |
|---|---|---|---|
| CONNECTED | 16 px 实心圆，内置 `✓` | `Connected` | 成功绿主题语义色 |
| CONNECTING | 16 px 环形旋转器，持续 12 fps 旋转 | `Connecting…` | `UIUtil.getContextHelpForeground()` |
| FAILED | 16 px 实心圆，内置 `!` | `Failed` | 错误红主题语义色 |
| DISCONNECTED | 16 px 空心圆 | `Not connected` | `UIUtil.getContextHelpForeground()` |

状态圆点由 `StatusIndicatorIcon` 绘制，以满足契约要求的实心圆、空心圆和动画形状；其内部标记采用第 4.2 节指定的 AllIcons 图形语言。不能用单个现成 AllIcons 直接替代状态圆点，因为平台图标库没有同时满足“空心圆 / 实心圆 / 可旋转环”这一组契约形状的同族资源。

### 4.2 AllIcons 常量选型

| 视觉元素 | 指定常量 | 用途 / 当前替换对象 |
|---|---|---|
| Connected 状态内部标记 | `AllIcons.Actions.Checked` | 绘制在成功实心圆内的 `✓`，替代当前 `JBLabel("●")` 的纯文本圆点。 |
| Connecting 状态内部标记 | `AllIcons.Process.Step_1` | 作为 `StatusIndicatorIcon` 的动画帧基础；以 Swing `Timer` 旋转，替代当前无动画的 `Connecting...` 按钮文案。 |
| Failed 状态内部标记 | `AllIcons.General.Error` | 绘制在失败实心圆内的错误标记，替代当前失败只写 Console、无行内图标。 |
| Disconnected 状态内部标记 | `AllIcons.General.Information` | 绘制在空心圆内的中性标记，替代当前 `JBLabel("◌")` 的纯文本圆点。 |
| Connect | `AllIcons.Actions.Execute` | 主操作按钮前置图标，替代当前无图标且硬编码绿色的文字按钮。 |
| Disconnect | `AllIcons.Actions.Suspend` | 次操作按钮前置图标，替代当前同为绿色且无图标的 Disconnect。 |
| Scan for devices | `AllIcons.Actions.Search` | 工具栏扫描按钮，替代当前无扫描入口。 |
| Cancel scan | `AllIcons.Actions.Suspend` | 扫描中同一位置的取消按钮，替代继续显示 Scan。 |
| Refresh devices | `AllIcons.Actions.Refresh` | 保留当前刷新图标。 |
| Auto-refresh on | `AllIcons.Actions.Refresh` | 选中态同一图标配平台 selected 背景/边框，替代当前没有开关入口。 |
| Auto-refresh off | `AllIcons.Actions.Refresh` | 未选中态同一图标配普通背景；不以颜色单独区分开关。 |
| 分组已展开 | `AllIcons.General.ArrowDown` | 替代当前 `⌃` 文本字符。 |
| 分组已折叠 | `AllIcons.General.ArrowRight` | 替代当前 `⌄` 文本字符。 |
| Forget device | `AllIcons.General.Remove` | 替代当前错误的 `AllIcons.Actions.GC`（垃圾桶 / GC 语义）。 |
| More actions | `AllIcons.Actions.More` | 保留现有更多操作图标。 |

### 4.3 Tooltip 与显式状态

Tooltip 是补充信息，绝不是唯一信息载体。状态文字、按钮文字、扫描进度和刷新时间必须始终可见。失败 tooltip 原样显示：

```text
Connection failed: {rawError}
```

`{rawError}` 使用 HDC 回调的未截断错误字符串；空白时使用 `Unknown error.`。错误文字同时写入 Console，前缀为 `Connection failed: `。

Auto-refresh tooltip 的精确模板：

```text
Auto-refresh: Off
Auto-refresh: Every {seconds}s
```

启用时 second line 不存在；关闭时只显示第一种。Split-button 箭头 tooltip 为 `Choose auto-refresh interval`。

## 5. 扫描交互

### 5.1 候选构建和扫描参数

用户点击 `Scan for devices` 后，先校验 HDC 可执行文件可用性；不可用时按钮禁用，tooltip 为 `HDC is not available. Configure it in Settings.`，不开始扫描。可用时建立一次扫描会话：

1. 始终加入已保存设备的 host，端口为保存地址中的端口。
2. 枚举本机可用 IPv4 接口；每个不同的 `/24` 加入其中 `.1`–`.254` host，端口为 `settings.defaultPort`。本机地址本身包含在范围内。
3. 若设置了非空自定义 CIDR，加入该 CIDR 的所有可用 host，端口为 `settings.defaultPort`；CIDR 中不允许 IPv6，IPv4 有效地址总数上限为 4096，超过时不启动扫描并显示错误状态。
4. `(host, port)` 去重；同一 host 来自保存设备时，保存地址端口优先于默认端口。

TCP 探测并发上限为 32，单 host connect timeout 为 300 ms。扫描会话持有取消令牌；取消后不再提交未开始的探测，已开始的 socket 立即关闭。该参数固定，理由是一个 `/24` 在低延迟局域网应快速完成且不会制造无上限连接风暴。

本规格将“扫描时覆盖端口”定为不在工具窗增加字段：扫描端口来自保存地址或 Default Wi-Fi port。用户需覆盖时在 Settings 修改 Default Wi-Fi port 后再扫描，避免工具栏塞入不可发现的临时输入。

### 5.2 状态流转

```text
Idle
  └─ click Scan for devices ─► Preparing candidates
       ├─ HDC unavailable ─► Disabled / no scan
       ├─ no usable IPv4 and no saved host and no custom CIDR ─► Completed empty
       ├─ invalid/oversized custom CIDR ─► Completed error
       └─ candidates ready ─► Scanning (Scanned 0/N)
             ├─ each probe settles ─► Scanning (Scanned n/N)
             ├─ click Cancel scan ─► Cancelling ─► Cancelled
             ├─ all probes settle, >0 reachable ─► Completed results
             └─ all probes settle, 0 reachable ─► Completed empty
```

扫描期间：

* 工具栏 Scan 图标替换为 Cancel scan，tooltip 为 `Cancel scan`；Add device、Settings、Console toggle 和设备行操作保持可用。
* Available 标题下面显示非闪烁进度行 `Scanned {completed}/{total}`；右侧显示文字按钮 `Cancel`。进度每完成一个候选更新一次，数字不倒退。
* Refresh devices 按钮保持可用；auto-refresh tick 保持运行。刷新只更新 Connected，不重置、清空或改变当前扫描候选和进度。
* 第二次 Scan 点击等同取消当前扫描；没有并行扫描会话。

完成后：

* 有结果时，Available 标题计数为可显示行数，按 `name/address` 不区分大小写排序；显示每台可达候选。扫描完成提示为 `Found {count} device(s).`，在进度行位置显示 4 秒后消失。
* 用户取消时，丢弃本次尚未提交的结果，保留上一次已完成扫描结果；进度行替换为 `Scan cancelled.`，显示 4 秒后恢复前一空态或上一结果。取消不是错误，不写失败状态。
* 可枚举来源全部为空时显示 `No network addresses are available to scan.`。
* 某个本机 `/24` 无结果时显示 `No devices found on {network}/24.`；多个本机网段无结果时显示 `No devices found on local networks.`；仅自定义网段无结果时显示 `No devices found on {cidr}.`；保存 host 唯一来源无结果时显示 `No saved devices are reachable.`。
* CIDR 无效或超限时显示 `Cannot scan {cidr}: use an IPv4 network with at most 4096 addresses.`。
* 网络接口枚举出现权限或系统错误时显示 `Unable to read local network interfaces. You can still connect a device manually.`；该错误写入 Console 一次，不打印堆栈。

### 5.3 扫描结果与连接状态的去重规则

每次渲染先按 address 合并 Connected、连接中/失败状态条目和扫描结果：

1. address 当前为 `CONNECTED` 时只出现在 Connected，绝不在 Available 重复出现。
2. 扫描命中一个已保存但未连接地址时，只在 Previously connected 显示；该行的可达性不是额外状态，仍显示 `Not connected` 或 `Failed`。Available 计数不包含它。
3. 扫描命中一个未保存地址时，显示在 Available。点击 Connect 成功后才 `rememberDevice`，立即从 Available 移至 Connected。
4. 扫描不直接改变 `DeviceConnectionState`、不会保存设备、不会刷新设备详情，也不会调用 HDC `tconn`，除非第 7 节的自动连接设置已启用。

这一定稿避免用户在两个分组看到同一地址而误以为是两台设备。

## 6. 自动刷新

Refresh 的轻量进行中指示放在工具栏 Refresh 图标上（活动 spinner / disabled icon），并在工具栏尾部显示只读标签 `Last updated {HH:mm:ss}`。尚无成功刷新时显示 `Last updated —`。时间使用本地 24 小时制、两位时分秒；失败刷新不更新最后成功时间。

自动 tick 在 `!isShowing` 时跳过且不排队；组件从不可见变为可见时立即请求一次刷新。若刷新正在进行，手动刷新、可见性补刷和 tick 统一只置一次 `refreshRequested`，当前请求结束后运行一次合并刷新。扫描不阻塞刷新，刷新不取消扫描。

仅当设备地址集合、每项的连接状态、保存设备可见字段或失败原因签名发生变化时重建设备区。签名相同只更新时间标签，不调用 `removeAll()`。必须重建时读取并恢复设备滚动条的垂直值。设备详情的获取仅针对新连接或详情缺失且距上次请求至少 60 秒的设备；自动刷新不能针对每个已连接设备重复执行 `param get`。

## 7. 自动连接

### 7.1 触发与队列

两项设置默认均为 `false`：

* `autoConnectDiscovered`：一次扫描完成后，对本次新发现且未连接的候选按地址排序串行发起连接。
* `autoConnectSaved`：工具窗首次打开时，对已保存但未连接的设备按地址排序串行发起连接。

两个来源共用单一 FIFO 队列，同一时刻只执行一个连接。启动已保存队列先入队；同一次扫描完成的 discovered 队列随后入队。连接已在进行、已连接、或本会话已自动尝试过的 address 不重复入队。每个 address 每会话自动尝试最多一次；用户手动重试不受此上限限制。

### 7.2 用户可见行为

队首开始时，对应行立即进入 `CONNECTING`，显示动画和 `Connecting…`；其按钮按第 3 节禁用。工具栏不弹通知。成功后转为 `CONNECTED`，并按第 2.2 节移动分组；失败后转为 `FAILED`，保留 `Connection failed: {rawError}` tooltip，并向 Console 写一行。队列不因单台失败停止，继续下一台。

对未保存的扫描结果，成功才调用 `rememberDevice`；自动连接失败不保存。手动点击 Connect 使用完全相同的 `requestConnection(address, origin)` 流程和状态迁移；其优先级高于尚未开始的自动队列项，若某地址正在自动连接，手动点击保持禁用而不创建第二个请求。选择此规则是为了让用户的显式动作不会被后台队列延迟，同时不产生同地址竞态。

断开操作不进入自动队列；本会话用户手动 Disconnect 的 address 加入“自动连接抑制集合”，直到下次工具窗新建或用户手动 Connect，防止自动刷新/后续扫描立刻把用户刚断开的设备重新连接。

## 8. Settings > Tools > HDC Wi-Fi

在现有 Connection 组中依次放置以下项目：

| 控件 | 英文 label | 英文说明 |
|---|---|---|
| 数字 spinner | `Auto-refresh interval:` | `Refresh connected devices automatically. Set 0 to turn it off.` |
| checkbox | `Connect discovered devices automatically` | `Connect each device found by a scan once. Failed connections stay visible and do not retry automatically.` |
| checkbox | `Connect saved devices when the tool window opens` | `Connect saved devices one at a time when this tool window opens. Failed connections stay visible and do not retry automatically.` |
| text field | `Custom network (CIDR):` | `Optional IPv4 network to include when scanning, for example 192.168.10.0/24. Networks larger than 4096 addresses are not scanned.` |

设置状态字段为 `autoConnectDiscovered: Boolean = false`、`autoConnectSaved: Boolean = false` 和 `customScanCidr: String = ""`。Apply 与 Reset 将这三项纳入 modified 比较。Apply 后工具窗立即读取新 refresh 值并调用 `configureAutoRefresh()`；自动连接复选框只影响未来触发，不为已开始的连接队列补发或取消任务。

## 9. 键盘可达性与辅助功能

工具栏和设备区控件均必须 `isFocusable = true`；当前 `iconButton` 设置 `isFocusable = false` 的行为必须移除。焦点顺序固定为：Add device → Scan/Cancel scan → Console toggle → Refresh → Auto-refresh 主区域 → Auto-refresh 下拉箭头 → Settings → 各分组标题 → 该分组每行的 Connect/Disconnect → Forget（若有）→ More actions。展开的行按视觉顺序从上到下进入焦点。

每个 icon-only 按钮同时设置 `toolTipText` 和 accessible name；toolbar 的 accessible name 分别为 `Add device`、`Scan for devices`、`Cancel scan`、`Show or hide command output`、`Refresh devices`、`Auto-refresh`、`Choose auto-refresh interval`、`HDC Wi-Fi settings`。Forget 为 `Forget device {address}`，More 为 `More actions for {address}`。按钮 label 是 Connect/Disconnect 的主可访问名称，图标不承担唯一语义。

分组标题使用可聚焦按钮或带 `AccessibleRole.PUSH_BUTTON` 的组件；Space 和 Enter 切换展开，Left 折叠、Right 展开。设备行的失败 tooltip 只补充原始错误；屏幕阅读器通过状态文字 `Failed` 与按钮 `Connect` 已能理解可重试状态。

色盲与高对比模式下，四态由“实心圆 + ✓ / 旋转环 / 实心圆 + ! / 空心圆”以及明确英文状态文案区分；绿色、红色和灰色不作为唯一信息。Auto-refresh 开关同时有 selected state、tooltip 和菜单中带 `✓` 的当前项，不只使用色彩。旋转器必须提供可读状态文字 `Connecting…`，不能把动效作为唯一反馈。

## 10. 实现映射与验收表

| SPEC 决策 | 契约条目 | 预期落地文件 / 类 / 函数 | 验收证据 |
|---|---|---|---|
| 显式四态、失败原因、同一状态来源 | §1.1, §1.4 | `model/HdcDevice.kt`：`DeviceConnectionState`、`HdcDevice.state`、failure 字段；`toolwindow/HdcMainPanel.kt`：状态存储与 `deviceRow` | 四态行、失败 tooltip 和重试后状态变化可见。 |
| 状态形状、动画和主题语义色 | §1 表、§0 | `toolwindow/HdcMainPanel.kt`：`StatusIndicatorIcon` / spinner Timer、状态颜色解析 | Connecting 连续旋转；高对比下状态仍靠形状/文字可辨；无 `Color(0x59A869)`。 |
| Connect/Disconnect 的图标、颜色和禁用策略 | §2.1–§2.4 | `toolwindow/HdcMainPanel.kt`：`actionButton`、`deviceRow` | Connected 仅 Disconnect；另三态仅 Connect；Connecting 中按钮可见禁用。 |
| 三个分组、默认展开、零计数与空态 | §4.5、§6.2–§6.4 | `model/HdcDevice.kt`：`DeviceGroups`、`groupDevices`；`toolwindow/HdcMainPanel.kt`：`render`、`sectionHeader` | 三组始终有标题；零时无 `(0)`；空态正确。 |
| 自适应设备行和分组箭头 | §6.1、§6.2 | `toolwindow/HdcMainPanel.kt`：`deviceRow`、`sectionHeader` | 长标题/详情不被 76 px 截断；无 `⌃/⌄`。 |
| Forget 使用 Remove | §2.3 | `toolwindow/HdcMainPanel.kt`：`deviceRow`、`forget` | 只见 `AllIcons.General.Remove`，无 `AllIcons.Actions.GC` 用于 Forget。 |
| 工具栏扫描入口、候选、并发和取消 | §4.1–§4.4 | `service/HdcService.kt`：`scanHosts` / `cancelScan` 或专用 `HdcDeviceScanner`；`toolwindow/HdcMainPanel.kt`：扫描会话、toolbar；`settings/HdcSettings.kt`：CIDR | 仅点击后 TCP 探测；并发≤32；Cancel 停止未开始任务。 |
| 扫描进度、空/错态、结果去重和不自动保存 | §4.4–§4.8 | `toolwindow/HdcMainPanel.kt`：Available render、扫描状态；`model/HdcDevice.kt`：合并规则 | 显示 `Scanned n/N`；Connected 不重复；扫描未连接不写保存项。 |
| 自动刷新开关、快捷间隔、可见性和最后更新时间 | §3.1–§3.4 | `toolwindow/HdcMainPanel.kt`：`createToolbar`、`configureAutoRefresh`、visibility listener、refresh label；`settings/HdcSettingsConfigurable.kt` | Off/5/10/30/60 菜单同步设置；不可见不 tick；可见补刷；显示时间。 |
| 去重重渲染、滚动保留和详情 TTL | §3.5–§3.6 | `toolwindow/HdcMainPanel.kt`：刷新签名、scroll 保存/恢复、`loadMissingDetails` TTL；`service/HdcService.kt`：详情缓存 | 相同 targets 不 `removeAll()`；滚动不跳；自动 tick 不反复 `param get`。 |
| 自动连接设置与串行队列 | §5.1–§5.3 | `settings/HdcSettings.kt`：新 State 字段；`settings/HdcSettingsConfigurable.kt`：checkbox/CIDR；`toolwindow/HdcMainPanel.kt`：队列、`requestConnection` | 默认两项 false；同刻一台；每地址每会话一次自动尝试。 |
| 手动/自动同路径与用户可见状态 | §5.3–§5.4 | `toolwindow/HdcMainPanel.kt`：`connect` 改为统一 `requestConnection`；`service/HdcService.kt`：复用 `connect` | 自动连接可见 Connecting→Connected/Failed；手动不走另一套逻辑。 |
| 可访问性与键盘顺序 | §0、§1、§2、§6 | `toolwindow/HdcMainPanel.kt`：按钮创建、accessible context、key bindings、section header | Tab 顺序、Space/Enter/Left/Right、accessible name 和非色彩冗余均可检查。 |

## 11. 对契约的异议

1. **契约 §4.3/§4.4 与现有服务能力冲突。** `HdcService` 目前只有 `connect`、`disconnect`、`listTargets` 和 `deviceInfo` 等 HDC CLI 能力，没有 TCP socket 探测、网络接口/CIDR 枚举、受限并发执行器或可取消任务 API；当前 executor 是无上限的 cached thread pool。按契约实现扫描必须新增网络扫描能力及其取消资源管理，不能只在 `HdcMainPanel` 里伪装扫描。建议 Lead 将 §4 的“实现要求”补充为：允许在 `HdcService` 或新建应用级 `HdcDeviceScanner` 中新增 TCP/网络枚举 API，并将并发上限作为可验证常量。

2. **契约 §1 的“从 AllIcons 指定状态指示”与要求的精确形状不完全兼容。** 当前平台 `AllIcons` 没有能同时精确代表实心圆、空心圆、可旋转环且同族一致的四态状态组。因此本规格将状态圆点定义为自绘 `StatusIndicatorIcon`，并使用 AllIcons 常量作为内部标记/图形语言。建议 Lead 明确允许此自绘状态 indicator；否则无法同时满足 §1 对几何形状和本任务对 AllIcons 选型的要求。

3. **契约 §4.2(c) 把自定义 CIDR 说为“可选项”，但 §4.4 要求其参与扫描。** 为使实施和验收无歧义，本规格将它定为必须新增、默认空的设置字段，且仅用户点击扫描时生效。建议 Lead 将“可选项”澄清为“功能必须提供、用户填写可选”。
