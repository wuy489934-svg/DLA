package com.example.chatdemo.nativeui

import android.content.Context
import java.util.Locale

/**
 * 原生界面的轻量中英双语（i18n）。
 *
 * 设计取向：**中文原文就是 key**，英文只是查表结果；查不到就回落中文，
 * 所以漏翻不会崩、也不会出现空白，只是那一句还是中文。
 *
 * - 语言偏好存在既有的 SharedPreferences("dla-native") 里，键名 `language`，
 *   取值 `zh` / `en` / `system`（默认 `system`：系统语言是中文就用中文，否则英文）。
 * - [init] 由 NativeShellActivity.onCreate 在任何 UI 之前调用，保证首帧就是对的；
 *   [set] 写偏好并立刻更新内存里的当前语言，调用方随后 `recreate()` 让整棵树重新取值。
 * - [t] 是普通函数（非 Composable），Compose、后台线程、错误回调都能直接用，
 *   所以「界面上显示的错误文案」不必绕回主线程再翻译。
 * - 带数量的模板支持 `单数|复数` 写法（见下），英文按数量自动选形：
 *   `" %d 轮" -> "%d round|%d rounds"`。模板里只有一个数字时才会切换；
 *   多数字模板（如「%d 轮 %d 步」）请拆成两次调用，不要用 `|`。
 *
 * 只翻译「显示用」的文案。逻辑值一律保持原样，例如：
 * - 会话占位标题 `新对话`（SessionStore 落盘并用它做判断，属于数据/逻辑，不在这里查表）；
 * - `mode == "agent"` / `"light"` / `"waiting"` / `"error"`、JSON key、SharedPreferences 键名、
 *   shell 命令、包名、`zh-CN` 之类的识别语言参数。
 */
object Lang {

    /** 偏好值：中文 */
    const val ZH = "zh"

    /** 偏好值：英文 */
    const val EN = "en"

    /** 偏好值：跟随系统（默认） */
    const val SYSTEM = "system"

    private const val PREFS = "dla-native"
    private const val KEY = "language"

    /** 用户偏好：zh / en / system */
    @Volatile
    private var pref: String = SYSTEM

    /** 当前生效语言：zh / en */
    @Volatile
    private var active: String = ZH

