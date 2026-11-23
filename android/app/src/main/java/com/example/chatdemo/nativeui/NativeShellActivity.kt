package com.example.chatdemo.nativeui

import android.util.Log
import android.app.Activity
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生壳（方案 B 完成态）：会话、发送、Agent 审批、Markdown 渲染、设置。
 *
 * WebView 只作为「隔离的 Markdown 渲染器」存在（见 Markdown.kt），
 * 网页界面降级为可选外壳，从设置里进入。
 */
class NativeShellActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // 网页界面会抢走 Agent 监听器，回到原生必须重新挂上
        ChatEngine.attachListener()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 语言必须在任何 UI 之前定下来，保证首次绘制就是正确语言；
        // 切换语言时本 Activity 会被 recreate，这里会重新读一遍偏好。
        Lang.init(this)
        enableEdgeToEdge()
        ChatEngine.attach(this)
        // 一次性把网页留下的 API Key 迁到原生设置，用户不需要重填
        KeyMigration.migrateOnce(this) { }
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
                    onOpenWeb = {
                        startActivity(
                            Intent(this, Class.forName("com.example.chatdemo.MainActivity"))
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    },
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
    data object List : Screen
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
            Screen.List -> "list"
            Screen.Settings -> "settings"
            Screen.Advanced -> "advanced"
            is Screen.Chat -> "chat:" + screen.id
        }
    },
    restore = { value ->
        when {
            value == "settings" -> Screen.Settings
            value == "advanced" -> Screen.Advanced
            value.startsWith("chat:") -> {
                val id = value.removePrefix("chat:")
                // 进程被杀后恢复时没有打开的会话，回列表，避免空白聊天页
                if (ChatEngine.open.value?.id == id) Screen.Chat(id) else Screen.List
            }
            else -> Screen.List
        }
    },
)

