package com.example.chatdemo.nativeui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chatdemo.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DLA 吉祥物动效层：v1「Cue」（终端精灵）与 v2「溟灯」（深海信标）。
 *
 * 约定（性能与生命周期）：
 * - 所有动效只改 `graphicsLayer`（缩放/位移/旋转/透明度）与绘制层，不触发测量与重组，
 *   `LazyColumn` 里滚动不会因为动画反复测高；
 * - 动效全部挂在 `rememberInfiniteTransition` 上，页面离开组合即随动画一起销毁；
 *   非动效状态**不创建**过渡，避免「静止了还在空转」；
 * - 不用 `Modifier.blur`（API 31+ 且耗性能），光晕/光斑一律用多层低 alpha 圆逼近高斯模糊。
 *
 * 图片资源：`res/drawable-nodpi/mascot_cue.png`、`mascot_moon.png`（1024×1024 透明 PNG）。
 */
object MascotMotion {

    // ---- v1 · Cue：思考中头像（2.4s 一个来回） ----
    const val CUE_BREATH_MS = 2400
    const val CUE_SCALE_MIN = 0.96f
    const val CUE_SCALE_MAX = 1.04f
    const val CUE_ALPHA_MIN = 0.85f
    const val CUE_ALPHA_MAX = 1.00f
    const val CUE_GLOW_MIN = 0.10f
    const val CUE_GLOW_MAX = 0.28f
    /** 卡片标题「极轻闪烁」：0.7→1，1.6s */
    const val CUE_TITLE_BLINK_MS = 1600
    const val CUE_TITLE_ALPHA_MIN = 0.70f

    // ---- v2 · 溟灯：首页分层光效 ----
    const val HERO_STAGE_DP = 188
    const val HERO_IMAGE_DP = 180
    const val HERO_IMAGE_ALPHA = 0.92f
    /** 底层渐变：半径 1.0→1.12 呼吸，4.5s */
    const val HERO_GLOW_BREATH_MS = 4500
    const val HERO_GLOW_SCALE_MAX = 1.12f
    /** 中层立绘：上下漂浮 ±6dp，5s */
    const val HERO_FLOAT_MS = 5000
    const val HERO_FLOAT_DP = 6
    /** 中层光晕：sweepGradient 自转，12s 一圈 */
    const val HERO_HALO_MS = 12000
    const val HERO_HALO_ALPHA = 0.12f
    /** 上层光斑：两枚不同相位漂移，14s 一个周期 */
    const val HERO_SPOT_MS = 14000

    // ---- v2 · 溟灯：空会话背景（极淡版） ----
    const val IDLE_STAGE_DP = 220
    const val IDLE_IMAGE_DP = 200
    const val IDLE_BREATH_MS = 6000
    const val IDLE_ALPHA_MIN = 0.10f
    const val IDLE_ALPHA_MAX = 0.18f
}

// ---------------- v1 · Cue：思考过程头像 ----------------

/**
 * 「思考过程」卡片左侧的 Cue 头像（默认 30dp，用 PNG 资源，不解析 SVG）。
 *
 * 思考中：2.4s 循环的呼吸（scale 0.96→1.04）、屏幕微亮（alpha 0.85→1）、
 * 薄荷色外发光（alpha 0.10→0.28）；三者共用一条 [rememberInfiniteTransition]。
 * 思考结束：不再创建过渡，头像立即回到 alpha=1 / scale=1，外发光消失。
 *
 * 头像始终占位 30dp，动画只作用于自身图层，卡片高度不会随动画变化
 * （所以不需要在 `LazyColumn` 里用 `AnimatedVisibility` 包整卡）。
 */
