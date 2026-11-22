# DLA Android Agent 工具层评测

本目录是 DLA Android Agent（WebView + `AndroidHost.debugTool`）的可重复工具层评测集。目标是验证原生设备信息、Settings 读取、等待、截图、文件列表/读取、shell 自检、耗时统计和失败路径；结果同时输出 JSON 与 Markdown 表格。

**安全边界**：只调用只读工具、`phone_wait` 和 `phone_screenshot`。不点击、不输入、不打开应用、不写用户文件、不改设置。`phone_screenshot` 会向相册写入一张截图，这是唯一预期的设备侧副作用。

## 快速开始

在仓库根目录运行：

```powershell
node evals/run-agent-evals.mjs
```

常用变体：

```powershell
# 指定包名和报告标签
node evals/run-agent-evals.mjs --package io.github.dla.agent --label "test device"

# 已手动 adb forward，且不想让脚本碰 adb
node evals/run-agent-evals.mjs --no-adb

# 多设备/多 WebView 时显式指定 socket
node evals/run-agent-evals.mjs --socket webview_devtools_remote_23540

# 严格模式：有 SKIP 或警告时退出码为 2（默认仍为 0）
node evals/run-agent-evals.mjs --strict

# 只跑某个用例
node evals/run-agent-evals.mjs --filter screenshot

# 只列出用例，不连接设备
node evals/run-agent-evals.mjs --list
```

要求：Node.js >= 22.12（内置 `fetch` / `WebSocket`），Android debug 包且 WebView 调试已启用（`BuildConfig.DEBUG` 时 `WebView.setWebContentsDebuggingEnabled(true)`）。没有 adb 也可在已手动 forward 时用 `--no-adb` 运行。

## 连接方式

1. `fetch http://127.0.0.1:9223/json/list`。
2. 选 `type === "page"` 的 target，优先 URL 含 `appassets`；若多个 target 会记录在报告里。
3. WebSocket 连 `webSocketDebuggerUrl`，用 `Runtime.evaluate`（`awaitPromise: true`, `returnByValue: true`）。
4. 页面内同步调用 `AndroidHost.debugTool(name, JSON.stringify(args))`；shell 自检调用 `AndroidHost.shellTest('id')`。
5. 如果端口不可用、包名不匹配或 target 为空，且未加 `--no-adb`：自动找 adb（`ADB` / `ANDROID_HOME` / 仓库内 `toolchains/android-sdk/platform-tools/adb.exe`），从 `/proc/net/unix` 找 `webview_devtools_remote_<pid>`，再执行：

   ```text
   adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>
   ```

   不会重启应用、不会点击、不会改设备设置；`adb forward` 是主机侧端口转发。

找不到 socket 或包不是 debuggable 时，脚本会以 **INFRA ERROR** 退出（码 3），并在报告中写明包名、PID、socket 列表和当前 forward，不会伪造 PASS。

## 用例

| # | id | 工具/接口 | 断言 |
| ---: | --- | --- | --- |
| 1 | `device.info` | `phone_device` | model/brand/android/sdk/screen/battery/storage 均存在，screen 为 `WxH @Ndpi` |
| 2 | `settings.system.screen_brightness` | `phone_setting_get` | `system/screen_brightness` 返回整数或 `（null）` |
| 3 | `settings.secure.accessibility_enabled` | `phone_setting_get` | `secure/accessibility_enabled` 返回 0/1 或 `（null）` |
| 4 | `wait.500ms` | `phone_wait` | 输出 `已等待 500ms`，页面内耗时 >= 450ms |
| 5 | `screen.dump` | `phone_screen` | 返回非空控件树；无障碍缺失时 SKIP |
| 6 | `screenshot.basic` | `phone_screenshot` | 解析截图路径、截图尺寸、实际屏幕尺寸、JPEG 字节数；并用 `phone_file_info` 验证路径存在 |
| 7 | `files.list_download` | `phone_files` | `/sdcard/Download` 非空；识别 native listing / `ls -la` fallback；系统错误文本视为失败 |
| 8 | `files.read_hosts` | `phone_read` | `/system/etc/hosts` 返回 `size=N bytes, offset=0` 且含 `localhost` |
| 9 | `files.file_info_download` | `phone_file_info` | `/sdcard/Download` 识别为 `directory` |
| 10 | `shell.test_id` | `AndroidHost.shellTest('id')` | 输出 `✅` 且含 `uid=...`；Shizuku 不可用时 SKIP |
| 11 | `failure.missing_file_read` | `phone_read` | 不存在的文件必须出现 `No such file` / `无法读取文件大小` 等明确失败文本 |
| 12 | `failure.unknown_tool` | `debugTool` 未知工具名 | 输出 `错误：未知手机工具：...` |

用例定义在 [`cases.json`](cases.json)，不是硬编码在脚本里。新增用例只需加一个 JSON 对象；断言支持 `contains` / `containsAny` / `notContains` / `regex` / `notRegex` / `minLength` / `minPageMs` / `maxPageMs` / `failureMarkers` / `warnings`。`{{runId}}` 可在 `args` 中展开为本次运行 ID，避免缺失文件路径碰巧存在。

