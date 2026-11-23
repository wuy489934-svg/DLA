package com.example.chatdemo.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatdemo.agent.Diagnostics
import com.example.chatdemo.agent.McpStore
import com.example.chatdemo.agent.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 高级设置：MCP 服务器、Termux 环境、数据管理。
 *
 * 只负责界面与本地状态，能力全部复用已有实现：
 * - MCP：agent 包 McpStore，配置落在 SharedPreferences 的 servers 数组里；
 * - Termux：agent 包 TermuxBridge，结果对象带 text() 与 isError()；
 * - 数据：agent 包 Diagnostics 导出 zip，本包 SessionStore 管理 files/native-sessions。
 *
 * 约定：磁盘与跨进程调用一律 withContext(Dispatchers.IO)，主线程只改 Compose 状态；
 * 不使用 WebView，不引入新依赖。
 *
 * 说明：SessionStore 的 importLegacy() 是 init 内的私有方法（构造时自动补一次网页历史），
 * 因此这里不提供手动导入按钮，只读取 cards() 展示数量并支持删除。
 */

private const val TERMUX_TEST_COMMAND = "echo DLA_TERMUX_OK"
private const val TERMUX_TEST_TIMEOUT_MS = 8000L
private const val TERMUX_RESULT_LIMIT = 400
private const val TERMUX_ALLOW_COMMAND =
    "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"

/** 列表展示用的 MCP 服务器快照（原始数据仍在 McpStore 的 JSON 里） */
private data class McpEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
)

/** Termux 状态快照 */
private data class TermuxInfo(
    val installed: Boolean = false,
    val version: String = "",
    val granted: Boolean = false,
)

