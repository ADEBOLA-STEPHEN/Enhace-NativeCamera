package com.mynativecamera.app.ui.components

import android.media.MediaRecorder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.camera.ExposureFormat
import com.mynativecamera.app.camera.FocusMode
import com.mynativecamera.app.camera.WbMode
import com.mynativecamera.app.ui.CameraUiState
import com.mynativecamera.app.ui.ColorProfile
import com.mynativecamera.app.ui.SliderModal
import com.mynativecamera.app.ui.theme.CineBlack
import com.mynativecamera.app.ui.theme.DeepSpaceBlack
import com.mynativecamera.app.ui.theme.ElectricCyan
import com.mynativecamera.app.ui.theme.GlassBlack
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudCaption
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.NavyAccent
import com.mynativecamera.app.ui.theme.NavyActive
import com.mynativecamera.app.ui.theme.NavyBase
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.NavyPanel
import com.mynativecamera.app.ui.theme.NavySurface
import com.mynativecamera.app.ui.theme.ReadyGreen
import com.mynativecamera.app.ui.theme.RecordRed
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary
import com.mynativecamera.app.ui.theme.WarningAmber
import java.util.Locale

/**
 * Cinematic Floating Modal (#1A1A2E Dark Purple/Navy) containing precision faders
 * and cinema camera parameter controls.
 */
@Composable
fun ProSliderModal(
    state: CameraUiState,
    onIsoIndexChange: (Int) -> Unit,
    onShutterIndexChange: (Int) -> Unit,
    onFocusModeChange: (FocusMode) -> Unit,
    onFocusRatioChange: (Float) -> Unit,
    onFocusDioptersChange: (Float) -> Unit = {},
    onWbModeChange: (WbMode) -> Unit,
    onWbKelvinChange: (Int) -> Unit,
    onAwbLockToggle: () -> Unit = {},
    onEvChange: (Int) -> Unit,
    onOisToggle: () -> Unit,
    onEisToggle: () -> Unit,
    onVideoConfigChange: (width: Int?, fps: Int, codec: Int) -> Unit,
    onBitrateChange: (Int) -> Unit = {},
    onColorProfileChange: (ColorProfile) -> Unit = {},
    onAudioSourceChange: (AudioSourceType) -> Unit = {},
    onLowCutToggle: () -> Unit = {},
    onAutoExposureToggle: () -> Unit = {},
    onMatchAutoExposure: () -> Unit = {},
    onSafeBoundsToggle: () -> Unit = {},
    onHapticsToggle: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.activeModal != SliderModal.None,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .background(NavyBase.copy(alpha = 0.96f), shape)
                .border(1.2.dp, NavyAccent, shape)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header with title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (state.activeModal) {
                        SliderModal.Iso -> "MANUAL ISO (SENSITIVITY)"
                        SliderModal.Shutter -> "MANUAL SHUTTER SPEED"
                        SliderModal.Focus -> "LENS FOCUS CONTROL (DIOPTERS)"
                        SliderModal.WhiteBalance -> "WHITE BALANCE & COLOR TEMP"
                        SliderModal.Ev -> "EXPOSURE COMPENSATION"
                        SliderModal.Stabilization -> "OPTICAL & ELECTRONIC STABILIZATION"
                        SliderModal.VideoSettings -> "PRO VIDEO ENGINE & COLOR PROFILES"
                        SliderModal.Audio -> "PRO AUDIO & INPUT ROUTING"
                        SliderModal.None -> ""
                    },
                    style = HudLabel,
                    color = NavyActive,
                    letterSpacing = 1.sp,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(NavySurface)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (state.activeModal) {
                SliderModal.Iso -> {
                    IsoSliderContent(
                        state = state,
                        onIndexChange = onIsoIndexChange,
                        onAutoExposureToggle = onAutoExposureToggle,
                        onMatchAutoExposure = onMatchAutoExposure,
                    )
                }
                SliderModal.Shutter -> {
                    ShutterSliderContent(
                        state = state,
                        onIndexChange = onShutterIndexChange,
                        onAutoExposureToggle = onAutoExposureToggle,
                        onMatchAutoExposure = onMatchAutoExposure,
                    )
                }
                SliderModal.Focus -> {
                    FocusSliderContent(
                        state = state,
                        onModeChange = onFocusModeChange,
                        onDioptersChange = onFocusDioptersChange,
                    )
                }
                SliderModal.WhiteBalance -> {
                    WbSliderContent(
                        state = state,
                        onModeChange = onWbModeChange,
                        onKelvinChange = onWbKelvinChange,
                        onAwbLockToggle = onAwbLockToggle,
                    )
                }
                SliderModal.Ev -> {
                    EvSliderContent(
                        state = state,
                        onEvChange = onEvChange,
                    )
                }
                SliderModal.Stabilization -> {
                    StabilizationContent(
                        state = state,
                        onOisToggle = onOisToggle,
                        onEisToggle = onEisToggle,
                    )
                }
                SliderModal.VideoSettings -> {
                    VideoSettingsContent(
                        state = state,
                        onConfigChange = onVideoConfigChange,
                        onBitrateChange = onBitrateChange,
                        onColorProfileChange = onColorProfileChange,
                        onSafeBoundsToggle = onSafeBoundsToggle,
                        onHapticsToggle = onHapticsToggle,
                    )
                }
                SliderModal.Audio -> {
                    AudioSettingsContent(
                        state = state,
                        onSourceChange = onAudioSourceChange,
                        onLowCutToggle = onLowCutToggle,
                    )
                }
                SliderModal.None -> Unit
            }
        }
    }
}

