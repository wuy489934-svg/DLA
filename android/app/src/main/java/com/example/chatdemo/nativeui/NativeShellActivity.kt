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
import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    override fun onResume() {
        super.onResume()
        // 回到前台重新挂上 Agent 监听器（系统恢复任务时也能收到事件）
        ChatEngine.attachListener()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 语言必须在任何 UI 之前定下来，保证首次绘制就是正确语言；
        // 切换语言时本 Activity 会被 recreate，这里会重新读一遍偏好。
        Lang.init(this)
        enableEdgeToEdge()
        ChatEngine.attach(this)

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
                )
            }
        }
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
 * 切语言时会 recreate 本 Activity，用 saved state 记住当前页面，
 * 回来后还停在设置页（同时能看到新语言），而不是被弹回会话列表。
 */
private val ScreenSaver: Saver<Screen, String> = Saver(
    save = { screen ->
        when (screen) {
            Screen.Home -> "home"
            Screen.Settings -> "settings"
            Screen.Advanced -> "advanced"
            is Screen.Zone -> "zone:" + screen.mode
            is Screen.Chat -> "chat:" + screen.id
        }
    },
    restore = { value ->
        when {
            value == "settings" -> Screen.Settings
            value == "advanced" -> Screen.Advanced
            value.startsWith("zone:") -> Screen.Zone(value.removePrefix("zone:"))
            value.startsWith("chat:") -> {
                val id = value.removePrefix("chat:")
                // 进程被杀后恢复时没有打开的会话，回首页，避免空白聊天页
                if (ChatEngine.open.value?.id == id) Screen.Chat(id) else Screen.Home
            }
            else -> Screen.Home
        }
    },
)

