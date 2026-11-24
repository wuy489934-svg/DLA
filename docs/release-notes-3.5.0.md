# DLA 3.5.0

> 自上一版 **v3.1.2** 以来的全部更新。这一版没有换界面骨架，做的是四件事：**把模型接口打通、把联网工具修好、让长任务不白跑、让每个结论都能对回身份和证据**——顺手把点击手感和任务提醒补齐。

## 一、换模型不再被协议卡住

- **两种协议**：`Anthropic Messages`（默认，DeepSeek `/anthropic/v1`、Claude）与 `OpenAI Chat Completions`（`/v1/chat/completions`：GPT、千问、GLM、Kimi、OpenRouter、vLLM、Ollama…）。内部消息结构不变，发请求前由 `OpenAiWire` 做映射：`tool_use → tool_calls`、`tool_result → role:tool`、图片 `→ image_url`、工具 schema 转 function 形式。
- **`ProviderCompat` 适配器**：12 个供应商预设一键填好协议 + Base URL + 默认模型；按 Base URL 与模型名自动识别供应商，并按家开关 `max_tokens` / `max_completion_tokens`、温度、`stream_options`、思考开关、服务端搜索工具与身份文案；切协议时自动把 `/anthropic/v1` ↔ `/v1` 互换。
- **拉取模型后直接挑**：`拉取并测试连接` 成功 → 「从 N 个模型里挑一个」（带搜索框）；模型输入框旁也有「选择」，不用每次重拉。

## 二、联网工具真的能用

- `web_fetch` 改成**原生抓取**：桌面 UA、跟随重定向、按 Content-Type / meta charset 解码、去脚本样式、压空白，拒绝内网地址，正文上限 24K 字。任何供应商都能用。
- `web_search` 按供应商分流：DeepSeek 端点走官方搜索（失败自动降级），其它供应商走免费 HTML 后端——中文 百度 → Bing → Bing RSS → DuckDuckGo，英文 Bing → 百度 → Bing RSS → DuckDuckGo；Bing 新老两套版式都认，另加 Bing RSS 与百度解析器。
- 失败信息带诊断（拿到多大页面、什么 Content-Type、有没有关键标记），调用指南明确要求**不许因为一次搜索失败就说“联网坏了”**。

## 三、长任务不白跑

- **参数截断自愈**：工具参数被输出上限截断时，不再整轮抛异常，而是返回可恢复错误并教模型分段写（`phone_write` + `phone_append`，每段 ≤3000 字符）。
- **长输出自动续写**：撞到输出上限且没有工具调用时，自动接上已生成部分继续写，最多 3 次。
- **退出重进回到上次会话**：杀掉应用再打开，直接回到上次那个会话。
- **界面进程被杀，回答不丢**：`:agent` 把每轮事件记账到 `files/agent-runs`，界面重启后认领；跑完的补成消息，还在跑的重新挂成流式草稿，`runId` 去重。
- **真页面栈**：会话 → 设置 → 返回回到会话；进设置不再把会话关掉。

## 四、每个结论都能对回「身份 + 证据」

- 每条工具结果带 `[工具 · 身份]` 标签（`[phone_sh · 应用身份]`、`[phone_shell · Shizuku uid 2000]`…），模型必须按标签归因。
- `phone_shell` 在 Shizuku 就绪时直接进核心工具，不用先 `tool_search`。
- 调用指南纠偏：应用身份下 `pm` / `am` / `settings` / `dumpsys` 会被拒、`ps` 只看得到自己；没有 `curl` / `wget`，联网用 `node -e fetch` 或 Python `urllib`；`python3` 不是 shell 命令。
- 思考卡流式不再自动合拢。

## 五、能看见自己的产物

- 新增 `phone_web_shot`：在 DLA 自己的窗口里**离屏渲染**本地 HTML 并截图（不打开浏览器、不抢前台），模型可以看着图改布局。要求 DLA 在前台，文件存 `/sdcard/Download/DLA-web-shots/`。
- README 里的[牛顿与苹果动画](https://github.com/wuy489934-svg/DLA/blob/main/docs/assets/demo-newton.gif)与[山间日落 SVG](https://github.com/wuy489934-svg/DLA/blob/main/docs/assets/demo-mountain-sunset.png)就是它在真机上做出来的东西，源文件在 `docs/samples/`。

## 六、手感与提醒

- **点击破碎方块**：7 块、2.5–7dp、最远 20dp 光环、430ms；只旁听指针事件，不抢点击。
- **音效搬出主线程**：点击 / 发送 / 完成 / 失败四声，独立线程 + 启动预热，点击不再卡一下。
- **任务通知**：完成 / 失败 / 停止都发通知，含结论预览与轮 / 步 / 耗时 / 最后工具，点一下回到会话；渠道 `agent-result-v2`（高优先级 + 提示音 + 振动）；任务结束撤掉常驻通知。
- **前台完成音**：0.35 秒上行双音（880 → 1318Hz，媒体音量），前台也听得见。

## 升级说明

- 同包名、同签名覆盖安装即可，**不要卸载**（卸载会丢会话）。
- 覆盖安装后无障碍启用项可能被系统清掉，请在「设置 → 更多 → 手机能力 → 打开无障碍设置」重新打开。
- 老版本的接口配置会保留；如果换了供应商，记得在设置页确认**协议**与 Base URL 匹配（页面会显示“当前适配”）。

**完整哈希与安装说明**见 Release 资产 `SHA256SUMS` 与 [`docs/INSTALL.md`](https://github.com/wuy489934-svg/DLA/blob/main/docs/INSTALL.md)；版本历史见 [`CHANGELOG.md`](https://github.com/wuy489934-svg/DLA/blob/main/CHANGELOG.md)。
