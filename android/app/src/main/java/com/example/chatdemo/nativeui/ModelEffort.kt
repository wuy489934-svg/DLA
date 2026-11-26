package com.example.chatdemo.nativeui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 模型 + 思考强度：两个区的统一入口。
 *
 * 一个胶囊显示「当前模型 · 低/中/高」，点开是同一个对话框选模型、选档位；
 * 图标是自绘的「表盘」——指针偏左=轻、居中=平衡、偏右=重，和别家图标区分开。
 */
@Composable
fun ModelEffortPill(mode: String?, onClick: () -> Unit) {
    val accent = if (mode == "agent") DlaColors.Mint else DlaColors.Warm
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(DlaColors.Surface)
                .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelDialGlyph(color = accent, size = 14.dp, level = ChatEngine.thinkingLevel)
            Spacer(Modifier.width(6.dp))
            Text(
                ChatEngine.model,
                color = DlaColors.Text,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "· " + levelLabel(ChatEngine.thinkingLevel),
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

fun levelLabel(level: String): String = when (level) {
    "low" -> Lang.t("低")
    "high" -> Lang.t("高")
    else -> Lang.t("中")
}

/** 自绘表盘图标：圆环 + 指针（指针角度随档位） */
@Composable
fun ModelDialGlyph(color: Color, size: Dp = 14.dp, level: String = "medium") {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.12f
        val radius = w * 0.40f
        val center = Offset(w / 2f, this.size.height / 2f)
        drawCircle(color = color.copy(alpha = 0.85f), radius = radius, center = center, style = Stroke(width = stroke))
        val angle = when (level) {
            "low" -> -40f
            "high" -> 40f
            else -> 0f
        }
        val radians = Math.toRadians(angle.toDouble() - 90.0)
        drawLine(
            color = color,
            start = center,
            end = Offset(
                center.x + (radius * 0.72f * Math.cos(radians)).toFloat(),
                center.y + (radius * 0.72f * Math.sin(radians)).toFloat(),
            ),
            strokeWidth = stroke * 1.1f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ModelEffortDialog(onDismiss: () -> Unit, onApplied: () -> Unit) {
    var model by remember { mutableStateOf(ChatEngine.model) }
    var level by remember { mutableStateOf(ChatEngine.thinkingLevel) }
    val cached = remember { ChatEngine.cachedModels }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t("模型与思考强度"), fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(Lang.t("模型名")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cached.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Lang.t("已缓存的模型（共 %d 个，可滚动）", cached.size),
                        color = DlaColors.TextDim,
                        fontSize = 10.sp,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cached.forEach { name ->
                            val active = name == model
                            Text(
                                name,
                                color = if (active) DlaColors.Warm else DlaColors.Text,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) DlaColors.Warm.copy(alpha = 0.14f) else DlaColors.SurfaceHigh)
                                    .clickable { model = name }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(Lang.t("思考强度"), color = DlaColors.TextDim, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("low" to "低", "medium" to "中", "high" to "高").forEach { (key, label) ->
                        val active = key == level
                        Text(
                            Lang.t(label),
                            color = if (active) DlaColors.OnAccent else DlaColors.Text,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) DlaColors.Warm else DlaColors.SurfaceHigh)
                                .clickable { level = key }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    Lang.t("低≈2048 / 中≈4096 / 高≈8192 tokens；DeepSeek 端点由官方控制，不受此档影响。"),
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (model.isNotBlank()) ChatEngine.model = model
                ChatEngine.thinkingLevel = level
                onApplied()
            }) { Text(Lang.t("应用"), color = DlaColors.Warm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Lang.t("取消"), color = DlaColors.TextDim) }
        },
    )
}