## 结果与状态

默认输出：

- `mobile/evals/results/latest.json`
- `mobile/evals/results/latest.md`

JSON 顶层字段：

```text
ok, verdict, summary, connection, environment, cases, artifacts
```

每个 case 包含 `status`、`reason`、`checks`、`timing.pageMs`、`timing.wallMs`、`details`、`output`。`pageMs` 是页面内 `performance.now()` 测得的工具执行时间；`wallMs` 额外包含 CDP 往返。

状态含义：

- `PASS`：全部断言通过。
- `PASS ⚠`：通过，但有警告（不改变默认退出码；`--strict` 时视为失败）。
- `FAIL`：断言失败或工具返回错误。
- `SKIP`：环境依赖缺失（无障碍未启用、Shizuku 未运行/未授权），不计为成功。
- `INFRA ERROR`：连不上 CDP、没有 page target、没有 `AndroidHost.debugTool` 等；退出码 3。

退出码：

| 码 | 含义 |
| ---: | --- |
| 0 | 没有 FAIL / INFRA ERROR（SKIP、警告默认允许） |
| 1 | 有 FAIL |
| 2 | `--strict` 下有 SKIP 或警告 |
| 3 | 连接/环境 INFRA ERROR |
| 4 | 报告写入失败 |

## 示例运行结果

在自用设备上运行过 12 个只读用例，结果为 12 通过 / 0 失败。具体设备型号、序列号、账号和目标包版本不写入开源仓库；请在自己的设备上运行并把 `results/` 保留在本地（该目录已被 `.gitignore` 忽略）。

```text
用例：12 总数 / 12 通过 / 0 失败 / 0 跳过 / 0 警告
结论：PASS
```
## 已知限制

- **release/自用包不可评测**：`BuildConfig.DEBUG` 为 false 时 WebView 调试不启用，设备上没有 `webview_devtools_remote_<pid>` socket。脚本会以 INFRA ERROR 明确失败，并列出包 PID / socket / forward 诊断。不要用改源码或装未确认包来绕过。
- **无障碍依赖**：`phone_screenshot` / `phone_screen` 依赖无障碍读屏。未启用时截图错误按 SKIP 记录，不记为 PASS。
- **Shizuku 依赖**：`shell.test_id` 需要 Shizuku 正在运行且已授权；未满足时 SKIP。文件工具的 shell fallback 也可能受此影响。
- **失败契约不统一**：`debugTool` 只返回文本，不暴露 `Result.isError`。`phone_read` 对不存在文件会把 `stat`/`head` 的 stderr 拼进 `size=...` 文本，而不是 `错误：` 前缀；当前记警告。若要求统一错误契约，可把 `cases.json` 中该用例的 warning 改为硬断言（会变成 FAIL）。
- **文件读取 fallback 不可直接观测**：`phone_read` 的 native 和 shell fallback 都返回同样的 `size=...` 前缀，无法从文本区分；`phone_files` 可通过输出格式区分 native / `ls -la`。
- **不要用 `/proc/version` 做读取用例**：procfs 文件的 `RandomAccessFile.length()` 可能为 0，`phone_read` 会误判 offset 超过末尾；因此读取用例选用稳定、非空、可读的 `/system/etc/hosts`。
- **隐私脱敏**：`phone_screen` 和 `phone_files` 成功时，报告只保存行数/模式/哈希等元数据，省略原始控件树和目录文件名，避免把个人内容写进评测产物。失败时保留错误文本。
- **计时受缓存和负载影响**：`phone_device` / `phone_setting_get` 在应用内有短时缓存；`phone_screen` 冷启动明显更慢（本次 246ms，前一次 29ms）。耗时统计反映当次状态，不应作为硬性能门槛。
- **截图会新增文件**：每跑一次 `screenshot.basic` 会在 `Pictures/DLA/` 新增一张 PNG；如需清理请自行在设备上删除。

## 故障排查

1. 先看 `results/latest.md` 的「失败原因（连接/环境）」和「连接尝试」。
2. 手动确认：

   ```powershell
   adb devices -l
   adb forward --list
   adb shell cat /proc/net/unix | Select-String webview_devtools
   adb shell pidof io.github.dla.agent
   ```

3. 确认包是 debuggable：

   ```powershell
   adb shell dumpsys package io.github.dla.agent | Select-String "DEBUGGABLE|versionName"
   ```

4. 手动 forward 后加 `--no-adb`：

   ```powershell
   adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>
   node evals/run-agent-evals.mjs --no-adb --socket webview_devtools_remote_<pid>
   ```

5. 截图 SKIP：在 设置 → Agent → 手机能力 启用无障碍读屏；shell SKIP：启动 Shizuku 并授权。

## 约束与不修改项

- 只新增/修改 `mobile/evals/` 下的文件；未改应用源码、开源树、构建配置，也未安装依赖。
- 未调用 `phone_tap` / `phone_input` / `phone_open` / `phone_shell` / `phone_write` / `phone_setting_put` 等会改变设备状态的工具。
- 脚本是纯 Node ESM，使用内置 `fetch` / `WebSocket` / `child_process`，无 npm 依赖。


