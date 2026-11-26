# DLA 26.11.4

> 上一版是 **v3.6.0**（2026-11-25）。这一版跨过 3.6.0fix–26.11.3 十个开发版：模型与思考收成一个入口、联网接上 DeepSeek 官方搜索/抓取、Agent 的过程叙述归入执行过程卡、手机工具补到「一类任务一个工具」。版本体系也从 3.6.x 换成 **26.11.x**（26 = 2026 年，11 = 11 月，末位是当月第几个迭代）。

DLA = **DeepSeek LLM Agent**，装在自己 Android 手机上的本地智能体；LLM 人设对话与手机 Agent 两个平级分区，同一个 APK。**发布版由本仓库源码构建，没有内部版。**

## 一、模型 · 思考强度：一个入口管两个区

- 聊天页标题下方新增「模型 · 思考强度」胶囊（自绘表盘图标，指针随档位偏移），LLM / Agent 两区都有，颜色随分区：LLM 琥珀 / Agent 薄荷。
- 点开是同一个对话框：模型名输入 + 已缓存模型（单列可滚动，全部可选）+ 思考强度三档（低 ≈2048 / 中 ≈4096 / 高 ≈8192 tokens）。
- 应用后写回同一份偏好，两区共用；DeepSeek 端点的思考档位仍由官方控制，不强行塞 `budget_tokens`。

## 二、联网：DeepSeek 官方搜索 / 抓取

- 设置 → 对话新增「DeepSeek 官方搜索 Key（可选）」：只填一个官方 Key，搜索/抓取就走 DeepSeek 官方接口，**与聊天用哪家模型无关**。
- `web_search` 优先走 `POST /v1/tools/search`；`web_fetch` 优先走 `POST /v1/tools/fetch`（直接返回 Markdown 正文），失败回退免费后端与原生抓取，并在结果里注明来源。
- 新增「测试官方 Key」自检：只回报命中条数与首条标题，不回显 Key；Key 输入改为密码框，可显示 / 隐藏。
- 免费搜索后端重做：多后端合并去重 + 相关性排序 + 垃圾结果过滤；`web_research` 一次返回搜索结果与前几页正文。

## 三、执行过程卡：轮旁白也收进去

- Agent 区带工具调用的轮次里，模型的过程叙述不再混在正文气泡，而是显示在该轮思考卡与工具卡上方：淡色小字「旁白 · …」（长按可复制）。
- 正文气泡只剩最终总结。消息模型新增 `round_note` 块（`{"type":"round_note","text":…,"round":N}`），排在该轮思考与工具之前落盘。
- 日志先认领、消息已落盘时，收尾会重写那条消息把旁白归位——实时收尾与进程认领两条路径口径一致。
- 边界：只处理有工具调用的轮次；没有工具的轮次正文仍是正常正文。旁白只对 Agent 区生效，LLM 区继续保持过程叙述不进气泡。

## 四、工具广度：一类任务一个工具

起因是一轮「数学动画 → 微信能播的视频」任务：没有 ffmpeg，模型绕了 40 轮、43 步。这一版按「一步能做完的事不拆成多条 shell」补齐：

| 新工具 | 一次调用能做完的事 |
|---|---|
| `phone_media` | 读时长/分辨率/码率/音轨；抽任意时刻一帧（图回传模型看）；按毫秒**无损剪**、去音轨（MediaExtractor+MediaMuxer，不重编码） |
| `phone_record` | 走 Shizuku 的 `screenrecord` 后台录屏，SIGINT 落盘 MP4 |
| `phone_image` | 按宽/高/百分比缩放、转 jpeg/png/webp、可回传图片自检 |
| `phone_share` | 复制到 cache + FileProvider 授权 → 系统分享面板 |
| `phone_http` | GET/POST/PUT/PATCH/DELETE，自定义头与 body，响应可存文件 |
| `phone_zip_create` | 文件/目录递归打包 zip，条目保留相对路径 |
| `web_research` | 搜索 + 读前几页正文一次完成 |

工具提示也写清楚了路径：动画/视频类任务用 HTML/Canvas 播放 → `phone_record` 录屏 → `phone_media` 剪/去音轨 → `phone_share`，不要再去找 ffmpeg。

## 五、两个区的对话体验

- **LLM 区能自己联网**：非 DeepSeek 模型也走 function calling 调 `web_search` / `web_fetch`（最多 4 轮），工具轮文本只进上下文，不再把过程叙述写进气泡；工具轮用尽时给出可继续的兜底句，不出现空气泡。
- **Qwen 关了思考不再“偷偷想”**：混合思考模型在不传 `enable_thinking` 时默认开思考，现在无条件下发布尔值（false 也发）；Qwen 端点不回缓存字段时显示「缓存 —」，不再显示假的 0%。
- **身份纠偏**：Qwen / GPT / GLM 等非 DeepSeek 端点拿到中性身份提示，不再自称 DeepSeek。
- 其他：Agent 注入防护、分区导出 zip、列表缓存 + 后台刷新（点击不卡）、多图空转修复、文件绝对路径搜索、输入框聚焦修复、导出四个按钮两行两列不再折行、执行过程换成自绘放大镜图标、迭代树流式时不再自己收起。

## 六、3.6.0 的底座都在

长任务「思考 → 调用 → 思考」（只降本轮 / 思考尾巴续接 / 预算 12K→6K→3K 自适应）、thinking 块（含 signature）随 assistant 消息回传、执行过程大卡与状态条重排、双协议 + `ProviderCompat`（12 预设）、`agent-runs` 记账与重启认领、参数截断自愈、长输出自动续写、`phone_web_shot` 离屏自检、`[工具 · 身份]` 标签、真页面栈、任务通知、完成音——这些都没有变。

## 关于 Termux（避免误解）

**DLA 不使用 Termux，也不支持 Termux。** Termux 集成在 2.2（2026-11-23）已整体删除：没有 `termux` 工具、没有 RUN_COMMAND、没有 Termux 面板，它不是“可选后端”。APK 自带 Android toybox shell、CPython 3.12、Node 24 与 ripgrep；可选增强只有 **Shizuku**（特权命令）和**无障碍**（读屏 / 点按）。CHANGELOG 里 1.4.x / 2.x 的 Termux 条目只是历史记录，**3.6.0 与 26.11.4 的更新与 Termux 无关**。

## 升级说明

- 同包名、同签名覆盖安装即可，**不要卸载**（卸载会丢会话）；`versionCode` 从 3.6.0 的 52 递增到 62，可以直接覆盖。
- 覆盖安装后无障碍启用项可能被系统清掉，请在「设置 → 更多 → 手机能力 → 打开无障碍设置」重新打开。
- 老版本的接口配置会保留；如果换了供应商，记得在设置页确认**协议**与 Base URL 匹配（页面会显示“当前适配”）。
- 想用 DeepSeek 官方搜索/抓取，在「设置 → 对话」填官方 Key 并点「测试官方 Key」自检；不填则仍走免费后端。

**完整哈希与安装说明**见 Release 资产 `SHA256SUMS`、[`docs/INSTALL.md`](https://github.com/wuy489934-svg/DLA/blob/main/docs/INSTALL.md) 与 README 的[安装与快速开始](https://github.com/wuy489934-svg/DLA#install)；版本历史见 [`CHANGELOG.md`](https://github.com/wuy489934-svg/DLA/blob/main/CHANGELOG.md)，运行时细节见 [`docs/AGENT-RUNTIME.md`](https://github.com/wuy489934-svg/DLA/blob/main/docs/AGENT-RUNTIME.md)。