@Composable
fun CueThinkingAvatar(
    thinking: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    // 三个值都是 State：绘制/图层阶段才读取，动画期间不重组、不测高
    val scale: State<Float>
    val avatarAlpha: State<Float>
    val glowAlpha: State<Float>
    if (thinking) {
        val transition = rememberInfiniteTransition(label = "cueBreath")
        val spec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = MascotMotion.CUE_BREATH_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        )
        scale = transition.animateFloat(
            initialValue = MascotMotion.CUE_SCALE_MIN,
            targetValue = MascotMotion.CUE_SCALE_MAX,
            animationSpec = spec,
            label = "cueScale",
        )
        avatarAlpha = transition.animateFloat(
            initialValue = MascotMotion.CUE_ALPHA_MIN,
            targetValue = MascotMotion.CUE_ALPHA_MAX,
            animationSpec = spec,
            label = "cueAlpha",
        )
        glowAlpha = transition.animateFloat(
            initialValue = MascotMotion.CUE_GLOW_MIN,
            targetValue = MascotMotion.CUE_GLOW_MAX,
            animationSpec = spec,
            label = "cueGlow",
        )
    } else {
        scale = remember { mutableStateOf(1f) }
        avatarAlpha = remember { mutableStateOf(1f) }
        glowAlpha = remember { mutableStateOf(0f) }
    }

    val glowColor = DlaColors.Mint
    // 外发光层：比头像大一圈（1.5×），静态 Brush + 只动图层 alpha，
    // 每帧不新建 shader；仍在卡片 9dp 内边距内，不会被圆角裁出硬边。
    val glowBrush = remember(glowColor) {
        Brush.radialGradient(
            colors = listOf(
                glowColor,
                glowColor.copy(alpha = 0.45f),
                glowColor.copy(alpha = 0.12f),
                Color.Transparent,
            ),
        )
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // requiredSize：允许光晕超出 30dp 头像框，而不用改动父布局的测量
                .requiredSize(size * 1.5f)
                .graphicsLayer { alpha = glowAlpha.value }
                .background(glowBrush, CircleShape),
        )
        Image(
            painter = painterResource(R.drawable.mascot_cue),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = avatarAlpha.value
                },
        )
    }
}

/**
 * 思考中标题的「极轻闪烁」：alpha 0.7→1，1.6s 一个来回。
 * 返回 State 供 `Modifier.graphicsLayer { alpha = ... }` 读取，避免逐帧重组。
 */
@Composable
fun rememberThinkingTitleAlpha(thinking: Boolean): State<Float> {
    if (!thinking) {
        // 思考结束：直接回到不透明，动画随过渡一起销毁
        return remember { mutableStateOf(1f) }
    }
    val transition = rememberInfiniteTransition(label = "cueTitle")
    return transition.animateFloat(
        initialValue = MascotMotion.CUE_TITLE_ALPHA_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MascotMotion.CUE_TITLE_BLINK_MS,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cueTitleAlpha",
    )
}

// ---------------- v2 · 溟灯：首页分层光效 ----------------

/**
 * 首页「溟灯」：静态 PNG + 纯绘制层的分层光效，从下到上——
 *
 * 1. 底：薄荷色径向渐变（中心 alpha 0.10 → 边缘透明），半径 1.0→1.12 呼吸，4.5s；
 * 2. 中：180dp 立绘，`alpha 0.92`，上下漂浮 ±6dp / 5s；立绘背后一层 sweepGradient
 *    光晕，12s 转一圈，alpha 0.12（**只转光晕，不转立绘**）；
 * 3. 上：两枚暖色/薄荷色光斑，各自不同相位缓慢漂移（多层半透明圆逼近高斯模糊）；
 * 4. 极淡扫描线（静态绘制，不参与动画）。
 */
@Composable
fun MoonBeacon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(MascotMotion.HERO_STAGE_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BeaconGlow(Modifier.fillMaxSize())
        BeaconHalo(MascotMotion.HERO_STAGE_DP.dp)
        BeaconMoonImage(
            size = MascotMotion.HERO_IMAGE_DP.dp,
            imageAlpha = MascotMotion.HERO_IMAGE_ALPHA,
        )
        BeaconSpots(Modifier.fillMaxSize(), durationMs = MascotMotion.HERO_SPOT_MS)
        BeaconScanlines(Modifier.fillMaxSize())
    }
}