/** 读取 MCP 列表；只应在 IO 线程调用 */
private fun readMcpEntries(context: Context): List<McpEntry> {
    val array = McpStore.load(context)
    val entries = ArrayList<McpEntry>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        entries += McpEntry(
            id = item.optString("id"),
            name = item.optString("name"),
            url = item.optString("url"),
            enabled = item.optBoolean("enabled", true),
        )
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit, onOpenWeb: () -> Unit) {
    val context = LocalContext.current
    val appContext: Context = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    // MCP 服务器
    var mcpEntries by remember { mutableStateOf<List<McpEntry>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var showMcpForm by remember { mutableStateOf(false) }
    var mcpName by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var mcpToken by remember { mutableStateOf("") }
    var mcpError by remember { mutableStateOf<String?>(null) }

    // Termux 环境
    var termuxInfo by remember { mutableStateOf(TermuxInfo()) }
    var termuxRefresh by remember { mutableStateOf(0) }
    var termuxResult by remember { mutableStateOf("") }
    var termuxFailed by remember { mutableStateOf(false) }
    var termuxTesting by remember { mutableStateOf(false) }
    var termuxCopied by remember { mutableStateOf(false) }

    // 数据
    var sessionCount by remember { mutableStateOf(-1) }
    var sessionRefresh by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var dataMessage by remember { mutableStateOf("") }
    var dataMessageError by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        mcpEntries = withContext(Dispatchers.IO) { readMcpEntries(appContext) }
    }

    LaunchedEffect(termuxRefresh) {
        val status = withContext(Dispatchers.IO) { TermuxBridge.status(appContext) }
        termuxInfo = TermuxInfo(
            installed = status.optBoolean("installed", false),
            version = status.optString("version"),
            granted = status.optBoolean("granted", false),
        )
    }

    LaunchedEffect(termuxCopied) {
        if (termuxCopied) {
            delay(2500)
            termuxCopied = false
        }
    }

    LaunchedEffect(sessionRefresh) {
        sessionCount = withContext(Dispatchers.IO) { SessionStore(appContext).cards().size }
    }

    val saveMcp: () -> Unit = {
        val name = mcpName.trim()
        val url = mcpUrl.trim()
        val token = mcpToken.trim()
        when {
            url.isEmpty() -> mcpError = Lang.t("请先填写 URL")
            !url.startsWith("https://", ignoreCase = true) -> mcpError = Lang.t("需要 HTTPS 地址")
            else -> {
                mcpError = null
                val payload = JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", name.ifEmpty { url })
                    .put("url", url)
                    .put("token", token)
                    .put("enabled", true)
                    .toString()
                scope.launch {
                    withContext(Dispatchers.IO) { McpStore.upsert(appContext, payload) }
                    mcpName = ""
                    mcpUrl = ""
                    mcpToken = ""
                    showMcpForm = false
                    refreshKey += 1
                }
            }
        }
    }

    val removeMcp: (String) -> Unit = { id ->
        scope.launch {
            withContext(Dispatchers.IO) { McpStore.remove(appContext, id) }
            refreshKey += 1
        }
    }

    val openTermux: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { TermuxBridge.open(appContext) } }
        }
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val copyTermuxCommand: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(Lang.t("Termux 授权命令"), TERMUX_ALLOW_COMMAND))
            termuxCopied = true
        }
    }

    val testTermux: () -> Unit = {
        if (!termuxTesting) {
            termuxTesting = true
            termuxResult = ""
            termuxFailed = false
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    TermuxBridge.run(appContext, TERMUX_TEST_COMMAND, TERMUX_TEST_TIMEOUT_MS)
                }
                termuxResult = result.text()
                termuxFailed = result.isError()
                termuxTesting = false
                termuxRefresh += 1
            }
        }
    }

    val exportDiagnostics: () -> Unit = {
        if (!exporting) {
            exporting = true
            dataMessageError = false
            dataMessage = Lang.t("正在导出诊断…")
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { Diagnostics.export(appContext) }
                }
                outcome.fold(
                    onSuccess = { path ->
                        dataMessage = Lang.t("已导出到 %s", path)
                        dataMessageError = false
                    },
                    onFailure = { error ->
                        dataMessage = Lang.t("导出失败：%s", error.message ?: error.javaClass.simpleName)
                        dataMessageError = true
                    },
                )
                exporting = false
            }
        }
    }

    val deleteAllSessions: () -> Unit = {
        if (!deleting) {
            deleting = true
            scope.launch {
                val removed = withContext(Dispatchers.IO) {
                    val store = SessionStore(appContext)
                    val cards = store.cards()
                    cards.forEach { store.delete(it.id) }
                    cards.size
                }
                deleting = false
                dataMessage = Lang.t("已删除 %d 个会话", removed)
                dataMessageError = false
                sessionRefresh += 1
            }
        }
    }

    Scaffold(
        containerColor = DlaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(Lang.t("高级设置"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", color = DlaColors.Mint, fontSize = 19.sp)
                    }
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- MCP 服务器 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DlaColors.Surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle(Lang.t("MCP 服务器"))
                    Text(
                        Lang.t("MCP 让 Agent 调用外部工具，需要填 HTTPS 地址。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        Lang.t("Token 只存在本机，仅作为 Bearer 头发给该服务器，可留空。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    if (mcpEntries.isEmpty()) {
                        Text(Lang.t("还没有服务器。"), color = DlaColors.TextDim, fontSize = 12.sp)
                    } else {
                        mcpEntries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = DlaColors.Line)
                            }
                            McpEntryRow(entry = entry, onRemove = { removeMcp(entry.id) })
                        }
                    }

                    HorizontalDivider(color = DlaColors.Line)

                    if (showMcpForm) {
                        OutlinedTextField(
                            value = mcpName,
                            onValueChange = {
                                mcpName = it
                                mcpError = null
                            },
                            label = { Text(Lang.t("名称")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = mcpUrl,
                            onValueChange = {
                                mcpUrl = it
                                mcpError = null
                            },
                            label = { Text("URL") },
                            placeholder = { Text("https://example.com/mcp", color = DlaColors.TextDim) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = mcpToken,
                            onValueChange = {
                                mcpToken = it
                                mcpError = null
                            },
                            label = { Text("Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        mcpError?.let { message ->
                            Text(message, color = DlaColors.Error, fontSize = 11.sp)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = saveMcp) {
                                Text(Lang.t("保存"), fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                showMcpForm = false
                                mcpError = null
                            }) {
                                Text(Lang.t("取消"), color = DlaColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    } else {
                        OutlinedButton(onClick = { showMcpForm = true }) {
                            Text(Lang.t("添加服务器"), fontSize = 12.sp)
                        }
                    }
                }
            }

            // ---------------- Termux 环境 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DlaColors.Surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle(Lang.t("Termux 环境"))
                    Text(
                        Lang.t("需要 Termux 里 allow-external-apps=true 并给 DLA 授予 Run commands 权限。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        Lang.t(
                            "Termux：%s",
                            if (termuxInfo.installed) Lang.t("已安装") else Lang.t("未安装"),
                        ),
                        color = if (termuxInfo.installed) DlaColors.Mint else DlaColors.Warm,
                        fontSize = 12.sp,
                    )
                    if (termuxInfo.version.isNotBlank()) {
                        Text(Lang.t("版本：%s", termuxInfo.version), color = DlaColors.TextDim, fontSize = 11.sp)
                    }
                    Text(
                        Lang.t(
                            "Run commands 权限：%s",
                            if (termuxInfo.granted) Lang.t("已授予") else Lang.t("未授予"),
                        ),
                        color = if (termuxInfo.granted) DlaColors.Mint else DlaColors.Warm,
                        fontSize = 12.sp,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openTermux) {
                            Text(Lang.t("打开 Termux"), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = openAppSettings) {
                            Text(Lang.t("应用权限"), fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = testTermux, enabled = !termuxTesting) {
                            Text(if (termuxTesting) Lang.t("测试中…") else Lang.t("测试连通"), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = copyTermuxCommand) {
                            Text(if (termuxCopied) Lang.t("已复制") else Lang.t("复制授权命令"), fontSize = 12.sp)
                        }
                    }

                    if (termuxResult.isNotEmpty()) {
                        HorizontalDivider(color = DlaColors.Line)
                        Text(
                            termuxResult.take(TERMUX_RESULT_LIMIT),
                            color = if (termuxFailed) DlaColors.Error else DlaColors.Text,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // ---------------- 数据 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DlaColors.Surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle(Lang.t("数据"))
                    Text(
                        if (sessionCount < 0) Lang.t("会话数：统计中…") else Lang.t("会话数：%d", sessionCount),
                        color = DlaColors.Text,
                        fontSize = 12.sp,
                    )
                    Text(
                        Lang.t("网页留下的历史会在原生会话仓库初始化时自动导入一次，只补缺失，无需手动导入。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = exportDiagnostics, enabled = !exporting) {
                            Text(if (exporting) Lang.t("导出中…") else Lang.t("导出诊断"), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching { SessionExport.exportAll(context) }
                                    }
                                    dataMessage = outcome.fold(
                                        onSuccess = { Lang.t("已导出到 %s", it) },
                                        onFailure = {
                                            Lang.t("导出失败：%s", it.message ?: it.javaClass.simpleName)
                                        },
                                    )
                                    dataMessageError = outcome.isFailure
                                }
                            },
                        ) { Text(Lang.t("导出历史"), fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { confirmDeleteAll = true },
                            enabled = !deleting,
                        ) {
                            Text(Lang.t("删除全部会话"), color = DlaColors.Error, fontSize = 12.sp)
                        }
                    }

                    if (dataMessage.isNotEmpty()) {
                        Text(
                            dataMessage,
                            color = if (dataMessageError) DlaColors.Error else DlaColors.TextDim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    HorizontalDivider(color = DlaColors.Line)
                    OutlinedButton(onClick = onOpenWeb) {
                        Text(Lang.t("打开网页界面"), color = DlaColors.Warm, fontSize = 12.sp)
                    }
                }
            }

            ShizukuCard()
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(Lang.t("删除全部会话")) },
            text = {
                Text(
                    if (sessionCount > 0) Lang.t("将删除本机 %d 个会话，无法恢复。", sessionCount)
                    else Lang.t("将删除本机全部会话，无法恢复。")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    deleteAllSessions()
                }) {
                    Text(Lang.t("删除"), color = DlaColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(Lang.t("取消"), color = DlaColors.TextDim)
                }
            },
            containerColor = DlaColors.SurfaceHigh,
            titleContentColor = DlaColors.Text,
            textContentColor = DlaColors.Text,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = DlaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun McpEntryRow(entry: McpEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name.ifBlank { Lang.t("未命名服务器") },
                color = DlaColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.url,
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.enabled) Lang.t("已启用") else Lang.t("已停用"),
                color = if (entry.enabled) DlaColors.Mint else DlaColors.TextDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(
                        if (entry.enabled) DlaColors.Mint.copy(alpha = 0.12f) else DlaColors.SurfaceHigh,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        TextButton(onClick = onRemove) {
            Text(Lang.t("删除"), color = DlaColors.Error, fontSize = 12.sp)
        }
    }
}
