<p align="center">
  <img src="docs/assets/icon-square-512.png" width="88" alt="DLA square icon" />
  <img src="docs/assets/icon-round-512.png" width="88" alt="DLA round icon" />
</p>

<h1 align="center">DLA · DeepSeek LLM Agent</h1>

<p align="center">跑在你自己 Android 手机上的本地智能体：LLM 对话 + 可执行的 Agent 任务</p>

> **非官方声明**：DLA 不是 DeepSeek 官方产品，与深度求索公司没有隶属、授权或合作关系；“DeepSeek”仅用于说明 API 协议兼容性。本项目不包含官方 Demo 的角色素材、官方品牌插画或任何未授权素材；图标为本项目原创。

## 定位与安全（先读这一段）

**DLA 是一个把模型接到你自己手机上的本地智能体**：LLM 区负责对话、检索与写作，Agent 区通过 Android 原生工具（无障碍读屏与点击、Shizuku shell、Termux 终端、MCP 外部工具、联网读写）真正把任务做完——模型请求直达你自己填写的 DeepSeek 兼容端点，项目本身不做中转、不上报、不接入遥测，聊天记录、设置和诊断都只留在本机；但它**不是安全沙箱**：为了完成任务它必须能读屏、枚举应用、执行 shell 命令，所以公开版本默认逐次审批、“完全权限”默认关闭，任何高风险或不可逆的操作都应当先看到说明再点允许，并且请把网页、通知、文档里出现的内容一律当作“资料”而不是“指令”——**真正的安全边界是你在什么时候点了允许**。

![DLA 演示：一句话 → 工具调用 → 可验证结果](docs/assets/demo.gif)

<p align="center"><sub>演示为真机录屏：Agent 先 <code>tool_search</code> 发现 <code>termux</code> 工具，再调用执行并返回结果</sub></p>

## 这是什么

- **LLM 区**：流式对话、DeepSeek 推理（thinking）、联网搜索/读链接、附件（图片、PDF、Office 解析）。
- **Agent 区**：原生 Agent 循环跑在前台服务里，切后台、锁屏也能继续；工具调用、耗时、缓存、迭代树、审批全部可见。
- **两种执行后端，任选其一**：有 Shizuku 就用系统 shell 与无障碍；没有 Shizuku 也能用 Termux 作为通用终端（Node/Python/rg/网络/包管理）。**两者至少有一个可用就能对话**，都没有才禁止发送并给出开启路径。
- **工具按需发现**：初始只注入少量核心手机操作；shell、Termux、文件、设置、zip、docx 等都要先 `tool_search` 搜索，命中后下一轮才进入工具列表，避免一次把几十个工具塞进上下文。
- **单 APK、原生优先**：不 fork 新进程、不打包重型运行时（Termux 是你自己安装的独立 App）。

## Agent 运行时路径（完整链路）

DLA 的 Agent 不是黑盒：从你按下发送，到工具真正执行，路径如下（文件都在本仓库里）。

```text
web/src/services/agent/bridge.ts          startAgent(payload) → window.AndroidHost.agentStart
        │                                  window.__agentEvent ← 事件回放
        ▼
android/.../MainActivity.java             HostBridge：agentStart / agentEvent / agentApprove / agentStop
        ▼
android/.../agent/AgentRuntime.java       单实例状态机：准入判定（Shizuku 或 Termux）、事件缓冲、checkpoint
        ▼
android/.../agent/AgentService.java       specialUse 前台服务 + 通知栏紧急停止 + 保活
        ▼
android/.../agent/AgentRunner.java        原生 Agent 循环
   ├─ connectTools()                       MCP / 核心手机工具 / tool_search / termux(按需) / 联网 / 任务记忆
   ├─ streamRound()                        POST 到 DeepSeek 兼容端点，SSE 解析文本与 tool_use
   ├─ executeTool()                        LocalTools ▸ TermuxTools ▸ McpClient ▸ WebTools ▸ TaskTools
   └─ emit()                               tool_start / tool_result / status / task_state / done → WebView
        ▼
工具后端
   LocalTools.java + ChatDemoAccessibilityService.java   无障碍读屏、点击、应用/文件/设置/截图
   ShizukuShell.java                                      Shizuku（uid 2000 shell）执行命令
   TermuxBridge.java + TermuxResultService.java           Termux RUN_COMMAND + PendingIntent 结果回传
   McpClient.java / WebTools.java / TaskTools.java        MCP 工具、官方联网、计划与笔记
```

