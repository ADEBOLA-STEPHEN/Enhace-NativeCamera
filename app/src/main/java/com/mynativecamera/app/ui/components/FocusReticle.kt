package com.mynativecamera.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mynativecamera.app.ui.theme.ElectricCyan
import com.mynativecamera.app.ui.theme.ReadyGreen
import kotlin.math.roundToInt

/**
 * Cinema-grade tap-to-focus and metering reticle.
 *
 * Renders 4 corner brackets and a center crosshair that pulses on tap,
 * transitions to [ReadyGreen] when focus locks, and smoothly fades out after 2.5 seconds.
 */
@Composable
fun FocusReticle(
    targetPoint: Offset?,
    isLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    if (targetPoint == null) return

    val scale = remember(targetPoint) { Animatable(1.5f) }
    val alpha = remember(targetPoint) { Animatable(1.0f) }

    LaunchedEffect(targetPoint) {
        scale.snapTo(1.6f)
        alpha.snapTo(1.0f)
        scale.animateTo(
            targetValue = 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
        // Keep visible for 2 seconds then smoothly fade out
        kotlinx.coroutines.delay(2000L)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        )
    }

    val reticleColor = if (isLocked) ReadyGreen else ElectricCyan
    val currentAlpha = alpha.value
    if (currentAlpha <= 0f) return

    val density = LocalDensity.current
    val boxSizePx = with(density) { 68.dp.toPx() } * scale.value
    val halfBox = boxSizePx / 2f
    val cornerLen = boxSizePx * 0.28f
    val strokeWidth = with(density) { 2.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Canvas(
            modifier = Modifier
                .size(with(density) { boxSizePx.toDp() })
                .offset {
                    IntOffset(
                        (targetPoint.x - halfBox).roundToInt(),
                        (targetPoint.y - halfBox).roundToInt(),
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val color = reticleColor.copy(alpha = currentAlpha)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // Top-Left Corner
            drawLine(color, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth = strokeWidth)
            drawLine(color, Offset(0f, 0f), Offset(0f, cornerLen), strokeWidth = strokeWidth)

            // Top-Right Corner
            drawLine(color, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeWidth = strokeWidth)
            drawLine(color, Offset(w, 0f), Offset(w, cornerLen), strokeWidth = strokeWidth)

            // Bottom-Left Corner
            drawLine(color, Offset(0f, h), Offset(cornerLen, h), strokeWidth = strokeWidth)
            drawLine(color, Offset(0f, h), Offset(0f, h - cornerLen), strokeWidth = strokeWidth)

            // Bottom-Right Corner
            drawLine(color, Offset(w, h), Offset(w - cornerLen, h), strokeWidth = strokeWidth)
            drawLine(color, Offset(w, h), Offset(w, h - cornerLen), strokeWidth = strokeWidth)

            // Center crosshair tick
            val cx = w / 2f
            val cy = h / 2f
            val crossSize = with(density) { 4.dp.toPx() }
            drawLine(color, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), strokeWidth = strokeWidth * 0.8f)
            drawLine(color, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), strokeWidth = strokeWidth * 0.8f)
        }
    }
}
