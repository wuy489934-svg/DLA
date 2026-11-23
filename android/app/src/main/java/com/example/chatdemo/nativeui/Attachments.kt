package com.example.chatdemo.nativeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream

/**
 * 附件：把相册图片压成可入请求的 data URL，并提供气泡里的小缩略图。
 *
 * 为什么压缩：Anthropic Messages 的图片块走 base64，原图动辄 5-8 MB，
 * 压到 1080px / JPEG 82 后通常 150-350 KB，既不糊也省流量与内存。
 */
object Attachments {

    private const val MAX_EDGE = 1080
    private const val QUALITY = 82

    fun dataUrl(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        dataUrlFrom { options ->
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()

    /** 本地文件版本：📂 文件浏览器在拿到全盘访问权限后直接按路径读，不再走 content:// */
    fun dataUrl(file: java.io.File): String? = runCatching {
        dataUrlFrom { options ->
            java.io.FileInputStream(file).use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()

    private fun dataUrlFrom(decode: (BitmapFactory.Options) -> Bitmap?): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = decode(options) ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest <= MAX_EDGE) {
            decoded
        } else {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        if (bytes.isEmpty()) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** data URL -> Bitmap；失败返回 null，UI 显示占位 */
    fun decode(dataUrl: String): Bitmap? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
fun AttachmentThumb(dataUrl: String, size: Dp = 72.dp, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(initialValue = null, dataUrl) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Attachments.decode(dataUrl)
        }
    }
    val image = bitmap
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(DlaColors.SurfaceHigh)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "附件图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text("图片", color = DlaColors.TextDim, fontSize = 10.sp)
        }
    }
}
