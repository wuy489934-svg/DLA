package com.example.chatdemo.nativeui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 点击「破碎成小方块」的质感动效。
 *
 * 交互层：在**父容器**用 Initial 阶段旁听指针事件，只观察、不消费，所以按钮/列表点的
 * 点击照常生效；抬手时位移在阈值内才算一次点击，滑动不会乱放特效。
 * 画面层：一个不接收触摸的 Canvas 盖在内容上方，按帧画小方块 + 一圈扩散的光环。
 *
 * 性能：没有爆点时不跑帧循环（snapshotFlow 等到下一次 burst 才唤醒），动画结束自动清空。
 */
@Stable
class ShatterController {

    internal class Piece(
        val angle: Float,
        val speed: Float,
        val size: Float,
        val spin: Float,
        val color: Color,
    )

    internal class Burst(
        val origin: Offset,
        val startNanos: Long,
        val pieces: List<Piece>,
        val durationNanos: Long,
    ) {
        fun progress(nowNanos: Long): Float =
            ((nowNanos - startNanos).toFloat() / durationNanos).coerceIn(0f, 1f)
    }

    internal val bursts = mutableStateListOf<Burst>()
    internal var frameNanos by mutableStateOf(0L)
        private set

    /** 关闭后只保留音效/不画特效（给「界面音效」总开关复用） */
    var enabled: Boolean = true

    fun burst(at: Offset, colors: List<Color>, count: Int = 7, power: Float = 1f) {
        if (!enabled || colors.isEmpty()) return
        val now = System.nanoTime()
        val random = Random(now)
        val palette = colors.ifEmpty { listOf(Color.White) }
        val pieces = ArrayList<Piece>(count)
        for (index in 0 until count) {
            val base = (360f / count) * index
            val angle = base + random.nextFloat() * (360f / count) * 0.6f - 14f
            // 扩散半径刻意收小：只在点击点附近炸开一小圈，不做全屏飞散
            val speed = (150f + random.nextFloat() * 260f) * power
            val size = 2.5f + random.nextFloat() * 4.5f
            val spin = random.nextFloat() * 240f - 120f
            pieces += Piece(
                angle = angle,
                speed = speed,
                size = size,
                spin = spin,
                color = palette[index % palette.size].copy(alpha = 0.85f),
            )
        }
        bursts += Burst(
            origin = at,
            startNanos = now,
            pieces = pieces,
            durationNanos = 430_000_000L,
        )
        // 同时最多留 3 个爆点，连点也不会堆爆
        while (bursts.size > 3) {
            bursts.removeAt(0)
        }
    }

    internal fun advance(nowNanos: Long) {
        frameNanos = nowNanos
        for (index in bursts.size - 1 downTo 0) {
            if (nowNanos - bursts[index].startNanos > bursts[index].durationNanos) {
                bursts.removeAt(index)
            }
        }
    }

    internal fun isEmpty(): Boolean = bursts.isEmpty()
}

/**
 * 把内容包起来：内容照常交互，点击处冒出一圈小方块。
 */
@Composable
fun ShatterHost(
    modifier: Modifier = Modifier,
    colors: List<Color>,
    controller: ShatterController = remember { ShatterController() },
    onTap: ((Offset) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    // 只在有爆点时跑帧循环
    LaunchedEffect(controller) {
        snapshotFlow { controller.bursts.isNotEmpty() }.collect { active ->
            if (!active) return@collect
            while (controller.bursts.isNotEmpty()) {
                withInfiniteAnimationFrameNanos { now -> controller.advance(now) }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(controller, density) {
                val slop = 24.dp.toPx()
                awaitPointerEventScope {
                    var downAt: Offset? = null
                    var downTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.changedToDownIgnoreConsumed()) {
                            downAt = change.position
                            downTime = System.currentTimeMillis()
                        } else if (change.changedToUpIgnoreConsumed()) {
                            val start = downAt
                            downAt = null
                            if (start != null &&
                                System.currentTimeMillis() - downTime < 400 &&
                                (change.position - start).getDistance() < slop
                            ) {
                                onTap?.invoke(start)
                                controller.burst(start, colors)
                                SoundFeedback.tap()
                            }
                        }
                    }
                }
            },
    ) {
        content()
        ShatterCanvas(controller)
    }
}

@Composable
private fun ShatterCanvas(controller: ShatterController) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val now = controller.frameNanos
        if (now <= 0L) return@Canvas
        for (burst in controller.bursts) {
            val progress = burst.progress(now)
            if (progress >= 1f) continue
            val eased = 1f - (1f - progress) * (1f - progress) // easeOutQuad
            val fade = if (progress < 0.6f) 1f else 1f - (progress - 0.6f) / 0.4f
            val seconds = (now - burst.startNanos) / 1_000_000_000f
            val gravity = 520f * seconds * seconds
            // 扩散光环：只到 22dp，收得很紧
            val ringRadius = 3.dp.toPx() + eased * 20.dp.toPx()
            drawCircle(
                color = burst.pieces.first().color.copy(alpha = 0.16f * fade),
                radius = ringRadius,
                center = burst.origin,
                style = Stroke(width = (1.8f - 1.2f * progress).coerceAtLeast(0.5f) * density),
            )
            // 小方块
            for (piece in burst.pieces) {
                val radians = Math.toRadians(piece.angle.toDouble())
                val drift = piece.speed * (eased * 0.6f + progress * 0.2f)
                val x = burst.origin.x + (cos(radians) * drift).toFloat()
                val y = burst.origin.y + (sin(radians) * drift).toFloat() + gravity
                val sizePx = piece.size * density * (1f - progress * 0.5f)
                if (sizePx <= 0.4f) continue
                rotate(degrees = piece.spin * progress, pivot = Offset(x, y)) {
                    drawRoundRect(
                        color = piece.color.copy(alpha = (0.9f * fade).coerceIn(0f, 1f)),
                        topLeft = Offset(x - sizePx / 2f, y - sizePx / 2f),
                        size = Size(sizePx, sizePx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(sizePx * 0.22f),
                    )
                }
            }
        }
    }
}
