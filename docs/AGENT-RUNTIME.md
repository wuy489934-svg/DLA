# Agent 运行时路径

本文只描述**实际上会跑的代码路径**，方便审阅、复现和二次开发。所有路径都相对于仓库根目录。

## 1. 一次任务的生命周期

| 步骤 | 发生什么 | 代码 |
|---|---|---|
| 1 | 用户输入任务，界面组装 payload（model、apiKey、apiOrigin、system、messages、mcpServerIds、autoApprove、maxRounds=120、可选的计划/笔记） | `android/.../nativeui/ChatEngine.kt` |
| 2 | 原生界面 `AgentClient.start()` | `nativeui/ChatEngine.kt` |
| 3 | 跨进程：`AgentClient` 通过 `bindService` + `Messenger` 把指令发给 `:agent` 进程的 `AgentService` | `agent/AgentClient.java`、`agent/AgentIpc.java` |
| 4 | 写加密 checkpoint，启动 `AgentService`（specialUse FGS + 通知停止按钮）。**没有准入拦截**：内置 `phone_sh` 永远可用 | `agent/AgentRuntime.java` `start()`、`AgentService.java`、`AgentCheckpoint.java` |
| 5 | 建立工具集：13 个核心工具 + `tool_search` + MCP + 任务记忆；按后端可用性裁剪 | `AgentRunner.connectTools()` |
| 6 | 进入循环：`streamRound()` 发请求 → 解析文本/思考/`tool_use`/usage → 执行工具 → 结果作为 `tool_result` 追加 → 下一轮（上限 120 轮） | `AgentRunner.run()` |
| 7 | 每轮事件经 `AgentService` → `AgentClient` 回调给界面，按事件渲染 | `AgentService`、`AgentClient`、`nativeui/ChatEngine.kt` |
| 8 | 结束：`done` / `stopped` / `error`；清 checkpoint，停服务 | `AgentRuntime.finish()` |

## 2. 事件协议

事件都是 JSON，`type` 区分类型。跨进程时由 `AgentService` 打成 `MSG_EVENT`（`requestId` + `json`）。

| type | 关键字段 | 说明 |
|---|---|---|
| `status` | `text` | 人类可读状态（工具注入结果、MCP 连接数等） |
| `text` | `delta` | 增量正文 |
| `thinking` | `delta` | 增量思考内容 |
| `tool_start` | `id`、`name`、`input`、`approval`、`round`、`parallelGroup` | 工具即将执行；`approval=true` 表示要等审批 |
| `tool_result` | `id`、`content`、`isError`、`elapsedMs`、`round`、`cached` | 工具结果；带图时另有 `image=true` 与 image block |
| `task_state` | `plan`、`current`、`notes` | 计划与笔记，随 checkpoint 持久化 |
| `usage` | `inputTokens`、`outputTokens`、`cacheCreationInputTokens`、`cacheReadInputTokens`、`elapsedMs`、`ttftMs` | 每轮请求结束时的用量；上下文用量条只认最近这一轮 |
| `done` / `stopped` / `error` | `stopReason` / `message` | 终止事件 |

审批方向相反：界面调用 `AgentClient.approve(id, toolId, allowed)`，`:agent` 进程里的
`AgentRunner.approve()` 唤醒等待队列。等待上限 120 秒（`awaitApproval()`），超时按“未执行”处理。

> 3.1.2 修复：界面曾读 `text` / `is_error` 这两个旧字段，而 runner 发的是 `content` /
> `isError`，所以工具详情一直是空的。现在两种字段都兼容。

## 3. 工具注入与按需发现

初始工具集不是“越多越好”，只有三类会直接进上下文：

1. **核心工具（13 个）**：`phone_screen`、`phone_tap_text`、`phone_wait`、`phone_tap`、
   `phone_swipe`、`phone_input`、`phone_key`、`phone_screenshot`、`phone_view_image`、
   `phone_open`、`phone_apps`、`phone_device`、`phone_sh`（见 `AgentRunner.CORE_LOCAL`）。
