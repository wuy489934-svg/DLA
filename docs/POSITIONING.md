# DLA 的定位

## 是什么

DLA = **DeepSeek LLM Agent**，是一个运行在 Android 系统内的本地智能体客户端。LLM 区和 Agent 区都只使用 DeepSeek 兼容协议；不再暴露其它模型服务。

- 原生 Android 工具层：无障碍、PackageManager、Shizuku、文件/设置/截图
- 原生 Agent 引擎：流式请求、工具调度、并行组、迭代树、checkpoint
- WebView UI：聊天、Agent 事件、上下文与用量展示
- 本地优先：会话/设置/诊断在本机；API 请求直达用户配置的端点

## 不是什么

- **不是官方 Demo 的打包壳**：上游 Web UI 是 MIT 许可的基础，DLA 的 Agent 引擎、原生工具、调度器、迭代树、诊断和评测是独立实现。
- **不是其它 Android Agent 的复刻**：不复制其 UI、资源或实现；DLA 的差异是 Android 原生工具 + 系统内运行 + 小体积。
- **不是手机上的 Linux**：不提供发行版、终端生态或通用 shell 环境；shell 仅作为 Android 权限模型的兜底。
- **不是多代理框架**：默认单 Agent；只有在明确、只读、有预算的场景才考虑有界子任务。

## 设计约束

1. **原生优先**：能用 Android API 就不 fork 新进程。
2. **系统内运行**：前台服务、无障碍、Shizuku 都在手机内完成。
3. **小巧但全能**：单 APK，不引入重型运行时。
4. **可解释**：工具耗时、并行组、缓存、上下文、失败原因对用户可见。
5. **开源**：Apache-2.0；保留上游 MIT 与第三方许可；不捆绑不可再分发素材。

## 品牌与素材

- 应用名：DLA
- 图标：Lucide `bot`，ISC
- 上游 Web UI：deepseek-ai/chat-demo，MIT
- 不包含官方 Demo 的角色素材或未授权插画
- 存储 key 中保留 `chat-demo:*` 仅用于兼容旧数据，不代表产品身份