@Composable
private fun NativeApp(
    onOpenWeb: () -> Unit,
    themeMode: String,
    onThemeMode: (String) -> Unit,
) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.List) }
    val sessions by ChatEngine.sessions.collectAsState()
    val open by ChatEngine.open.collectAsState()

    LaunchedEffect(screen) {
        if (screen is Screen.List) ChatEngine.refresh()
    }

    BackHandler(enabled = screen !is Screen.List) {
        ChatEngine.close()
        screen = Screen.List
    }

    Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { current ->
        when (current) {
            is Screen.List -> SessionListScreen(
                sessions = sessions,
                onOpen = { id ->
                    ChatEngine.open(id)
                    screen = Screen.Chat(id)
                },
                onDelete = { ChatEngine.delete(it) },
                onNew = { mode ->
                    ChatEngine.create(mode)
                    screen = Screen.Chat(ChatEngine.open.value?.id.orEmpty())
                },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Chat -> ChatScreen(
                onBack = {
                    ChatEngine.close()
                    screen = Screen.List
                },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.List },
                onOpenWeb = onOpenWeb,
                onAdvanced = { screen = Screen.Advanced },
                themeMode = themeMode,
                onThemeMode = onThemeMode,
            )
            is Screen.Advanced -> AdvancedSettingsScreen(
                onBack = { screen = Screen.Settings },
                onOpenWeb = onOpenWeb,
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
private fun SessionListScreen(
    sessions: List<SessionCard>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: (String) -> Unit,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NewSessionButton(Lang.t("LLM 对话"), Lang.t("纯聊天与检索"), DlaColors.Mint, Modifier.weight(1f)) {
                    onNew("llm")
                }
                NewSessionButton(Lang.t("Agent 任务"), Lang.t("调用工具执行"), DlaColors.Warm, Modifier.weight(1f)) {
                    onNew("agent")
                }
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
                    Text(Lang.t("上面选一个模式开始"), color = DlaColors.TextDim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
private fun NewSessionButton(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .scale(if (pressed) 0.97f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.10f))
            .androidClickable(interaction, onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = DlaColors.TextDim, fontSize = 11.sp)
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
                    append(Lang.t("%d 条", card.messageCount))
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

@Composable
private fun ModeChip(mode: String) {
    val agent = mode == "agent"
    Text(
        text = if (agent) "AGENT" else "LLM",
        color = if (agent) DlaColors.Mint else DlaColors.TextDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(
                if (agent) DlaColors.Mint.copy(alpha = 0.12f) else DlaColors.SurfaceHigh,
                RoundedCornerShape(6.dp),
            )
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
    var input by remember(current?.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingAttachments by remember(current?.id) { mutableStateOf<List<Attachment>>(emptyList()) }

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
                            color = DlaColors.TextDim,
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", color = DlaColors.Mint, fontSize = 19.sp) }
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
                            is ChatRow.User -> UserMessageRow(row.message)
                            is ChatRow.Tree -> IterationTree(
                                rounds = row.rounds,
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
                            is ChatRow.BlockRow -> BlockRowView(row.block)
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

            Composer(
                value = input,
                enabled = current != null,
                streaming = current?.streaming == true,
                attachments = pendingAttachments,
                onChange = { input = it },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                        ChatEngine.send(text, pendingAttachments)
                        input = ""
                        pendingAttachments = emptyList()
                    }
                },
                onStop = { ChatEngine.stop() },
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
}

@Composable
private fun UserMessageRow(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            message.attachments.forEach { attachment ->
                if (attachment.kind == Documents.KIND_IMAGE && attachment.dataUrl != null) {
                    AttachmentThumb(attachment.dataUrl, 168.dp)
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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BlockRowView(block: Block) {
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
                if (needsRichRenderer(block.text)) {
                    MarkdownText(block.text)
                } else {
                    NativeMarkdown(block.text)
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
                Text("${block.elapsedMs}ms", color = DlaColors.TextDim, fontSize = 10.sp)
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
    var thinking by remember { mutableStateOf(ChatEngine.thinking) }
    var search by remember { mutableStateOf(ChatEngine.search) }
    var autoApprove by remember { mutableStateOf(ChatEngine.autoApprove) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Lang.t("对话"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
    var persona by remember { mutableStateOf(ChatEngine.persona) }
    var mirror by remember { mutableStateOf(ChatEngine.mirrorMode) }
    var hasImage by remember { mutableStateOf(ChatEngine.personaImage != null) }
    // 开源版不内置人设资产：资产缺失时开关仍可用，但标签与注释要说明「未内置人设」
    val builtInPersona = remember { MirrorPersona.hasBuiltIn(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ChatEngine.personaImage = "data:$mime;base64,$data"
                hasImage = true
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Lang.t("人设（仅 LLM 区）"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = persona,
            onValueChange = { persona = it },
            label = { Text(Lang.t("系统提示词（留空用内置身份）")) },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { ChatEngine.persona = persona }) { Text(Lang.t("保存人设"), color = DlaColors.Mint) }
            TextButton(onClick = { picker.launch("image/*") }) {
                Text(if (hasImage) Lang.t("更换立绘") else Lang.t("上传立绘"), color = DlaColors.Warm)
            }
            if (hasImage) {
                TextButton(onClick = { ChatEngine.personaImage = null; hasImage = false }) {
                    Text(Lang.t("移除"), color = DlaColors.TextDim)
                }
            }
        }
        SettingSwitch(
            if (builtInPersona) {
                Lang.t("镜子模式（内置人设 + 立绘）")
            } else {
                Lang.t("镜子模式（未内置人设）")
            },
            mirror,
        ) {
            mirror = it
            ChatEngine.mirrorMode = it
        }
        Text(
            if (builtInPersona) {
                Lang.t("人设与立绘只在 LLM 区生效；开启镜子模式会覆盖自定义人设。")
            } else {
                Lang.t("开源版不内置人设与立绘：镜子模式开关保留，但需要自行放入 assets/mirror_persona.txt 与 assets/mirror_portrait.webp 才会生效。")
            },
            color = DlaColors.TextDim,
            fontSize = 11.sp,
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
                            AttachmentThumb(attachment.dataUrl, 64.dp)
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

// ---------------- 设置 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
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
            Text(Lang.t("模型接口"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

            HorizontalDivider(color = DlaColors.Line)
            Text(Lang.t("界面"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                Lang.t("当前使用原生界面。网页界面保留为兼容外壳，随时可以打开。"),
                color = DlaColors.TextDim,
                fontSize = 12.sp,
            )
            TextButton(onClick = onOpenWeb) { Text(Lang.t("打开网页界面"), color = DlaColors.Warm) }

            HorizontalDivider(color = DlaColors.Line)
            Text(Lang.t("主题"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(Lang.t("深色"), themeMode == "dark") { onThemeMode("dark") }
                ThemeChip(Lang.t("浅色"), themeMode == "light") { onThemeMode("light") }
                ThemeChip(Lang.t("跟随系统"), themeMode == "system") { onThemeMode("system") }
            }

            // 语言：写偏好后 recreate，整棵树重新按新语言取值；
            // 页面本身由 rememberSaveable 记住，回来后还停在这里。
            HorizontalDivider(color = DlaColors.Line)
            Text(Lang.t("语言"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val languageMode = Lang.mode()
                ThemeChip(Lang.t("中文"), languageMode == Lang.ZH) { switchLanguage(context, Lang.ZH) }
                ThemeChip("English", languageMode == Lang.EN) { switchLanguage(context, Lang.EN) }
                ThemeChip(Lang.t("跟随系统"), languageMode == Lang.SYSTEM) { switchLanguage(context, Lang.SYSTEM) }
            }

            HorizontalDivider(color = DlaColors.Line)
            ConversationSettings()

            HorizontalDivider(color = DlaColors.Line)
            PersonaSettings()

            HorizontalDivider(color = DlaColors.Line)
            Text(Lang.t("更多"), color = DlaColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onAdvanced) { Text(Lang.t("MCP / Termux / 数据管理"), color = DlaColors.Mint) }

            HorizontalDivider(color = DlaColors.Line)
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