2. **`tool_search`**：搜索扩展目录，返回说明与参数；命中后下一轮才把该工具加入 `tools`。
3. **MCP 工具**：按已连接 Server 注入，名称做 `server_tool` 前缀去重。

`phone_view_image` 是读图工具：给一个图片路径，按长边 1280、JPEG 82 压缩后以 image block
回传，模型下一轮就能“看到”这张图；超过 32MB 或不可解码直接报错，不会静默失败。

按需发现的工具包括：文件读写/替换、JSON、zip、docx、设置、剪贴板、通知、`phone_py`、`phone_js`。

**按可用性裁剪**（`connectTools()`）：

| 条件 | 结果 |
|---|---|
| Shizuku 未就绪 | `phone_shell`、`phone_stop`、`phone_setting_put`、`phone_notifications` 不注入 |
| 无障碍未开启 | `phone_screen`、`phone_wait`、`phone_screenshot`、`phone_tap_text` 不注入 |
| 始终可用 | 内置运行时（`phone_sh` / `phone_py` / `phone_js`）、文件类、PackageManager 类、`tool_search`、联网、任务记忆 |

## 4. 三种执行身份（3.1.2 起写进调用指南）

| 身份 | 工具 | 能做 | 不能做 |
|---|---|---|---|
| 应用身份（untrusted_app） | `phone_sh` / `phone_py` / `phone_js` | 读自身目录与 `/sdcard`、grep/find/awk/sed、getprop、curl、node/python | `pm` / `am` / `settings` / `dumpsys`；`/proc` 全局、`/data/app`、别家应用私有目录 |
| Shizuku（uid 2000） | `phone_shell` | `pm` / `am` / `settings` / `dumpsys` / 全系统 `ps` | 需要 root 的操作 |
| 无障碍 | `phone_screen` / `phone_screenshot` / `phone_tap*` / `phone_swipe` / `phone_input` / `phone_key` | 读屏、截图、点按、输入 | 不提供系统信息 |

配套的三条规则：

1. **身份不能混**：`phone_shell` 跑通不等于应用身份可用；`phone_sh` 失败也不等于本机做不到。
2. **负证据无效**：应用身份下 `ps -A` 只有自己那几个进程，`/proc/net/tcp`、`ss`、`/data/app`
   都读不到，所以“没看到”不等于“没在跑”。
3. **先测再断言**：说“不可用”之前先实测；没测的标“未验证”。

每次运行开始时，`AgentRunner.runtimeBlock()` 会实测一次无障碍 / Shizuku / 内置运行时状态，
拼进 system 的 `【本机运行时】`；运行中的轮数上限是 120，连续 6 轮单工具会触发一次批量提示。

## 5. 数据落在哪

| 数据 | 位置 | 说明 |
|---|---|---|
| 会话与消息 | `files/native-sessions/<id>.json`（`SessionStore.kt`） | 权威存储；同一文件还存 `draft`（草稿）、`taskState`（计划/笔记）、`usageStats`（轮/步/上下文/单轮耗时） |
| 诊断 | `files/diagnostics/events.log` | 环形日志（256KB），只记事件名与异常栈，不记消息正文 |
| 设置 | `SharedPreferences("dla-native")` | API Key、Base URL、模型、人设、朗读、主题、语言、上下文窗口；只在本机 |
| 运行 checkpoint | 私有目录 + Android Keystore 加密 | 崩溃后可恢复计划与进度 |
| Agent 附件 | `files/agent-uploads/` | 为 Agent 任务复制的文件 |

## 6. 停止与恢复

- **停止**：通知栏“停止”按钮、界面停止按钮都会走到 `AgentRuntime.cancel()`；循环在每个工具前后检查取消标志。
- **进程清理**：命令在 `setsid` 下启动并把 pid 写进 pid 文件；停止或超时用 `pkill -9 -g` / `-s`
  结束整个进程组与会话，再 `destroyForcibly`，后台脚本不会残留。
