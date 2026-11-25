# Agent 运行时路径

本文只描述**实际上会跑的代码路径**，方便审阅、复现和二次开发。所有路径都相对于仓库根目录。当前对应 **DLA 3.6.0**。

## 1. 一次任务的生命周期

| 步骤 | 发生什么 | 代码 |
|---|---|---|
| 1 | 用户输入任务，界面组装 payload（sessionId、mode、model、apiKey、apiOrigin、protocol、provider、system、messages、mcpServerIds、autoApprove、maxRounds=120、可选的计划/笔记） | `android/.../nativeui/ChatEngine.kt` |
| 2 | 原生界面 `AgentClient.start()` | `nativeui/ChatEngine.kt` |
| 3 | 跨进程：`AgentClient` 通过 `bindService` + `Messenger` 把指令发给 `:agent` 进程的 `AgentService` | `agent/AgentClient.java`、`agent/AgentIpc.java` |
| 4 | 开一份运行日志账本 `AgentRunJournal`，写加密 checkpoint，启动 `AgentService`（specialUse FGS + 通知停止按钮）。**没有准入拦截**：内置 `phone_sh` 永远可用 | `agent/AgentRuntime.java` `start()`、`agent/AgentRunJournal.java`、`AgentService.java`、`AgentCheckpoint.java` |
| 5 | 建立工具集：13 个核心工具（Shizuku 就绪时 14 个）+ `tool_search` + 两个联网工具 + MCP + 任务记忆；按后端可用性裁剪 | `AgentRunner.connectTools()` |
| 6 | 进入循环：`streamRound()` 按所选协议发请求 → 解析文本/思考/`tool_use`/usage → 执行工具 → 结果作为 `tool_result` 追加 → 下一轮（上限 120 轮） | `AgentRunner.run()` |
| 7 | 每轮事件经 `AgentService` → `AgentClient` 回调给界面；同一份事件也折叠进运行日志（最多 700ms 写一次盘） | `AgentService`、`AgentClient`、`AgentRunJournal`、`nativeui/ChatEngine.kt` |
| 8 | 结束：`done` / `stopped` / `error`；日志落终态并广播、发结果通知、清 checkpoint、停服务 | `AgentRuntime.finish()` |

## 2. 事件协议

事件都是 JSON，`type` 区分类型。跨进程时由 `AgentService` 打成 `MSG_EVENT`（`requestId` + `json`）。

| type | 关键字段 | 说明 |
|---|---|---|
| `status` | `text` | 人类可读状态；运行开始时那条会报「核心工具 N 个已加载、无障碍开/关、Shizuku 就绪/未就绪、内置运行时、联网工具」，思考降档时也会报「第 N 轮思考过长，本轮改为直接执行（下一轮恢复思考）」 |
| `text` | `delta` | 增量正文 |
| `thinking` | `delta`、`round` | 增量思考内容；`round` 让界面按轮分卡 |
| `tool_start` | `id`、`name`、`input`、`approval`、`round`、`parallelGroup` | 工具即将执行；`approval=true` 表示要等审批 |
| `tool_result` | `id`、`name`、`content`、`isError`、`elapsedMs`、`round`、`parallelGroup`、`cached` | 工具结果；带图时另有 `image=true` 与 image block。`content` 已带 `[工具 · 身份]` 前缀 |
| `task_state` | `plan`、`current`、`notes` | 计划与笔记，随 checkpoint 持久化 |
| `usage` | `inputTokens`、`outputTokens`、`cacheCreationInputTokens`、`cacheReadInputTokens`、`elapsedMs`、`ttftMs` | 每轮请求结束时的用量；上下文用量条只认最近这一轮 |
| `done` / `stopped` / `error` | `stopReason` / `message` | 终止事件；`done` 的 `stopReason` 可能是 `end_turn` 或 `max_tokens` |

