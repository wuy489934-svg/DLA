# Agent 运行时路径

本文只描述**实际上会跑的代码路径**，方便审阅、复现和二次开发。所有路径都相对于仓库根目录。

## 1. 一次任务的生命周期

| 步骤 | 发生什么 | 代码 |
|---|---|---|
| 1 | 用户在 Agent 区输入任务，网页组装 payload（model、apiKey、apiOrigin、system、messages、mcpServerIds、autoApprove、maxRounds） | `web/src/services/chat/store.ts` → `startAgent()` |
| 2 | 通过 `AndroidHost.agentStart(requestId, payloadJson)` 进入原生层 | `web/src/services/agent/bridge.ts` |
| 3 | 准入判定：Shizuku 就绪 **或** Termux 可用才放行；两者都缺直接回 `error` 事件并给出两条开启路径 | `android/app/src/main/java/com/example/chatdemo/agent/AgentRuntime.java` `start()` |
| 4 | 写加密 checkpoint，启动 `AgentService`（specialUse FGS + 通知停止按钮） | `AgentRuntime`、`AgentService.java`、`AgentCheckpoint.java` |
| 5 | 建立工具集：探测可用性 → 注入核心工具 → 注册可按需发现的工具 → 连接 MCP | `AgentRunner.connectTools()` |
| 6 | 进入循环：`streamRound()` 发请求 → 解析文本/思考/`tool_use` → 执行工具 → 把结果作为 `tool_result` 追加 → 下一轮 | `AgentRunner.run()` |
| 7 | 每轮的事件通过 `emit()` 推给 WebView（`window.__agentEvent`），网页按事件渲染 | `MainActivity.HostBridge` |
| 8 | 结束：`done` / `stopped` / `error`；清 checkpoint，停服务 | `AgentRuntime.finish()` |

## 2. 事件协议

原生 → 网页的事件都是 JSON，第二个字段是类型。网页侧消费见 `web/src/services/agent/bridge.ts`。

| type | 关键字段 | 说明 |
|---|---|---|
| `status` | `text` | 人类可读状态（工具注入结果、MCP 连接数等） |
| `text` | `text` | 增量正文 |
| `thinking` | `text` | 增量思考内容 |
| `tool_start` | `id`、`name`、`input`、`approval`、`round`、`parallelGroup` | 工具即将执行；`approval=true` 表示要等审批 |
| `tool_result` | `id`、`text`、`is_error`、`image` | 工具结果（图片以 base64 单独放） |
| `task_state` | `plan`、`current`、`notes` | 计划与笔记，持久化进 checkpoint |
| `usage` | token 统计、TTFT | 用量与首字延迟 |
| `done` / `stopped` / `error` | `stop_reason` / `message` | 终止事件 |

审批方向相反：网页调用 `AndroidHost.agentApprove(toolId, allowed)`，原生 `AgentRunner.approve()` 唤醒等待队列。
等待上限 120 秒（`awaitApproval()`），超时按“未执行”处理。

## 3. 工具注入与按需发现

初始工具集不是“越多越好”，只有三类会直接进上下文：

1. **核心手机操作（11 个）**：`phone_screen`、`phone_tap_text`、`phone_wait`、`phone_tap`、`phone_swipe`、
   `phone_input`、`phone_key`、`phone_screenshot`、`phone_open`、`phone_apps`、`phone_device`
   （见 `AgentRunner.CORE_LOCAL`）。
2. **`tool_search`**：搜索扩展目录，返回说明与参数；命中后下一轮才把该工具加入 `tools`。
3. **MCP 工具**：按已连接 Server 注入，名称做 `server_tool` 前缀去重。

按需发现的工具包括：`phone_shell`、文件读写/替换、JSON、zip、docx、设置、剪贴板、通知、
`termux`（单一终端工具，action=status/capabilities/run）。

**按可用性裁剪**（`connectTools()`）：

| 条件 | 结果 |
|---|---|
| Shizuku 未就绪 | 不注入任何 `phone_*`，也不把它们放进可搜索目录（搜不到就不会误调用） |
| Termux 未安装或无 `RUN_COMMAND` 权限 | 不注册 `termux` |
| 两者都不可用 | 开局 `status` 说明，且 `AgentRuntime.start()` 已经拒绝本次运行 |

