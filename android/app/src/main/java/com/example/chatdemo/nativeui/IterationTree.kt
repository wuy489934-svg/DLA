package com.example.chatdemo.nativeui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一轮 Agent 迭代：同一轮里可能有多个工具调用 */
data class IterationRound(
    val round: Int,
    val tools: List<Block.Tool>,
    val thinking: String? = null,
    val text: String? = null,
)

/**
 * 折叠式「Agent 迭代树」。
 *
 * 观感要点：
 * - 折叠头本身就是一个明确的入口：左侧薄荷色竖条 + 图标 + 标题 + 轮/步 chips + 总耗时；
 * - 展开后按轮列出，**点某一轮会展开该轮的工具参数/结果，并回调 [onLocate] 让聊天列表把这一轮滚出来**；
 * - 状态点与聊天区的工具卡保持一致（等待/进行中=暖色，失败=错误色，完成=主色）。
 */
@Composable
fun IterationTree(
    rounds: List<IterationRound>,
    modifier: Modifier = Modifier,
    onLocate: ((Int) -> Unit)? = null,
    /** 这一轮从开始到结束的真实耗时；0 表示老数据，退回按工具耗时求和 */
    totalMs: Long = 0L,
) {
    // 不按 rounds 做 key：流式输出时 rounds 每帧都是新 List，key 一变就把用户展开的状态重置掉
    // （这就是「模型还在输出时迭代树自己关掉」的老 bug）。卡片本身在 LazyColumn 里有稳定 key。
    var expanded by remember { mutableStateOf(false) }
    var openRound by remember { mutableStateOf<Int?>(null) }
    val totalSteps = rounds.sumOf { it.tools.size }
    val toolMs = rounds.sumOf { round -> round.tools.sumOf { it.elapsedMs } }
    val shownMs = if (totalMs > 0) totalMs else toolMs

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(180)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DlaColors.Mint.copy(alpha = 0.28f)),
        colors = CardDefaults.cardColors(containerColor = DlaColors.Surface),
    ) {
        Row {
            // 左侧强调条：让迭代树在消息流里一眼可辨
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(DlaColors.Mint.copy(alpha = 0.85f)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = if (expanded) Lang.t("收起迭代树") else Lang.t("展开迭代树"),
                        ) { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DlaColors.Mint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⟡", color = DlaColors.Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Lang.t("迭代树"),
                        color = DlaColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Tag(Lang.t("%d 轮", rounds.size))
                    Spacer(Modifier.width(4.dp))
                    Tag(Lang.t("%d 步", totalSteps))
                    Spacer(Modifier.weight(1f))
                    if (shownMs > 0) {
                        Text(
                            formatElapsed(shownMs),
                            color = DlaColors.TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (expanded) Lang.t("收起") else Lang.t("展开"),
                        color = DlaColors.Mint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    Column {
                        HorizontalDivider(color = DlaColors.Line)
                        if (rounds.isEmpty()) {
                            Text(
                                Lang.t("暂无迭代记录"),
                                color = DlaColors.TextDim,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        rounds.forEachIndexed { index, round ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = DlaColors.Line.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                            RoundBlock(
                                round = round,
                                open = openRound == round.round,
                                onToggle = {
                                    openRound = if (openRound == round.round) null else round.round
                                    if (openRound != null) {
                                        expanded = true
                                        onLocate?.invoke(round.round)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「执行过程」的自家图标：一枚搜证用的放大镜。
 *
 * 思考卡用吉祥物（溟灯），执行过程用放大镜：一眼能分清「它在想」还是「它在查/做」。
 */
@Composable
fun ProcessGlyph(color: Color = DlaColors.Mint, size: androidx.compose.ui.unit.Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.13f
        val radius = w * 0.28f
        val center = Offset(w * 0.42f, h * 0.42f)
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(center.x + radius * 0.75f, center.y + radius * 0.75f),
            end = Offset(w * 0.88f, h * 0.88f),
            strokeWidth = stroke * 1.2f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text = text,
        color = DlaColors.TextDim,
        fontSize = 10.sp,
        modifier = Modifier
            .background(DlaColors.SurfaceHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RoundBlock(round: IterationRound, open: Boolean, onToggle: () -> Unit) {
    val roundMs = round.tools.sumOf { it.elapsedMs }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) Lang.t("收起该轮") else Lang.t("定位到该轮")) { onToggle() }
            .background(
                if (open) DlaColors.Mint.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(tween(160)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Lang.t("第 %d 轮", round.round),
                color = if (open) DlaColors.Mint else DlaColors.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            if (roundMs > 0) {
                Text(
                    formatElapsed(roundMs),
                    color = DlaColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (open) Lang.t("收起") else Lang.t("定位"),
                color = DlaColors.Mint,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        round.tools.forEach { tool ->
            ToolLine(tool)
            if (open) {
                ToolDetail(tool)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (!round.thinking.isNullOrBlank()) {
            val summary = round.thinking.trim().replace(Regex("\\s+"), " ").take(80)
            Text(
                Lang.t("思考 · %s", summary) + if (round.thinking.trim().length > 80) "…" else "",
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ToolLine(tool: Block.Tool) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tool.status)
        Spacer(Modifier.width(8.dp))
        Text(
            tool.name,
            color = DlaColors.Text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tool.elapsedMs > 0) {
            Text(
                formatElapsed(tool.elapsedMs),
                color = DlaColors.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 展开某轮后直接看到参数与结果，不用再去点工具卡 */
@Composable
private fun ToolDetail(tool: Block.Tool) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 4.dp),
    ) {
        if (tool.input.isNotBlank()) {
            Text(Lang.t("参数"), color = DlaColors.TextDim, fontSize = 9.sp)
            Text(
                tool.input.take(400),
                color = DlaColors.Text,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            )
        }
        tool.result?.let { result ->
            Spacer(Modifier.height(3.dp))
            Text(Lang.t("结果"), color = DlaColors.TextDim, fontSize = 9.sp)
            Text(
                result.take(400),
                color = DlaColors.Text,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "error" -> DlaColors.Error
        "running", "waiting" -> DlaColors.Warm
        else -> DlaColors.Mint
    }
    if (status == "running") {
        val transition = rememberInfiniteTransition(label = "dot")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
    } else {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}