审批方向相反：界面调用 `AgentClient.approve(id, toolId, allowed)`，`:agent` 进程里的
`AgentRunner.approve()` 唤醒等待队列。等待上限 120 秒（`awaitApproval()`），超时按“未执行”处理。

> 兼容性：界面曾经只认 `text` / `is_error` 两个旧字段，而 runner 发的是 `content` /
> `isError`，所以工具详情一直是空的。现在两种字段都兼容。

## 3. 工具注入与按需发现

初始工具集不是“越多越好”，只有这几类会直接进上下文：

1. **核心工具（13 个）**：`phone_screen`、`phone_tap_text`、`phone_wait`、`phone_tap`、
   `phone_swipe`、`phone_input`、`phone_key`、`phone_screenshot`、`phone_view_image`、
   `phone_open`、`phone_apps`、`phone_device`、`phone_sh`（见 `AgentRunner.CORE_LOCAL`）。
2. **`phone_shell`（Shizuku 就绪时第 14 个）**：直接进核心，不再需要先 `tool_search` 一次，
   避免“运行时块写着可用、工具列表里却没有”。
3. **联网（2 个）**：`web_search`、`web_fetch` 始终注入——`web_fetch` 是原生抓取，任何供应商都能用。
4. **`tool_search`**：搜索扩展目录，返回说明与参数；命中后下一轮才把该工具加入 `tools`。
5. **MCP 工具**：按已连接 Server 注入，名称做 `server_tool` 前缀去重。

`phone_view_image` 是读图工具：给一个图片路径，按长边 1280、JPEG 82 压缩后以 image block
回传，模型下一轮就能“看到”这张图；超过 32MB 或不可解码直接报错，不会静默失败。

按需发现的工具包括：文件读写/替换、JSON、zip、docx、设置、剪贴板、通知、`phone_py`、`phone_js`、
`phone_web_shot`（关键词「网页 / HTML / 截图」）。

**按可用性裁剪**（`connectTools()`）：

| 条件 | 结果 |
|---|---|
| Shizuku 未就绪 | `phone_shell`、`phone_stop`、`phone_setting_put`、`phone_notifications` 不注入 |
| 无障碍未开启 | `phone_screen`、`phone_wait`、`phone_screenshot`、`phone_tap_text` 不注入 |
| 始终可用 | 内置运行时（`phone_sh` / `phone_py` / `phone_js`）、文件类、PackageManager 类、`tool_search`、联网、任务记忆 |

## 4. 三种执行身份与证据规则

| 身份 | 工具 | 能做 | 不能做 |
|---|---|---|---|
| 应用身份（untrusted_app） | `phone_sh` / `phone_py` / `phone_js` | 读自身目录与 `/sdcard`、grep/find/awk/sed、getprop、node/python | `pm` / `am` / `settings` / `dumpsys`；`/proc` 全局、`/data/app`、别家应用私有目录 |
| Shizuku（uid 2000） | `phone_shell` | `pm` / `am` / `settings` / `dumpsys` / 全系统 `ps` | 需要 root 的操作 |
| 无障碍 | `phone_screen` / `phone_screenshot` / `phone_tap*` / `phone_swipe` / `phone_input` / `phone_key` | 读屏、截图、点按、输入 | 不提供系统信息 |

配套的四条规则（`AgentRunner.TOOL_MAP` + 末尾 `SELF_CHECK`）：

1. **身份不能混**：`phone_shell` 跑通不等于应用身份可用；`phone_sh` 失败也不等于本机做不到。
2. **负证据无效**：应用身份下 `ps -A` 只有自己那几个进程，`/proc/net/tcp`、`ss`、`/data/app`
   都读不到，所以“没看到”不等于“没在跑”。
3. **先测再断言**：说“不可用”之前先实测；没测的标“未验证”。
4. **来源以标签为准**：每条工具结果开头都有 `[工具 · 身份]`，归因必须对得上。

