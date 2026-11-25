package com.example.chatdemo.nativeui

import android.util.Log
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生 Compose 界面（唯一入口）：会话、发送、Agent 审批、Markdown 渲染、设置。
 *
 * WebView 只作为「隔离的 Markdown 渲染器」存在（见 Markdown.kt），
 * 没有任何网页应用入口。
 */
class NativeShellActivity : ComponentActivity() {

    companion object {
        /** 主进程当前活着的界面；网页截图要挂到它的窗口里渲染才有画面 */
        @Volatile
        var instance: NativeShellActivity? = null
            private set
    }

    /** 通知点进来要打开的会话 id（Activity 已在前台时也能收到） */
    private val sessionRequest = MutableStateFlow<String?>(null)

    private fun consumeSessionIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(com.example.chatdemo.agent.AgentService.EXTRA_SESSION)
        if (!id.isNullOrBlank()) {
            Log.d("DlaAgent", "open session from intent: $id")
            sessionRequest.value = id
            // 同一个 Intent 只消费一次，避免 onResume 反复把用户拽回那个会话
            intent?.removeExtra(com.example.chatdemo.agent.AgentService.EXTRA_SESSION)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSessionIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // 回到前台重新挂上 Agent 监听器（系统恢复任务时也能收到事件）
        ChatEngine.attachListener()
        // 后台期间 Agent 可能已经跑完：把 :agent 留下的运行日志认领回来
        runCatching { ChatEngine.recoverRuns() }
        // 有些机型点通知只把界面拉到前台、不走 onNewIntent：这里补一次，且幂等
        consumeSessionIntent(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 语言必须在任何 UI 之前定下来，保证首次绘制就是正确语言；
        // 切换语言时本 Activity 会被 recreate，这里会重新读一遍偏好。
        Lang.init(this)
        enableEdgeToEdge()
        ChatEngine.attach(this)
        consumeSessionIntent(intent)

        setContent {
            var themeMode by remember { mutableStateOf(ChatEngine.themeMode) }
            val dark = when (themeMode) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
                window.setBackgroundDrawable(
                    ColorDrawable(if (dark) 0xFF0A1312.toInt() else 0xFFF4F7F6.toInt())
                )
            }
            DlaTheme(themeMode) {
                NativeApp(
                    themeMode = themeMode,
                    onThemeMode = {
                        themeMode = it
                        ChatEngine.themeMode = it
                    },
                    sessionRequests = sessionRequest,
                )
            }
        }
    }

    /**
     * 在主进程当前窗口里挂一个离屏 WebView，把本地 HTML 渲染成截图。
     *
     * 为什么不在 :agent 里做：没有挂到窗口上的 WebView 不会真正渲染，draw 出来是纯黑。
     * 这里把它 attach 到正在显示的 Activity（放到窗口外，用户看不见），截完立即移除，
     * 结果通过广播回给 :agent。不弹新界面，也不会把别的应用抢到前台。
     */
    fun renderWebShot(
        requestId: String,
        path: String,
        widthPx: Int,
        heightPx: Int,
        fullPage: Boolean,
        waitMs: Int,
        maxHeightPx: Int,
    ) {
        val appContext = applicationContext

        fun reply(ok: Boolean, filePath: String?, error: String?) {
            WebShotReply.send(appContext, requestId, ok, filePath, error)
        }

        val file = File(path)
        if (!file.isFile) {
            reply(false, null, "HTML 文件不存在：$path")
            return
        }
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (root == null) {
            reply(false, null, "找不到界面容器")
            return
        }

        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        var web: WebView? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun finish(capture: Boolean) {
            if (!settled.compareAndSet(false, true)) return
            val view = web ?: return
            try {
                if (!capture) {
                    reply(false, null, "页面加载失败或没有渲染出内容")
                    return
                }
                var height = heightPx
                if (fullPage) {
                    val content = view.contentHeight
                    if (content > 0) {
                        val scaled = Math.round(content * resources.displayMetrics.density)
                        height = Math.max(heightPx, Math.min(scaled, maxHeightPx))
                    }
                }
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, widthPx, height)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                if (isNearlyBlank(bitmap)) {
                    bitmap.recycle()
                    reply(false, null, "页面几乎是空白：可能是动画还没开始、文件内容为空，或页面依赖网络资源")
                    return
                }
                val dir = File("/sdcard/Download/DLA-web-shots")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, "shot-" + System.currentTimeMillis() + ".jpg")
                java.io.FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                reply(true, target.absolutePath, null)
            } catch (error: Throwable) {
                reply(false, null, "截图失败：" + error)
            } finally {
                runCatching { root.removeView(view) }
                runCatching { view.destroy() }
                web = null
            }
        }

        try {
            val view = WebView(this)
            web = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                blockNetworkLoads = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
            }
            view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            view.setBackgroundColor(0xFF05060F.toInt())
            // 放到窗口外：attached 但用户看不见，WebView 仍然会真正渲染
            view.translationX = -20000f
            root.addView(view, android.view.ViewGroup.LayoutParams(widthPx, heightPx))
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) {
                    handler.postDelayed({
                        // 离屏渲染时 CSS 动画的时钟不一定推进，先把动画/过渡"快进"到终态再截
                        runCatching {
                            v.evaluateJavascript(
                                "(function(){try{var s=document.createElement('style');" +
                                    "s.textContent='*,*::before,*::after{animation:none !important;transition:none !important}';" +
                                    "if(document.head){document.head.appendChild(s);}}catch(e){}})()",
                                null,
                            )
                        }
                        handler.postDelayed({ finish(true) }, 180)
                    }, waitMs.toLong())
                }

                override fun onReceivedError(
                    v: WebView,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request != null && !request.isForMainFrame) return
                    finish(false)
                }
            }
            view.loadUrl(file.toURI().toString())
            // 兜底：页面脚本卡住时也要给模型一张当前画面
            handler.postDelayed({ finish(true) }, maxOf(4000, waitMs + 3000).toLong())
        } catch (error: Throwable) {
            reply(false, null, "WebView 初始化失败：" + error)
            web?.let { view ->
                runCatching { root.removeView(view) }
                runCatching { view.destroy() }
            }
            web = null
        }
    }

    /** 判断截图是否几乎单色（空白页），避免把纯黑图当成成功结果交给模型 */
    private fun isNearlyBlank(bitmap: Bitmap): Boolean {
        val step = 37
        val first = bitmap.getPixel(0, 0)
        var different = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != first) different++
                y += step
            }
            x += step
        }
        return different < 3
    }
}

/** 把页面编码成偏好里的值；空串表示首页 */
private fun screenKey(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    Screen.Settings -> "settings"
    Screen.Advanced -> "advanced"
    is Screen.Chat -> "chat:" + screen.id
    is Screen.Zone -> "zone:" + screen.mode
}

/** 反序列化；解析不出来就返回 null（栈里的坏元素会被丢掉） */
private fun screenFromKey(key: String): Screen? = when {
    key == "home" -> Screen.Home
    key == "settings" -> Screen.Settings
    key == "advanced" -> Screen.Advanced
    key.startsWith("zone:") -> Screen.Zone(key.removePrefix("zone:"))
    key.startsWith("chat:") -> key.removePrefix("chat:").takeIf { it.isNotBlank() }?.let { Screen.Chat(it) }
    else -> null
}

/** 恢复上次停留的页面：会话还在就回到聊天，否则回对应分区或首页 */
private fun restoreScreen(): Screen {
    val raw = ChatEngine.lastScreen
    return when {
        raw.startsWith("chat:") -> {
            val id = raw.removePrefix("chat:")
            if (ChatEngine.sessionExists(id)) {
                ChatEngine.open(id)
                Screen.Chat(id)
            } else Screen.Home
        }
        raw.startsWith("zone:") -> Screen.Zone(raw.removePrefix("zone:"))
        else -> Screen.Home
    }
}

private sealed interface Screen {
    /** 首页：只有两个分区入口，不再混排会话记录 */
    data object Home : Screen

    /** 分区页：LLM / Agent 各自的会话列表，互不混排 */
    data class Zone(val mode: String) : Screen

    data class Chat(val id: String) : Screen
    data object Settings : Screen
    data object Advanced : Screen
}

/**
 * 页面栈（不是单个页面）。
 *
 * 之前只存「当前是哪一页」，返回只能一刀切回首页——从会话页进设置，返回就丢了会话。
 * 现在把整条路径存下来：[首页 → 分区 → 会话 → 设置 → 高级设置]，
 * 返回就是弹一层，进设置时也不再关掉会话。
 *
 * 冷启动恢复时只有一层（聊天或分区），所以「返回」还要有兜底：
 * - 直接进会话的，返回补一个同 mode 的分区当上一级；
 * - 直接进分区的，返回回首页。
 */
private val StackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { screenKey(it) } },
    restore = { keys ->
        keys.mapNotNull { screenFromKey(it) }.ifEmpty { listOf(Screen.Home) }
    },
)

