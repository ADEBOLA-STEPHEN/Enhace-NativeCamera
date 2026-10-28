package com.mynativecamera.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mynativecamera.app.ui.theme.GlassBlack
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudCaption
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.HudReadout
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary
import com.mynativecamera.app.ui.theme.TextTertiary
import kotlin.math.max
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(22.dp)

/**
 * Stepped vertical fader for manual exposure (ISO / shutter).
 *
 * - **Relative drag**: touching the track never makes the value jump; the value moves only
 *   by how far you drag (like a physical fader). A minimum travel per step keeps 30-stop
 *   ladders precise on small screens.
 * - **Nudge buttons** step exactly one 1/3 stop for fine adjustments while recording.
 * - A haptic tick on every stop change.
 *
 * Up always means *brighter* (higher ISO / longer exposure).
 */
@Composable
fun ProVerticalSlider(
    label: String,
    valueText: String,
    stepCount: Int,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val latestIndex by rememberUpdatedState(selectedIndex)
    val latestOnChange by rememberUpdatedState(onIndexChange)
    var isDragging by remember { mutableStateOf(false) }

    val maxIndex = (stepCount - 1).coerceAtLeast(1)
    val fraction by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, maxIndex) / maxIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "faderFraction",
    )
    val glow by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(160),
        label = "faderGlow",
    )

    fun nudge(delta: Int) {
        val target = (latestIndex + delta).coerceIn(0, stepCount - 1)
        if (target != latestIndex) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            latestOnChange(target)
        }
    }

    Column(
        modifier = modifier
            .width(78.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CardShape)
            .background(GlassBlack)
            .border(1.dp, lerp(Hairline, NavyGlow, glow), CardShape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = HudLabel, color = TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(
            text = valueText,
            style = HudReadout,
            color = TextPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = caption ?: " ",
            style = HudCaption,
            color = TextTertiary,
            maxLines = 1,
        )

        NudgeButton(Icons.Rounded.KeyboardArrowUp, "Increase $label", enabled) { nudge(+1) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("slider_track_$label")
                .semantics {
                    contentDescription = "$label fader"
                    stateDescription = valueText
                }
                .pointerInput(enabled, stepCount) {
                    if (!enabled || stepCount < 2) return@pointerInput
                    val minStepPx = 12.dp.toPx()
                    var accumulated = 0f
                    var anchor = 0
                    var lastEmitted = 0
                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulated = 0f
                            anchor = latestIndex
                            lastEmitted = latestIndex
                            isDragging = true
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        accumulated -= dragAmount // screen Y grows downward; up = increase
                        val usable = (size.height - TRACK_INSET.toPx() * 2).coerceAtLeast(1f)
                        val stepPx = max(usable / (stepCount - 1), minStepPx)
                        val target = (anchor + (accumulated / stepPx).roundToInt())
                            .coerceIn(0, stepCount - 1)
                        if (target != lastEmitted) {
                            lastEmitted = target
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnChange(target)
                        }
                    }
                }
                .drawBehind { drawFader(fraction, stepCount, glow) },
        )

        NudgeButton(Icons.Rounded.KeyboardArrowDown, "Decrease $label", enabled) { nudge(-1) }
    }
}

@Composable
private fun NudgeButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 30.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = TextSecondary)
    }
}

private val TRACK_INSET = 10.dp

private fun DrawScope.drawFader(fraction: Float, stepCount: Int, glow: Float) {
    val inset = TRACK_INSET.toPx()
    val top = inset
    val bottom = size.height - inset
    val height = (bottom - top).coerceAtLeast(1f)
    val cx = size.width / 2f
    val trackW = 4.dp.toPx()
    val thumbY = bottom - height * fraction

    // Tick ladder on both sides; every 3rd tick (= one full stop) is longer.
    if (stepCount > 1) {
        val gap = 6.dp.toPx()
        val minor = 5.dp.toPx()
        val major = 10.dp.toPx()
        val tickStroke = 1.dp.toPx()
        for (i in 0 until stepCount) {
            val y = bottom - height * (i / (stepCount - 1f))
            val isMajor = i % 3 == 0
            val len = if (isMajor) major else minor
            val color = Color.White.copy(alpha = if (isMajor) 0.42f else 0.18f)
            val leftEnd = cx - trackW / 2 - gap
            val rightStart = cx + trackW / 2 + gap
            drawLine(color, Offset(leftEnd - len, y), Offset(leftEnd, y), tickStroke)
            drawLine(color, Offset(rightStart, y), Offset(rightStart + len, y), tickStroke)
        }
    }

    // Track background
    drawRoundRect(
        color = Color.White.copy(alpha = 0.12f),
        topLeft = Offset(cx - trackW / 2, top),
        size = Size(trackW, height),
        cornerRadius = CornerRadius(trackW / 2),
    )
    // Active fill (bottom -> thumb), navy gradient
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(NavyGlow, Navy700), startY = thumbY, endY = bottom),
        topLeft = Offset(cx - trackW / 2, thumbY),
        size = Size(trackW, bottom - thumbY),
        cornerRadius = CornerRadius(trackW / 2),
    )

    // Thumb with a glow halo while dragging
    val thumbW = 38.dp.toPx()
    val thumbH = 14.dp.toPx()
    if (glow > 0f) {
        val haloPad = 6.dp.toPx()
        drawRoundRect(
            color = NavyGlow.copy(alpha = 0.35f * glow),
            topLeft = Offset(cx - thumbW / 2 - haloPad, thumbY - thumbH / 2 - haloPad),
            size = Size(thumbW + haloPad * 2, thumbH + haloPad * 2),
            cornerRadius = CornerRadius(thumbH),
        )
    }
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(cx - thumbW / 2, thumbY - thumbH / 2),
        size = Size(thumbW, thumbH),
        cornerRadius = CornerRadius(thumbH / 2),
    )
    val notch = 14.dp.toPx()
    drawLine(
        color = Navy700,
        start = Offset(cx - notch / 2, thumbY),
        end = Offset(cx + notch / 2, thumbY),
        strokeWidth = 2.dp.toPx(),
    )
}
