package com.example.chatdemo.nativeui

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
import androidx.compose.foundation.layout.width
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
import com.example.chatdemo.agent.NativeShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 高级设置：MCP 服务器、运行环境、数据管理。
 *
 * 只负责界面与本地状态，能力全部复用已有实现：
 * - MCP：agent 包 McpStore，配置落在 SharedPreferences 的 servers 数组里；
 * - 运行环境：本机终端 / Python / Node / ripgrep 全部内置，[RuntimeStatus] 做自检；
 * - 数据：agent 包 Diagnostics 导出 zip，本包 SessionStore 管理 files/native-sessions。
 *
 * 约定：磁盘与跨进程调用一律 withContext(Dispatchers.IO)，主线程只改 Compose 状态；
 * 不使用 WebView，不引入新依赖。
 */

private const val RUNTIME_RESULT_LIMIT = 600

/** 列表展示用的 MCP 服务器快照（原始数据仍在 McpStore 的 JSON 里） */
private data class McpEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
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
fun AdvancedSettingsScreen(onBack: () -> Unit) {
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

    // 运行环境（本机终端 / Python / Node / ripgrep）
    var runtime by remember { mutableStateOf(RuntimeSnapshot()) }
    var runtimeRefresh by remember { mutableStateOf(0) }
    var runtimeResult by remember { mutableStateOf("") }
    var runtimeFailed by remember { mutableStateOf(false) }
    var runtimeTesting by remember { mutableStateOf(false) }

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

    LaunchedEffect(runtimeRefresh) {
        runtime = withContext(Dispatchers.IO) { RuntimeStatus.probe(appContext) }
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

    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // 真跑一条命令：同时验证 shell、Node、ripgrep 三个内置运行时
    val testRuntime: () -> Unit = {
        if (!runtimeTesting) {
            runtimeTesting = true
            runtimeResult = ""
            runtimeFailed = false
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        NativeShell.execute(
                            appContext,
                            JSONObject().put(
                                "command",
                                "id -u; toybox | wc -w; node -v; rg --version | head -1",
                            ),
                        )
                    }.getOrNull()
                }
                runtimeResult = result?.text.orEmpty()
                runtimeFailed = result?.isError ?: true
                runtimeTesting = false
                runtimeRefresh += 1
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

            // ---------------- 运行环境 ----------------
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
                    SectionTitle(Lang.t("运行环境"))
                    Text(
                        Lang.t("全部内置，不需要安装 Termux；命令以应用身份运行，不会修改系统。"),
                        color = DlaColors.TextDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    RuntimeRow(
                        Lang.t("本机终端"),
                        if (runtime.checked) {
                            Lang.t("Android %s · toybox %d 条命令", runtime.androidRelease, runtime.shellCommands)
                        } else {
                            Lang.t("检测中…")
                        },
                    )
                    RuntimeRow(
                        Lang.t("Python"),
                        if (runtime.checked) Lang.t("内置 %s", runtime.pythonVersion) else Lang.t("检测中…"),
                    )
                    RuntimeRow(
                        Lang.t("Node"),
                        if (runtime.checked) Lang.t("内置 %s", runtime.nodeVersion) else Lang.t("检测中…"),
                    )
                    RuntimeRow(
                        Lang.t("ripgrep"),
                        if (runtime.checked) Lang.t("内置 %s", runtime.rgVersion) else Lang.t("检测中…"),
                    )
                    RuntimeRow(Lang.t("工作目录"), runtime.home.ifBlank { "…" })

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = testRuntime, enabled = !runtimeTesting) {
                            Text(if (runtimeTesting) Lang.t("自检中…") else Lang.t("运行自检"), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = openAppSettings) {
                            Text(Lang.t("应用权限"), fontSize = 12.sp)
                        }
                    }

                    if (runtimeResult.isNotEmpty()) {
                        HorizontalDivider(color = DlaColors.Line)
                        Text(
                            runtimeResult.take(RUNTIME_RESULT_LIMIT),
                            color = if (runtimeFailed) DlaColors.Error else DlaColors.Text,
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
                        Lang.t("旧版本留下的历史会在会话仓库初始化时自动导入一次，只补缺失，无需手动导入。"),
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

                }
            }

            PhoneCapabilityCard()
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
@Composable
private fun RuntimeRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = DlaColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            color = DlaColors.Text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