@Composable
private fun IsoSliderContent(
    state: CameraUiState,
    onIndexChange: (Int) -> Unit,
    onAutoExposureToggle: () -> Unit,
    onMatchAutoExposure: () -> Unit,
) {
    val stops = state.isoStops
    val maxIdx = (stops.size - 1).coerceAtLeast(1)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NavyBase)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!state.isManualExposure) NavyActive else Color.Transparent)
                    .clickable { if (state.isManualExposure) onAutoExposureToggle() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AUTO (BALANCED)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (!state.isManualExposure) Color.White else TextSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (state.isManualExposure) NavyActive else Color.Transparent)
                    .clickable { if (!state.isManualExposure) onMatchAutoExposure() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "PRO MANUAL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isManualExposure) Color.White else TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (state.isManualExposure) "ISO ${state.iso}" else "ISO ${state.liveMeteredIso} (AUTO)",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.isManualExposure) TextPrimary else NavyActive,
        )
        Text(
            text = if (state.isManualExposure) "Manual 1/3-Stop Precision Sensitivity Ladder" else "Continuously balancing sensor exposure for lighting",
            style = HudCaption,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))

        Slider(
            value = state.isoIndex.toFloat(),
            onValueChange = { onIndexChange(it.toInt()) },
            valueRange = 0f..maxIdx.toFloat(),
            steps = maxIdx - 1,
            colors = SliderDefaults.colors(
                thumbColor = if (state.isManualExposure) NavyActive else NavyAccent,
                activeTrackColor = if (state.isManualExposure) NavyActive else NavyAccent,
                inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${stops.first()}", style = HudCaption, color = TextSecondary)
            Text("${stops[stops.size / 2]}", style = HudCaption, color = TextSecondary)
            Text("${stops.last()}", style = HudCaption, color = TextSecondary)
        }
    }
}

