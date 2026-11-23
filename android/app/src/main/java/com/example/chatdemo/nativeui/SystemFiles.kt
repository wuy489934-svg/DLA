package com.example.chatdemo.nativeui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 系统文件访问：通过 SAF 让用户授权一个文件夹（或存储根目录），
 * 再把这个文件夹下的文件**复制**到应用私有目录，交给 Agent 用 Termux 读取。
 *
 * 为什么是复制而不是直接给 URI：Termux 里的 shell 只能读真实路径，
 * 读不了 content:// 的授权 URI。复制一份是最稳的做法，也避免用户撤销授权后任务失败。
 */
object SystemFiles {

    data class Found(val name: String, val uri: Uri, val size: Long)

    private const val MAX_FILES = 24
    private const val MAX_DEPTH = 3
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    fun list(context: Context, treeUri: Uri): List<Found> {
        val result = ArrayList<Found>()
        walk(context, treeUri, 0, result)
        return result.take(MAX_FILES)
    }

    private fun walk(context: Context, parent: Uri, depth: Int, out: MutableList<Found>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent,
                DocumentsContract.getDocumentId(parent),
            )
        }.getOrNull() ?: return
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < MAX_FILES) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent, id)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(context, fileUri, depth + 1, out)
                    } else if (size in 0..MAX_FILE_BYTES) {
                        out += Found(name, fileUri, size)
                    }
                }
            }
        }
    }

    /** 把授权文件夹里的文件逐个复制进 agent-uploads，返回可直接给模型的附件 */
    fun importFolder(context: Context, treeUri: Uri): List<Attachment> {
        val found = list(context, treeUri)
        val attachments = ArrayList<Attachment>(found.size)
        found.forEach { file ->
            val copy = Documents.copyForAgent(context, file.uri, file.name) ?: return@forEach
            attachments += Attachment(
                kind = "file",
                name = file.name,
                localPath = copy.first,
                sizeBytes = copy.second,
            )
        }
        return attachments
    }

    /** 长期保留授权，下次可以直接再导入 */
    fun persist(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        ChatEngine.folderUri = treeUri.toString()
    }
}
