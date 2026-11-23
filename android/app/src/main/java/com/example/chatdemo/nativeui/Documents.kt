package com.example.chatdemo.nativeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 文档附件：不依赖服务端 Files API，尽量在本地把内容变成模型能读的东西。
 *
 * - 纯文本（txt/md/csv/json/日志/代码）：直接读成文本，截断到 [MAX_TEXT] 字符；
 * - PDF：用系统 PdfRenderer 渲染前几页成 JPEG（走已有的图片块通道）；
 * - docx：解压 `word/document.xml`，去标签取正文；
 * - 其它：返回空，由 UI 提示不支持。
 */
object Documents {

    private const val MAX_TEXT = 20_000
    private const val MAX_PDF_PAGES = 3
    private const val PAGE_MAX_EDGE = 1400
    private const val PAGE_QUALITY = 80

    /** 附件类型常量，和 SessionModels.Attachment.kind 对应 */
    const val KIND_IMAGE = "image"
    const val KIND_TEXT = "text"

    /** Agent 区：把原文件复制到应用私有目录，返回路径与大小，供 Termux 直接读取 */
    fun copyForAgent(context: Context, uri: Uri, name: String): Pair<String, Long>? = runCatching {
        copyForAgent(context, name) { context.contentResolver.openInputStream(uri) }
    }.getOrNull()

    /** 本地文件版本：📂 文件浏览器拿到真实路径后直接复制，不再绕 content:// */
    fun copyForAgent(context: Context, file: java.io.File): Pair<String, Long>? = runCatching {
        copyForAgent(context, file.name) { java.io.FileInputStream(file) }
    }.getOrNull()

    private fun copyForAgent(
        context: Context,
        name: String,
        open: () -> java.io.InputStream?,
    ): Pair<String, Long>? {
        val dir = java.io.File(context.filesDir, "agent-uploads").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(60)
            .ifBlank { "upload" }
        val target = java.io.File(dir, "${System.currentTimeMillis()}-$safe")
        open()?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return target.absolutePath to target.length()
    }