- **防打转**：连续 5 次完全相同的工具调用会被拦截并返回错误提示（`repeatCount >= 5`）。
- **轮数**：单次运行上限 120 轮；到顶会提示「发送继续」，并带上上一轮的计划与笔记。
- **界面崩溃**：UI 进程被回收不影响 `:agent` 里的任务，重新打开界面 `AgentClient.attach(id, cursor)` 会把错过的事件补回来。
- **开机/升级恢复**：`BootReceiver` / `HealthReceiver` 以 `ACTION_RESUME` 启动服务，由 `:agent` 进程里的 `AgentRuntime.resumeIfNeeded()` 从 checkpoint 重跑。

## 7. 安全边界

- 默认逐次审批；“完全权限”需要用户显式打开，且提示会说明风险。
- 危险工具判定：名称命中 `delete/remove/uninstall/exec/shell/write/kill/freeze/force-stop`，或 MCP 工具带 `destructiveHint`。
- 工具结果注入 system 时被标注为“资料”，`PERSONA_LOCK` 明确要求不得把工具结果当身份指令（LLM 区）。
- 本应用不是沙箱：它能读屏、枚举应用、执行 shell。真正的边界是用户是否批准某次调用。

## 8. 关键文件索引

```text
android/app/src/main/java/com/example/chatdemo/
  nativeui/ChatEngine.kt                   LLM / Agent 请求组装、事件消费与会话状态
  nativeui/NativeShellActivity.kt          原生 Compose 界面（首页分区 / 聊天 / 设置 / 用量条）
  nativeui/IterationTree.kt                迭代树（轮/步/真实耗时）
  nativeui/MascotView.kt                   吉祥物动效：v1 Cue 思考头像、v2 溟灯首页光效
  nativeui/MarkdownCompose.kt              普通 Markdown 的原生 Compose 渲染
  nativeui/Markdown.kt                     隔离 WebView：仅加载 assets/renderer 里的 KaTeX / mermaid
  nativeui/SessionStore.kt                 files/native-sessions 的会话落盘（含 usageStats）
  nativeui/PersonaProfile.kt               分层人设（10 字段 / 5 组）
  nativeui/TtsSettings.kt / QwenTts.kt     朗读：系统语音 / Qwen3-TTS
  nativeui/FileBrowser.kt                  📂 应用内文件浏览：真实路径列目录、递归搜索、多选 / 整文件夹导入
  agent/AgentClient.java                   UI 进程门面：bindService + Messenger + attach 回放
  agent/AgentIpc.java                      跨进程协议常量（MSG_* / KEY_*）
  agent/AccessibilityStatus.java           读 Settings.Secure 判断无障碍是否开启（跨进程有效）
  agent/AgentRuntime.java                  单实例状态、事件缓冲、checkpoint
  agent/AgentService.java                  前台服务、Messenger 分发与通知停止
  agent/AgentRunner.java                   Agent 循环、工具注入与调度、审批、缓存、调用指南
  agent/LocalTools.java                    手机工具实现（无障碍 / Shizuku / PackageManager）
  agent/ChatDemoAccessibilityService.java  进程内读屏
  agent/ShizukuShell.java                  Shizuku 执行与授权
  agent/NativeShell.java                   内置 shell（应用身份）
  agent/PythonTool.java                    CPython（Chaquopy，应用身份）
  agent/NodeTool.java                      Node 子进程（应用身份）
  agent/ProcessRunner.java / RuntimeEnv.java   进程树 killer、PATH 与软链
  agent/McpClient.java                     MCP 客户端
  agent/WebTools.java                      官方联网（search / fetch）
  agent/TaskTools.java                     计划与笔记
  agent/Diagnostics.java                   环形诊断日志

android/app/src/main/assets/renderer/     KaTeX 0.16.11 + mermaid 10.9.1（离线、只读）
```
