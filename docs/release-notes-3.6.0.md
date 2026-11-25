# DLA 3.6.0

> 上一版是 **v3.5.0**。这一版只做一件事的四个面：**让长任务保持「思考 → 调用 → 思考」**，并把执行过程收进一张卡、把状态条理顺。顺手修掉了一个只要思考常在就必现的协议问题（thinking 块回传）。

DLA = **DeepSeek LLM Agent**，装在自己 Android 手机上的本地智能体；LLM 人设对话与手机 Agent 两个平级分区，同一个 APK。**发布版由本仓库源码构建，没有内部版。**

## 一、长任务：思考 → 调用 → 思考

3.5.2 的看门狗一触发就把整个任务的思考永久关掉，长任务后半程只剩工具卡。3.6.0 把“降档”收回到**单轮**：

- **只降本轮**：思考超限时只让这一轮关思考重试，**下一轮自动恢复思考**；每轮开始都会重新允许思考。
- **思考尾巴续接**：被截断的思考取尾巴（≤600 字符）接进重试提示——“接上它继续，不要整段复述”，不从头再想。
- **预算自适应**：连续想过头时本轮思考预算 **12K → 6K → 3K 字符**收紧，成功执行一轮立刻回到 12K，不再“一刀切”。
- **不消耗轮数**：同一轮内层重试，不占用 120 轮预算；状态提示带“第 N 轮”。
- **纯思考也不终止**：`max_tokens` 收尾但一个正文字符都没有时，走同样的“本轮关思考重来、下一轮恢复”，不把空 assistant 塞回历史。

## 二、修掉 thinking 块回传（DeepSeek / Anthropic 硬要求）

只要请求开着思考，历史里由“思考 + 工具调用”产生的 assistant 消息就必须把 **thinking 块（含 signature）原样回传**，否则下一轮直接 400：

```text
The content[].thinking in the thinking mode must be passed back to the API.
```

3.5.2 没撞到是因为它很早就永久关了思考；思考常在之后这个坑必现。3.6.0 的处理：

- 流式解析累积思考正文与 `signature`（`content_block_start` / `signature_delta`）；
- `assistantMessage()` 在 content 最前面回传 `{"type":"thinking","thinking":…,"signature":…}`；
- 被看门狗截断、重试时思考是关的，就把中止前的思考补回该条 assistant 消息；
- 只对 Anthropic 方言下发，OpenAI 兼容层不接受这个块。

真机实测：9 轮 / 13 轮长任务连续跑完，没有再出现这个 400。

## 三、执行过程收成一张卡

- 所有“第 N 轮”的思考与工具收进**一张**可折叠的「执行过程」大卡，放在迭代树下方。
- **运行中默认展开**，任务结束自动折叠；点标题随时展开/收起。
- 迭代树与执行过程**轮数口径统一**：有思考没工具的轮也算一轮。
- 轮内不再各套一层小卡片，改用分隔线；工具卡仍可单独展开看参数、结果与执行身份。

## 四、状态条重排

- 缓存命中率与本轮耗时单独一行（12sp），放在上下文占用上方；
- 下面一行才是上下文十段虚线 + 百分比 + token 数；
- 没有缓存/耗时时不占这一行。

## 五、3.5.0 的底座都在

双协议 + `ProviderCompat`（12 预设 / 自动识别 / 各家 compat）、拉取后挑模型、原生 `web_fetch` + 免费搜索链、`agent-runs` 记账与重启认领、参数截断自愈、长输出自动续写、`phone_web_shot` 离屏自检、`[工具 · 身份]` 标签、真页面栈、破碎动效 + 独立线程音效、任务通知 `agent-result-v2`、前台完成音——这些都没有变。

## 关于 Termux（避免误解）

**DLA 不使用 Termux，也不支持 Termux。** Termux 集成在 2.2（2026-11-23）已整体删除：没有 `termux` 工具、没有 RUN_COMMAND、没有 Termux 面板，它不是“可选后端”。APK 自带 Android toybox shell、CPython 3.12、Node 24 与 ripgrep；可选增强只有 **Shizuku**（特权命令）和**无障碍**（读屏 / 点按）。CHANGELOG 里 1.4.x / 2.x 的 Termux 条目只是历史记录，**3.5.0 / 3.6.0 的更新与 Termux 无关**。

## 升级说明

- 同包名、同签名覆盖安装即可，**不要卸载**（卸载会丢会话）。
- 覆盖安装后无障碍启用项可能被系统清掉，请在「设置 → 更多 → 手机能力 → 打开无障碍设置」重新打开。
- 老版本的接口配置会保留；如果换了供应商，记得在设置页确认**协议**与 Base URL 匹配（页面会显示“当前适配”）。
- 思考开关在设置页「深度思考（LLM / Agent 区）」；打开后长任务会按上面的策略自适应。

**完整哈希与安装说明**见 Release 资产 `SHA256SUMS`、[`docs/INSTALL.md`](https://github.com/wuy489934-svg/DLA/blob/main/docs/INSTALL.md) 与 README 的[安装与快速开始](https://github.com/wuy489934-svg/DLA#install)；版本历史见 [`CHANGELOG.md`](https://github.com/wuy489934-svg/DLA/blob/main/CHANGELOG.md)，运行时细节见 [`docs/AGENT-RUNTIME.md`](https://github.com/wuy489934-svg/DLA/blob/main/docs/AGENT-RUNTIME.md)。