每次运行开始时，`AgentRunner.runtimeBlock()` 会实测一次无障碍 / Shizuku / 内置运行时状态，
拼进 system 的 `【本机运行时】`；运行中的轮数上限是 120，连续 6 轮单工具会在第 6/12/18… 轮
各追加一次批量提示。

## 5. 运行日志与重启认领

正文和工具卡原本只累积在界面进程的内存里，界面一被系统杀掉，跑完的 `done` 就没人接——
表现是「任务完成，但 AI 消息消失」。现在由 `:agent` 记账：

| 项 | 行为 |
|---|---|
| 路径 | `files/agent-runs/<requestId>.json`，一个 run 一个文件 |
| 内容 | `text`、`thinking`、`tools`（名称/参数/结果/状态/耗时/轮次）、`usage`、`taskState`、`status` |
| 写盘 | 关键事件（工具开始/结束、`task_state`、终态）立即写；纯文本增量按 700ms 节流 |
| 单条上限 | 单条工具结果最多存 20000 字符，超出标注“日志已截断” |
| 终态 | `done` / `stopped` / `error` 后广播 `com.example.chatdemo.action.AGENT_RUN_UPDATE`，界面收到立刻认领 |
| 认领 | 界面启动/恢复/收到广播时 `ChatEngine.recoverRuns()`：跑完的补成消息，还在跑的重新挂成流式草稿 |
| 去重 | 助手消息带 `runId`，`SessionStore.hasRun()` 判重；实时事件与日志认领同时发生也只落一条 |
| 清理 | 认领成功后删除该文件 |

运行日志只用于“界面进程没接住”这一种情况；正常路径仍是实时事件直写会话。

## 6. 输出上限：截断自愈与自动续写

| 场景 | 以前 | 现在 |
|---|---|---|
| 工具参数被 `max_tokens` 截断 | 构造 assistant 消息时 `new JSONObject` 抛异常，整轮运行终止 | `parseArguments()` 返回 null；该调用标记 `truncated`，不审批、不执行，回一条可恢复提示（教 `phone_write` + `phone_append` 每段 ≤3000 字符），**本轮继续** |
| 回复本身撞到输出上限、且没有工具调用 | 当成任务结束，用户看到「想了一会儿就停了」 | 把已生成部分作为 assistant 消息，追加一条「从中断处继续、不要重复」的提示，最多 3 次，每次发 `status` 事件 |
| 输出上限被思考吃光、一个正文都没有 | 关闭思考重试，但此后整个 run 都不再思考 | 只让**这一轮**关思考重来；被截断的思考补进提示，下一轮恢复思考（见 6.1） |

## 6.1 思考预算与降档（3.6.0）

长任务里的目标是「思考 → 调用 → 思考」，不能因为一次想太久就把整个任务的思考关掉。

| 触发 | 处理 | 上限 |
|---|---|---|
| 本轮思考超过预算，且还没有正文 / 工具调用 | 中止本轮（断开流），本轮关思考重试；下一轮重新允许思考 | 同轮只重试 1 次 |
| `max_tokens` 收尾但一个正文字符都没有 | 同上：本轮关思考重来，不把空 assistant 塞进历史 | 同轮最多 2 次恢复 |
| 连续多轮想过头 | 本轮思考预算 12K → 6K → 3K 字符收紧；成功执行一轮立刻回到 12K | 看门狗整轮运行最多出手 10 次 |
| 重试 | 取被中止思考的尾巴（≤600 字符）接进提示，要求「接上它继续，不要整段复述」 | 不消耗轮数 |

### thinking 块必须回传

Anthropic Messages（含 DeepSeek `/anthropic/v1`）在开思考时要求：**历史里由「思考 + 工具调用」产生的 assistant 消息必须带回 thinking 块（含 signature）**，否则下一轮 400：

```text
The content[].thinking in the thinking mode must be passed back to the API.
```

实现：