/**
 * 空会话的背景光效（极淡版）：尺寸 220dp，整体 alpha 在 0.10～0.18 之间以 6s 呼吸。
 *
 * 只有整层 alpha 在动（一个图层动画），内部光斑/立绘保持静态，
 * 放在消息列表下层时几乎不占 GPU：不滚动、不点击、不影响列表测量。
 */
@Composable
fun MoonBeaconBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "beaconIdle")
    val breath = transition.animateFloat(
        initialValue = MascotMotion.IDLE_ALPHA_MIN,
        targetValue = MascotMotion.IDLE_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MascotMotion.IDLE_BREATH_MS,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "beaconIdleAlpha",
    )
    val mint = DlaColors.Mint
    val warm = DlaColors.Warm
    Box(
        modifier = modifier
            .size(MascotMotion.IDLE_STAGE_DP.dp)
            .graphicsLayer { alpha = breath.value },
        contentAlignment = Alignment.Center,
    ) {
        BeaconGlow(Modifier.fillMaxSize(), animate = false)
        Image(
            painter = painterResource(R.drawable.mascot_moon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alpha = MascotMotion.HERO_IMAGE_ALPHA,
            modifier = Modifier.size(MascotMotion.IDLE_IMAGE_DP.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val span = this.size.minDimension
            softSpot(center + Offset(-span * 0.28f, -span * 0.22f), span * 0.13f, warm)
            softSpot(center + Offset(span * 0.30f, span * 0.24f), span * 0.17f, mint)
        }
    }
}

/**
 * 底层：薄荷径向渐变。
 * 中心 `#7FE7C4`（深色主题下的 `DlaColors.Mint`）alpha 0.10 → 边缘透明，
 * 半径呼吸 1.0→1.12（4.5s，Reverse，FastOutSlowIn），只缩放图层不改尺寸。
 */
@Composable
private fun BeaconGlow(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val mint = DlaColors.Mint
    val scaleState: State<Float>
    if (animate) {
        val transition = rememberInfiniteTransition(label = "beaconGlow")
        scaleState = transition.animateFloat(
            initialValue = 1f,
            targetValue = MascotMotion.HERO_GLOW_SCALE_MAX,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MascotMotion.HERO_GLOW_BREATH_MS,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "beaconGlowScale",
        )
    } else {
        scaleState = remember { mutableStateOf(1f) }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scaleState.value
                scaleY = scaleState.value
            }
            .background(
                Brush.radialGradient(
                    0.00f to mint.copy(alpha = 0.10f),
                    0.55f to mint.copy(alpha = 0.040f),
                    1.00f to Color.Transparent,
                ),
                CircleShape,
            ),
    )
}

/**
 * 中层：立绘背后的 sweepGradient 光晕，`graphicsLayer { rotationZ }` 12s 转一圈，alpha 0.12。
 *
 * 只用薄荷色（暖色大面积低 alpha 在深墨绿底上会糊成灰褐色污渍），
 * 并用三圈不同宽度的描边叠出径向衰减，避免出现明显的「轨道线」硬边。
 */
@Composable
private fun BeaconHalo(size: Dp) {
    val transition = rememberInfiniteTransition(label = "beaconHalo")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MascotMotion.HERO_HALO_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beaconHaloAngle",
    )
    val mint = DlaColors.Mint
    val sweep = Brush.sweepGradient(
        0.00f to Color.Transparent,
        0.06f to Color.Transparent,
        0.12f to mint.copy(alpha = 0.85f),
        0.20f to mint.copy(alpha = 0.24f),
        0.30f to Color.Transparent,
        0.50f to Color.Transparent,
        0.56f to mint.copy(alpha = 0.55f),
        0.62f to mint.copy(alpha = 0.16f),
        0.70f to Color.Transparent,
        1.00f to Color.Transparent,
    )
    Canvas(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                rotationZ = angle.value
                alpha = MascotMotion.HERO_HALO_ALPHA
            },
    ) {
        val radius = this.size.minDimension * 0.47f
        drawCircle(brush = sweep, radius = radius, center = this.center, style = Stroke(width = 18.dp.toPx()))
        drawCircle(brush = sweep, radius = radius, center = this.center, style = Stroke(width = 9.dp.toPx()))
        drawCircle(brush = sweep, radius = radius, center = this.center, style = Stroke(width = 4.dp.toPx()))
    }
}