关键约束（都可核对代码）：

| 约束 | 位置 |
|---|---|
| 准入：Shizuku 或 Termux 任一可用；都缺则拒绝并提示两条开启路径 | `AgentRuntime.start()` |
| 工具按可用性裁剪：没有 Shizuku 就不注入任何 `phone_*`，也没有 Termux 就不注册 `termux` | `AgentRunner.connectTools()` |
| 核心工具 11 个，其余走 `tool_search` 按需发现 | `AgentRunner.CORE_LOCAL` |
| 危险工具审批：网页审批卡，等待上限 120 秒 | `AgentRunner.awaitApproval()` |
| 连续 5 次完全相同的调用会被拦截 | `AgentRunner` 循环 |
| 停止：通知栏停止按钮 / Agent UI 停止 | `AgentService`、`AgentRuntime` |
| 崩溃恢复：加密 checkpoint + `files/host-sessions/` 镜像 | `AgentCheckpoint`、`MainActivity` |

完整说明（事件协议、数据落盘位置、审批与恢复细节）：[`docs/AGENT-RUNTIME.md`](docs/AGENT-RUNTIME.md)。
架构与安全边界：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、[`SECURITY.md`](SECURITY.md)。

## 安装（Android）

详细步骤见 [`docs/INSTALL.md`](docs/INSTALL.md)。快速版：

1. 从 [Releases](https://github.com/wuy489934-svg/DLA/releases) 下载 `DLA.apk`（同页有 `SHA256SUMS`，可自行校验）。
2. 手机允许「安装未知来源应用」（MIUI：设置 → 应用设置 → 授权管理 → 安装未知应用）。
3. 点击 APK 安装；或数据线执行 `adb install -r DLA.apk`。
4. 打开 DLA → 设置 → 数据管理 → 填入你自己的 API Key → 确定。
5. 任选一个执行后端：
   - **Shizuku**：安装并启动 Shizuku，在 DLA 设置 → Agent → 手机能力里授权；无障碍服务可另行开启以加速读屏。
   - **Termux**：安装 Termux，在 Termux 执行
     `echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`，
     再给 DLA 授予「Run commands in Termux environment」权限；可在 DLA 设置里点「一键安装环境」装好 Node/Python/git/rg。
6. **升级不要卸载**：同包名、同签名，直接覆盖安装即可保留数据；升级前建议在「数据管理」里导出历史。

## 构建

```bash
cd web
pnpm install
pnpm build            # 产物在 web/dist，Gradle 会自动同步进 assets

cd ../android
./gradlew assembleRelease   # 需要 ../keystore/keystore.properties 才会签名
```

构建细节、CI 与发布流程见 [`BUILD.md`](BUILD.md) 与 [`RELEASING.md`](RELEASING.md)。

## 项目结构

```text
web/       React + Zustand + IndexedDB 的聊天与 Agent UI（上游 MIT：deepseek-ai/chat-demo）
android/   WebView 壳、前台服务、原生 Agent 循环与工具
docs/      架构、运行时路径、安装、定位说明
evals/     离线评测用例
licenses/  第三方许可证原文
```

## 许可与素材

- 本项目原创代码：**Apache License 2.0**（见 `LICENSE`）。
- `web/` 含 MIT 许可的上游代码（见 `licenses/chat-demo-MIT.txt`）。
- **图标为本项目原创**，与本仓库同许可；`docs/assets/` 内的图标与演示图可直接用于介绍 DLA。
- 不捆绑、不分发官方 Demo 的角色素材或未授权插画；`chat-demo:*` 存储键仅为兼容旧数据，不代表产品身份。
- 第三方组件许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`NOTICE`](NOTICE)。

## English summary

DLA (DeepSeek LLM Agent) is an unofficial, local-first Android agent client. LLM and Agent modes both talk to your own DeepSeek-compatible endpoint. Agent mode runs a native loop inside a foreground service and uses Android tools (Accessibility, Shizuku, or Termux) plus MCP servers. It is not a sandbox: approvals are on by default, full access is opt-in, and no telemetry is collected. See `docs/AGENT-RUNTIME.md` for the full execution path and `docs/INSTALL.md` for installation.