@Composable
private fun ShutterSliderContent(
    state: CameraUiState,
    onIndexChange: (Int) -> Unit,
    onAutoExposureToggle: () -> Unit,
    onMatchAutoExposure: () -> Unit,
) {
    val stops = state.shutterStops
    val maxIdx = (stops.size - 1).coerceAtLeast(1)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NavyBase)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!state.isManualExposure) NavyActive else Color.Transparent)
                    .clickable { if (state.isManualExposure) onAutoExposureToggle() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AUTO (BALANCED)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (!state.isManualExposure) Color.White else TextSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (state.isManualExposure) NavyActive else Color.Transparent)
                    .clickable { if (!state.isManualExposure) onMatchAutoExposure() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "PRO MANUAL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isManualExposure) Color.White else TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val currentShutter = if (state.isManualExposure) state.shutterNs else state.liveMeteredShutterNs
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (state.isManualExposure) ExposureFormat.shutter(currentShutter) else "${ExposureFormat.shutter(currentShutter)} (AUTO)",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.isManualExposure) TextPrimary else NavyActive,
            )
            Text(
                text = ExposureFormat.shutterAngle(currentShutter, state.videoSpec.fps),
                fontSize = 16.sp,
                color = NavyActive,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = if (state.isManualExposure) "Standard Cinema Shutter Angles & Speeds" else "Balancing shutter angle for smooth cinematic motion",
            style = HudCaption,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))

        Slider(
            value = state.shutterIndex.toFloat(),
            onValueChange = { onIndexChange(it.toInt()) },
            valueRange = 0f..maxIdx.toFloat(),
            steps = maxIdx - 1,
            colors = SliderDefaults.colors(
                thumbColor = if (state.isManualExposure) NavyActive else NavyAccent,
                activeTrackColor = if (state.isManualExposure) NavyActive else NavyAccent,
                inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(ExposureFormat.shutter(stops.first()), style = HudCaption, color = TextSecondary)
            Text(ExposureFormat.shutter(stops[stops.size / 2]), style = HudCaption, color = TextSecondary)
            Text(ExposureFormat.shutter(stops.last()), style = HudCaption, color = TextSecondary)
        }
    }
}

/**
 * Focus slider mapped directly to LENS_FOCUS_DISTANCE in Diopters (dpt = 1.0f / distance_in_meters).
 */
@Composable
private fun FocusSliderContent(
    state: CameraUiState,
    onModeChange: (FocusMode) -> Unit,
    onDioptersChange: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (state.focusMode == FocusMode.Auto) {
                        if (state.liveFocusDiopters > 0.05f) {
                            "CONTINUOUS AF (${"%.1f".format(Locale.US, state.liveFocusDiopters)} dpt)"
                        } else {
                            "CONTINUOUS AF (AUTO)"
                        }
                    } else {
                        val distanceMeters = if (state.focusDiopters > 0.05f) {
                            String.format(Locale.US, "%.2f m", 1.0f / state.focusDiopters)
                        } else {
                            "∞ (Infinity)"
                        }
                        "${"%.1f".format(Locale.US, state.focusDiopters)} dpt · $distanceMeters"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Text(
                    text = "Diopters (dpt) = 1.0 / distance_in_meters",
                    style = HudCaption,
                    color = TextSecondary,
                )
            }

            ChipButton(
                text = "AUTO AF",
                selected = state.focusMode == FocusMode.Auto,
                onClick = { onModeChange(FocusMode.Auto) },
            )
        }

        Spacer(Modifier.height(10.dp))

        val maxDpt = if (state.maxFocusDiopters > 0f) state.maxFocusDiopters else 10f

        Slider(
            value = state.focusDiopters.coerceIn(0f, maxDpt),
            onValueChange = onDioptersChange,
            valueRange = 0f..maxDpt,
            enabled = state.focusSupported,
            colors = SliderDefaults.colors(
                thumbColor = NavyActive,
                activeTrackColor = NavyActive,
                inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0.0 dpt (∞ Infinity)", style = HudCaption, color = TextSecondary)
            Text("${"%.1f".format(Locale.US, maxDpt / 2f)} dpt", style = HudCaption, color = TextSecondary)
            Text("${"%.1f".format(Locale.US, maxDpt)} dpt (Macro)", style = HudCaption, color = TextSecondary)
        }
    }
}