@Composable
private fun NativeApp(
    themeMode: String,
    onThemeMode: (String) -> Unit,
    sessionRequests: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
) {
    // 冷启动时恢复上次停留的页面（会话/分区），不必重新点一遍
    var stack by rememberSaveable(stateSaver = StackSaver) {
        mutableStateOf(listOf(restoreScreen()))
    }
    val screen = stack.last()
    val sessions by ChatEngine.sessions.collectAsState()
    val open by ChatEngine.open.collectAsState()
    val context = LocalContext.current

    fun push(next: Screen) {
        if (stack.last() == next) return
        stack = stack + next
    }

    /** 返回上一级：会话页要顺手关掉会话；栈底兜底到分区/首页 */
    fun goBack() {
        val current = stack.last()
        if (current is Screen.Chat) {
            val mode = ChatEngine.open.value?.mode ?: "agent"
            ChatEngine.close()
            stack = if (stack.size >= 2 && stack[stack.size - 2] is Screen.Zone) {
                stack.dropLast(1)
            } else {
                listOf(Screen.Zone(mode))
            }
            return
        }
        if (stack.size >= 2) {
            stack = stack.dropLast(1)
            return
        }
        // 冷启动直接落在分区页：返回回首页
        stack = listOf(Screen.Home)
    }

    val canGoBack = stack.size > 1 || screen is Screen.Chat

    // 点通知进来：直接落到那条任务所在的会话（上一级补成分区）
    val requestedSession by (sessionRequests ?: remember { MutableStateFlow<String?>(null) })
        .collectAsState()
    LaunchedEffect(requestedSession) {
        val id = requestedSession ?: return@LaunchedEffect
        Log.d("DlaAgent", "session request: $id exists=${ChatEngine.sessionExists(id)}")
        if (ChatEngine.sessionExists(id)) {
            ChatEngine.open(id)
            val mode = ChatEngine.open.value?.mode ?: "agent"
            stack = listOf(Screen.Zone(mode), Screen.Chat(id))
        }
        sessionRequests?.value = null
    }

    // 通知权限：Android 13+ 第一次使用时就问一次；之后在设置页里可以再授权
    var notifGranted by remember { mutableStateOf(ChatEngine.canPostNotifications()) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
    }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !ChatEngine.canPostNotifications()) {
            val prefs = context.getSharedPreferences("dla-native", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifAsked", false)) {
                prefs.edit().putBoolean("notifAsked", true).apply()
                runCatching {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    LaunchedEffect(stack) {
        if (screen is Screen.Home || screen is Screen.Zone) ChatEngine.refresh()
        // 只记「基础页」：设置/高级设置是弹层，重启后应该回到它下面的那一页
        val base = stack.lastOrNull { it !is Screen.Settings && it !is Screen.Advanced } ?: Screen.Home
        ChatEngine.lastScreen = screenKey(base)
    }

    BackHandler(enabled = canGoBack) { goBack() }

    // 点击处冒出破碎的小方块（只观察指针、不抢点击），配合 SoundFeedback 的短促点击音
    ShatterHost(colors = listOf(DlaColors.Mint, DlaColors.Warm, DlaColors.Text)) {
        Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { current ->
            when (current) {
                is Screen.Home -> HomeScreen(
                    sessions = sessions,
                    onOpenZone = { mode -> push(Screen.Zone(mode)) },
                    onSettings = { push(Screen.Settings) },
                )
                is Screen.Zone -> ZoneScreen(
                    mode = current.mode,
                    sessions = sessions.filter { it.mode == current.mode },
                    onBack = { goBack() },
                    onSettings = { push(Screen.Settings) },
                    onOpen = { id ->
                        ChatEngine.open(id)
                        push(Screen.Chat(id))
                    },
                    onDelete = { ChatEngine.delete(it) },
                    onNew = {
                        ChatEngine.create(current.mode)
                        ChatEngine.open.value?.id?.let { push(Screen.Chat(it)) }
                    },
                )
                is Screen.Chat -> ChatScreen(
                    onBack = { goBack() },
                    onSettings = { push(Screen.Settings) },
                )
                is Screen.Settings -> SettingsScreen(
                    // 从会话页进设置：返回要回到会话，不能关掉会话、更不能弹回首页
                    onBack = { goBack() },
                    onAdvanced = { push(Screen.Advanced) },
                    themeMode = themeMode,
                    onThemeMode = onThemeMode,
                )
                is Screen.Advanced -> AdvancedSettingsScreen(
                    onBack = { goBack() },
                )
            }
        }
    }

    // 审批弹窗：Agent 调用危险工具时出现
    val approval = open?.approval
    LaunchedEffect(approval?.toolId) {
        android.util.Log.d("DlaAgent", "dialog approval=$approval")
    }
    if (approval != null) {
        AlertDialog(
            onDismissRequest = { ChatEngine.approve(approval.toolId, false) },
            title = { Text(Lang.t("需要确认")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Lang.t("Agent 想执行：%s", approval.name), fontWeight = FontWeight.Medium)
                    if (approval.input.isNotBlank()) {
                        Text(
                            approval.input.take(600),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = DlaColors.TextDim,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ChatEngine.approve(approval.toolId, true) }) {
                    Text(Lang.t("允许"), color = DlaColors.Mint)
                }
            },
            dismissButton = {
                TextButton(onClick = { ChatEngine.approve(approval.toolId, false) }) {
                    Text(Lang.t("拒绝"), color = DlaColors.TextDim)
                }
            },
            containerColor = DlaColors.SurfaceHigh,
            titleContentColor = DlaColors.Text,
            textContentColor = DlaColors.Text,
        )
    }
}


// ---------------- 首页视觉 ----------------

@Composable
private fun HomeHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // v2「溟灯」：分层光效（径向渐变呼吸 + 立绘漂浮 + 旋转光晕 + 漂移光斑），
        // 实现在 MascotView.kt；动画只走 graphicsLayer / 绘制层，不参与测量。
        MoonBeacon()
        Text(
            Lang.t("今天想让我做什么？"),
            color = DlaColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            Lang.t("本地运行 · 模型来自 DeepSeek · 数据不出手机"),
            color = DlaColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- 列表 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    sessions: List<SessionCard>,
    onOpenZone: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        containerColor = DlaColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DLA", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        Text(Lang.t("本地智能体 · 原生界面"), color = DlaColors.TextDim, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Text("⚙", fontSize = 18.sp, color = DlaColors.TextDim) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DlaColors.Background,
                    titleContentColor = DlaColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            HomeHero()
            // 首页只保留两个分区入口：会话记录已经收进各自分区，不再混排在这里
            Spacer(Modifier.weight(0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZoneCard(
                    mode = "llm",
                    title = Lang.t("LLM 对话"),
                    subtitle = Lang.t("纯聊天与检索"),
                    count = sessions.count { it.mode == "llm" },
                    modifier = Modifier.weight(1f),
                ) { onOpenZone("llm") }
                ZoneCard(
                    mode = "agent",
                    title = Lang.t("Agent 任务"),
                    subtitle = Lang.t("调用工具执行"),
                    count = sessions.count { it.mode == "agent" },
                    modifier = Modifier.weight(1f),
                ) { onOpenZone("agent") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                Lang.t("两个分区各自保存会话，互不混排"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 分区入口卡片：颜色由分区决定（Agent = 绿，LLM = 暖色） */
@Composable
private fun ZoneCard(
    mode: String,
    title: String,
    subtitle: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = modeAccent(mode)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .scale(if (pressed) 0.97f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .androidClickable(interaction, onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = DlaColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Lang.t("%d 个会话", count),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("→", color = accent, fontSize = 14.sp)
        }
    }
}

/** 分区页：某个模式的会话列表（LLM 与 Agent 相互隔离） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneScreen(
    mode: String,
    sessions: List<SessionCard>,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
) {
    val accent = modeAccent(mode)
    val title = if (mode == "agent") Lang.t("Agent 任务") else Lang.t("LLM 对话")
    val subtitle = if (mode == "agent") Lang.t("调用工具执行") else Lang.t("纯聊天与检索")
    Scaffold(
        containerColor = DlaColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            subtitle + " · " + Lang.t("%d 个会话", sessions.size),
                            color = DlaColors.TextDim,
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", color = accent, fontSize = 19.sp) }
                },
                actions = {
                    IconButton(onClick = onSettings) { Text("⚙", fontSize = 18.sp, color = DlaColors.TextDim) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DlaColors.Background,
                    titleContentColor = DlaColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .androidClickableSimple(onNew)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    Lang.t("新对话"),
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(Lang.t("还没有会话"), color = DlaColors.Text, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(Lang.t("点上面「新对话」开始"), color = DlaColors.TextDim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { card ->
                        SessionRow(card, onOpen = { onOpen(card.id) }, onDelete = { onDelete(card.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(card: SessionCard, onOpen: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (pressed) 0.985f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(DlaColors.Surface)
            .androidClickable(interaction, onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip(card.mode)
            Spacer(Modifier.width(8.dp))
            Text(
                displayTitle(card.title),
                color = DlaColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Text("×", color = DlaColors.TextDim, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(
                        if (card.messageCount == 0 && card.hasDraft) {
                            Lang.t("草稿")
                        } else {
                            Lang.t("%d 条", card.messageCount)
                        },
                    )
                    if (card.steps > 0) {
                        append(" · ")
                        append(Lang.t("%d 轮", card.rounds))
                        append(" ")
                        append(Lang.t("%d 步", card.steps))
                    }
                },
                color = DlaColors.TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(timeAgo(card.updatedAt), color = DlaColors.TextDim, fontSize = 11.sp)
        }
    }
}

/** 分区主色：Agent 用主色薄荷绿；LLM 用暖色，两个区一眼能分开 */
@Composable
private fun modeAccent(mode: String?): Color =
    if (mode == "agent") DlaColors.Mint else DlaColors.Warm

@Composable
private fun ModeChip(mode: String) {
    val accent = modeAccent(mode)
    Text(
        text = if (mode == "agent") "AGENT" else "LLM",
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// ---------------- 会话 ----------------

// @OptIn 与 @Composable 属于下面的 ChatScreen，接口这里不能挂

/** 同一轮的「思考卡 + 这一轮的工具卡」：按轮交错，能一眼看出因果 */
private data class RoundGroup(val round: Int, val blocks: List<Block>)

/** 聊天列表的一行：消息拆成用户行 / 迭代树行 / 内容块行，便于按轮精确定位 */
private sealed interface ChatRow {
    val key: String

    data class User(val message: ChatMessage) : ChatRow {
        override val key: String = "${message.id}:u"
    }

    data class Tree(val message: ChatMessage, val rounds: List<IterationRound>) : ChatRow {
        override val key: String = "${message.id}:t"
    }

    data class BlockRow(val message: ChatMessage, val block: Block, val index: Int) : ChatRow {
        override val key: String = "${message.id}:b$index"
    }

    /**
     * 所有轮收成一张可折叠的「执行过程」大卡片：运行中默认展开，任务结束自动折叠。
     * 以前每轮一张小卡，长任务里会有几十张卡把正文推得很远。
     */
    data class Rounds(
        val message: ChatMessage,
        val groups: List<RoundGroup>,
        val totalMs: Long,
    ) : ChatRow {
        override val key: String = "${message.id}:rounds"
    }
}

private fun buildRows(messages: List<ChatMessage>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size * 2)
    messages.forEach { message ->
        if (message.role == "user") {
            rows += ChatRow.User(message)
            return@forEach
        }
        val rounds = buildRounds(message.blocks)
        if (rounds.isNotEmpty()) rows += ChatRow.Tree(message, rounds)
        // 思考与工具按轮收进同一张大卡，放在迭代树下面
        val groups = collectRoundGroups(message.blocks)
        if (groups.isNotEmpty()) {
            rows += ChatRow.Rounds(message, groups, message.elapsedMs)
        }
        // 正文块与「没有轮次」的旧思考/工具块单独成行（正常顺序里正文都在执行过程之后）
        message.blocks.forEachIndexed { index, block ->
            val blockRound = when (block) {
                is Block.Thinking -> block.round
                is Block.Tool -> block.round
                else -> 0
            }
            if (blockRound > 0) return@forEachIndexed
            rows += ChatRow.BlockRow(message, block, index)
        }
    }
    return rows
}

/** 把相邻、同轮的思考/工具块收成一组；正文块不参与 */
private fun collectRoundGroups(blocks: List<Block>): List<RoundGroup> {
    val groups = ArrayList<RoundGroup>()
    var index = 0
    while (index < blocks.size) {
        val blockRound = when (val block = blocks[index]) {
            is Block.Thinking -> block.round
            is Block.Tool -> block.round
            else -> 0
        }
        if (blockRound <= 0) {
            index++
            continue
        }
        val group = ArrayList<Block>()
        while (index < blocks.size) {
            val candidate = blocks[index]
            val candidateRound = when (candidate) {
                is Block.Thinking -> candidate.round
                is Block.Tool -> candidate.round
                else -> -1
            }
            if (candidateRound != blockRound) break
            group += candidate
            index++
        }
        groups += RoundGroup(blockRound, group)
    }
    return groups
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(onBack: () -> Unit, onSettings: () -> Unit) {
    val open by ChatEngine.open.collectAsState()
    val current = open
    // 打开会话时恢复输入框草稿：空会话靠它保留全貌
    var input by remember(current?.id) { mutableStateOf(current?.draftInput.orEmpty()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingAttachments by remember(current?.id) { mutableStateOf<List<Attachment>>(emptyList()) }
    // 长按文本弹出的复制面板 / 点图后的非全覆盖预览
    var copyTarget by remember(current?.id) { mutableStateOf<String?>(null) }
    // LLM 区「编辑上一条」：只对最后一条用户消息开放
    var editTarget by remember(current?.id) { mutableStateOf<ChatMessage?>(null) }
    var previewTarget by remember(current?.id) { mutableStateOf<String?>(null) }
    // Agent 状态条的「本轮」计时：运行中每 0.5 秒刷新，结束后停在最终值
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current?.streaming) {
        if (current?.streaming == true) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    // 图片：走系统照片选择器，单点直接进相册，不出现 SAF 的「安全访问」界面
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val data = Attachments.dataUrl(context, uri)
            if (data != null) {
                pendingAttachments = pendingAttachments +
                    Attachment(
                        kind = Documents.KIND_IMAGE,
                        name = Documents.fileName(context, uri),
                        dataUrl = data,
                    )
            }
        }
    }
    // 兜底：只有「所有文件访问权限」没给时，授权卡片里才会引导到这两个系统选择器
    val systemFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val forAgent = ChatEngine.open.value?.mode == "agent"
            val extracted = Documents.extract(context, uri, forAgent = forAgent)
            if (extracted.isEmpty()) {
                Toast.makeText(
                    context,
                    Lang.t("暂不支持这种文件，试试图片、PDF、docx 或文本"),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                pendingAttachments = pendingAttachments + extracted
            }
        }
    }
    val systemFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {
            if (ChatEngine.open.value?.mode != "agent") {
                Toast.makeText(
                    context,
                    Lang.t("文件夹批量导入仅 Agent 区可用"),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                SystemFiles.persist(context, tree)
                val imported = runCatching { SystemFiles.importFolder(context, tree) }.getOrDefault(emptyList())
                if (imported.isEmpty()) {
                    Toast.makeText(context, Lang.t("这个文件夹里没有可导入的文件"), Toast.LENGTH_LONG).show()
                } else {
                    pendingAttachments = pendingAttachments + imported
                }
            }
        }
    }
    // 📂 本机文件浏览（替代原来的 SAF 文档/文件夹按钮）
    var showBrowser by remember(current?.id) { mutableStateOf(false) }

    val messages = current?.messages.orEmpty()
    val lastUserId = remember(messages) { messages.lastOrNull { it.role == "user" }?.id }
    val rows = remember(messages) { buildRows(messages) }
    val reversed = remember(rows) { rows.asReversed() }

    // 反向列表：index 0 就是底部。新消息或流式开始时回到 0 即可保持贴底
    LaunchedEffect(messages.size, current?.streaming) {
        runCatching { listState.animateScrollToItem(0) }
    }

    Scaffold(
        containerColor = DlaColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            current?.let { displayTitle(it.title) } ?: Lang.t("会话"),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (current?.mode == "agent") "Agent" else "LLM")
                                if (current?.streaming == true) append(" · ").append(Lang.t("生成中"))
                            },
                            color = modeAccent(current?.mode),
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", color = modeAccent(current?.mode), fontSize = 19.sp) }
                },
                actions = {
                    IconButton(onClick = onSettings) { Text("⚙", fontSize = 17.sp, color = DlaColors.TextDim) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DlaColors.Background,
                    titleContentColor = DlaColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            if (current?.mode != "agent") {
                QuickToggles(cachePercent = current?.usageTotals?.cachePercent)
            }
            LaunchedEffect(current?.streaming) {
                while (current?.streaming == true) {
                    delay(1500)
                    ChatEngine.reconcileIfStuck()
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // 空会话：把「极淡版」溟灯垫在消息列表下层（先画背景，再画 LazyColumn）。
                // 光效没有任何 pointerInput，不参与命中测试，滚动/点击仍由上层列表处理。
                if (messages.isEmpty() && current?.streaming != true) {
                    MoonBeaconBackdrop(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 40.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (current?.streaming == true) {
                        item(key = "draft") {
                            DraftMessage(current)
                        }
                    }
                    items(reversed, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.User -> UserMessageRow(
                                row.message,
                                onCopy = { copyTarget = it },
                                onPreview = { previewTarget = it },
                                editable = current?.mode == "llm" &&
                                    current?.streaming != true &&
                                    row.message.id == lastUserId,
                                onEdit = { editTarget = row.message },
                            )
                            is ChatRow.Tree -> IterationTree(
                                rounds = row.rounds,
                                // 折叠头的总耗时用「这一轮从开始到结束」的实际耗时；
                                // 老数据没有这个字段就退回按工具耗时求和
                                totalMs = row.message.elapsedMs,
                                modifier = Modifier.fillMaxWidth(),
                                onLocate = { round ->
                                    // 所有轮都在同一张执行过程卡里，定位到这张卡即可
                                    val target = reversed.indexOfFirst {
                                        it is ChatRow.Rounds &&
                                            it.message.id == row.message.id &&
                                            it.groups.any { group -> group.round == round }
                                    }
                                    if (target >= 0) {
                                        scope.launch { listState.animateScrollToItem(target) }
                                    }
                                },
                            )
                            is ChatRow.Rounds -> AgentProcessCard(
                                groups = row.groups,
                                stateKey = row.message.id,
                                streaming = false,
                                totalMs = row.totalMs,
                                messageId = row.message.id,
                                onCopy = { copyTarget = it },
                            )
                            is ChatRow.BlockRow -> BlockRowView(
                                row.block,
                                messageId = row.message.id,
                                onCopy = { copyTarget = it },
                            )
                        }
                    }
                }
            }

            current?.error?.let { error ->
                Text(
                    error,
                    color = DlaColors.Error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // 只在 Agent 区：上下文占用（十段虚线）+ 本轮总耗时（只记这一轮，不累计）
            if (current != null && current.mode == "agent") {
                AgentUsageBar(
                    usedTokens = current.contextTokens,
                    windowTokens = current.contextWindow,
                    cachePercent = current.usageTotals?.cachePercent,
                    turnMs = if (current.streaming && current.startedAt > 0) {
                        (nowMs - current.startedAt).coerceAtLeast(0L)
                    } else {
                        current.lastTurnMs
                    },
                )
            }

            Composer(
                value = input,
                enabled = current != null,
                streaming = current?.streaming == true,
                attachments = pendingAttachments,
                onChange = {
                    input = it
                    ChatEngine.updateDraft(it)
                },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                        ChatEngine.send(text, pendingAttachments)
                        input = ""
                        pendingAttachments = emptyList()
                    }
                },
                onStop = { ChatEngine.stop() },
                onPreview = { previewTarget = it },
                onPickImage = {
                    runCatching {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
                onPickFiles = { showBrowser = true },
                onRemoveAttachment = { index ->
                    pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
                },
            )
        }
    }

    val editing = editTarget
    if (editing != null && current != null) {
        EditMessageDialog(
            initial = editing.text,
            onDismiss = { editTarget = null },
            onConfirm = { edited ->
                editTarget = null
                ChatEngine.editLastUserMessage(current.id, edited)
            },
        )
    }

    if (showBrowser) {
        BackHandler { showBrowser = false }
        FileBrowserPanel(
            forAgent = current?.mode == "agent",
            onDismiss = { showBrowser = false },
            onPicked = { picked ->
                showBrowser = false
                if (picked.isEmpty()) {
                    Toast.makeText(
                        context,
                        Lang.t("选中的文件里没有可用的内容"),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    pendingAttachments = pendingAttachments + picked
                }
            },
            onSystemFilePicker = {
                showBrowser = false
                runCatching { systemFilePicker.launch(arrayOf("*/*")) }
            },
            onSystemFolderPicker = {
                showBrowser = false
                runCatching { systemFolderPicker.launch(null) }
            },
        )
    }

    // 长按文本 → 复制面板；点图 → 非全覆盖预览
    copyTarget?.let { text ->
        CopyTextPanel(text = text, onDismiss = { copyTarget = null })
    }
    previewTarget?.let { dataUrl ->
        ImagePreviewOverlay(dataUrl = dataUrl, onDismiss = { previewTarget = null })
    }
}

/** 长按手势：不消费拖动，所以不会抢走列表的上下滑动 */
private fun Modifier.onLongPressText(action: () -> Unit): Modifier = this.pointerInput(action) {
    detectTapGestures(onLongPress = { action() })
}

/**
 * 复制面板：长按消息出现，而不是让整块屏幕进入选择态。
 *
 * - 「复制全部」一键复制整条内容；
 * - 「选择文本」在这里才把内容放进 SelectionContainer，可以只拖选一段；
 * - 点空白处/返回键退出，不影响聊天列表的滚动。
 */
@Composable
private fun CopyTextPanel(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = DlaColors.Surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    Lang.t("复制文本"),
                    color = DlaColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = text,
                        color = DlaColors.Text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(scroll),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Lang.t("长按文字可只选一段"),
                        color = DlaColors.TextDim,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(Lang.t("取消"), color = DlaColors.TextDim, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(DlaColors.Mint)
                            .clickable {
                                copyToClipboard(context, text)
                                Toast.makeText(context, Lang.t("已复制"), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        Text(Lang.t("复制全部"), color = DlaColors.OnAccent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 一键复制：正文下方的小按钮 */
@Composable
private fun CopyChip(text: String) {
    if (text.isBlank()) return
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                copyToClipboard(context, text)
                Toast.makeText(context, Lang.t("已复制"), Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⧉", color = DlaColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(Lang.t("复制"), color = DlaColors.TextDim, fontSize = 10.sp)
    }
}

/**
 * 朗读：点一下开始念这条正文，再点一下停止。
 *
 * 引擎没就绪时 Speaker 会先把文字排队，就绪后自动念；不可用时会在设置里给出原因。
 */
@Composable
private fun SpeakChip(id: String, text: String) {
    if (text.isBlank()) return
    val speaking by ChatEngine.speaking.collectAsState()
    val active = speaking == id
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { ChatEngine.toggleSpeak(id, text) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (active) "■" else "🔊", color = if (active) DlaColors.Mint else DlaColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            if (active) Lang.t("停止") else Lang.t("朗读"),
            color = if (active) DlaColors.Mint else DlaColors.TextDim,
            fontSize = 10.sp,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("DLA", text))
    }
}

/**
 * 图片预览：只占屏幕中央一块（宽 90%、高 72%），四周仍能看到聊天背景，
 * 点任意位置或按返回关闭。不是全屏看图器，避免「一点图就被铺满全屏」。
 */
@Composable
private fun ImagePreviewOverlay(dataUrl: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, dataUrl) {
        value = withContext(Dispatchers.IO) { Attachments.decode(dataUrl) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = DlaColors.Mint, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = Lang.t("图片预览"),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        Text(
            Lang.t("点按关闭"),
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
    }
}

@Composable
private fun UserMessageRow(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onPreview: (String) -> Unit,
    /** LLM 区：最后一条用户消息可以改完重答 */
    editable: Boolean = false,
    onEdit: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            message.attachments.forEach { attachment ->
                if (attachment.kind == Documents.KIND_IMAGE && attachment.dataUrl != null) {
                    AttachmentThumb(
                        dataUrl = attachment.dataUrl,
                        size = 168.dp,
                        onClick = { onPreview(attachment.dataUrl) },
                    )
                } else {
                    Text(
                        attachment.name ?: Lang.t("文档"),
                        color = DlaColors.Text,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(DlaColors.SurfaceHigh, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    color = DlaColors.Text,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .width(285.dp)
                        .background(DlaColors.Mint.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                        .onLongPressText { onCopy(message.text) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            if (editable) {
                Text(
                    Lang.t("编辑"),
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun BlockRowView(block: Block, messageId: String, onCopy: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        when (block) {
            is Block.Thinking -> ThinkingCard(block.text, block.seconds)
            is Block.Tool -> ToolCard(block)
            is Block.Text -> {
                // 普通 Markdown 走纯 Compose：首帧即可同步测高，LazyColumn 快速滑动
                // 回收/复用条目时不会重建 WebView，避免正文忽隐忽现和高度跳变。
                // 含公式（$...$ / $$...$$）或 ```mermaid 的文本仍交给 WebView，
                // 保留 KaTeX 排版与流程图渲染能力。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onLongPressText { onCopy(block.text) },
                ) {
                    if (needsRichRenderer(block.text)) {
                        MarkdownText(block.text)
                    } else {
                        NativeMarkdown(block.text)
                    }
                    if (block.text.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CopyChip(block.text)
                            Spacer(Modifier.width(2.dp))
                            SpeakChip("$messageId#${block.text.hashCode()}", block.text)
                        }
                    }
                }
            }
            is Block.Other -> Unit
        }
    }
}
/**
 * 「执行过程」大卡片：把每一轮的思考卡与该轮工具卡收进同一张可折叠卡片。
 *
 * - 运行中的草稿默认展开（实时看到在想什么、调了什么），任务结束自动折叠；
 * - 已经落盘的历史消息默认折叠，点一下能展开完整的思考与工具；
 * - 每轮内部仍是原来的思考卡 / 工具卡，但不再各自带一圈外框，避免「卡片套卡片」。
 */
@Composable
private fun AgentProcessCard(
    groups: List<RoundGroup>,
    stateKey: String,
    streaming: Boolean,
    totalMs: Long,
    messageId: String,
    onCopy: (String) -> Unit,
) {
    // stateKey 区分「这一次运行的草稿」与「某条历史消息」：换任务不会串状态
    var expanded by rememberSaveable(stateKey) { mutableStateOf(streaming) }
    val totalSteps = remember(groups) {
        groups.sumOf { group -> group.blocks.count { block -> block is Block.Tool } }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DlaColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, DlaColors.Mint.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .androidClickableSimple { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(DlaColors.Mint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⟳", color = DlaColors.Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(9.dp))
            Text(
                Lang.t("执行过程"),
                color = DlaColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                Lang.t("%d 轮", groups.size),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                Lang.t("%d 步", totalSteps),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            if (totalMs > 0) {
                Text(
                    formatElapsed(totalMs),
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (expanded) Lang.t("收起") else Lang.t("展开"),
                color = DlaColors.Mint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column {
                groups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(color = DlaColors.Line.copy(alpha = 0.7f))
                    }
                    RoundContent(
                        round = group.round,
                        blocks = group.blocks,
                        messageId = messageId,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

/** 大卡片里的一轮：标题「第 N 轮」+ 这一轮的思考/工具，不再单独包一层卡片 */
@Composable
private fun RoundContent(
    round: Int,
    blocks: List<Block>,
    messageId: String,
    onCopy: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DlaColors.Mint.copy(alpha = 0.65f)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                Lang.t("第 %d 轮", round),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        blocks.forEach { block ->
            BlockRowView(block, messageId = messageId, onCopy = onCopy)
        }
    }
}

@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t("修改这条消息")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text(Lang.t("保存并重新生成"), color = DlaColors.Mint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Lang.t("取消")) }
        },
    )
}

@Composable
private fun DraftMessage(open: ChatEngine.Open) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (open.draftStatus.isNotBlank()) {
            Text(
                open.draftStatus,
                color = DlaColors.Mint.copy(alpha = 0.85f),
                fontSize = 11.sp,
            )
        }
        // 流式时也按轮分块：每一轮的思考卡跟着这一轮的工具走，整轮收进同一张大卡
        val groups = remember(open.draftThinkingRounds, open.draftTools) {
            val byRound = java.util.TreeMap<Int, MutableList<Block>>()
            open.draftThinkingRounds.forEach { (round, section) ->
                if (section.isNotBlank()) {
                    byRound.getOrPut(round) { ArrayList() } += Block.Thinking(section, null, round)
                }
            }
            open.draftTools.forEach { tool ->
                byRound.getOrPut(tool.round) { ArrayList() } += tool
            }
            byRound.map { (round, blocks) -> RoundGroup(round, blocks.toList()) }
        }
        // round = 0 的是 LLM 区的单段思考，不参与「第 N 轮」分组
        groups.filter { it.round <= 0 }.forEach { group ->
            group.blocks.forEach { block -> BlockRowView(block, messageId = "draft", onCopy = {}) }
        }
        val roundGroups = groups.filter { it.round > 0 }
        // 迭代树与执行过程卡口径一致：有思考没工具的轮也算一轮
        val rounds = roundGroups.map { group -> iterationRound(group) }
        if (rounds.isNotEmpty()) {
            IterationTree(rounds)
        }
        if (roundGroups.isNotEmpty()) {
            AgentProcessCard(
                groups = roundGroups,
                // 每次运行的 startedAt 都不同：换任务时大卡重新展开
                stateKey = "draft-${open.startedAt}",
                streaming = true,
                totalMs = 0L,
                messageId = "draft",
                onCopy = {},
            )
        } else if (open.draftThinking.isNotBlank()) {
            ThinkingCard(open.draftThinking, null)
        }
        if (open.draftThinking.isBlank() && open.draftThinkingRounds.isEmpty() &&
            open.draftText.isBlank() && open.draftTools.isEmpty()
        ) {
            TypingDots()
        }
        if (open.draftText.isNotBlank()) {
            Text(
                text = open.draftText,
                color = DlaColors.Text,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (open.draftText.isBlank() && open.draftTools.isEmpty()) {
            TypingDots()
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = DlaColors.Mint.copy(alpha = alpha),
        )
        Text(Lang.t("正在生成"), color = DlaColors.TextDim.copy(alpha = alpha), fontSize = 12.sp)
    }
}

@Composable
private fun ThinkingCard(text: String, seconds: Double?) {
    // 不能以 text 做 key：流式时每个 token 都会重置展开状态，卡片会自己合拢
    var expanded by remember { mutableStateOf(false) }
    // 「思考中」= 还没有落定秒数，且流式仍在进行（busy 只在请求开始/结束时翻转，
    // 不会随每个 token 触发重组）。只有这段时间跑动画。
    val busy by ChatEngine.busy.collectAsState()
    val thinking = seconds == null && busy
    val titleAlpha = rememberThinkingTitleAlpha(thinking)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DlaColors.Surface)
            .androidClickableSimple { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .animateContentSize(tween(180)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1 Cue：思考中做 2.4s 呼吸 + 薄荷外发光，结束后立刻静止；
            // 头像固定占 30dp，动画只改图层，卡片高度不随动画变化。
            CueThinkingAvatar(thinking = thinking, size = 30.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                Lang.t("思考过程"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha.value },
            )
            seconds?.let { Text("  ${"%.1f".format(it)}s", color = DlaColors.TextDim, fontSize = 10.sp) }
            Spacer(Modifier.weight(1f))
            Text(if (expanded) Lang.t("收起") else Lang.t("展开"), color = DlaColors.Mint, fontSize = 10.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Text(
                text,
                color = DlaColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 工具的执行身份：只在容易混淆的三个运行时工具上显示，
 * 让用户一眼看懂「为什么 pm 在 phone_sh 里失败、在 phone_shell 里成功」。
 */
private fun identityOf(name: String): String? = when (name) {
    "phone_sh", "phone_py", "phone_js" -> Lang.t("应用身份（无特权）")
    "phone_shell" -> Lang.t("Shizuku（uid 2000）")
    else -> null
}

@Composable
private fun ToolCard(block: Block.Tool) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val statusColor = when (block.status) {
        "error" -> DlaColors.Error
        "running", "waiting" -> DlaColors.Warm
        else -> DlaColors.Mint
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DlaColors.Surface)
            .androidClickableSimple { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(tween(180)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                block.name,
                color = DlaColors.Text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (block.elapsedMs > 0) {
                Text(formatElapsed(block.elapsedMs), color = DlaColors.TextDim, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (expanded) Lang.t("收起") else Lang.t("详情"), color = DlaColors.Mint, fontSize = 10.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column {
                identityOf(block.name)?.let { identity ->
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = DlaColors.Line)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Lang.t("身份：%s", identity),
                        color = DlaColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
                if (block.input.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = DlaColors.Line)
                    Spacer(Modifier.height(8.dp))
                    Text(Lang.t("参数"), color = DlaColors.TextDim, fontSize = 10.sp)
                    Text(
                        block.input.take(1200),
                        color = DlaColors.Text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
                block.result?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(Lang.t("结果"), color = DlaColors.TextDim, fontSize = 10.sp)
                    Text(
                        result.take(1600),
                        color = DlaColors.Text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

// ---------------- 对话开关 ----------------

@Composable
private fun QuickToggles(cachePercent: Int? = null) {
    var thinking by remember { mutableStateOf(ChatEngine.thinking) }
    var search by remember { mutableStateOf(ChatEngine.search) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleChip(Lang.t("深度思考"), thinking) {
            thinking = !thinking
            ChatEngine.thinking = thinking
        }
        ToggleChip(Lang.t("联网搜索"), search) {
            search = !search
            ChatEngine.search = search
        }
        Spacer(Modifier.weight(1f))
        if (cachePercent != null) {
            Text(
                Lang.t("缓存 %s", "$cachePercent%"),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, onToggle: () -> Unit) {
    val accent = if (active) DlaColors.Mint else DlaColors.TextDim
    Text(
        text = label,
        color = accent,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) DlaColors.Mint.copy(alpha = 0.14f) else DlaColors.Surface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------- 设置：对话与人设 ----------------

@Composable
private fun ConversationSettings() {
    val context = LocalContext.current
    var thinking by remember { mutableStateOf(ChatEngine.thinking) }
    var search by remember { mutableStateOf(ChatEngine.search) }
    var autoApprove by remember { mutableStateOf(ChatEngine.autoApprove) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSwitch(Lang.t("深度思考（LLM / Agent 区）"), thinking) { thinking = it; ChatEngine.thinking = it }
        SettingSwitch(Lang.t("联网搜索（LLM 区）"), search) { search = it; ChatEngine.search = it }
        SettingSwitch(Lang.t("完全权限（Agent 不再逐次确认）"), autoApprove) {
            autoApprove = it
            ChatEngine.autoApprove = it
        }
        var sound by remember { mutableStateOf(ChatEngine.soundEnabled) }
        SettingSwitch(Lang.t("界面音效"), sound) {
            sound = it
            ChatEngine.soundEnabled = it
            SoundFeedback.syncEnabled()
            if (it) SoundFeedback.send()
        }
        var location by remember { mutableStateOf(ChatEngine.location) }
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text(Lang.t("所在地区（可选，用于本地化回答）")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            ChatEngine.location = location
            Toast.makeText(context, Lang.t("已保存"), Toast.LENGTH_SHORT).show()
        }) {
            Text(Lang.t("保存地区"), color = DlaColors.Mint)
        }
    }
}

/**
 * 任务通知：Agent 完成 / 失败 / 停止时提醒。
 *
 * Android 13+ 需要 POST_NOTIFICATIONS：这里给「授权通知权限」入口，
 * 已授权时只显示状态；被拒绝时按钮会跳到系统应用通知设置。
 */
@Composable
private fun NotificationSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ChatEngine.notifyResult) }
    var granted by remember { mutableStateOf(ChatEngine.canPostNotifications()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSwitch(Lang.t("任务通知（完成 / 失败时提醒）"), enabled) {
            enabled = it
            ChatEngine.notifyResult = it
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (granted) {
                    Lang.t("通知权限：已开启")
                } else {
                    Lang.t("通知权限：未开启（收不到任务提醒）")
                },
                color = if (granted) DlaColors.TextDim else DlaColors.Warm,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            if (!granted) {
                TextButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                }) {
                    Text(Lang.t("授权通知权限"), color = DlaColors.Mint)
                }
            }
        }
        Text(
            Lang.t("通知里会带上结论摘要、轮/步、耗时与最后调用的工具；点一下直接回到那个会话。"),
            color = DlaColors.TextDim,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

/** 系统禁止后再要权限就不会弹了，只能把用户送到系统的通知设置页 */
private fun openNotificationSettings(context: Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + context.packageName))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun ThemeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val accent = if (active) DlaColors.Mint else DlaColors.TextDim
    Text(
        text = label,
        color = accent,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) DlaColors.Mint.copy(alpha = 0.14f) else DlaColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DlaColors.Surface)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = DlaColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(CircleShape)
                .background(if (checked) DlaColors.Mint.copy(alpha = 0.35f) else DlaColors.SurfaceHigh),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (checked) DlaColors.Mint else DlaColors.TextDim),
            )
        }
    }
}

@Composable
private fun PersonaSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(ChatEngine.personaProfile) }
    var mirror by remember { mutableStateOf(ChatEngine.mirrorMode) }
    var hasImage by remember { mutableStateOf(ChatEngine.personaImage != null) }
    var loading by remember { mutableStateOf<ChatEngine.PersonaDraftMode?>(null) }
    var pending by remember { mutableStateOf<ChatEngine.PersonaDraft?>(null) }
    var pendingMode by remember { mutableStateOf<ChatEngine.PersonaDraftMode?>(null) }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf("核心身份")) }
    var confirmClear by remember { mutableStateOf(false) }

    fun save(next: PersonaProfile = profile) {
        ChatEngine.personaProfile = next
        profile = next
    }

    val portraitPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val data = Attachments.dataUrl(context, uri)
            if (data != null) {
                ChatEngine.personaImage = data
                hasImage = true
                hint = Lang.t("立绘已保存；它会让 AI 对角色的把握更强")
            } else {
                error = Lang.t("没有读到图片")
            }
        }
    }

    val analysisPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val data = Attachments.dataUrl(context, uri)
            if (data == null) {
                error = Lang.t("没有读到图片")
            } else {
                pendingImage = data
                error = null
                hint = null
                loading = ChatEngine.PersonaDraftMode.IMAGE
                scope.launch {
                    val result = ChatEngine.draftPersona(ChatEngine.PersonaDraftMode.IMAGE, data)
                    loading = null
                    result.onSuccess { draft ->
                        pending = draft
                        pendingMode = ChatEngine.PersonaDraftMode.IMAGE
                    }.onFailure { failure ->
                        error = failure.message ?: Lang.t("图片分析失败")
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Lang.t("角色设定（仅 LLM 区）"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            Lang.t("分层填写更容易稳定。文字写得越具体，角色越不容易跑偏；上传一张立绘后 AI 对角色的把握最强，不传图也能只靠文字。"),
            color = DlaColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        PersonaProfile.GROUPS.forEach { group ->
            val open = expanded.contains(group.title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DlaColors.Surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = if (open) expanded - group.title else expanded + group.title
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        group.title,
                        color = DlaColors.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (open) Lang.t("收起") else Lang.t("展开"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                if (open) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.fields.forEach { field ->
                            OutlinedTextField(
                                value = profile.value(field.key),
                                onValueChange = { text -> profile = profile.with(field.key, text) },
                                label = {
                                    Text(
                                        if (field.key == "boundaries") {
                                            Lang.t("边界与底线（必须遵守）")
                                        } else {
                                            field.label
                                        },
                                    )
                                },
                                minLines = field.minLines,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                save()
                hint = Lang.t("已保存角色设定")
                error = null
                Toast.makeText(context, Lang.t("已保存角色设定"), Toast.LENGTH_SHORT).show()
            }) { Text(Lang.t("保存设定"), color = DlaColors.Mint) }
            TextButton(
                onClick = {
                    error = null
                    hint = null
                    loading = ChatEngine.PersonaDraftMode.FILL
                    scope.launch {
                        val result = ChatEngine.draftPersona(ChatEngine.PersonaDraftMode.FILL)
                        loading = null
                        result.onSuccess { draft ->
                            pending = draft
                            pendingMode = ChatEngine.PersonaDraftMode.FILL
                        }.onFailure { failure ->
                            error = failure.message ?: Lang.t("补全失败")
                        }
                    }
                },
                enabled = loading == null,
            ) {
                Text(
                    if (loading == ChatEngine.PersonaDraftMode.FILL) Lang.t("补全中…") else Lang.t("AI 补全设定"),
                    color = DlaColors.Warm,
                )
            }
            TextButton(onClick = { confirmClear = true }) {
                Text(Lang.t("清空"), color = DlaColors.TextDim)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { analysisPicker.launch("image/*") },
                enabled = loading == null,
            ) {
                Text(
                    if (loading == ChatEngine.PersonaDraftMode.IMAGE) Lang.t("分析中…") else Lang.t("上传图片分析"),
                    color = DlaColors.Warm,
                )
            }
            TextButton(onClick = { portraitPicker.launch("image/*") }) {
                Text(if (hasImage) Lang.t("更换立绘") else Lang.t("上传立绘"), color = DlaColors.Mint)
            }
            if (hasImage) {
                TextButton(onClick = {
                    ChatEngine.personaImage = null
                    hasImage = false
                }) { Text(Lang.t("移除立绘"), color = DlaColors.TextDim) }
            }
        }
        Text(
            Lang.t("上传立绘后，它会作为「你的样子」注入给她；如果图片和文字设定冲突，以文字为准。"),
            color = DlaColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        if (loading != null) {
            Text(
                Lang.t("正在让 AI 先考虑情感需求、事实定义与用户安全，再动笔…"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
            )
        }
        error?.let { Text(it, color = DlaColors.Error, fontSize = 11.sp, lineHeight = 16.sp) }
        hint?.let { Text(it, color = DlaColors.Mint, fontSize = 11.sp, lineHeight = 16.sp) }

        val builtInPersona = remember { MirrorPersona.hasBuiltIn(context) }
        SettingSwitch(
            if (builtInPersona) Lang.t("镜子模式（内置人设 + 立绘）") else Lang.t("镜子模式（未内置人设）"),
            mirror,
        ) {
            mirror = it
            ChatEngine.mirrorMode = it
        }
        Text(
            if (mirror) {
                Lang.t("镜子模式生效时会覆盖上面的自定义设定，改用内置人设。")
            } else {
                Lang.t("人设与立绘只在 LLM 区生效；开启镜子模式会覆盖自定义设定。")
            },
            color = DlaColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(Lang.t("清空角色设定？")) },
            text = { Text(Lang.t("会清空所有分层设定（立绘不受影响），用于重新开始。")) },
            confirmButton = {
                TextButton(onClick = {
                    save(PersonaProfile())
                    confirmClear = false
                    error = null
                    hint = Lang.t("已清空角色设定")
                }) { Text(Lang.t("清空"), color = DlaColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(Lang.t("取消")) }
            },
        )
    }

    val draft = pending
    if (draft != null) {
        val (merged, written) = profile.mergeMissing(draft.profile)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    if (pendingMode == ChatEngine.PersonaDraftMode.IMAGE) {
                        Lang.t("图片分析结果")
                    } else {
                        Lang.t("AI 补全结果")
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (written.isEmpty()) {
                        Text(
                            Lang.t("没有空缺字段可补全；已有设定不会被覆盖。"),
                            color = DlaColors.TextDim,
                            fontSize = 12.sp,
                        )
                    } else {
                        Text(
                            Lang.t("只会补写下面这些为空的字段，已有内容不动："),
                            color = DlaColors.TextDim,
                            fontSize = 11.sp,
                        )
                        written.forEach { field ->
                            Text(
                                field.label,
                                color = DlaColors.Mint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(draft.profile.value(field.key), color = DlaColors.Text, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (written.isNotEmpty()) {
                    TextButton(onClick = {
                        save(merged)
                        pending = null
                        hint = Lang.t("已补全 %d 项设定", written.size)
                    }) { Text(Lang.t("应用到设定"), color = DlaColors.Mint) }
                }
            },
            dismissButton = {
                Row {
                    if (pendingMode == ChatEngine.PersonaDraftMode.IMAGE && pendingImage != null) {
                        TextButton(onClick = {
                            save(merged)
                            ChatEngine.personaImage = pendingImage
                            hasImage = true
                            pending = null
                            hint = Lang.t("已应用，并把这张图设为立绘")
                        }) { Text(Lang.t("应用并设为立绘"), color = DlaColors.Warm) }
                    }
                    TextButton(onClick = { pending = null }) { Text(Lang.t("取消")) }
                }
            },
        )
    }
}

// ---------------- 输入栏 ----------------

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    streaming: Boolean,
    attachments: List<Attachment>,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPreview: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPaddingCompat(),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEachIndexed { index, attachment ->
                    Box {
                        if (attachment.kind == Documents.KIND_IMAGE && attachment.dataUrl != null) {
                            AttachmentThumb(
                                dataUrl = attachment.dataUrl,
                                size = 64.dp,
                                onClick = { onPreview(attachment.dataUrl) },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .height(64.dp)
                                    .widthIn(max = 180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DlaColors.SurfaceHigh)
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Column {
                                    Text(
                                        attachment.name ?: Lang.t("文档"),
                                        color = DlaColors.Text,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        Lang.t("文本"),
                                        color = DlaColors.TextDim,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(DlaColors.Background.copy(alpha = 0.8f))
                                .androidClickableSimple { onRemoveAttachment(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("×", color = DlaColors.Text, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            ComposerIcon("🖼") { onPickImage() }
            Spacer(Modifier.width(4.dp))
            ComposerIcon("📂") { onPickFiles() }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(Lang.t("描述任务或提问…"), color = DlaColors.TextDim, fontSize = 13.sp) },
                maxLines = 6,
                shape = RoundedCornerShape(18.dp),
                // Enter 只换行；发送必须点右侧按钮，避免手机上误发
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                textStyle = androidx.compose.ui.text.TextStyle(color = DlaColors.Text, fontSize = 14.sp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            streaming -> DlaColors.Error.copy(alpha = 0.18f)
                            enabled && (value.isNotBlank() || attachments.isNotEmpty()) -> DlaColors.Mint
                            else -> DlaColors.SurfaceHigh
                        },
                    )
                    .androidClickableSimple {
                        if (streaming) {
                            onStop()
                        } else if (enabled && (value.isNotBlank() || attachments.isNotEmpty())) {
                            onSend()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (streaming) "■" else "↑",
                    color = if (streaming) DlaColors.Error else DlaColors.OnAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
@Composable
private fun ComposerIcon(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(DlaColors.SurfaceHigh)
            .androidClickableSimple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp)
    }
}
@Composable
private fun Modifier.navigationBarsPaddingCompat(): Modifier =
    this.windowInsetsPadding(WindowInsets.navigationBars)

// ---------------- Agent 用量条 ----------------

/**
 * Agent 区的状态条：上面一行是缓存命中率与本轮耗时，下面一行是上下文占用（十段虚线）。
 *
 * - 缓存 / 本轮耗时单独一行、字号略大，不和上下文条挤在同一行；
 * - 百分比按 usage 里的 input + cache_creation + cache_read + output 计算，精确到 0.1%；
 * - 视觉上最低显示 1%，避免刚开始时看起来像没占用；没有用量数据时显示「—」；
 * - 耗时只显示这一轮（用户发出 → 这一轮结束），不跨轮累计。
 */
@Composable
private fun AgentUsageBar(
    usedTokens: Int,
    windowTokens: Int,
    turnMs: Long,
    cachePercent: Int? = null,
) {
    val window = windowTokens.coerceAtLeast(1)
    val percent = if (usedTokens > 0) usedTokens * 100.0 / window else 0.0
    val shown = if (usedTokens > 0) maxOf(1.0, percent) else 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (cachePercent != null || turnMs > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cachePercent != null) {
                    Text(
                        Lang.t("缓存 %s", "$cachePercent%"),
                        color = DlaColors.Warm,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (turnMs > 0) {
                    Text(
                        Lang.t("本轮 %s", formatElapsed(turnMs)),
                        color = DlaColors.TextDim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Lang.t("上下文"), color = DlaColors.TextDim, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            ContextDashes(percent = shown, modifier = Modifier.width(96.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (usedTokens > 0) String.format(Locale.US, "%.1f%%", shown) else "—",
                color = DlaColors.Mint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (usedTokens > 0) formatTokens(usedTokens) + " / " + formatTokens(window) else "",
                color = DlaColors.TextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 十段虚线：每段代表 10%，段内按比例填充（例如 1% 只填第一段的十分之一） */
@Composable
private fun ContextDashes(percent: Double, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(10) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(DlaColors.Line),
            ) {
                val fraction = ((percent - index * 10.0) / 10.0).coerceIn(0.0, 1.0).toFloat()
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(DlaColors.Mint),
                    )
                }
            }
        }
    }
}

/** token 数量：15.7K / 128K / 1.0M */
private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

// ---------------- 设置 ----------------

@OptIn(ExperimentalMaterial3Api::class)
/**
 * 设置区块：左侧强调条 + 标题/说明 + 圆角卡片。
 * 所有卡片共用同一套间距与边框，滚动时一眼能分出「模型 / 外观 / 对话 / 朗读 / 角色设定 / 更多」。
 */
@Composable
private fun SettingsSection(
    title: String,
    caption: String?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DlaColors.Mint.copy(alpha = 0.9f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = DlaColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(caption, color = DlaColors.TextDim, fontSize = 10.sp)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DlaColors.Surface)
                .border(1.dp, DlaColors.Line, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onAdvanced: () -> Unit,
    themeMode: String,
    onThemeMode: (String) -> Unit,
) {
    var key by remember { mutableStateOf(ChatEngine.apiKey) }
    var base by remember { mutableStateOf(ChatEngine.baseUrl) }
    var model by remember { mutableStateOf(ChatEngine.model) }
    var protocol by remember { mutableStateOf(ChatEngine.protocol) }
    // 供应商：空串 = 自动识别（按 Base URL / 模型名猜），选中预设则写死
    var provider by remember { mutableStateOf(ChatEngine.provider) }
    // 拉取到的模型列表：存偏好里，重进设置页还能挑，不用再拉一遍
    var models by remember { mutableStateOf(ChatEngine.cachedModels) }
    var showModelPicker by remember { mutableStateOf(false) }
    // 「自动识别」用：输入一变就重算，不需要手动点
    val activeProvider = provider.ifBlank {
        com.example.chatdemo.agent.ProviderCompat.detect(base, model, protocol)
    }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    // 连接结果的好坏用结构化字段判断，不再从显示文案里 startsWith，避免和翻译耦合
    var testOk by remember { mutableStateOf(false) }
    // 上下文窗口：Agent 区用量条的分母（模型能力，接口不会告诉你）
    var contextWindow by remember { mutableStateOf(ChatEngine.contextWindow) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        containerColor = DlaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(Lang.t("设置"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", color = DlaColors.Mint, fontSize = 19.sp) }
                },
                actions = {
                    TextButton(onClick = {
                        // 重新读一遍偏好：外部改过配置（或刚保存完想确认）时不用退出去重进
                        key = ChatEngine.apiKey
                        base = ChatEngine.baseUrl
                        model = ChatEngine.model
                        protocol = ChatEngine.protocol
                        provider = ChatEngine.provider
                        models = ChatEngine.cachedModels
                        contextWindow = ChatEngine.contextWindow
                        saved = false
                        testResult = ""
                        ChatEngine.refresh()
                        Toast.makeText(context, Lang.t("已刷新"), Toast.LENGTH_SHORT).show()
                    }) { Text(Lang.t("刷新"), color = DlaColors.Mint, fontSize = 13.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DlaColors.Background,
                    titleContentColor = DlaColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(Lang.t("模型接口"), Lang.t("DeepSeek 兼容端点")) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it; saved = false },
                    label = { Text(Lang.t("Base URL（留空用默认）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 供应商预设：一键把协议 / Base URL / 默认模型填好。
                // 认不出是哪家的中转站，就选「自定义 / 中转站」，只填 Base URL。
                Text(Lang.t("供应商预设"), color = DlaColors.TextDim, fontSize = 11.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = com.example.chatdemo.agent.ProviderCompat.presets()
                    for (i in 0 until presets.length()) {
                        val item = presets.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        ThemeChip(label = item.optString("name"), active = provider == id) {
                            provider = id
                            protocol = item.optString("protocol", "openai")
                            val presetBase = item.optString("baseUrl")
                            if (presetBase.isNotBlank()) base = presetBase
                            val presetModel = item.optString("model")
                            if (presetModel.isNotBlank()) model = presetModel
                            saved = false
                        }
                    }
                }
                Text(
                    buildString {
                        append(Lang.t("当前适配："))
                        append(com.example.chatdemo.agent.ProviderCompat.displayName(activeProvider))
                        if (provider.isBlank()) {
                            append("（").append(Lang.t("按 Base URL / 模型名自动识别")).append("）")
                        }
                        val note = providerNote(activeProvider)
                        if (note.isNotBlank()) append(" · ").append(note)
                    },
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; saved = false },
                        label = { Text(Lang.t("模型")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (models.isNotEmpty()) {
                        TextButton(onClick = { showModelPicker = true }) {
                            Text(Lang.t("选择"), color = DlaColors.Warm)
                        }
                    }
                }
                if (models.isNotEmpty()) {
                    Text(
                        Lang.t("已缓存 %d 个模型，点「选择」直接挑", models.size),
                        color = DlaColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
                Text(
                    Lang.t("接口协议"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(
                        label = Lang.t("Anthropic Messages"),
                        active = protocol == "anthropic",
                    ) { protocol = "anthropic" }
                    ThemeChip(
                        label = Lang.t("OpenAI Chat Completions"),
                        active = protocol == "openai",
                    ) { protocol = "openai" }
                }
                Text(
                    if (protocol == "openai") {
                        Lang.t("OpenAI 协议：Base URL 通常以 /v1 结尾，例如 https://api.deepseek.com/v1 或 https://api.openai.com/v1")
                    } else {
                        Lang.t("Anthropic 协议：Base URL 通常以 /v1 结尾，例如 https://api.deepseek.com/anthropic/v1")
                    },
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    Lang.t("上下文窗口（Agent 区用量条用）"),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(64_000, 128_000, 256_000, 1_000_000).forEach { size ->
                        ThemeChip(
                            label = if (size >= 1_000_000) "1M" else "${size / 1000}K",
                            active = contextWindow == size,
                        ) {
                            contextWindow = size
                            ChatEngine.contextWindow = size
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        ChatEngine.apiKey = key
                        // 允许清空：空串 = 回到默认地址（以前留空会被忽略，用户以为没保存上）
                        ChatEngine.baseUrl = base
                        if (model.isNotBlank()) ChatEngine.model = model
                        ChatEngine.protocol = protocol
                        ChatEngine.provider = provider
                        if (models.isNotEmpty()) ChatEngine.cachedModels = models
                        saved = true
                        val noKey = ChatEngine.apiKey.isBlank()
                        Toast.makeText(
                            context,
                            if (noKey) Lang.t("已保存，但还没填 API Key，暂时不能对话")
                            else Lang.t("已保存，下一次请求生效"),
                            if (noKey) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(Lang.t("保存"), color = DlaColors.Mint) }
                    TextButton(
                        onClick = {
                            ChatEngine.apiKey = key
                            if (base.isNotBlank()) ChatEngine.baseUrl = base
                            testing = true
                            testResult = ""
                            val protocolNow = protocol
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    NativeApi.fetchModels(ChatEngine.apiKey, ChatEngine.baseUrl, protocolNow)
                                }
                                testResult = r.message
                                testOk = r.ok
                                testing = false
                                // 拉取成功后立刻把列表留在界面上，直接可挑
                                if (r.ok && r.models.isNotEmpty()) {
                                    models = r.models
                                    ChatEngine.cachedModels = r.models
                                    if (model.isBlank()) showModelPicker = true
                                }
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) Lang.t("测试中…") else Lang.t("拉取并测试连接"), color = DlaColors.Warm) }
                    if (saved) Text(Lang.t("已保存"), color = DlaColors.TextDim, fontSize = 11.sp)
                }
                if (testResult.isNotBlank()) {
                    Text(
                        testResult,
                        color = if (testOk) DlaColors.Mint else DlaColors.Error,
                        fontSize = 11.sp,
                    )
                }
                if (models.isNotEmpty()) {
                    TextButton(onClick = { showModelPicker = true }) {
                        Text(
                            Lang.t("从 %d 个模型里挑一个", models.size),
                            color = DlaColors.Mint,
                        )
                    }
                }
            }

            SettingsSection(Lang.t("外观"), Lang.t("主题与语言")) {
                Text(Lang.t("主题"), color = DlaColors.TextDim, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(Lang.t("深色"), themeMode == "dark") { onThemeMode("dark") }
                    ThemeChip(Lang.t("浅色"), themeMode == "light") { onThemeMode("light") }
                    ThemeChip(Lang.t("跟随系统"), themeMode == "system") { onThemeMode("system") }
                }
                HorizontalDivider(color = DlaColors.Line)
                Text(Lang.t("语言"), color = DlaColors.TextDim, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val languageMode = Lang.mode()
                    ThemeChip(Lang.t("中文"), languageMode == Lang.ZH) { switchLanguage(context, Lang.ZH) }
                    ThemeChip("English", languageMode == Lang.EN) { switchLanguage(context, Lang.EN) }
                    ThemeChip(Lang.t("跟随系统"), languageMode == Lang.SYSTEM) { switchLanguage(context, Lang.SYSTEM) }
                }
            }

            SettingsSection(Lang.t("对话"), Lang.t("思考 · 搜索 · 权限")) {
                ConversationSettings()
            }

            SettingsSection(Lang.t("通知"), Lang.t("任务完成 / 失败提醒")) {
                NotificationSettings()
            }

            SettingsSection(Lang.t("朗读"), Lang.t("默认关闭 · 系统语音 / Qwen3-TTS")) {
                TtsSettings()
            }

            SettingsSection(Lang.t("角色设定"), Lang.t("仅 LLM 区")) {
                PersonaSettings()
            }

            SettingsSection(Lang.t("更多"), null) {
                TextButton(onClick = onAdvanced) {
                    Text(Lang.t("运行环境 / MCP / 数据"), color = DlaColors.Mint)
                }
            }

            Text(
                Lang.t("DLA · 本地智能体\n模型来自 DeepSeek，非官方产品。\n数据只存在本机。"),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }

    // 模型选择：拉取成功后才能挑，省得手打一长串模型 id（还容易打错）
    if (showModelPicker) {
        ModelPickerDialog(
            models = models,
            current = model,
            onPick = { picked ->
                model = picked
                ChatEngine.apiKey = key
                if (base.isNotBlank()) ChatEngine.baseUrl = base
                ChatEngine.model = picked
                ChatEngine.protocol = protocol
                ChatEngine.provider = provider
                saved = true
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** 预设里的那句「这家的坑」提示；没有就返回空串 */
private fun providerNote(id: String): String {
    val presets = com.example.chatdemo.agent.ProviderCompat.presets()
    for (i in 0 until presets.length()) {
        val item = presets.optJSONObject(i) ?: continue
        if (item.optString("id") == id) return item.optString("note")
    }
    return ""
}

/**
 * 模型选择弹窗：带搜索框的列表。
 *
 * 为什么不直接用下拉菜单：一家中转站动辄几十个模型，下拉一屏放不下、也不好搜；
 * 这里给一个可搜索的全屏列表，点一下即选中并保存。
 */
@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(models, filter) {
        if (filter.isBlank()) models else models.filter { it.contains(filter, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = DlaColors.SurfaceHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    Lang.t("选择模型"),
                    color = DlaColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(Lang.t("搜索模型")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    Lang.t("共 %d 个，点一下即选中并保存", shown.size),
                    color = DlaColors.TextDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(shown, key = { it }) { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(name) }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                color = if (name == current) DlaColors.Mint else DlaColors.Text,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (name == current) {
                                Text("✓", color = DlaColors.Mint, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = DlaColors.Line)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(Lang.t("取消"), color = DlaColors.TextDim)
                }
            }
        }
    }
}

// ---------------- 工具 ----------------

@Composable
private fun Modifier.androidClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

@Composable
private fun Modifier.androidClickableSimple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** 把消息里的工具调用按轮次聚合，供迭代树展示 */
/** 把消息里的思考/工具按轮聚合；老数据没有 round 时退回「按 1 轮 + 全部工具」，别把迭代树弄丢 */
private fun buildRounds(blocks: List<Block>): List<IterationRound> {
    val groups = collectRoundGroups(blocks)
    if (groups.isNotEmpty()) return groups.map { group -> iterationRound(group) }
    // 3.5.1 之前的会话没有 round：沿用旧版「一轮 + 全部工具」的展示
    val tools = blocks.filterIsInstance<Block.Tool>()
    if (tools.isEmpty()) return emptyList()
    val thinking = blocks.filterIsInstance<Block.Thinking>()
        .joinToString("\n") { it.text }
        .trim()
        .ifEmpty { null }
    return listOf(IterationRound(round = 1, tools = tools, thinking = thinking))
}

/** 一轮的分组 → 迭代树里的一轮（思考 + 该轮工具），两边的轮数口径保持一致 */
private fun iterationRound(group: RoundGroup): IterationRound = IterationRound(
    round = group.round,
    tools = group.blocks.filterIsInstance<Block.Tool>(),
    thinking = group.blocks.filterIsInstance<Block.Thinking>()
        .joinToString("\n") { it.text }
        .trim()
        .ifEmpty { null },
)

private fun timeAgo(millis: Long): String {
    if (millis <= 0L) return ""
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < 60_000 -> Lang.t("刚刚")
        delta < 3_600_000 -> Lang.t("%d 分钟前", delta / 60_000)
        delta < 86_400_000 -> Lang.t("%d 小时前", delta / 3_600_000)
        delta < 7 * 86_400_000L -> Lang.t("%d 天前", delta / 86_400_000)
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
    }
}

/**
 * 会话标题的占位值来自 SessionStore 的存储层（新建会话写「新对话」，
 * 纯图片消息写「图片」）。它们是**数据/逻辑值**，不参与翻译；
 * 只有轮到显示时才按当前语言给出对应标签。
 */
private const val DEFAULT_SESSION_TITLE = "新对话"
private const val IMAGE_SESSION_TITLE = "图片"

private fun displayTitle(raw: String?): String {
    val title = raw.orEmpty().trim()
    return when (title) {
        "", DEFAULT_SESSION_TITLE -> Lang.t("新对话")
        IMAGE_SESSION_TITLE -> Lang.t("图片")
        else -> title
    }
}

/** 切换语言：先写偏好并立即生效，再 recreate 让整棵树重新取值 */
private fun switchLanguage(context: Context, mode: String) {
    if (Lang.mode() == mode) return // 点当前这一档就不用重来一遍
    Lang.set(context, mode)
    context.findActivity()?.recreate()
}

/** Compose 拿到的 Context 可能被包了一层主题，往上找到真正的 Activity */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

