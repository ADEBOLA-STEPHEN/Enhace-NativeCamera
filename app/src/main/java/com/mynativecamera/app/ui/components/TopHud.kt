package com.mynativecamera.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mynativecamera.app.audio.AudioMeterData
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.camera.ExposureFormat
import com.mynativecamera.app.ui.CameraStatus
import com.mynativecamera.app.ui.CameraUiState
import com.mynativecamera.app.ui.CaptureMode
import com.mynativecamera.app.ui.SliderModal
import com.mynativecamera.app.ui.theme.GlassBlack
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.NavyAccent
import com.mynativecamera.app.ui.theme.NavyActive
import com.mynativecamera.app.ui.theme.NavyBase
import com.mynativecamera.app.ui.theme.NavySurface
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.ReadyGreen
import com.mynativecamera.app.ui.theme.RecordRed
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary
import com.mynativecamera.app.ui.theme.TextTertiary
import com.mynativecamera.app.ui.theme.Timecode
import com.mynativecamera.app.ui.theme.WarningAmber

/**
 * Minimal top heads-up display:
 * Clean, lightweight cinema status bar without bulky text tabs.
 */
@Composable
fun TopHud(
    state: CameraUiState,
    onGridToggle: () -> Unit,
    onRawCaptureToggle: () -> Unit = {},
    onHdrBurstToggle: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAudioSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: Tally / Timecode or Photo badge
        if (state.captureMode == CaptureMode.Video) {
            if (state.isRecording) {
                RecordingTally(elapsedMs = state.recordingElapsedMs)
            } else {
                Spacer(Modifier.size(32.dp))
            }
        } else {
            PhotoBadge()
        }

        // Middle: Minimal scrollable status badges
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.captureMode == CaptureMode.Video) {
                    // Video format chip
                    val spec = state.videoSpec
                    SpecChip(
                        text = "${spec.height}p ${spec.fps} FPS · ${spec.codecLabel}",
                        highlighted = state.activeModal == SliderModal.VideoSettings,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onOpenSettings),
                    )

                    val colorMode = when {
                        state.hdrEnabled && state.hdrSupported -> "HLG10"
                        state.hdrEnabled -> "LOG/FLAT"
                        else -> "SDR 709"
                    }
                    SpecChip(colorMode, highlighted = state.hdrEnabled)

                    if (state.oisEnabled && state.oisSupported) SpecChip("OIS", highlighted = true)
                    if (state.eisEnabled && state.eisSupported) SpecChip("EIS", highlighted = true)

                    // Audio Meter VU chip
                    AudioMeterChip(
                        meter = state.audioMeter,
                        highlighted = state.activeModal == SliderModal.Audio,
                        onClick = onOpenAudioSettings,
                    )
                } else {
                    SpecChip(
                        text = if (state.hdrBurstPhotoEnabled) "HDR BURST" else "PHOTO",
                        highlighted = state.hdrBurstPhotoEnabled,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onHdrBurstToggle),
                    )
                    if (state.rawSupported && !state.hdrBurstPhotoEnabled) {
                        SpecChip(
                            text = if (state.rawCaptureEnabled) "RAW DNG" else "JPG",
                            highlighted = state.rawCaptureEnabled,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onRawCaptureToggle),
                        )
                    }
                }
            }
        }

        // Right side: Quick utility chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SpecChip(
                text = "GRID",
                highlighted = state.showGrid,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Switch, onClick = onGridToggle)
                    .testTag("grid_toggle"),
            )
        }
    }
}

@Composable
private fun PhotoBadge() {
    Row(
        modifier = Modifier
            .background(NavySurface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .border(1.dp, NavyAccent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(Color.White, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "STILL",
            style = HudLabel,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AudioMeterChip(
    meter: AudioMeterData,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    val border = if (highlighted) NavyActive else Hairline
    val bg = if (highlighted) NavyAccent.copy(alpha = 0.9f) else NavySurface.copy(alpha = 0.75f)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (meter.activeSource) {
                AudioSourceType.BuiltIn -> "MIC"
                AudioSourceType.Usb -> "USB"
                AudioSourceType.Bluetooth -> "BT"
            },
            style = HudLabel,
            fontSize = 9.sp,
            color = if (highlighted) NavyActive else TextSecondary,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(11.dp),
        ) {
            val fracL = meter.peakNormalized.coerceIn(0.05f, 1f)
            val fracR = ((meter.rmsDb + 60f) / 60f).coerceIn(0.05f, 1f)
            val barColorL = if (meter.isClipping) RecordRed else ReadyGreen
            val barColorR = if (meter.isClipping) RecordRed else ReadyGreen

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fracL)
                    .background(barColorL, RoundedCornerShape(1.dp)),
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fracR)
                    .background(barColorR, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun RecordingTally(elapsedMs: Long) {
    val infiniteTransition = rememberInfiniteTransition(label = "tally_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tally_alpha",
    )

    Row(
        modifier = Modifier
            .background(NavySurface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .border(1.dp, RecordRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(pulseAlpha)
                .background(RecordRed, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "REC",
            style = HudLabel,
            color = RecordRed,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ExposureFormat.timecode(elapsedMs),
            style = Timecode,
            color = TextPrimary,
        )
    }
}

@Composable
fun SpecChip(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(7.dp)
    val border = if (highlighted) NavyActive.copy(alpha = 0.85f) else Hairline
    val bg = if (highlighted) NavyAccent.copy(alpha = 0.9f) else NavySurface.copy(alpha = 0.65f)
    val textColor = if (highlighted) NavyActive else TextSecondary
    Box(
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = HudLabel, color = textColor)
    }
}