@Composable
private fun WbSliderContent(
    state: CameraUiState,
    onModeChange: (WbMode) -> Unit,
    onKelvinChange: (Int) -> Unit,
    onAwbLockToggle: () -> Unit = {},
) {
    val presets = listOf(
        2800 to "2800K Tungsten",
        3200 to "3200K Studio",
        4000 to "4000K Fluor",
        5600 to "5600K Day",
        6500 to "6500K D65",
        7500 to "7500K Shade",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (state.wbMode == WbMode.Auto) "AUTO WB (${state.wbKelvin}K)" else "${state.wbKelvin}K",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Text(
                    text = if (state.wbMode == WbMode.Auto) "Automatic white balance tracking" else "Manual Planckian kelvin control",
                    style = HudCaption,
                    color = TextSecondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton(
                    text = "AUTO",
                    selected = state.wbMode == WbMode.Auto && !state.awbLocked,
                    onClick = { onModeChange(WbMode.Auto) },
                )
                if (state.awbLockAvailable) {
                    ChipButton(
                        text = if (state.awbLocked) "LOCKED" else "LOCK",
                        selected = state.awbLocked,
                        onClick = onAwbLockToggle,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Cinema Kelvin Presets Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { (kelvin, label) ->
                val isSelected = state.wbMode == WbMode.Manual && state.wbKelvin == kelvin
                ChipButton(
                    text = label,
                    selected = isSelected,
                    onClick = {
                        onKelvinChange(kelvin)
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Slider(
            value = state.wbKelvin.toFloat().coerceIn(2000f, 10000f),
            onValueChange = { onKelvinChange(it.toInt()) },
            valueRange = 2000f..10000f,
            colors = SliderDefaults.colors(
                thumbColor = NavyActive,
                activeTrackColor = NavyActive,
                inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("2000K (Warm/Flame)", style = HudCaption, color = TextSecondary)
            Text("5600K (Daylight)", style = HudCaption, color = TextSecondary)
            Text("10000K (Cool/Sky)", style = HudCaption, color = TextSecondary)
        }
    }
}

@Composable
private fun EvSliderContent(
    state: CameraUiState,
    onEvChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "EV: ${state.evDisplay}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavyActive,
                )
                Text(
                    text = "Exposure bias to preserve highlights and shadows",
                    style = HudCaption,
                    color = TextSecondary,
                )
            }

            ChipButton(
                text = "RESET 0.0",
                selected = state.evIndex == 0,
                onClick = { onEvChange(0) },
            )
        }

        Spacer(Modifier.height(10.dp))

        val minVal = state.evMin.toFloat()
        val maxVal = if (state.evMax > state.evMin) state.evMax.toFloat() else minVal + 1f
        val stepsCount = ((state.evMax - state.evMin) - 1).coerceAtLeast(0)

        Slider(
            value = state.evIndex.toFloat().coerceIn(minVal, maxVal),
            onValueChange = { onEvChange(it.toInt()) },
            valueRange = minVal..maxVal,
            steps = stepsCount,
            colors = SliderDefaults.colors(
                thumbColor = NavyActive,
                activeTrackColor = NavyActive,
                inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${"%.1f".format(Locale.US, state.evMin * state.evStep)} EV (Darker)", style = HudCaption, color = TextSecondary)
            Text("0.0 EV (Neutral)", style = HudCaption, color = TextSecondary)
            Text("+${"%.1f".format(Locale.US, state.evMax * state.evStep)} EV (Brighter)", style = HudCaption, color = TextSecondary)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(-2.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 2.0f).forEach { targetEv ->
                val targetIdx = Math.round(targetEv / state.evStep).coerceIn(state.evMin, state.evMax)
                val label = if (targetEv > 0f) "+${targetEv} EV" else if (targetEv == 0f) "0.0 EV" else "${targetEv} EV"
                ChipButton(
                    text = label,
                    selected = state.evIndex == targetIdx,
                    onClick = { onEvChange(targetIdx) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Dedicated Stabilization Modal for OIS & EIS toggles.
 */
@Composable
private fun StabilizationContent(
    state: CameraUiState,
    onOisToggle: () -> Unit,
    onEisToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "STATUS: ${state.stabDisplay}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavyActive,
                )
                Text(
                    text = "Hardware Optical & Electronic Stabilization",
                    style = HudCaption,
                    color = TextSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChipButton(
                text = if (state.oisSupported) {
                    if (state.oisEnabled) "OIS: ACTIVE (OPTICAL)" else "OIS: OFF"
                } else {
                    "OIS: NOT SUPPORTED"
                },
                selected = state.oisEnabled && state.oisSupported,
                enabled = state.oisSupported,
                onClick = onOisToggle,
                modifier = Modifier.weight(1f),
            )

            ChipButton(
                text = if (state.eisSupported) {
                    if (state.eisEnabled) "EIS: ACTIVE (ELECTRONIC)" else "EIS: OFF"
                } else {
                    "EIS: NOT SUPPORTED"
                },
                selected = state.eisEnabled && state.eisSupported,
                enabled = state.eisSupported,
                onClick = onEisToggle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Phase 2 Pro Video Engine & Color Profiles:
 * 1. Codecs: H.264, HEVC/H.265, AV1 (if hardware supports it).
 * 2. Custom Bitrate Slider: 10 Mbps to 100 Mbps.
 * 3. 10-Bit HLG HDR status.
 * 4. Custom Color Profiles: Safe (High Quality) vs Native/Minimal (TonemapCurve contrast curve, NR & Edge off).
 */
@Composable
private fun VideoSettingsContent(
    state: CameraUiState,
    onConfigChange: (width: Int?, fps: Int, codec: Int) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onColorProfileChange: (ColorProfile) -> Unit,
    onSafeBoundsToggle: () -> Unit,
    onHapticsToggle: () -> Unit,
) {
    val currentW = state.targetResolutionWidth
    val currentFps = state.targetFps
    val currentCodec = state.targetCodec
    val profile = state.deviceProfile

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Color Profile (Safe vs Native/Minimal Tonemapping)
        Column {
            Text("PROCESSING COLOR PROFILE (TONEMAPPING)", style = HudCaption, color = TextSecondary)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton(
                    text = "NATIVE / MINIMAL (FLAT LOG)",
                    selected = state.colorProfile == ColorProfile.NativeMinimal,
                    onClick = { onColorProfileChange(ColorProfile.NativeMinimal) },
                    modifier = Modifier.weight(1f),
                )
                ChipButton(
                    text = "SAFE (HIGH QUALITY)",
                    selected = state.colorProfile == ColorProfile.Safe,
                    onClick = { onColorProfileChange(ColorProfile.Safe) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (state.colorProfile == ColorProfile.NativeMinimal) {
                    "Native/Minimal: Linear contrast curve for max grading dynamic range. Noise reduction & edge ringing disabled."
                } else {
                    "Safe: OEM High Quality Tonemapping, noise suppression, and edge enhancement active."
                },
                style = HudCaption,
                color = NavyActive,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        // 2. Video Codec Selection (H.264, HEVC, AV1)
        Column {
            Text("VIDEO ENCODER CODEC", style = HudCaption, color = TextSecondary)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton(
                    text = "HEVC / H.265",
                    selected = currentCodec == MediaRecorder.VideoEncoder.HEVC,
                    onClick = { onConfigChange(currentW, currentFps, MediaRecorder.VideoEncoder.HEVC) },
                    modifier = Modifier.weight(1f),
                )
                ChipButton(
                    text = "AVC / H.264",
                    selected = currentCodec == MediaRecorder.VideoEncoder.H264,
                    onClick = { onConfigChange(currentW, currentFps, MediaRecorder.VideoEncoder.H264) },
                    modifier = Modifier.weight(1f),
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ChipButton(
                        text = if (state.isAv1Supported) "AV1" else "AV1 (N/A)",
                        selected = currentCodec == MediaRecorder.VideoEncoder.AV1,
                        enabled = state.isAv1Supported,
                        onClick = { onConfigChange(currentW, currentFps, MediaRecorder.VideoEncoder.AV1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 3. Custom Bitrate Slider (10 Mbps to 100 Mbps)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("VIDEO ENCODING BITRATE", style = HudCaption, color = TextSecondary)
                Text(
                    text = "${state.customBitrateMbps} Mbps",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavyActive,
                )
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = state.customBitrateMbps.toFloat(),
                onValueChange = { onBitrateChange(it.toInt()) },
                valueRange = 10f..100f,
                steps = 17, // 5 Mbps increments
                colors = SliderDefaults.colors(
                    thumbColor = NavyActive,
                    activeTrackColor = NavyActive,
                    inactiveTrackColor = NavyAccent.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("10 Mbps", style = HudCaption, color = TextSecondary)
                Text("50 Mbps", style = HudCaption, color = TextSecondary)
                Text("100 Mbps (Master)", style = HudCaption, color = TextSecondary)
            }
        }

        // 4. 10-Bit HLG HDR Info Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NavySurface)
                .border(1.dp, if (state.hdrSupported) NavyActive.copy(alpha = 0.5f) else NavyAccent, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "10-BIT HLG HDR",
                    style = HudLabel,
                    fontSize = 11.sp,
                    color = if (state.hdrSupported) NavyActive else TextSecondary,
                )
                Text(
                    text = if (state.hdrSupported) "SUPPORTED (HLG10)" else "NOT SUPPORTED ON SENSOR",
                    style = HudCaption,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.hdrSupported) ReadyGreen else WarningAmber,
                )
            }
        }

        // 5. Resolution & Frame rate rows
        Column {
            Text("RESOLUTION & CADENCE", style = HudCaption, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    null to "MAX",
                    3840 to "4K",
                    1920 to "1080p",
                    1280 to "720p",
                ).forEach { (w, label) ->
                    ChipButton(
                        text = label,
                        selected = currentW == w,
                        onClick = { onConfigChange(w, currentFps, currentCodec) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(24 to "24 FPS", 30 to "30 FPS", 60 to "60 FPS").forEach { (fps, label) ->
                    ChipButton(
                        text = label,
                        selected = currentFps == fps,
                        onClick = { onConfigChange(currentW, fps, currentCodec) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Hardware Protection Bounds
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton(
                text = if (state.safeBoundsEnabled) "SAFE BOUNDS: ON" else "SAFE BOUNDS: OFF",
                selected = state.safeBoundsEnabled,
                onClick = onSafeBoundsToggle,
                modifier = Modifier.weight(1f),
            )
            ChipButton(
                text = if (state.hapticsEnabled) "HAPTICS: ON" else "HAPTICS: OFF",
                selected = state.hapticsEnabled,
                onClick = onHapticsToggle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    val bgModifier = if (selected) {
        Modifier.background(NavyActive, shape)
    } else {
        Modifier.background(NavySurface, shape)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(bgModifier)
            .border(
                1.dp,
                if (selected) NavyGlow else NavyAccent,
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun AudioSettingsContent(
    state: CameraUiState,
    onSourceChange: (AudioSourceType) -> Unit,
    onLowCutToggle: () -> Unit,
) {
    val meter = state.audioMeter
    val available = state.availableAudioSources

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("AUDIO INPUT SOURCE ROUTING", style = HudCaption, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AudioSourceType.BuiltIn to "BUILT-IN MIC",
                    AudioSourceType.Usb to "USB MIC / DAC",
                    AudioSourceType.Bluetooth to "BLUETOOTH SCO",
                ).forEach { (src, label) ->
                    val isAvailable = available.contains(src)
                    ChipButton(
                        text = label,
                        selected = state.activeAudioSource == src,
                        enabled = isAvailable,
                        onClick = { onSourceChange(src) },
                    )
                }
            }
        }

        Column {
            Text("HARDWARE ACOUSTIC PROCESSING", style = HudCaption, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Low-Cut / Wind Filter (NoiseSuppressor)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Hardware high-pass filtering for wind and acoustic rumble",
                        fontSize = 10.sp,
                        color = TextSecondary,
                    )
                }
                ChipButton(
                    text = if (state.lowCutFilterEnabled) "FILTER: ACTIVE" else "FILTER: BYPASS",
                    selected = state.lowCutFilterEnabled,
                    onClick = onLowCutToggle,
                )
            }
        }

        // Audio Level VU Meter Visualizer
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("REAL-TIME AUDIO VU METER", style = HudCaption, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meter.isClipping) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(RecordRed)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("CLIP ALERT", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }
                    Text(
                        text = "${"%.1f".format(Locale.US, meter.peakDb)} dBFS",
                        style = HudCaption,
                        color = if (meter.isClipping) RecordRed else NavyActive,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            val peakProgress = ((meter.peakDb + 60f) / 60f).coerceIn(0f, 1f)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NavySurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(peakProgress)
                            .fillMaxHeight()
                            .background(
                                if (meter.isClipping) RecordRed
                                else if (peakProgress > 0.85f) Color(0xFFFFB300)
                                else NavyActive
                            )
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("-60", fontSize = 9.sp, color = TextSecondary)
                    Text("-24", fontSize = 9.sp, color = TextSecondary)
                    Text("-12", fontSize = 9.sp, color = TextSecondary)
                    Text("-6", fontSize = 9.sp, color = TextSecondary)
                    Text("0 dBFS", fontSize = 9.sp, color = if (meter.isClipping) RecordRed else TextSecondary)
                }
            }
        }
    }
}
