package com.example.chatdemo.nativeui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * 附件入口：一个按键收口「拍照 / 图片 / 文件」，图标全部自绘。
 *
 * 为什么不用 emoji：🖼 / 📂 是系统字体里的彩色符号，深色主题下饱和度和描边都不受控，
 * 和 DLA 的薄荷绿细线风格对不上；这里用 Canvas 按 24 格栅自绘，颜色全部取自 [DlaColors]，
 * 缩放时线宽跟着等比走。
 */
enum class AttachKind { CAMERA, IMAGE, FILE }

/** 入口图标：圆角方框（灯座）+ 中心暖色灯点 + 右下角加号徽章 */
@Composable
fun AttachGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    accent: Color,
    backing: Color,
) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val frame = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(4.2f * u, 3.2f * u),
            size = Size(15.2f * u, 15.2f * u),
            cornerRadius = CornerRadius(4.4f * u, 4.4f * u),
            style = frame,
        )
        drawCircle(color = accent, radius = 2.3f * u, center = Offset(11.8f * u, 10.8f * u))
        // 加号徽章：先用背景色盖掉被压住的边框，再画十字
        val badge = Offset(18.2f * u, 18.4f * u)
        drawCircle(color = backing, radius = 4.3f * u, center = badge)
        val arm = 3.2f * u
        drawLine(tint, Offset(badge.x - arm, badge.y), Offset(badge.x + arm, badge.y), 2.0f * u, StrokeCap.Round)
        drawLine(tint, Offset(badge.x, badge.y - arm), Offset(badge.x, badge.y + arm), 2.0f * u, StrokeCap.Round)
    }
}

/** 三个入口各自的细线图标：同一套 24 格栅、同一线宽 */
@Composable
fun AttachKindGlyph(
    kind: AttachKind,
    modifier: Modifier = Modifier,
    tint: Color,
    accent: Color,
) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val line = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            AttachKind.CAMERA -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.2f * u, 7.4f * u),
                    size = Size(17.6f * u, 12.0f * u),
                    cornerRadius = CornerRadius(2.8f * u, 2.8f * u),
                    style = line,
                )
                val hump = Path().apply {
                    moveTo(8.6f * u, 7.4f * u)
                    lineTo(9.8f * u, 5.2f * u)
                    lineTo(14.2f * u, 5.2f * u)
                    lineTo(15.4f * u, 7.4f * u)
                }
                drawPath(hump, color = tint, style = line)
                drawCircle(color = tint, radius = 3.5f * u, center = Offset(12f * u, 13.4f * u), style = line)
                drawCircle(color = accent, radius = 1.0f * u, center = Offset(17.6f * u, 9.6f * u))
            }
            AttachKind.IMAGE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.2f * u, 4.8f * u),
                    size = Size(17.6f * u, 14.4f * u),
                    cornerRadius = CornerRadius(2.8f * u, 2.8f * u),
                    style = line,
                )
                drawCircle(color = accent, radius = 1.7f * u, center = Offset(8.4f * u, 9.6f * u))
                val hills = Path().apply {
                    moveTo(4.8f * u, 17.4f * u)
                    lineTo(9.8f * u, 11.4f * u)
                    lineTo(13.0f * u, 15.0f * u)
                    lineTo(15.6f * u, 12.4f * u)
                    lineTo(19.4f * u, 17.4f * u)
                }
                drawPath(hills, color = tint, style = line)
            }
            AttachKind.FILE -> {
                val page = Path().apply {
                    moveTo(6.2f * u, 3.6f * u)
                    lineTo(14.2f * u, 3.6f * u)
                    lineTo(18.4f * u, 7.8f * u)
                    lineTo(18.4f * u, 20.2f * u)
                    lineTo(6.2f * u, 20.2f * u)
                    close()
                }
                drawPath(page, color = tint, style = line)
                val fold = Path().apply {
                    moveTo(14.2f * u, 3.6f * u)
                    lineTo(14.2f * u, 7.8f * u)
                    lineTo(18.4f * u, 7.8f * u)
                }
                drawPath(fold, color = tint, style = line)
                drawLine(tint, Offset(9.2f * u, 12.6f * u), Offset(15.4f * u, 12.6f * u), 1.5f * u, StrokeCap.Round)
                drawLine(tint, Offset(9.2f * u, 16.0f * u), Offset(15.4f * u, 16.0f * u), 1.5f * u, StrokeCap.Round)
            }
        }
    }
}

/** 输入栏那一个按键：点开是三个入口的菜单 */
@Composable
fun AttachButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val mint = DlaColors.Mint
    val warm = DlaColors.Warm
    val surface = DlaColors.Surface
    val surfaceHigh = DlaColors.SurfaceHigh
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (expanded) mint.copy(alpha = 0.16f) else surfaceHigh)
                .border(
                    1.dp,
                    if (expanded) mint.copy(alpha = 0.55f) else Color.Transparent,
                    RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            AttachGlyph(
                modifier = Modifier.size(21.dp),
                tint = mint,
                accent = warm,
                backing = surfaceHigh,
            )
        }
        AttachMenu(
            expanded = expanded,
            onDismiss = onDismiss,
            onPickCamera = onPickCamera,
            onPickImage = onPickImage,
            onPickFiles = onPickFiles,
            background = surface,
        )
    }
}

@Composable
private fun AttachMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickFiles: () -> Unit,
    background: Color,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = background,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(176.dp),
    ) {
        AttachMenuItem(AttachKind.CAMERA, Lang.t("拍照"), onPickCamera)
        AttachMenuItem(AttachKind.IMAGE, Lang.t("图片"), onPickImage)
        AttachMenuItem(AttachKind.FILE, Lang.t("文件"), onPickFiles)
    }
}

@Composable
private fun AttachMenuItem(kind: AttachKind, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachKindGlyph(
                    kind = kind,
                    modifier = Modifier.size(18.dp),
                    tint = DlaColors.Mint,
                    accent = DlaColors.Warm,
                )
                Spacer(Modifier.width(12.dp))
                Text(label, color = DlaColors.Text, fontSize = 13.sp)
            }
        },
        onClick = onClick,
    )
}

/**
 * 拍照：系统相机把照片写进我们通过 FileProvider 给出的 content:// URI。
 *
 * ActivityResultContracts.TakePicture 只塞 EXTRA_OUTPUT、不加授权标志，
 * 部分 ROM（MIUI / 定制相机）会因此写不进去，所以这里自带一个带 grant 的契约，
 * 并且在启动前对每个能响应拍照的包补一次临时 URI 授权。
 */
class TakePictureWithGrantContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}

object PhotoCapture {

    /** 缓存目录里的临时目标文件：拍完读成 data URL 就删 */
    fun newTarget(context: Context): File {
        val dir = File(context.cacheDir, "camera")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "dla-photo-" + System.currentTimeMillis() + ".jpg")
    }

    fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** 逐个相机应用补临时授权：比只靠 intent 标志稳 */
    fun grant(context: Context, uri: Uri) {
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val handlers = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                    PackageManager.ResolveInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), flags)
            }
        }.getOrDefault(emptyList())
        handlers.forEach { info ->
            runCatching {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
}
