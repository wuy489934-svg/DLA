# 安装与首次配置（Android）

## 1. 要求

- Android 9（API 29）及以上；targetSdk 36。
- 你自己的 DeepSeek 兼容 API Key（DLA 不提供、不代管、不转售 Key）。
- 至少一个执行后端：
  - **Shizuku**（推荐）：提供 uid 2000 的 shell 能力；
  - 或 **Termux**：提供本地终端（Node/Python/rg/网络/包管理）。
- 无障碍服务可选，开启后读屏更快。

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
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 包名相同但签名不同 | 你装的是别的签名版本（例如调试包）。需要先卸载，**会丢数据** |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本号低于已安装版本 | 用更新的 APK，或卸载后重装 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | 清理后重试 |

## 5. 首次配置

### 5.1 API Key

DLA → 设置 → 数据管理 → 填入 API Key → 确定。
Key 只保存在本机；Agent 与 LLM 请求都直接用你的 Key 访问你配置的端点。

模型接口默认使用 DeepSeek：Base URL / 模型留空即用内置默认值，也可以自己填写兼容端点。

### 5.2 Shizuku（可选，二者其一）

1. 安装 [Shizuku](https://shizuku.rikka.app/)。
2. 用 **无线调试**（Android 11+）或数据线启动 Shizuku 服务；手机重启后需要重新启动。
3. 打开 DLA → 设置 → Agent → 手机能力 → 授权。
4. 需要读屏的话，到系统设置里开启 DLA 的无障碍服务。

### 5.3 Termux（可选，二者其一）

1. 从 [F-Droid](https://f-droid.org/packages/com.termux/) 或 GitHub 安装 Termux（不要用 Play 商店的旧版本）。
2. 在 Termux 里执行一次性授权：

```bash
echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
```

3. 给 DLA 授予「Run commands in Termux environment」权限：
   系统设置 → 应用 → DLA → 权限 → 其它权限 / 特殊权限里找到该项。

   也可以用 adb：

```bash
adb shell pm grant io.github.dla.agent com.termux.permission.RUN_COMMAND
```

4. 可选：DLA → 设置 → Agent → Termux 环境 → 「一键安装环境」，会安装 Node.js、Python、git、ripgrep
   （优先清华/中科大镜像，npm 用 npmmirror，pip 用清华 PyPI）。

> MIUI 会拦截后台跨应用启动 Termux 的服务。点「一键安装环境」或「测试 Termux」时，先让 Termux 在前台，
> DLA 已经会在执行前把 Termux 切到前台并做 8 秒探针。

### 5.4 验证

- 设置 → Agent → 手机能力：显示「已就绪」。
- 设置 → Agent → Termux 环境 → 「测试 Termux」：返回 `DLA_TERMUX_OK` 与版本号。
- 回到首页选「Agent 任务」，随便让它做一件小事（例如“报告电量”）。只要 Shizuku 或 Termux 有一个可用就能发送。

## 6. 升级

- **同包名、同签名，直接覆盖安装**，不要卸载：

```bash
adb install -r DLA-<version>.apk
```

- 卸载会删除应用私有数据（聊天记录、Key、设置），升级前建议在 数据管理 → 导出所有历史对话 备份。
- 从别的签名版本（如自行编译的 debug 包）切到 Release 包必须卸载，请先导出历史。

## 7. 卸载与数据

卸载后 WebView IndexedDB、`host-sessions` 镜像、API Key 一并删除，且无法恢复。
如果只是想清空聊天：数据管理 → 删除所有对话。

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| Agent 区提示“需要 Shizuku 或 Termux 至少一项可用” | 两个后端都不可用。按 5.2 或 5.3 配好其中一个 |
| Termux 调用超时 | `allow-external-apps` 未生效或 DLA 没有 `RUN_COMMAND` 权限；重做 5.3 的 2、3 步 |
| Shizuku 显示“未运行” | 手机重启后 Shizuku 服务会停止，用无线调试重新启动 |
| 手机锁屏后任务中断 | 允许 DLA 后台运行、关闭电池优化（DLA 会请求忽略电池优化） |
| 安装包报签名冲突 | 之前的包来自不同签名，只能卸载重装（先导出历史） |