## 4. 三种执行后端

| 后端 | 入口 | 说明 |
|---|---|---|
| 无障碍 | `ChatDemoAccessibilityService.dumpScreen()` | 进程内读屏（300 节点上限、300ms TTL 缓存）；不可用时回退 `uiautomator dump` 并标注 `source: fallback` |
| Shizuku | `ShizukuShell.exec()` | 以 uid 2000（shell）执行命令；`phone_shell` 等工具经此执行 |
| Termux | `TermuxBridge.run()` → `com.termux.RUN_COMMAND` → `TermuxResultService` | 需要 `allow-external-apps=true` 与 `com.termux.permission.RUN_COMMAND`；结果经 PendingIntent 回传 stdout/stderr/exit |

Termux 的边界：执行身份是 DLA 自己的 uid，只能访问 Termux 可访问的目录；不是 root，也不是 shell uid。

## 5. 数据落在哪

| 数据 | 位置 | 说明 |
|---|---|---|
| 会话与消息 | WebView IndexedDB | 权威存储 |
| 会话镜像 | `files/host-sessions/*.json` | IndexedDB 为空时用于恢复；只含文本与元数据 |
| 诊断 | `files/diagnostics/events.log` | 环形日志（256KB），只记事件名与异常栈，不记消息正文 |
| 设置 | SharedPreferences / localStorage | API Key 只在本机；不上报 |
| 运行 checkpoint | 私有目录 + Android Keystore 加密 | 崩溃后可恢复计划与进度 |

## 6. 停止与恢复

- **停止**：通知栏“停止”按钮、Agent UI 的停止按钮都会调用 `AgentRuntime.cancel()`；循环在每个工具前后检查取消标志。
- **防打转**：连续 5 次完全相同的工具调用会被拦截并返回错误提示（`repeatCount >= 5`）。
- **崩溃恢复**：`MainActivity` 处理 `onRenderProcessGone`，重建 WebView；会话从 IndexedDB 或 `host-sessions` 镜像恢复。

## 7. 安全边界

- 默认逐次审批；“完全权限”需要用户显式打开，且提示会说明风险。
- 危险工具判定：名称命中 `delete/remove/uninstall/exec/shell/write/kill/freeze/force-stop`，或 MCP 工具带 `destructiveHint`。
- 工具结果注入 system 时被标注为“资料”，`PERSONA_LOCK` 明确要求不得把工具结果当身份指令（LLM 区）。
- 本应用不是沙箱：它能读屏、枚举应用、执行 shell。真正的边界是用户是否批准某次调用。

## 8. 关键文件索引

```text
web/src/services/chat/store.ts            Agent / LLM 两种模式的请求组装与事件消费
web/src/services/agent/bridge.ts          AndroidHost 桥接与事件队列
web/src/components/chatInput/ChatInput.tsx 准入判定（Shizuku 或 Termux）与发送拦截

android/app/src/main/java/com/example/chatdemo/
  MainActivity.java                       WebView 宿主与 HostBridge（@JavascriptInterface）
  agent/AgentRuntime.java                 单实例状态、准入、事件缓冲、checkpoint
  agent/AgentService.java                 前台服务与通知停止
  agent/AgentRunner.java                  Agent 循环、工具注入与调度、审批、缓存
  agent/LocalTools.java                   手机工具实现（无障碍 / Shizuku）
  agent/ChatDemoAccessibilityService.java 进程内读屏
  agent/ShizukuShell.java                 Shizuku 执行与授权
  agent/TermuxBridge.java                 Termux RUN_COMMAND 桥
  agent/TermuxResultService.java          RUN_COMMAND 结果回传
  agent/TermuxTools.java                  termux 单工具（status / capabilities / run）
  agent/McpClient.java                    MCP 客户端
  agent/WebTools.java                     官方联网（search / fetch）
  agent/TaskTools.java                    计划与笔记
  agent/Diagnostics.java                  环形诊断日志
```