    /** 启动时初始化：读偏好 + 系统语言，决定当前语言。任何 UI 之前调用。 */
    fun init(context: Context) {
        val stored = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, SYSTEM)
        }.getOrNull()
        pref = normalize(stored)
        active = if (pref == SYSTEM) systemLanguage(context) else pref
    }

    /** 写入偏好并立即生效（调用方负责 recreate，让已经画出来的界面重新取值）。 */
    fun set(context: Context, mode: String) {
        val value = normalize(mode)
        pref = value
        active = if (value == SYSTEM) systemLanguage(context) else value
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, value)
                .apply()
        }
    }

    /** 当前偏好：zh / en / system */
    fun mode(): String = pref

    /** 当前生效语言：zh / en */
    fun language(): String = active

    fun isEnglish(): Boolean = active == EN

    /** 取当前语言的文案；英文查表，查不到或当前是中文时原样返回。 */
    fun t(zh: String): String = if (isEnglish()) EN_TEXT[zh] ?: zh else zh

    /**
     * 带参数的文案，[String.format] 风格：`Lang.t("%d 轮", n)`。
     *
     * 英文模板支持 `单数|复数`，只会按唯一的数字参数选形；
     * 任何格式化异常都会退回中文原文，绝不让一句文案把界面搞崩。
     */
    fun t(zh: String, vararg args: Any): String {
        val template = template(zh, args)
        return runCatching { String.format(Locale.getDefault(), template, *args) }
            .getOrElse {
                runCatching { String.format(Locale.getDefault(), zh, *args) }.getOrDefault(zh)
            }
    }

    private fun normalize(mode: String?): String = when (mode) {
        ZH, EN -> mode
        else -> SYSTEM
    }

    /** 系统语言是中文就中文，否则英文 */
    private fun systemLanguage(context: Context): String {
        val locale = runCatching {
            context.applicationContext.resources.configuration.locales.get(0)
        }.getOrNull() ?: Locale.getDefault()
        return if (locale.language.startsWith("zh", ignoreCase = true)) ZH else EN
    }

    /** 选出当前语言的模板，并按数量处理英文单复数 */
    private fun template(zh: String, args: Array<out Any>): String {
        val translated = if (isEnglish()) EN_TEXT[zh] ?: zh else zh
        if (!translated.contains('|')) return translated
        val numbers = args.filterIsInstance<Number>()
        val singular = numbers.size == 1 && numbers[0].toLong() == 1L
        val variants = translated.split('|')
        return if (singular) variants.first() else variants.last()
    }

    // ------------------------------------------------------------------
    // 翻译表：中文（原文，同时是 key）-> 英文
    // 分组与界面一致，按使用处搜索即可
    // ------------------------------------------------------------------
    private val EN_TEXT: Map<String, String> = mapOf(

        // ---- 会话列表 / 首页 ----
        "本地智能体 · 原生界面" to "Local agent · Native UI",
        "今天想让我做什么？" to "What would you like me to do today?",
        "本地运行 · 模型来自 DeepSeek · 数据不出手机" to
            "Runs locally · Powered by DeepSeek · Data stays on your phone",
        "LLM 对话" to "LLM Chat",
        "纯聊天与检索" to "Chat and search",
        "Agent 任务" to "Agent Task",
        "调用工具执行" to "Runs tools for you",
        "还没有会话" to "No sessions yet",
        "上面选一个模式开始" to "Pick a mode above to start",
        "点上面「新对话」开始" to "Tap New chat above to start",
        "两个分区各自保存会话，互不混排" to "Each mode keeps its own sessions",
        "%d 个会话" to "%d session|%d sessions",
        // ---- Agent 用量条 ----
        "上下文" to "Context",
        "本轮 %s" to "Turn %s",
        "上下文窗口（Agent 区用量条用）" to "Context window (Agent usage bar)",
        // ---- Agent 工具身份（3.1.2） ----
        "身份：%s" to "Identity: %s",
        "应用身份（无特权）" to "App identity (no privileges)",
        "Shizuku（uid 2000）" to "Shizuku (uid 2000)",
        "新对话" to "New chat",
        "会话" to "Session",
        "图片" to "Image",
    "文件夹批量导入仅 Agent 区可用" to "Folder import is only available in Agent mode",
    "这个文件夹里没有可导入的文件" to "No importable files in this folder",
        "%d 条" to "%d message|%d messages",
        "%d 轮" to "%d round|%d rounds",
        "%d 步" to "%d step|%d steps",
        "刚刚" to "Just now",
        "%d 分钟前" to "%d min ago",
        "%d 小时前" to "%d hr ago",
        "%d 天前" to "%d day ago|%d days ago",

        // ---- 聊天页 ----
        "Agent 想执行：%s" to "Agent wants to run: %s",
        "需要确认" to "Approval needed",
        "允许" to "Allow",
        "拒绝" to "Deny",
        "生成中" to "Generating",
        "正在生成" to "Generating",
        "思考过程" to "Thinking",
        "收起" to "Collapse",
        "展开" to "Expand",
        "详情" to "Details",
        "参数" to "Arguments",
        "结果" to "Result",
        "深度思考" to "Deep thinking",
        "联网搜索" to "Web search",
        "将随消息一起发送" to "Sent with your message",
        "描述任务或提问…" to "Describe a task or ask a question…",
    "暂不支持这种文件，试试图片、PDF、docx 或文本" to
        "This file type is not supported yet. Try an image, PDF, docx or plain text.",
    "打开系统语音设置" to "Open system speech settings",
    "打不开文件选择器，请用系统文件管理器分享到 DLA" to
        "Could not open the file picker. Try sharing the file to DLA from your file manager.",
    "文档" to "Document",
    "文本" to "Text",

        // ---- 📂 本机文件浏览 ----
        "选择文件" to "Choose files",
        "关闭" to "Close",
        "添加" to "Add",
        "上一级" to "Up",
        "文件夹" to "Folder",
        "内部存储" to "Internal storage",
        "图库" to "Gallery",
        "相机" to "Camera",
        "下载" to "Downloads",
        "全部" to "All",
        "搜索当前目录（含子文件夹）" to "Search here and in subfolders",
        "这个文件夹是空的" to "This folder is empty",
        "没有匹配的文件" to "No matching files",
        "点文件名选中，点文件夹进入" to "Tap a file to select, a folder to open",
        "已选 %d 个" to "%d selected",
        "导入此文件夹" to "Import this folder",
        "需要「所有文件访问权限」" to "All-files access is required",
        "授权后可以直接按路径浏览、搜索并发送本机文件，不需要再走系统的安全访问界面。文件不会被上传，只有你选中的内容会进入对话。" to
            "With this access DLA can browse and search files by real path, with no system picker in the way. Nothing is uploaded; only what you pick goes into the conversation.",
        "去授权" to "Grant access",
        "改用系统文件选择器" to "Use system file picker",
        "改用系统文件夹选择器" to "Use system folder picker",
        "选中的文件里没有可用的内容" to "Nothing usable in the selected files",

        // ---- 复制 / 图片预览 ----
        "复制文本" to "Copy text",
        "复制全部" to "Copy all",
        "复制" to "Copy",
        "长按文字可只选一段" to "Long-press the text to select part of it",
        "草稿" to "Draft",
        "图片预览" to "Image preview",
        "点按关闭" to "Tap to close",

        // ---- 设置：对话 / 人设 ----
        "设置" to "Settings",
        "模型接口" to "Model API",
        "DeepSeek 兼容端点" to "DeepSeek-compatible endpoint",
        "外观" to "Appearance",
        "主题与语言" to "Theme & language",
        "思考 · 搜索 · 权限" to "Thinking · search · permissions",
        "默认关闭 · 系统语音 / Qwen3-TTS" to "Off by default · system speech / Qwen3-TTS",
        "仅 LLM 区" to "LLM chat only",
        "朗读引擎" to "Speech engine",
        "系统语音（本机）" to "System speech (local)",
        "Qwen3-TTS（百炼）" to "Qwen3-TTS (Bailian)",
        "百炼 API Key（只存本机）" to "Bailian API key (stored on this device)",
        "北京" to "Beijing",
        "新加坡" to "Singapore",
        "音色" to "Voice",
        "音色名（可自定义）" to "Voice name (editable)",
        "模型（qwen3-tts-flash）" to "Model (qwen3-tts-flash)",
        "已保存 Qwen3-TTS 设置" to "Qwen3-TTS settings saved",
        "Qwen3-TTS 不可用：%s" to "Qwen3-TTS unavailable: %s",
        "Qwen3-TTS 走百炼接口：单次最多 600 字，长回复按句子分段合成后连续播放；Key 只存本机。" to
            "Qwen3-TTS uses the Bailian API: up to 600 characters per call; long replies are split by sentence and played back to back. The key stays on this device.",
        "默认关闭；打开后回复结束会自动朗读" to "Off by default; when on, replies are read aloud",
        "正在初始化系统语音…" to "Starting system speech…",
        "打开朗读或点「试听」时才会初始化系统语音。" to
            "System speech starts only when you turn on reading or tap Preview.",
        "合成并播放成功" to "Synthesized and played",
        "失败：%s" to "Failed: %s",
        "合成中…" to "Synthesizing…",
        "正在合成语音…" to "Synthesizing speech…",

        "Base URL（留空用默认）" to "Base URL (leave blank for default)",
        "模型（留空用默认）" to "Model (leave blank for default)",
        "保存" to "Save",
        "测试中…" to "Testing…",
        "拉取并测试连接" to "Fetch models & test",
        "已保存" to "Saved",
        "主题" to "Theme",
        "深色" to "Dark",
        "浅色" to "Light",
        "跟随系统" to "Follow system",
        "语言" to "Language",
        "中文" to "Chinese",
        "对话" to "Conversation",
        "深度思考（LLM 区）" to "Deep thinking (LLM chat)",
        "联网搜索（LLM 区）" to "Web search (LLM chat)",
        "完全权限（Agent 不再逐次确认）" to "Full access (no per-step approval)",
        "界面音效" to "UI sounds",
        "朗读回答（系统 TTS）" to "Read replies aloud (system TTS)",
        "所在地区（可选，用于本地化回答）" to "Region (optional, used for local answers)",
        "保存地区" to "Save region",
        "人设（仅 LLM 区）" to "Persona (LLM chat only)",
        "系统提示词（留空用内置身份）" to "System prompt (leave blank for the built-in identity)",
        "保存人设" to "Save persona",
        "更换立绘" to "Replace portrait",
        "上传立绘" to "Upload portrait",
        "移除" to "Remove",
        "镜子模式（内置人设 + 立绘）" to "Mirror mode (built-in persona + portrait)",
        "人设与立绘只在 LLM 区生效；开启镜子模式会覆盖自定义人设。" to
            "Persona and portrait apply to LLM chat only; mirror mode overrides a custom persona.",
        "更多" to "More",
        "运行环境 / MCP / 数据" to "Runtime / MCP / Data",
        "DLA · 本地智能体\n模型来自 DeepSeek，非官方产品。\n数据只存在本机。" to
            "DLA · Local agent\nPowered by DeepSeek, not an official product.\nAll data stays on this device.",

        // ---- 高级设置：MCP ----
        "高级设置" to "Advanced settings",
        "MCP 服务器" to "MCP servers",
        "MCP 让 Agent 调用外部工具，需要填 HTTPS 地址。" to
            "MCP lets the Agent call external tools. An HTTPS URL is required.",
        "Token 只存在本机，仅作为 Bearer 头发给该服务器，可留空。" to
            "The token stays on this device and is sent only to that server as a Bearer header. It can be left blank.",
        "还没有服务器。" to "No servers yet.",
        "名称" to "Name",
        "取消" to "Cancel",
        "添加服务器" to "Add server",
        "未命名服务器" to "Unnamed server",
        "已启用" to "Enabled",
        "已停用" to "Disabled",
        "请先填写 URL" to "Enter a URL first",
        "需要 HTTPS 地址" to "An HTTPS address is required",

        // ---- 高级设置：运行环境 ----
        "运行环境" to "Runtime",
        "全部内置，不需要安装 Termux；命令以应用身份运行，不会修改系统。" to
            "Everything is built in; Termux is not required. Commands run as the app identity and never modify the system.",
        "本机终端" to "Terminal",
        "Android %s · toybox %d 条命令" to "Android %s · %d toybox commands",
        "内置 %s" to "Built-in %s",
        "检测中…" to "Checking…",
        "工作目录" to "Working dir",
        "运行自检" to "Run self-check",
        "自检中…" to "Checking…",
        "应用权限" to "App permissions",
        // ---- 高级设置：数据 ----
        "数据" to "Data",
        "会话数：统计中…" to "Sessions: counting…",
        "会话数：%d" to "Sessions: %d",
        "旧版本留下的历史会在会话仓库初始化时自动导入一次，只补缺失，无需手动导入。" to
            "History left by older versions is imported once when the session store initializes. Only missing sessions are added, so no manual import is needed.",
        "正在导出诊断…" to "Exporting diagnostics…",
        "导出中…" to "Exporting…",
        "导出诊断" to "Export diagnostics",
        "导出历史" to "Export history",
        "已导出到 %s" to "Exported to %s",
        "导出失败：%s" to "Export failed: %s",
        "删除全部会话" to "Delete all sessions",
        "将删除本机 %d 个会话，无法恢复。" to
            "This deletes %d session on this device and cannot be undone.|This deletes %d sessions on this device and cannot be undone.",
        "将删除本机全部会话，无法恢复。" to
            "This deletes all sessions on this device and cannot be undone.",
        "已删除 %d 个会话" to "Deleted %d session|Deleted %d sessions",
        "删除" to "Delete",

        // ---- 高级设置：手机能力（无障碍 + Shizuku）----
        "手机能力" to "Device capabilities",
        "读屏、截图、点按走系统无障碍服务，不需要 Shizuku；Shizuku 只用于 pm/am/settings 这类特权命令。" to
            "Screen reading, screenshots and taps use Android's accessibility service and do not need Shizuku; Shizuku is only for privileged commands such as pm/am/settings.",
        "无障碍服务：%s" to "Accessibility service: %s",
        "已开启" to "on",
        "未开启" to "off",
        "未开启时读屏、截图、点按文字都不可用：打开系统无障碍设置，在「已下载的应用」里找到 DLA 并打开。" to
            "Without it, screen reading, screenshots and tapping by text are unavailable: open the system accessibility settings and enable DLA under downloaded apps.",
        "打开无障碍设置" to "Open accessibility settings",
        "Shizuku：%s" to "Shizuku: %s",
        "未运行" to "not running",
        "已运行，未授权" to "running, not authorized",
        "已就绪" to "ready",
        "需要特权命令时：打开 Shizuku，用无线调试点「启动」（手机重启后要重新启动）。" to
            "For privileged commands: open Shizuku and tap Start (it must be started again after a reboot).",
        "打开 Shizuku" to "Open Shizuku",
        "请求授权" to "Request permission",
        "测试 shell" to "Test shell",
        "执行失败" to "Command failed",

        // ---- 迭代树 ----
        "迭代树" to "Iteration tree",
        "收起迭代树" to "Collapse iteration tree",
        "展开迭代树" to "Expand iteration tree",
        "暂无迭代记录" to "No iterations yet",
        "第 %d 轮" to "Round %d",
        "定位" to "Locate",
        "收起该轮" to "Collapse this round",
        "定位到该轮" to "Jump to this round",
        "思考 · %s" to "Thinking · %s",

        // ---- 会显示给用户的错误 / 状态 ----
        "请先在设置里填写 API Key" to "Add your API key in Settings first",
        "请先填写 API Key" to "Add your API key first",
        "Agent 运行失败" to "Agent run failed",
        "服务端没有返回事件流（%s）" to "The server did not return an event stream (%s)",
        "连接成功（该服务未提供模型列表）" to "Connected (the service returned no model list)",
        "连接成功，发现 %d 个模型" to "Connected, found %d model|Connected, found %d models",
        "网络错误" to "Network error",

        // ---- 设置：TTS ----
        "朗读回答（TTS）" to "Read replies aloud (TTS)",
        "朗读引擎：%s" to "Speech engine: %s",
        "选择引擎" to "Choose engine",
        "选择朗读引擎" to "Choose a speech engine",
        "添加语音提供商" to "Add speech provider",
        "编辑语音提供商" to "Edit speech provider",
        "合成中…" to "Synthesizing…",
        "试听已发送（若没有声音，检查音量或引擎设置）" to
            "Preview sent (if you hear nothing, check the volume or engine settings)",
        "试听失败：%s" to "Preview failed: %s",
        "系统语音不可用：%s" to "System speech unavailable: %s",
        "也可以添加一个云端语音提供商，不依赖本机引擎。" to
            "You can also add a cloud speech provider that does not rely on the local engine.",
        "系统语音已就绪" to "System speech ready",
        "正在初始化系统语音…" to "Starting system speech…",
        "开启朗读或点「试听」时才会初始化系统语音。" to
            "System speech starts only when you enable reading or tap Preview.",
        "云端语音不可用：%s" to "Cloud speech unavailable: %s",
        "正在合成语音…" to "Synthesizing speech…",
        "云端语音可用" to "Cloud speech available",
        "点「试听」验证这个提供商（合成一次，播放一次）。" to
            "Tap Preview to verify this provider (one synthesis, one playback).",
        "默认使用本机系统语音；也可以添加 OpenAI 兼容的云端 TTS（如 /audio/speech）。密钥只存在本机。" to
            "System speech is the default; you can also add an OpenAI-compatible cloud TTS (e.g. /audio/speech). Keys stay on this device.",
        "已切换到「%s」" to "Switched to \"%s\"",
        "已删除「%s」" to "Deleted \"%s\"",
        "已保存并切换到「%s」" to "Saved and switched to \"%s\"",
        "未命名" to "Unnamed",
        "使用中" to "In use",
        "编辑" to "Edit",
        "关闭" to "Close",
        "名称（例如：我的云端语音）" to "Name (e.g. My cloud voice)",
        "Base URL（例如 https://api.openai.com/v1）" to "Base URL (e.g. https://api.openai.com/v1)",
        "API Key（只存本机）" to "API key (stored only on this device)",
        "模型（例如 tts-1 / FunAudioLLM/CosyVoice2-0.5B）" to "Model (e.g. tts-1 / FunAudioLLM/CosyVoice2-0.5B)",
        "音色（例如 alloy / 中文女）" to "Voice (e.g. alloy)",
        "格式" to "Format",
        "语速" to "Speed",
        "协议：POST {Base URL}/audio/speech，Body 为 model / input / voice / response_format / speed。" to
            "Protocol: POST {Base URL}/audio/speech with model / input / voice / response_format / speed.",
        "测试" to "Test",
        "测试中…" to "Testing…",
        "合成并播放成功" to "Synthesized and played",
        "失败：%s" to "Failed: %s",
        "先填一个名称" to "Enter a name first",

        "语音引擎不可用：%s" to "Speech engine unavailable: %s",
        "打开语音设置" to "Open speech settings",
        "试听" to "Preview voice",
        "语音引擎已就绪" to "Speech engine ready",
        "正在初始化语音引擎…" to "Starting the speech engine…",
        "开启朗读或点「试听」时才会初始化语音引擎。" to
            "The speech engine starts only when you enable reading or tap Preview voice.",
        "你好，我是你的伙伴，今天想聊点什么？" to "Hi, I'm here for you. What would you like to talk about?",
        "朗读" to "Read aloud",
        "停止" to "Stop",

        // ---- 角色设定（分层）----
        "角色设定（仅 LLM 区）" to "Character settings (LLM chat only)",
        "清空" to "Clear",
        "清空角色设定？" to "Clear the character settings?",
        "会清空所有分层设定（立绘不受影响），用于重新开始。" to
            "This clears every layer (the portrait is kept) so you can start over.",
        "已清空角色设定" to "Character settings cleared",
        "分层填写更容易稳定。文字写得越具体，角色越不容易跑偏；上传一张立绘后 AI 对角色的把握最强，不传图也能只靠文字。" to
            "Filling the layers makes the character stable. The more specific the text, the less the model drifts; uploading a portrait gives the strongest grasp of the character, and text alone also works.",
        "核心身份" to "Core identity",
        "性格与表达" to "Personality & expression",
        "关系与背景" to "Relationship & background",
        "边界与底线" to "Boundaries",
        "补充" to "Extra",
        "收起" to "Collapse",
        "展开" to "Expand",
        "保存设定" to "Save settings",
        "已保存角色设定" to "Character settings saved",
        "AI 补全设定" to "AI complete settings",
        "补全中…" to "Completing…",
        "补全失败" to "Completion failed",
        "上传图片分析" to "Analyze a picture",
        "分析中…" to "Analyzing…",
        "图片分析失败" to "Image analysis failed",
        "没有读到图片" to "Could not read the picture",
        "上传立绘" to "Upload portrait",
        "更换立绘" to "Replace portrait",
        "移除立绘" to "Remove portrait",
        "立绘已保存；它会让 AI 对角色的把握更强" to "Portrait saved; it strengthens the AI's grasp of the character",
        "上传立绘后，它会作为「你的样子」注入给她；如果图片和文字设定冲突，以文字为准。" to
            "After uploading, the portrait is injected as “how you look”; if it conflicts with the text, the text wins.",
        "正在让 AI 先考虑情感需求、事实定义与用户安全，再动笔…" to
            "Asking the AI to consider emotional needs, factual consistency and user safety before writing…",
        "镜子模式生效时会覆盖上面的自定义设定，改用内置人设。" to
            "Mirror mode overrides the custom settings above and uses the built-in persona.",
        "图片分析结果" to "Picture analysis",
        "AI 补全结果" to "AI completion",
        "没有空缺字段可补全；已有设定不会被覆盖。" to
            "No empty fields to fill; existing settings are never overwritten.",
        "只会补写下面这些为空的字段，已有内容不动：" to
            "Only these empty fields will be filled; existing text stays unchanged:",
        "已补全 %d 项设定" to "Completed %d setting|Completed %d settings",
        "应用到设定" to "Apply to settings",
        "应用并设为立绘" to "Apply and use as portrait",
        "已应用，并把这张图设为立绘" to "Applied, and this picture is now the portrait",
        "模型没有返回可用的设定，请重试" to "The model returned no usable settings; please try again",
        "名字 / 称呼" to "Name / how to address",
        "身份 / 物种 / 年龄感" to "Identity / species / age",
        "外貌" to "Appearance",
        "性格" to "Personality",
        "说话风格 / 口癖" to "Speaking style / verbal tics",
        "情感表达" to "Emotional expression",
        "与用户的关系" to "Relationship with the user",
        "来历 / 背景" to "Background",
        "边界与底线（必须遵守）" to "Boundaries (must follow)",
    )
}
