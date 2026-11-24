# 安装与首次配置（Android）

> 3.1.2：原生 Compose 界面是唯一入口；没有网页界面，也不需要 Termux——shell、Python、Node、ripgrep 都在 APK 里。

## 1. 要求

- Android 10（API 29）及以上，**arm64-v8a（64 位）**；APK 约 48 MB，安装后约 150 MB。
- 你自己的 DeepSeek 兼容 API Key（DLA 不提供、不代管、不转售 Key）。
- **可选增强**：
  - **Shizuku**：提供 uid 2000 的 shell，`pm / am / settings / dumpsys` 这类特权命令只有它能跑；
  - **无障碍服务**：开启后读屏 / 截图 / 点按类工具才可用。
- **不需要 Termux**：Android 自带 shell（toybox）、CPython 3.12、Node 24、ripgrep 随 APK 提供，装完即可执行。

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

### 5.1 API Key

DLA → 设置 → 模型接口 → 填入 API Key → 保存。
Key 只保存在本机；LLM 区与 Agent 区的请求都直接用你的 Key 访问你配置的端点。

Base URL / 模型留空即用内置默认值（DeepSeek 兼容端点、`deepseek-flash`），也可以自己填写兼容端点。

### 5.2 上下文窗口（Agent 区用量条的分母）

设置 → 模型接口 → 上下文窗口，可选 **64K / 128K / 256K / 1M**，默认 128K。
接口只会告诉你“这次用了多少 token”，窗口大小是模型能力，需要按你实际使用的模型来选；换模型后记得同步改，否则百分比会不准。

### 5.3 Shizuku（可选）

不装也能用：shell、Python、Node、读图、文件、应用列表都不依赖它。装了之后才能用 `pm / am / settings / dumpsys`。

1. 安装 [Shizuku](https://shizuku.rikka.app/)。
2. 用 **无线调试**（Android 11+）或数据线启动 Shizuku 服务；手机重启后需要重新启动。
3. 打开 DLA → 设置 → 更多 → 手机能力 → Shizuku → 「请求授权」。
4. 授权后「测试 shell」应返回 `uid=2000(shell)`。

### 5.4 无障碍（可选）

读屏、截图、点按需要系统无障碍服务：系统设置 → 无障碍 → 已下载的应用 → DLA → 开启。
DLA 的设置 → 更多 → 手机能力里能看到当前状态，并有一键跳转。

> 覆盖安装 APK 后，系统的无障碍启用项可能被清掉，需要重新打开。

### 5.5 验证

- 设置 → 更多 → 手机能力：无障碍「已开启」、Shizuku「已就绪」（如果你装了）。
- 首页新建一个「Agent 任务」，输入 `run uname -a`：
  - 回答会写明用了 `phone_sh`、应用身份、退出码 0；
  - 输入框上方出现上下文用量条和「本轮 x.x s」；
  - 工具卡展开后能看到参数、结果和执行身份。

## 6. 升级

- **同包名、同签名，直接覆盖安装**，不要卸载：

```bash
adb install -r DLA-<version>.apk
```

- 卸载会删除应用私有数据（会话、草稿、Key、设置），升级前建议在 设置 → 更多 → 数据管理 → 导出所有历史对话 备份。
- 从别的签名版本（例如自行编译的 debug 包）切到 Release 包必须卸载，请先导出历史。

## 7. 卸载与数据

卸载后会话（`files/native-sessions/*.json`）、草稿、API Key 一并删除，且无法恢复。
如果只是想清空聊天：设置 → 更多 → 数据管理 → 删除所有对话。

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 发送后提示“请先在设置里填写 API Key” | 没填 Key 或保存失败；到 设置 → 模型接口 重新保存 |
| Agent 说 `pm` / `dumpsys` 被拒绝 | 那是应用身份（`phone_sh`）的限制；装并启动 Shizuku 后用 `phone_shell` |
| Shizuku 显示“未运行” | 手机重启后 Shizuku 服务会停止，用无线调试重新启动 |
| 读屏 / 截图工具不在列表里 | 无障碍服务没开，或覆盖安装后被系统清掉；重新开启 |
| 上下文百分比明显不对 | 设置里的上下文窗口与模型不匹配（默认 128K） |
| 手机锁屏后任务中断 | 允许 DLA 后台运行、关闭电池优化（DLA 会请求忽略电池优化） |
| 安装包报签名冲突 | 之前的包来自不同签名，只能卸载重装（先导出历史） |
