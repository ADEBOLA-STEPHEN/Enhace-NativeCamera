package com.mynativecamera.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.RecordRed

/**
 * Prominent record button: a white bezel around a red disc that morphs into a rounded
 * "stop" square while recording, with a slow pulsing halo as an unmistakable tally.
 */
@Composable
fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "recPress",
    )
    val coreSize by animateDpAsState(
        targetValue = if (isRecording) 28.dp else 60.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "recCoreSize",
    )
    val coreCorner by animateDpAsState(
        targetValue = if (isRecording) 7.dp else 30.dp,
        animationSpec = tween(220),
        label = "recCoreCorner",
    )

    val pulse = rememberInfiniteTransition(label = "recPulse")
    val haloScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "haloScale",
    )
    val haloAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "haloAlpha",
    )

    Box(
        modifier = modifier
            .size(92.dp)
            .testTag("record_button")
            .semantics { contentDescription = if (isRecording) "Stop recording" else "Start recording" }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            Box(
                Modifier
                    .size(76.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = haloAlpha
                    }
                    .background(RecordRed, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .background(if (isRecording) Color.Black.copy(alpha = 0.35f) else Navy700.copy(alpha = 0.6f), CircleShape)
                .border(4.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(coreSize)
                    .clip(RoundedCornerShape(coreCorner))
                    .background(RecordRed),
            )
        }
    }
}