/**
 * 中层：v2 立绘。不旋转、不缩放，只做 ±6dp 的上下漂浮（5s，Reverse）。
 */
@Composable
private fun BeaconMoonImage(
    size: Dp,
    imageAlpha: Float,
    floatDistance: Dp = MascotMotion.HERO_FLOAT_DP.dp,
    floatMs: Int = MascotMotion.HERO_FLOAT_MS,
) {
    val transition = rememberInfiniteTransition(label = "beaconFloat")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = floatMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "beaconFloatProgress",
    )
    Image(
        painter = painterResource(R.drawable.mascot_moon),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                // 0→1 映射到 -6dp→+6dp，等于「上下漂浮 ±6dp」
                translationY = (progress.value - 0.5f) * 2f * floatDistance.toPx()
                alpha = imageAlpha
            },
    )
}

/**
 * 上层：两枚光斑（暖色 + 薄荷色），不同相位缓慢漂移，制造「信标在水下」的感觉。
 * 每枚光斑由 6 层同心半透明圆叠加，近似高斯模糊（不用 blur）。
 */
@Composable
private fun BeaconSpots(modifier: Modifier = Modifier, durationMs: Int) {
    val transition = rememberInfiniteTransition(label = "beaconSpots")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beaconSpotsPhase",
    )
    val warm = DlaColors.Warm
    val mint = DlaColors.Mint
    Canvas(modifier = modifier) {
        val span = this.size.minDimension
        val angle = phase.value * TWO_PI
        // 暖色光斑：左上暗角；薄荷光斑：右下暗角。漂移是 ±0.05 身位的小幅摆动，
        // 刻意放在立绘剪影之外，否则会被 PNG 挡住、白白空转。
        val warmCenter = this.center + Offset(
            x = cos(angle) * span * 0.05f - span * 0.28f,
            y = sin(angle * 0.8f) * span * 0.05f - span * 0.22f,
        )
        val mintCenter = this.center + Offset(
            x = cos(angle + PI.toFloat()) * span * 0.06f + span * 0.30f,
            y = sin(angle * 0.7f + PI.toFloat()) * span * 0.06f + span * 0.24f,
        )
        softSpot(warmCenter, span * 0.13f, warm)
        softSpot(mintCenter, span * 0.17f, mint)
    }
}

/** 极淡扫描线：静态绘制一次，不参与动画，只负责质感（不引入额外资源）。 */
@Composable
private fun BeaconScanlines(modifier: Modifier = Modifier) {
    val line = DlaColors.Mint
    Canvas(modifier = modifier) {
        val pitch = 3.dp.toPx()
        var y = 0f
        while (y < this.size.height) {
            drawLine(
                color = line.copy(alpha = 0.022f),
                start = Offset(0f, y),
                end = Offset(this.size.width, y),
                strokeWidth = 1f,
            )
            y += pitch
        }
    }
}

// 多层同心圆：外圈步进极小、越靠中心越亮，叠加后近似高斯衰减。
// 层数不能太少——6 层时相邻环的 alpha 差 ~0.02，在深底上肉眼能看出「同心圆台阶」。
private val SpotRadius = floatArrayOf(
    1.00f, 0.93f, 0.86f, 0.79f, 0.72f, 0.65f, 0.58f,
    0.51f, 0.44f, 0.37f, 0.30f, 0.23f, 0.16f, 0.10f,
)
private val SpotAlpha = floatArrayOf(
    0.008f, 0.010f, 0.012f, 0.014f, 0.017f, 0.020f, 0.024f,
    0.028f, 0.033f, 0.038f, 0.044f, 0.050f, 0.058f, 0.066f,
)
private const val TWO_PI = 6.2831855f

private fun DrawScope.softSpot(center: Offset, radius: Float, color: Color) {
    for (i in SpotRadius.indices) {
        drawCircle(
            color = color.copy(alpha = SpotAlpha[i]),
            radius = radius * SpotRadius[i],
            center = center,
        )
    }
}