    /**
     * @param forAgent Agent 区会把原文件落到私有目录并带上路径；LLM 区只做能直接进上下文的形式
     */
    fun extract(context: Context, uri: Uri, forAgent: Boolean = false): List<Attachment> {
        val name = queryName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val lower = name.lowercase()

        return when {
            mime.startsWith("image/") -> {
                val data = Attachments.dataUrl(context, uri) ?: return emptyList()
                listOf(Attachment(kind = KIND_IMAGE, name = name, dataUrl = data))
            }

            mime == "application/pdf" || lower.endsWith(".pdf") -> {
                val blocks = renderPdf(name) {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }.toMutableList()
                if (forAgent) {
                    copyForAgent(context, uri, name)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            lower.endsWith(".docx") || mime.contains("wordprocessingml") -> {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { docxText(it.readBytes()) }
                }.getOrNull()
                val blocks = textBlock(name, text).toMutableList()
                if (forAgent) {
                    copyForAgent(context, uri, name)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            isTextual(mime, lower) -> {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                val blocks = textBlock(name, text).toMutableList()
                if (forAgent) {
                    // 文本类也留一份原文件，方便 Termux 里用 grep/awk 等工具
                    copyForAgent(context, uri, name)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            // Agent 区：其余格式一律落到私有目录，由 Termux 侧的 pdftotext/unzip/python 等处理
            forAgent -> copyForAgent(context, uri, name)?.let {
                listOf(Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second))
            } ?: emptyList()

            else -> emptyList()
        }
    }

    fun fileName(context: Context, uri: Uri): String = queryName(context, uri)

    /**
     * 本地文件版提取（📂 文件浏览器）：不再依赖 content://，按真实路径直接读。
     *
     * 规则与 [extract] 一致：图片压成 data URL；PDF 渲页图；docx 抽正文；文本截断。
     * Agent 区额外把原文件复制到 `agent-uploads`，让 Termux 能按绝对路径处理
     * （图片除外，它与 [extract] 保持同样的行为，只走图片块）。
     */
    fun extractLocal(context: Context, file: java.io.File, forAgent: Boolean = false): List<Attachment> {
        val name = file.name
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")
        val mime = runCatching {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull().orEmpty()

        return when {
            mime.startsWith("image/") || ext in IMAGE_EXTS -> {
                val data = Attachments.dataUrl(file) ?: return emptyList()
                listOf(Attachment(kind = KIND_IMAGE, name = name, dataUrl = data))
            }

            mime == "application/pdf" || ext == "pdf" -> {
                val blocks = renderPdf(name) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }.toMutableList()
                if (forAgent) {
                    copyForAgent(context, file)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            ext == "docx" || mime.contains("wordprocessingml") -> {
                val bytes = runCatching {
                    if (file.length() > MAX_DOCX_BYTES) null else file.readBytes()
                }.getOrNull()
                val blocks = textBlock(name, bytes?.let { docxText(it) }).toMutableList()
                if (forAgent) {
                    copyForAgent(context, file)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            isTextual(mime, lower) -> {
                // 只读文件头：日志/数据文件可能有几百 MB，读全量会把主线程外的内存打满
                val bytes = runCatching { readHead(file, MAX_TEXT * 4) }.getOrNull()
                val text = bytes?.let { it.toString(Charsets.UTF_8) }
                val blocks = textBlock(name, text).toMutableList()
                if (forAgent) {
                    copyForAgent(context, file)?.let {
                        blocks += Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second)
                    }
                }
                blocks
            }

            forAgent -> copyForAgent(context, file)?.let {
                listOf(Attachment(kind = "file", name = name, localPath = it.first, sizeBytes = it.second))
            } ?: emptyList()

            else -> emptyList()
        }
    }

    /** 图片扩展名：MimeTypeMap 对 heic/webp 偶有缺失，这里兜一层 */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    private const val MAX_DOCX_BYTES = 30L * 1024 * 1024

    private fun readHead(file: java.io.File, limit: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = input.read(buffer, read, limit - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    }

    private fun textBlock(name: String, text: String?): List<Attachment> {
        if (text.isNullOrBlank()) return emptyList()
        val trimmed = if (text.length > MAX_TEXT) {
            text.take(MAX_TEXT) + "\n\n…（内容过长，已截断）"
        } else {
            text
        }
        return listOf(Attachment(kind = KIND_TEXT, name = name, text = trimmed))
    }

    private fun isTextual(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-sh",
                "application/x-yaml",
            )
        ) {
            return true
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log",
            "ini", "conf", "properties", "sh", "bash", "py", "js", "ts", "kt", "java",
            "c", "h", "cpp", "go", "rs", "sql", "html", "css",
        )
    }

    private fun renderPdf(name: String, open: () -> ParcelFileDescriptor?): List<Attachment> {
        val descriptor = open() ?: return emptyList()
        val attachments = ArrayList<Attachment>()
        runCatching {
            PdfRenderer(descriptor).use { renderer ->
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (index in 0 until pages) {
                    renderer.openPage(index).use { page ->
                        val ratio = PAGE_MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                        val width = (page.width * ratio).toInt().coerceAtLeast(1)
                        val height = (page.height * ratio).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PDF 页面默认透明，先铺白底，避免模型看到"黑底白字"的反色图
                        android.graphics.Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bytes = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, PAGE_QUALITY, out)
                            out.toByteArray()
                        }
                        bitmap.recycle()
                        if (bytes.isNotEmpty()) {
                            attachments += Attachment(
                                kind = KIND_IMAGE,
                                name = "$name 第${index + 1}页",
                                dataUrl = "data:image/jpeg;base64," +
                                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                            )
                        }
                    }
                }
            }
        }
        // PdfRenderer 关闭会连带关闭 fd，这里只兜底
        runCatching { descriptor.close() }
        return attachments
    }

    private fun docxText(bytes: ByteArray): String? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return cleanWordXml(xml)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** docx 的段落标记要换成换行，其它标签直接去掉 */
    private fun cleanWordXml(xml: String): String {
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("<w:br/>", "\n")
            .replace("<w:tab/>", "\t")
        val withoutTags = withBreaks.replace(Regex("<[^>]+>"), "")
        return withoutTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()
    }

    private fun queryName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index) ?: fallback
                } else {
                    fallback
                }
            } ?: fallback
        }.getOrDefault(fallback)
    }
}