@Composable
private fun NativeApp(
    themeMode: String,
    onThemeMode: (String) -> Unit,
) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
    val sessions by ChatEngine.sessions.collectAsState()
    val open by ChatEngine.open.collectAsState()

    LaunchedEffect(screen) {
        if (screen is Screen.Home || screen is Screen.Zone) ChatEngine.refresh()
    }

    // 从聊天页退出：回到这个会话所属的分区，而不是混排的首页
    fun leaveChatToZone() {
        val mode = ChatEngine.open.value?.mode ?: "llm"
        ChatEngine.close()
        screen = Screen.Zone(mode)
    }

    BackHandler(enabled = screen !is Screen.Home) {
        when (screen) {
            is Screen.Chat -> leaveChatToZone()
            else -> {
                ChatEngine.close()
                screen = Screen.Home
            }
        }
    }

    Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { current ->
        when (current) {
            is Screen.Home -> HomeScreen(
                sessions = sessions,
                onOpenZone = { mode -> screen = Screen.Zone(mode) },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Zone -> ZoneScreen(
                mode = current.mode,
                sessions = sessions.filter { it.mode == current.mode },
                onBack = { screen = Screen.Home },
                onSettings = { screen = Screen.Settings },
                onOpen = { id ->
                    ChatEngine.open(id)
                    screen = Screen.Chat(id)
                },
                onDelete = { ChatEngine.delete(it) },
                onNew = {
                    ChatEngine.create(current.mode)
                    ChatEngine.open.value?.id?.let { screen = Screen.Chat(it) }
                },
            )
            is Screen.Chat -> ChatScreen(
                onBack = { leaveChatToZone() },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Settings -> SettingsScreen(
                onBack = {
                    ChatEngine.close()
                    screen = Screen.Home
                },
                onAdvanced = { screen = Screen.Advanced },
                themeMode = themeMode,
                onThemeMode = onThemeMode,
            )
            is Screen.Advanced -> AdvancedSettingsScreen(
                onBack = { screen = Screen.Settings },
            )
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
        message.blocks.forEachIndexed { index, block ->
            rows += ChatRow.BlockRow(message, block, index)
        }
    }
    return rows
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
                QuickToggles()
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
                            )
                            is ChatRow.Tree -> IterationTree(
                                rounds = row.rounds,
                                // 折叠头的总耗时用「这一轮从开始到结束」的实际耗时；
                                // 老数据没有这个字段就退回按工具耗时求和
                                totalMs = row.message.elapsedMs,
                                modifier = Modifier.fillMaxWidth(),
                                onLocate = { round ->
                                    val target = reversed.indexOfFirst {
                                        it is ChatRow.BlockRow &&
                                            it.message.id == row.message.id &&
                                            (it.block as? Block.Tool)?.round == round
                                    }
                                    if (target >= 0) {
                                        scope.launch { listState.animateScrollToItem(target) }
                                    }
                                },
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
@Composable
private fun DraftMessage(open: ChatEngine.Open) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rounds = remember(open.draftTools) { buildRoundsFromTools(open.draftTools) }
        if (rounds.isNotEmpty()) {
            IterationTree(rounds)
        }
        open.draftTools.forEach { tool -> ToolCard(tool) }
        if (open.draftThinking.isNotBlank()) {
            ThinkingCard(open.draftThinking, null)
        }
        if (open.draftThinking.isBlank() && open.draftText.isBlank() && open.draftTools.isEmpty()) {
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
    var expanded by remember(text) { mutableStateOf(false) }
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
private fun QuickToggles() {
    var thinking by remember { mutableStateOf(ChatEngine.thinking) }
    var search by remember { mutableStateOf(ChatEngine.search) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleChip(Lang.t("深度思考"), thinking) {
            thinking = !thinking
            ChatEngine.thinking = thinking
        }
        ToggleChip(Lang.t("联网搜索"), search) {
            search = !search
            ChatEngine.search = search
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
        SettingSwitch(Lang.t("深度思考（LLM 区）"), thinking) { thinking = it; ChatEngine.thinking = it }
        SettingSwitch(Lang.t("联网搜索（LLM 区）"), search) { search = it; ChatEngine.search = it }
        SettingSwitch(Lang.t("完全权限（Agent 不再逐次确认）"), autoApprove) {
            autoApprove = it
            ChatEngine.autoApprove = it
        }
        var sound by remember { mutableStateOf(ChatEngine.soundEnabled) }
        SettingSwitch(Lang.t("界面音效"), sound) {
            sound = it
            ChatEngine.soundEnabled = it
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
        TextButton(onClick = { ChatEngine.location = location }) {
            Text(Lang.t("保存地区"), color = DlaColors.Mint)
        }
    }
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
 * Agent 区的状态条：左边是上下文占用（十段虚线，每段代表 10%），右边是本轮总耗时。
 *
 * - 百分比按 usage 里的 input + cache_creation + cache_read + output 计算，精确到 0.1%；
 * - 视觉上最低显示 1%，避免刚开始时看起来像没占用；没有用量数据时显示「—」；
 * - 耗时只显示这一轮（用户发出 → 这一轮结束），不跨轮累计。
 */
@Composable
private fun AgentUsageBar(
    usedTokens: Int,
    windowTokens: Int,
    turnMs: Long,
) {
    val window = windowTokens.coerceAtLeast(1)
    val percent = if (usedTokens > 0) usedTokens * 100.0 / window else 0.0
    val shown = if (usedTokens > 0) maxOf(1.0, percent) else 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
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
        Spacer(Modifier.weight(1f))
        if (turnMs > 0) {
            Text(
                Lang.t("本轮 %s", formatElapsed(turnMs)),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
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
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    label = { Text(Lang.t("模型（留空用默认）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                        if (base.isNotBlank()) ChatEngine.baseUrl = base
                        if (model.isNotBlank()) ChatEngine.model = model
                        saved = true
                    }) { Text(Lang.t("保存"), color = DlaColors.Mint) }
                    TextButton(
                        onClick = {
                            ChatEngine.apiKey = key
                            if (base.isNotBlank()) ChatEngine.baseUrl = base
                            testing = true
                            testResult = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    NativeApi.fetchModels(ChatEngine.apiKey, ChatEngine.baseUrl)
                                }
                                testResult = r.message
                                testOk = r.ok
                                testing = false
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
private fun buildRounds(blocks: List<Block>): List<IterationRound> {
    val tools = blocks.filterIsInstance<Block.Tool>()
    val thinking = blocks.filterIsInstance<Block.Thinking>()
        .joinToString("\n") { it.text }
        .trim()
    val rounds = buildRoundsFromTools(tools)
    if (rounds.isEmpty()) return rounds
    return rounds.mapIndexed { index, round ->
        if (index == 0 && thinking.isNotEmpty()) round.copy(thinking = thinking) else round
    }
}

private fun buildRoundsFromTools(tools: List<Block.Tool>): List<IterationRound> {
    if (tools.isEmpty()) return emptyList()
    val grouped = tools.groupBy { it.round.coerceAtLeast(1) }.toSortedMap()
    return grouped.map { (round, list) -> IterationRound(round = round, tools = list) }
}

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

