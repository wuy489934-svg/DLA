# 安装与首次配置（Android）

> 26.11.4：原生 Compose 界面是唯一入口；没有网页界面，也不需要 Termux——shell、Python、Node、ripgrep 都在 APK 里。模型接口支持 Anthropic Messages 与 OpenAI Chat Completions 两种协议。

## 1. 要求

- Android 10（API 29）及以上，**arm64-v8a（64 位）**；APK 约 48 MB，安装后约 150 MB。
- 你自己的 API Key（DeepSeek，或任意 Anthropic / OpenAI 兼容端点）。DLA 不提供、不代管、不转售 Key。
- **可选增强**：
  - **Shizuku**：提供 uid 2000 的 shell，`pm / am / settings / dumpsys` 这类特权命令只有它能跑；
  - **无障碍服务**：开启后读屏 / 截图 / 点按类工具才可用。
- **不需要 Termux，也不支持 Termux**：Termux 集成已在 2.2 整体删除（不是可选后端）。Android 自带 shell（toybox）、CPython 3.12、Node 24、ripgrep 随 APK 提供，装完即可执行。

## 2. 下载与校验

1. 打开 [Releases](https://github.com/wuy489934-svg/DLA/releases)，下载最新 `DLA.apk` 与 `SHA256SUMS`。
2. 校验（可选但建议）：

```bash
# Windows PowerShell
Get-FileHash .\DLA.apk -Algorithm SHA256
# macOS / Linux
sha256sum DLA.apk
```

把输出与 `SHA256SUMS` 里的值对比。

## 3. 允许安装

Android 默认阻止侧载，需要给“文件管理/浏览器”或 adb 授予安装权限：

| 系统 | 路径 |
|---|---|
| MIUI / HyperOS | 设置 → 应用设置 → 授权管理 → 安装未知应用 → 选中你用来打开安装包的应用 → 允许 |
| ColorOS / realme UI | 设置 → 应用 → 特殊应用权限 → 安装未知应用 |
| OriginOS / Funtouch | 设置 → 应用与权限 → 权限管理 → 安装未知应用 |
| 原生 Android / Pixel | 设置 → 应用 → 特殊应用权限 → 安装未知应用 |
| HarmonyOS 4 | 设置 → 安全 → 更多安全设置 → 外部来源应用下载 |

> MIUI 还可能在安装时弹“风险提示/15 秒等待”，属于系统行为，不是 APK 损坏。

## 4. 安装

**方式 A（手机直接装）**：用文件管理器点开 `DLA.apk` → 安装。

**方式 B（数据线 / adb，推荐用于升级）**：

```bash
adb install -r DLA.apk
```

`-r` 表示覆盖安装，能保留已有数据。首次安装也可以不加 `-r`。

常见失败码：

| 报错 | 含义 | 处理 |
|---|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | 系统拦截了安装 | 按第 3 节允许安装未知来源；保持屏幕点亮后重试 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 包名相同但签名不同 | 你装的是别的签名版本（例如自己编译的 debug 包）。需要先卸载，**会丢数据** |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本号低于已安装版本 | 用更新的 APK，或卸载后重装 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | 清理后重试 |

## 5. 首次配置

设置页按区块排列：**模型接口 / 外观 / 对话 / 通知 / 朗读 / 角色设定**，最后是**更多**（运行环境、MCP、数据、手机能力）。

### 5.1 API Key 与接口协议

设置 → 模型接口：

1. **接口协议**二选一：
   - **Anthropic Messages**（默认）：`POST {base}/messages`，Base URL 形如 `https://api.deepseek.com/anthropic/v1`；
   - **OpenAI Chat Completions**：`POST {base}/chat/completions`，Base URL 通常以 `/v1` 结尾。
   切换协议后请求会自动换到对应路径（`/anthropic/v1` ↔ `/v1`），不用手改；手填的其它地址不会被改写。
2. 填 **API Key**；Base URL / 模型留空即用内置默认（`https://api.deepseek.com/anthropic/v1` + `deepseek-flash`）。

### 5.2 供应商预设与自动识别

同一张卡片里的**供应商预设**可一键填好协议 + Base URL + 默认模型，共 12 个：DeepSeek、DeepSeek（OpenAI 协议）、通义千问 / 百炼、月之暗面 Kimi、智谱 GLM、OpenAI、Anthropic Claude、OpenRouter、硅基流动、Groq、Ollama（本机）、自定义 / 中转站。卡片底部那句「当前适配」后面会跟一句“这家的坑”（选预设和自动识别都会带，例如 GLM 的 Base URL 是 `/api/paas/v4` 而不是 `/v1`）。

不选预设时，DLA 会**自动识别**供应方：先看 Base URL（`api.deepseek.com` / `dashscope` / `bigmodel.cn` / `api.groq.com` / `localhost:11434` …），再看模型名前缀（`deepseek` / `claude` / `gpt` / `qwen` / `glm` / `kimi`），都认不出就按“自定义”处理。识别结果只影响兼容开关（输出上限字段、是否带 `stream_options`、是否发温度、思考开关写法、是否下发服务端联网工具、身份文案），不会把请求写坏。卡片底部会显示“当前适配：…”。

### 5.3 拉取模型并从列表挑选

1. 点**拉取并测试连接**：DLA 用 Bearer（Anthropic 协议再补一次 `x-api-key`）请求 `/models`；粘成半截的地址（`…/chat/completions`、`…/messages`）会自动去掉，`/anthropic/v1` 还会额外试 `/v1/models` 与 `/models`。Key 不被接受（401 / 403）时直接停止，不无意义重试。
2. 成功后显示“连接成功，发现 N 个模型”，并出现**「从 N 个模型里挑一个」**或模型输入框旁的**「选择」**：点开是带搜索框的列表，点一下即选中。
3. 列表会缓存在本机，下次进设置页不用重新拉取。
4. 最后点**保存**。LLM 区与 Agent 区共用这一套设置。

### 5.4 上下文窗口（Agent 区用量条的分母）

设置 → 模型接口 → 上下文窗口，可选 **64K / 128K / 256K / 1M**，默认 128K。
接口只会告诉你“这次用了多少 token”，窗口大小是模型能力，需要按你实际使用的模型来选；换模型后记得同步改，否则百分比会不准。

### 5.5 朗读（TTS，默认关闭）

设置 → 朗读：

- 开关**默认关闭**；打开后回复结束会自动朗读。也可以点每条助手正文下的「🔊 朗读」手动朗读（再点停止）。
- **朗读引擎**二选一：
  - **系统语音（本机）**：零配置、离线；引擎不可用时显示原因并提供「打开语音设置」。
  - **Qwen3-TTS（百炼）**：需要你自己的百炼 API Key，选音色（Cherry / Serena / Ethan / Chelsie，也可自己填）、区域（北京 / 新加坡）、模型（`qwen3-tts-flash`），可先「试听」。单次合成上限 600 字，长回复按句子切分（最多 5 段）依次合成、连续播放。
- 所有 Key 只保存在本机。

### 5.6 通知

Android 13+ 首次使用时会请求一次通知权限（只问一次）。设置 → 通知：

- **任务通知（完成 / 失败时提醒）**：默认开启。
- 权限状态与**「授权通知权限」**按钮；被系统拒绝过之后，按钮会跳到系统通知设置页。
- Agent 任务完成 / 失败 / 停止时会发一条通知：标题按结果区分，正文是结论摘要 + 轮 / 步 / 耗时 / 最后调用的工具，展开可看更多；**点一下回到那个会话**。这条通知走独立渠道（`agent-result-v2`，高重要性、带提示音与振动）。
- DLA 在前台时，任务完成还会播一段约 0.35 秒的提示音（走媒体音量，所以媒体音量为 0 时听不到）。

### 5.7 Shizuku（可选）

不装也能用：shell、Python、Node、读图、文件、应用列表都不依赖它。装了之后才能用 `pm / am / settings / dumpsys`。

1. 安装 [Shizuku](https://shizuku.rikka.app/)。
2. 用 **无线调试**（Android 11+）或数据线启动 Shizuku 服务；手机重启后需要重新启动。
3. 打开 DLA → 设置 → 更多 → 手机能力 → Shizuku → 「请求授权」。
4. 授权后「测试 shell」应返回 `uid=2000(shell)`。

### 5.8 无障碍（可选）

读屏、截图、点按需要系统无障碍服务：系统设置 → 无障碍 → 已下载的应用 → DLA → 开启。
DLA 的设置 → 更多 → 手机能力里能看到当前状态，并有一键跳转。

> 覆盖安装 APK 后，系统的无障碍启用项可能被清掉，需要重新打开。

### 5.9 验证

- 设置 → 更多 → 手机能力：无障碍「已开启」、Shizuku「已就绪」（如果你装了）。
- 首页 → Agent 分区 → 新建任务，输入 `run uname -a`：
  - 回答会写明用了 `phone_sh`、应用身份、退出码 0；
  - 输入框上方出现上下文用量条和「本轮 x.x s」；
  - 工具卡展开后能看到参数、结果和执行身份。
- 首页 → LLM 分区 → 发一句话：应能流式回复；如果开了思考 / 联网，思考卡与搜索工具都能正常显示。

## 6. 升级

- **同包名、同签名，直接覆盖安装**，不要卸载：

```bash
adb install -r DLA-<version>.apk
```

- 升级不会清掉会话：**退出重进会回到上次所在的会话**，覆盖安装也一样。
- 覆盖安装后，系统的无障碍启用项可能被清掉，需要按 5.8 重新打开。
- 从别的签名版本（例如自行编译的 debug 包）切到 Release 包必须卸载，请先导出历史。

## 7. 卸载与数据

卸载后会话（`files/native-sessions/*.json`）、草稿、API Key、朗读设置一并删除，且无法恢复。
如果只是想清空聊天：设置 → 更多 → 数据 → 删除全部会话；同一处还有「导出历史」可用于备份。

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 需要装 Termux 吗？ | 不需要，也不支持：Termux 集成已在 2.2 整体删除；内置 shell / Python / Node / ripgrep |
| 发送后提示“请先在设置里填写 API Key” | 没填 Key 或保存失败；到 设置 → 模型接口 重新保存 |
| 拉取模型报 401 / 403 | Key 不对，或这个 Key 没有该端点的模型列表权限；换地址也没用 |
| 对话报 400 / 说什么参数缺失 | 协议与端点不匹配（Anthropic 端点用了 OpenAI 方言，或反过来）；用「供应商预设」重选一次，或检查 Base URL 结尾是 `/anthropic/v1` 还是 `/v1` |
| 模型名不存在 | 用「拉取并测试连接」后从列表里挑，别手打 |
| Agent 说 `pm` / `dumpsys` 被拒绝 | 那是应用身份（`phone_sh`）的限制；装并启动 Shizuku 后用 `phone_shell` |
| Shizuku 显示“未运行” | 手机重启后 Shizuku 服务会停止，用无线调试重新启动 |
| 读屏 / 截图工具不在列表里 | 无障碍服务没开，或覆盖安装后被系统清掉；重新开启 |
| 上下文百分比明显不对 | 设置里的上下文窗口与模型不匹配（默认 128K） |
| 朗读没有声音 | 媒体音量为 0；系统语音引擎缺失（改用 Qwen3-TTS）；Qwen3-TTS 没填百炼 Key |
| 收不到任务完成通知 | Android 13+ 没给通知权限：设置 → 通知 → 授权通知权限 |
| 手机锁屏后任务中断 | 允许 DLA 后台运行、关闭电池优化（DLA 会请求忽略电池优化） |
| 网页截图报“DLA 不在前台” | `phone_web_shot` 需要 DLA 在前台才有窗口可渲染；切回 DLA 再试 |
| 安装包报签名冲突 | 之前的包来自不同签名，只能卸载重装（先导出历史） |