- `handleEvent()` 在 `content_block_start` / `thinking_delta` / `signature_delta` 里累积思考与签名；
- `assistantMessage()` 把 `{"type":"thinking","thinking":…,"signature":…}` 放在 content 最前面；
- 看门狗截断、重试时思考是关的，这一轮不会再产出 thinking：把中止前的思考补回该条 assistant 消息（`recoveryThinking`）；
- 只对 Anthropic 方言下发；OpenAI 兼容层的 tool 消息不接受这个块。

Claude 走 `thinking.budget_tokens=4096` 做服务端硬预算；DeepSeek 及其它端点靠上面的客户端看门狗。

## 7. `phone_web_shot`（离屏网页截图）

- 参数：`path`（本地 HTML）、`width`（dp，240–1200，默认 412）、`height`（dp，320–2000，默认 860）、
  `full_page`（默认 false）、`wait_ms`（0–8000，默认 900）。
- 链路：`:agent` 的 `WebShot` 发广播 → 主进程 `WebShotReceiver` → `NativeShellActivity.renderWebShot()`
  在**当前窗口**里挂一个移出可视区的 WebView 渲染、截图、存盘 → 广播回路径 → 工具返回图片块 + 路径。
- 为什么不在 `:agent` 里做：没有挂到窗口的 WebView 不会真正绘制，`draw()` 出来纯黑。
- 限制：HTML ≤2MB、渲染超时 45 秒、`full_page` 最高 2000dp；**DLA 不在前台时直接报错**，
  不会为了截图把界面抢到前台。截图存 `/sdcard/Download/DLA-web-shots/`。

## 8. 数据落在哪

| 数据 | 位置 | 说明 |
|---|---|---|
| 会话与消息 | `files/native-sessions/<id>.json`（`SessionStore.kt`） | 权威存储；同一文件还存 `draft`（草稿）、`taskState`（计划/笔记）、`usageStats`（轮/步/上下文/单轮耗时）；助手消息带 `runId` |
| 运行日志 | `files/agent-runs/<requestId>.json` | 界面被杀后用来认领已完成/仍在跑的回答；认领后删除 |
| 诊断 | `files/diagnostics/events.log` | 环形日志（256KB），只记事件名与异常栈，不记消息正文 |
| 设置 | `SharedPreferences("dla-native")` | API Key、Base URL、模型、协议、供应商、缓存的模型列表、人设、朗读、主题、语言、上下文窗口、通知开关；只在本机 |
| 运行 checkpoint | 私有目录 + Android Keystore 加密 | 崩溃后可恢复计划与进度 |
| Agent 附件 | `files/agent-uploads/` | 为 Agent 任务复制的文件 |
| 网页截图 | `/sdcard/Download/DLA-web-shots/` | `phone_web_shot` 的产物，用户可直接打开 |

## 9. 停止与恢复

- **停止**：通知栏“停止”按钮、界面停止按钮都会走到 `AgentRuntime.cancel()`；循环在每个工具前后检查取消标志。
- **进程清理**：命令在 `setsid` 下启动并把 pid 写进 pid 文件；停止或超时用 `pkill -9 -g` / `-s`
  结束整个进程组与会话，再 `destroyForcibly`，后台脚本不会残留。
- **防打转**：连续 5 次完全相同的工具调用会被拦截并返回错误提示（`repeatCount >= 5`）；第 3 次会先提醒一次。
- **轮数**：单次运行上限 120 轮；到顶会提示「发送继续」，并带上上一轮的计划与笔记。
- **界面崩溃**：UI 进程被回收不影响 `:agent` 里的任务；重开界面先 `recoverRuns()` 认领日志，
  再用 `AgentClient.attach(id, cursor)` 补回错过的事件。
- **开机/升级恢复**：`BootReceiver` / `HealthReceiver` 以 `ACTION_RESUME` 启动服务，由 `:agent` 进程里的 `AgentRuntime.resumeIfNeeded()` 从 checkpoint 重跑。

## 10. 安全边界

