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
        "新对话" to "New chat",
        "会话" to "Session",
        "图片" to "Image",
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
        "说出你的问题" to "Say your question",
    "暂不支持这种文件，试试图片、PDF、docx 或文本" to
        "This file type is not supported yet. Try an image, PDF, docx or plain text.",
    "打开系统语音设置" to "Open system speech settings",
    "打不开文件选择器，请用系统文件管理器分享到 DLA" to
        "Could not open the file picker. Try sharing the file to DLA from your file manager.",
    "文档" to "Document",
    "文本" to "Text",
    "本机没有可用的语音识别服务，请安装或在键盘上用语音输入" to
        "No speech recognition service on this device. Install one, or use the keyboard's voice input.",
    "语音识别启动失败，请稍后重试" to "Could not start speech recognition. Please try again.",

        // ---- 设置：对话 / 人设 ----
        "设置" to "Settings",
        "模型接口" to "Model API",
        "Base URL（留空用默认）" to "Base URL (leave blank for default)",
        "模型（留空用默认）" to "Model (leave blank for default)",
        "保存" to "Save",
        "测试中…" to "Testing…",
        "拉取并测试连接" to "Fetch models & test",
        "已保存" to "Saved",
        "界面" to "Interface",
        "当前使用原生界面。网页界面保留为兼容外壳，随时可以打开。" to
            "You are using the native UI. The web UI is kept as a compatibility shell and can be opened at any time.",
        "打开网页界面" to "Open web UI",
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
        "镜子模式（未内置人设）" to "Mirror mode (no built-in persona)",
        "人设与立绘只在 LLM 区生效；开启镜子模式会覆盖自定义人设。" to
            "Persona and portrait apply to LLM chat only; mirror mode overrides a custom persona.",
        "开源版不内置人设与立绘：镜子模式开关保留，但需要自行放入 assets/mirror_persona.txt 与 assets/mirror_portrait.webp 才会生效。" to
            "This open-source build ships no built-in persona or portrait. The mirror-mode switch is kept, but it only takes effect after you provide assets/mirror_persona.txt and assets/mirror_portrait.webp.",
        "更多" to "More",
        "MCP / Termux / 数据管理" to "MCP / Termux / Data",
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

        // ---- 高级设置：Termux ----
        "Termux 环境" to "Termux environment",
        "需要 Termux 里 allow-external-apps=true 并给 DLA 授予 Run commands 权限。" to
            "Requires allow-external-apps=true in Termux and the Run commands permission granted to DLA.",
        "Termux：%s" to "Termux: %s",
        "已安装" to "Installed",
        "未安装" to "Not installed",
        "版本：%s" to "Version: %s",
        "Run commands 权限：%s" to "Run commands permission: %s",
        "已授予" to "Granted",
        "未授予" to "Not granted",
        "打开 Termux" to "Open Termux",
        "应用权限" to "App permissions",
        "测试连通" to "Test connection",
        "复制授权命令" to "Copy grant command",
        "已复制" to "Copied",
        "Termux 授权命令" to "Termux permission command",

        // ---- 高级设置：数据 ----
        "数据" to "Data",
        "会话数：统计中…" to "Sessions: counting…",
        "会话数：%d" to "Sessions: %d",
        "网页留下的历史会在原生会话仓库初始化时自动导入一次，只补缺失，无需手动导入。" to
            "History left by the web UI is imported once when the native session store initializes. Only missing sessions are added, so no manual import is needed.",
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

        // ---- 高级设置：Shizuku ----
        "手机能力（Shizuku）" to "Device capabilities (Shizuku)",
        "Shizuku 提供 uid 2000 的 shell，用于手机工具；和 Termux 二选一即可运行 Agent。" to
            "Shizuku provides a uid 2000 shell for device tools; either Shizuku or Termux is enough to run the Agent.",
        "状态：未运行（手机重启后需要重新启动 Shizuku）" to
            "Status: not running (start Shizuku again after a phone reboot)",
        "状态：已运行，未授权" to "Status: running, not authorized",
        "状态：已就绪" to "Status: ready",
        "打开 Shizuku" to "Open Shizuku",
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
    )
}
