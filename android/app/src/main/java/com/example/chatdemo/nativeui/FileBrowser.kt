package com.example.chatdemo.nativeui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本机文件浏览（📂）：不经过 SAF 的「安全访问」界面，直接按真实路径列目录、搜索、选文件。
 *
 * 前提是「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）。没授权时这里只显示一张说明卡，
 * 一键跳到系统授权页；返回时用 Lifecycle ON_RESUME 自动复查，不用手动刷新。
 * 授权页打不开（个别 ROM 裁剪了该设置项）才会退回系统文件选择器。
 *
 * 为什么不用 SAF：Android 11+ 的 SAF 根目录是灰的（「为保护您的隐私，请选择其他文件夹」），
 * 而且系统选择器里没有搜索。真实路径 + 自己画列表才能做到「单点直达 + 搜索」。
 */
object LocalFiles {

    /** 单次选文件上限 */
    const val MAX_SELECTED = 20

    /** 文件夹导入上限，与原来的 SAF 批量导入保持一致 */
    const val MAX_FOLDER_FILES = 24
    const val MAX_FOLDER_BYTES = 8L * 1024 * 1024

    private const val SEARCH_LIMIT = 300
    private const val SEARCH_DEPTH = 6

    @Volatile
    var lastDir: File? = null

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun openAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val direct = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(direct) }.isSuccess ||
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            if (ok) return
        }
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** 默认落点：优先「图片」目录，其次相机目录，最后存储根 */
    fun startDir(): File {
        lastDir?.let { if (it.isDirectory && it.canRead()) return it }
        val root = storageRoot()
        return listOf(
            File(root, "Pictures"),
            File(File(root, "DCIM"), "Camera"),
            File(root, "DCIM"),
            root,
        ).firstOrNull { it.isDirectory && it.canRead() } ?: root
    }

    fun picturesDir(): File {
        val root = storageRoot()
        return listOf(
            File(root, "Pictures"),
            File(File(root, "DCIM"), "Camera"),
            File(root, "DCIM"),
            root,
        ).firstOrNull { it.isDirectory } ?: root
    }

    /**
     * 绝对路径解析：以 `/` 开头、`~` 开头或写成「内部存储/…」都认。
     *
     * 返回 File 不代表它存在——上层用「存在则直达 / 不存在则按父目录 + 文件名搜」两种策略。
     * 不是路径形态（普通关键词）返回 null，走原来的搜索。
     */
    fun asPath(raw: String): File? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val root = storageRoot()
        return when {
            text == "~" -> root
            text.startsWith("~/") -> File(root, text.substring(2))
            text == "内部存储" -> root
            text.startsWith("内部存储/") -> File(root, text.removePrefix("内部存储/"))
            text.startsWith("/") -> File(text)
            else -> null
        }
    }

    /** 全盘搜索：从存储根开始，忽略当前目录 */
    fun searchAll(query: String): List<File> = search(storageRoot(), query)

    fun children(dir: File): List<File> {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        return files
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /**
     * 递归搜索：跳过隐藏目录与 Android/{data,obb}（里面全是别的应用的私有目录）。
     * 命中上限后立刻停止，避免在几千个文件的目录里卡住。
     */
    fun search(root: File, query: String): List<File> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<File>()
        val dirs = ArrayDeque<Pair<File, Int>>()
        dirs.addLast(root to 0)
        while (dirs.isNotEmpty() && out.size < SEARCH_LIMIT) {
            val (dir, depth) = dirs.removeLast()
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (file.name.startsWith(".")) continue
                if (file.isDirectory) {
                    if (depth >= SEARCH_DEPTH) continue
                    if (file.name == "Android") continue
                    dirs.addLast(file to depth + 1)
                } else if (file.name.lowercase().contains(needle)) {
                    out += file
                    if (out.size >= SEARCH_LIMIT) break
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** 文件夹导入：当前目录起递归收集，上限与 SAF 版本一致 */
    fun collectFolder(dir: File): List<File> {
        val out = ArrayList<File>()
        val dirs = ArrayDeque<Pair<File, Int>>()
        dirs.addLast(dir to 0)
        while (dirs.isNotEmpty() && out.size < MAX_FOLDER_FILES) {
            val (current, depth) = dirs.removeLast()
            val files = runCatching { current.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (file.name.startsWith(".")) continue
                if (file.isDirectory) {
                    if (depth >= 3 || file.name == "Android") continue
                    dirs.addLast(file to depth + 1)
                } else if (file.length() in 0..MAX_FOLDER_BYTES) {
                    out += file
                    if (out.size >= MAX_FOLDER_FILES) break
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun displayPath(file: File): String {
        val root = storageRoot().absolutePath
        val path = file.absolutePath
        val label = Lang.t("内部存储")
        return if (path == root) label else path.replaceFirst(root, label)
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    fun iconFor(file: File): String {
        if (file.isDirectory) return "📁"
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> "🖼"
            "pdf" -> "📕"
            "docx", "doc", "txt", "md" -> "📄"
            "zip", "7z", "rar", "tar", "gz" -> "🗜"
            "mp4", "mov", "mkv", "avi" -> "🎬"
            "mp3", "wav", "flac", "m4a", "ogg" -> "🎵"
            "apk" -> "📦"
            else -> "📄"
        }
    }
}

@Composable
fun FileBrowserPanel(
    forAgent: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<Attachment>) -> Unit,
    onSystemFilePicker: () -> Unit,
    onSystemFolderPicker: () -> Unit,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(LocalFiles.hasAccess(context)) }

    // 从系统授权页返回后自动复查，省掉「我已授权」这种手动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = LocalFiles.hasAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { access = LocalFiles.hasAccess(context) }

    // 不用 Dialog：独立窗口在 MIUI 上手势条 inset 不生效，底部按钮会被压住。
    // 直接铺在聊天页上面（同一 Activity 窗口），insets 与其它页面一致。
    Surface(
        color = DlaColors.Background,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text(Lang.t("关闭"), color = DlaColors.TextDim, fontSize = 14.sp)
                }
                Text(
                    Lang.t("选择文件"),
                    color = DlaColors.Text,
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (access) {
                BrowserBody(
                    forAgent = forAgent,
                    onPicked = onPicked,
                )
            } else {
                AccessGate(
                    modifier = Modifier.fillMaxSize(),
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            LocalFiles.openAccessSettings(context)
                        } else {
                            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    onSystemFilePicker = onSystemFilePicker,
                    onSystemFolderPicker = if (forAgent) onSystemFolderPicker else null,
                )
            }
        }
    }
}

@Composable
private fun AccessGate(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onSystemFilePicker: () -> Unit,
    onSystemFolderPicker: (() -> Unit)?,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📂", fontSize = 34.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            Lang.t("需要「所有文件访问权限」"),
            color = DlaColors.Text,
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            Lang.t("授权后可以直接按路径浏览、搜索并发送本机文件，不需要再走系统的安全访问界面。文件不会被上传，只有你选中的内容会进入对话。"),
            color = DlaColors.TextDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(DlaColors.Mint)
                .clickable(onClick = onRequest)
                .padding(horizontal = 26.dp, vertical = 11.dp),
        ) {
            Text(Lang.t("去授权"), color = DlaColors.OnAccent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSystemFilePicker) {
            Text(Lang.t("改用系统文件选择器"), color = DlaColors.TextDim, fontSize = 12.sp)
        }
        if (onSystemFolderPicker != null) {
            TextButton(onClick = onSystemFolderPicker) {
                Text(Lang.t("改用系统文件夹选择器"), color = DlaColors.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BrowserBody(
    forAgent: Boolean,
    onPicked: (List<Attachment>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(LocalFiles.startDir()) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    /** 搜索范围：默认当前目录；打开后从存储根全盘搜 */
    var wholeDisk by remember { mutableStateOf(false) }

    LaunchedEffect(current, query, wholeDisk) {
        val raw = query.trim()
        // 绝对路径只「预览」不自动跳转：一个字一个字输入时会一路跳目录，反而找不到东西
        val target = LocalFiles.asPath(raw)
        busy = true
        entries = withContext(Dispatchers.IO) {
            when {
                raw.isBlank() -> LocalFiles.children(current)
                target == null -> if (wholeDisk) LocalFiles.searchAll(raw) else LocalFiles.search(current, raw)
                target.isDirectory -> LocalFiles.children(target)
                target.isFile -> listOf(target)
                target.parentFile?.isDirectory == true -> LocalFiles.children(target.parentFile!!)
                    .filter { !it.isDirectory && it.name.contains(target.name, ignoreCase = true) }
                else -> LocalFiles.searchAll(target.name)
            }
        }
        if (target != null && target.isFile && entries.size == 1) {
            selected = setOf(target.absolutePath)
        }
        busy = false
    }

    fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            val picked = withContext(Dispatchers.IO) {
                files.take(LocalFiles.MAX_SELECTED).flatMap { file ->
                    runCatching { Documents.extractLocal(context, file, forAgent) }.getOrDefault(emptyList())
                }
            }
            busy = false
            LocalFiles.lastDir = current
            onPicked(picked)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickChip(Lang.t("图库"), start = LocalFiles.picturesDir()) { current = it; query = "" }
            QuickChip(Lang.t("相机"), start = File(File(LocalFiles.storageRoot(), "DCIM"), "Camera")) { current = it; query = "" }
            QuickChip(Lang.t("下载"), start = File(LocalFiles.storageRoot(), "Download")) { current = it; query = "" }
            QuickChip(Lang.t("文档"), start = File(LocalFiles.storageRoot(), "Documents")) { current = it; query = "" }
            QuickChip(Lang.t("全部"), start = LocalFiles.storageRoot()) { current = it; query = "" }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            placeholder = {
                Text(
                    Lang.t("搜文件名，或直接粘贴绝对路径（/sdcard/…）"),
                    color = DlaColors.TextDim,
                    fontSize = 13.sp,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = DlaColors.Text, fontSize = 14.sp),
        )
        Spacer(Modifier.height(6.dp))
        // 绝对路径命中目录时给一个明确的「进入」：输入过程中不会自动跳，避免边打字边换目录
        val pathTarget = LocalFiles.asPath(query.trim())
        if (pathTarget != null && pathTarget.isDirectory) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DlaColors.Mint.copy(alpha = 0.10f))
                    .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pathTarget.absolutePath,
                    color = DlaColors.Text,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    current = pathTarget
                    query = ""
                }) {
                    Text(Lang.t("进入"), color = DlaColors.Mint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "↑ " + Lang.t("上一级"),
                color = if (current.parentFile != null) DlaColors.Mint else DlaColors.TextDim.copy(alpha = 0.4f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = current.parentFile != null) {
                        current.parentFile?.let {
                            current = it
                            query = ""
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                LocalFiles.displayPath(current),
                color = DlaColors.TextDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            // 搜索范围：当前目录 ↔ 全盘（全盘慢一点，但不用一层层点进去找）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (wholeDisk) DlaColors.Mint.copy(alpha = 0.16f) else DlaColors.SurfaceHigh)
                    .clickable { wholeDisk = !wholeDisk }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (wholeDisk) Lang.t("全盘") else Lang.t("当前目录"),
                    color = if (wholeDisk) DlaColors.Mint else DlaColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f)) {
            if (entries.isEmpty() && !busy) {
                Text(
                    if (query.isBlank()) Lang.t("这个文件夹是空的") else Lang.t("没有匹配的文件"),
                    color = DlaColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 2.dp,
                    bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.absolutePath }) { file ->
                    val checked = selected.contains(file.absolutePath)
                    EntryRow(
                        file = file,
                        checked = checked,
                        showParent = query.isNotBlank(),
                        onClick = {
                            if (file.isDirectory) {
                                current = file
                                query = ""
                            } else {
                                selected = if (checked) {
                                    selected - file.absolutePath
                                } else {
                                    selected + file.absolutePath
                                }
                            }
                        },
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    color = DlaColors.Mint,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected.isEmpty()) {
                    Lang.t("点文件名选中，点文件夹进入")
                } else {
                    Lang.t("已选 %d 个", selected.size)
                },
                color = DlaColors.TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (forAgent && query.isBlank()) {
                TextButton(
                    onClick = {
                        val files = LocalFiles.collectFolder(current)
                        importFiles(files)
                    },
                    enabled = !busy,
                ) {
                    Text(Lang.t("导入此文件夹"), color = DlaColors.TextDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(4.dp))
            val enabled = selected.isNotEmpty() && !busy
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (enabled) DlaColors.Mint else DlaColors.SurfaceHigh)
                    .clickable(enabled = enabled) {
                        importFiles(selected.map { File(it) })
                    }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text(Lang.t("添加"), color = DlaColors.OnAccent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, start: File, onPick: (File) -> Unit) {
    val exists = start.isDirectory
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DlaColors.SurfaceHigh)
            .clickable(enabled = exists) { if (exists) onPick(start) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (exists) DlaColors.Text else DlaColors.TextDim.copy(alpha = 0.45f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EntryRow(
    file: File,
    checked: Boolean,
    showParent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) DlaColors.Mint.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(LocalFiles.iconFor(file), fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                color = DlaColors.Text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                file.isDirectory -> Lang.t("文件夹")
                // 搜索结果直接给绝对路径：能复制、能贴回搜索框，也方便让 Agent 直接读
                showParent -> (file.parentFile?.absolutePath ?: file.absolutePath) +
                    " · " + LocalFiles.formatSize(file.length())
                else -> LocalFiles.formatSize(file.length())
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = DlaColors.TextDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!file.isDirectory) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) DlaColors.Mint else androidx.compose.ui.graphics.Color.Transparent)
                    .border(
                        1.dp,
                        if (checked) DlaColors.Mint else DlaColors.TextDim.copy(alpha = 0.5f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Text("✓", color = DlaColors.OnAccent, fontSize = 11.sp)
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
    }
}