- 默认逐次审批；“完全权限”需要用户显式打开，且提示会说明风险。
- 危险工具判定：名称命中 `delete/remove/uninstall/exec/shell/write/kill/freeze/force-stop`，或 MCP 工具带 `destructiveHint`。
- 工具结果注入 system 时被标注为“资料”，`PERSONA_LOCK` 明确要求不得把工具结果当身份指令（LLM 区）。
- 本应用不是沙箱：它能读屏、枚举应用、执行 shell。真正的边界是用户是否批准某次调用。

## 11. 关键文件索引

```text
android/app/src/main/java/com/example/chatdemo/
  nativeui/ChatEngine.kt                   LLM / Agent 请求组装、事件消费、会话状态、日志认领
  nativeui/NativeShellActivity.kt          原生 Compose 界面（页面栈 / 分区 / 聊天 / 设置 / 用量条 / 网页截图渲染）
  nativeui/IterationTree.kt                迭代树（轮/步/真实耗时）
  nativeui/MascotView.kt                   吉祥物动效：v1 Cue 思考头像、v2 溟灯首页光效
  nativeui/MarkdownCompose.kt              普通 Markdown 的原生 Compose 渲染
  nativeui/Markdown.kt                     隔离 WebView：仅加载 assets/renderer 里的 KaTeX / mermaid
  nativeui/SessionStore.kt                 files/native-sessions 的会话落盘（含 usageStats / runId）
  nativeui/PersonaProfile.kt               分层人设（10 字段 / 5 组）
  nativeui/TtsSettings.kt / TtsConfig.kt / QwenTts.kt   朗读：系统语音 / Qwen3-TTS
  nativeui/FileBrowser.kt                  📂 应用内文件浏览：真实路径列目录、递归搜索、多选 / 整文件夹导入
  nativeui/Shatter.kt                      点击破碎动效（观察指针，不消费）
  nativeui/SoundFeedback.kt                音效（独立 HandlerThread；完成/失败用合成 WAV + MediaPlayer）
  nativeui/WebShotReceiver.kt              把网页截图请求转交给主进程窗口
  agent/AgentClient.java                   UI 进程门面：bindService + Messenger + attach 回放
  agent/AgentIpc.java                      跨进程协议常量（MSG_* / KEY_*）
  agent/AccessibilityStatus.java           读 Settings.Secure 判断无障碍是否开启（跨进程有效）
  agent/AgentRuntime.java                  单实例状态、事件缓冲、checkpoint、结果通知
  agent/AgentService.java                  前台服务、Messenger 分发、通知渠道与通知停止
  agent/AgentRunner.java                   Agent 循环、工具注入与调度、审批、缓存、调用指南
  agent/AgentRunJournal.java               单次运行的日志账本（界面重启后认领）
  agent/ProviderCompat.java                供应商适配（预设 / 识别 / 各家 compat）
  agent/OpenAiWire.java                    Anthropic block → OpenAI Chat Completions 映射
  agent/LocalTools.java                    手机工具实现（无障碍 / Shizuku / PackageManager / 文件）
  agent/ChatDemoAccessibilityService.java  进程内读屏
  agent/ShizukuShell.java                  Shizuku 执行与授权
  agent/NativeShell.java                   内置 shell（应用身份）
  agent/PythonTool.java                    CPython（Chaquopy，应用身份）
  agent/NodeTool.java                      Node 子进程（应用身份）
  agent/ProcessRunner.java / RuntimeEnv.java   进程树 killer、PATH 与软链
  agent/McpClient.java                     MCP 客户端
  agent/WebTools.java                      联网：web_search（按供应商分流）+ web_fetch（原生抓取）
  agent/WebShot.java                       phone_web_shot 的 :agent 侧
  agent/TaskTools.java                     计划与笔记
  agent/Diagnostics.java                   环形诊断日志

android/app/src/main/assets/renderer/     KaTeX 0.16.11 + mermaid 10.9.1（离线、只读）
```
