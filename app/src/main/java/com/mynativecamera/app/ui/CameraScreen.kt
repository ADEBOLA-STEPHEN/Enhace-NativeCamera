package com.mynativecamera.app.ui

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.camera.ExposureFormat
import com.mynativecamera.app.camera.FocusMode
import com.mynativecamera.app.camera.LensOption
import com.mynativecamera.app.camera.WbMode
import com.mynativecamera.app.ui.components.ControlPanel
import com.mynativecamera.app.ui.components.LensSwitcher
import com.mynativecamera.app.ui.components.ProOvalPill
import com.mynativecamera.app.ui.components.ProSliderModal
import com.mynativecamera.app.ui.components.TopHud
import com.mynativecamera.app.ui.components.Viewfinder
import com.mynativecamera.app.ui.theme.DeepSpaceBlack
import com.mynativecamera.app.ui.theme.GlassBlack
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudCaption
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.MyNativeCameraTheme
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.Navy900
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.ReadyGreen
import com.mynativecamera.app.ui.theme.RecordRed
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary
import com.mynativecamera.app.ui.theme.TextTertiary
import com.mynativecamera.app.ui.theme.WarningAmber
import kotlinx.coroutines.delay

/** Stateful entry point: binds the ViewModel and lifecycle to the stateless screen. */
@Composable
fun CameraRoute(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onPause()
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.onStop()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    viewModel.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val context = LocalContext.current
    val actions = remember(viewModel, context) {
        fun getRotationDeg(): Int {
            val activity = context as? android.app.Activity
            val rot = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    activity?.display?.rotation ?: android.view.Surface.ROTATION_0
                } else {
                    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
                }
            } catch (_: Exception) {
                android.view.Surface.ROTATION_0
            }
            return when (rot) {
                android.view.Surface.ROTATION_0 -> 0
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
        }

        CameraActions(
            onSurfaceAvailable = viewModel::onPreviewSurfaceAvailable,
            onSurfaceSizeChanged = viewModel::onPreviewSurfaceSizeChanged,
            onSurfaceDestroyed = viewModel::onPreviewSurfaceDestroyed,
            onIsoIndexChange = viewModel::onIsoIndexChanged,
            onShutterIndexChange = viewModel::onShutterIndexChanged,
            onFocusModeChange = viewModel::onFocusModeChanged,
            onFocusRatioChange = viewModel::onFocusRatioChanged,
            onWbModeChange = viewModel::onWbModeChanged,
            onWbKelvinChange = viewModel::onWbKelvinChanged,
            onAwbLockToggle = viewModel::onAwbLockToggled,
            onEvChange = viewModel::onEvIndexChanged,
            onOisToggle = viewModel::onOisToggled,
            onEisToggle = viewModel::onEisToggled,
            onVideoConfigChange = viewModel::onVideoConfigChanged,
            onBitrateChange = viewModel::onBitrateChanged,
            onColorProfileChange = viewModel::onColorProfileChanged,
            onLensSelected = viewModel::onLensSelected,
            onAudioSourceChange = viewModel::onAudioSourceChanged,
            onLowCutToggle = viewModel::onLowCutToggled,
            onRawCaptureToggle = viewModel::onRawCaptureToggled,
            onBypassToggle = viewModel::onBypassToggled,
            onHdrToggle = viewModel::onHdrToggled,
            onGridToggle = viewModel::onGridToggled,
            onRecordClick = { viewModel.onRecordClicked(getRotationDeg()) },
            onPhotoClick = { viewModel.onTakePhoto(getRotationDeg()) },
            onFlipCamera = viewModel::onFlipCamera,
            onThumbnailClick = { viewModel.onThumbnailClick(context) },
            onModeChange = viewModel::onCaptureModeChanged,
            onModalToggle = viewModel::onActiveModalToggled,
            onDismissModal = viewModel::onDismissModal,
            onRetry = viewModel::onRetryClicked,
            onMessageShown = viewModel::onMessageShown,
            onTapToFocus = viewModel::onTapToFocus,
            onFocusDioptersChange = viewModel::onFocusDioptersChanged,
            onAutoExposureToggle = viewModel::onAutoExposureToggled,
            onMatchAutoExposure = viewModel::onMatchAutoExposure,
            onSafeBoundsToggle = viewModel::onSafeBoundsToggled,
            onHapticsToggle = viewModel::onHapticsToggled,
            onHdrBurstToggle = viewModel::onHdrBurstPhotoToggled,
        )
    }

    CameraScreen(state = state, actions = actions)
}

class CameraActions(
    val onSurfaceAvailable: (android.graphics.SurfaceTexture, Int, Int) -> Unit = { _, _, _ -> },
    val onSurfaceSizeChanged: (android.graphics.SurfaceTexture, Int, Int) -> Unit = { _, _, _ -> },
    val onSurfaceDestroyed: () -> Unit = {},
    val onIsoIndexChange: (Int) -> Unit = {},
    val onShutterIndexChange: (Int) -> Unit = {},
    val onFocusModeChange: (FocusMode) -> Unit = {},
    val onFocusRatioChange: (Float) -> Unit = {},
    val onWbModeChange: (WbMode) -> Unit = {},
    val onWbKelvinChange: (Int) -> Unit = {},
    val onAwbLockToggle: () -> Unit = {},
    val onEvChange: (Int) -> Unit = {},
    val onOisToggle: () -> Unit = {},
    val onEisToggle: () -> Unit = {},
    val onVideoConfigChange: (width: Int?, fps: Int, codec: Int) -> Unit = { _, _, _ -> },
    val onBitrateChange: (Int) -> Unit = {},
    val onColorProfileChange: (ColorProfile) -> Unit = {},
    val onLensSelected: (LensOption) -> Unit = {},
    val onAudioSourceChange: (AudioSourceType) -> Unit = {},
    val onLowCutToggle: () -> Unit = {},
    val onRawCaptureToggle: () -> Unit = {},
    val onBypassToggle: (Boolean) -> Unit = {},
    val onHdrToggle: (Boolean) -> Unit = {},
    val onGridToggle: () -> Unit = {},
    val onRecordClick: () -> Unit = {},
    val onPhotoClick: () -> Unit = {},
    val onFlipCamera: () -> Unit = {},
    val onThumbnailClick: () -> Unit = {},
    val onModeChange: (CaptureMode) -> Unit = {},
    val onModalToggle: (SliderModal) -> Unit = {},
    val onDismissModal: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMessageShown: (Long) -> Unit = {},
    val onTapToFocus: (androidx.compose.ui.geometry.Offset, Float, Float) -> Unit = { _, _, _ -> },
    val onFocusDioptersChange: (Float) -> Unit = {},
    val onAutoExposureToggle: () -> Unit = {},
    val onMatchAutoExposure: () -> Unit = {},
    val onSafeBoundsToggle: () -> Unit = {},
    val onHapticsToggle: () -> Unit = {},
    val onHdrBurstToggle: () -> Unit = {},
)

/**
 * Single-page pro camera layout (portrait):
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │ [STBY/REC 00:00]   [VIDEO|PHOTO] [1080p 30p]  ↺FRONT LOGS│  TopHud
 * │                                                          │
 * │                                                          │
 * │               unobstructed cinema viewfinder             │
 * │                                                          │
 * │       [ProSliderModal (floating above pills when open)]   │
 * │                                                          │
 * │ [ISO·400] [SHUTTER·1/60] [EV·0.0] [FOCUS·AF] [WB·5600K]  │  Collapsible Oval Pills
 * │ [PRO BYPASS ●]          ( ◉ )         [CINEMA LOG ●]     │  ControlPanel (navy)
 * └──────────────────────────────────────────────────────────┘
 */
@Composable
fun CameraScreen(state: CameraUiState, actions: CameraActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Layer 1: the live image (with front camera selfie mirror and tap-to-focus reticle).
        Viewfinder(
            aspectRatio = state.previewAspectRatio,
            showGrid = state.showGrid,
            isRecording = state.isRecording,
            onSurfaceAvailable = actions.onSurfaceAvailable,
            onSurfaceSizeChanged = actions.onSurfaceSizeChanged,
            onSurfaceDestroyed = actions.onSurfaceDestroyed,
            isFrontCamera = state.isFrontCamera,
            focusTargetPoint = state.focusTargetPoint,
            focusLocked = state.focusLocked,
            onTapToFocus = actions.onTapToFocus,
        )

        // Layer 2: pipeline status (opening / error) centred over the image.
        PipelineStatusOverlay(
            status = state.status,
            errorText = state.errorText,
            onRetry = actions.onRetry,
            modifier = Modifier.align(Alignment.Center),
        )

        // Layer 3: HUD and controls.
        Column(Modifier.fillMaxSize()) {
            TopHud(
                state = state,
                onGridToggle = actions.onGridToggle,
                onRawCaptureToggle = actions.onRawCaptureToggle,
                onHdrBurstToggle = actions.onHdrBurstToggle,
                onOpenSettings = { actions.onModalToggle(SliderModal.VideoSettings) },
                onOpenAudioSettings = { actions.onModalToggle(SliderModal.Audio) },
            )

            // Center Viewport Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                // Temporary user toast / notification
                MessageBanner(
                    message = state.message,
                    onShown = actions.onMessageShown,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                )

                // Floating Multi-Lens Switcher (.5x, 1x, 2x, 3x) anchored above the oval pills when not in selfie or modal
                if (!state.isFrontCamera && state.activeModal == SliderModal.None) {
                    LensSwitcher(
                        activeLens = state.activeLens,
                        onLensSelected = actions.onLensSelected,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }

                // Floating precision slider modal (anchored above the oval pills)
                ProSliderModal(
                    state = state,
                    onIsoIndexChange = actions.onIsoIndexChange,
                    onShutterIndexChange = actions.onShutterIndexChange,
                    onFocusModeChange = actions.onFocusModeChange,
                    onFocusRatioChange = actions.onFocusRatioChange,
                    onFocusDioptersChange = actions.onFocusDioptersChange,
                    onWbModeChange = actions.onWbModeChange,
                    onWbKelvinChange = actions.onWbKelvinChange,
                    onAwbLockToggle = actions.onAwbLockToggle,
                    onEvChange = actions.onEvChange,
                    onOisToggle = actions.onOisToggle,
                    onEisToggle = actions.onEisToggle,
                    onVideoConfigChange = actions.onVideoConfigChange,
                    onBitrateChange = actions.onBitrateChange,
                    onColorProfileChange = actions.onColorProfileChange,
                    onAudioSourceChange = actions.onAudioSourceChange,
                    onLowCutToggle = actions.onLowCutToggle,
                    onAutoExposureToggle = actions.onAutoExposureToggle,
                    onMatchAutoExposure = actions.onMatchAutoExposure,
                    onSafeBoundsToggle = actions.onSafeBoundsToggle,
                    onHapticsToggle = actions.onHapticsToggle,
                    onDismiss = actions.onDismissModal,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }

            // Bottom Control Bar: floating, horizontally scrollable Row containing real-time states
            // ISO, Shutter, Focus (dpt), EV, Temp (K), Stab (OIS+EIS status), Profile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepSpaceBlack)
                    .border(0.8.dp, Hairline)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1. ISO (e.g. ISO 250)
                ProOvalPill(
                    label = "ISO",
                    valueText = if (state.isManualExposure) "ISO ${state.iso}" else "ISO ${state.displayIso}",
                    isActive = state.activeModal == SliderModal.Iso,
                    onClick = { actions.onModalToggle(SliderModal.Iso) },
                    enabled = state.exposureControlsEnabled,
                )
                // 2. Shutter (e.g. 1/48)
                ProOvalPill(
                    label = "SHUTTER",
                    valueText = if (state.isManualExposure) ExposureFormat.shutter(state.shutterNs) else ExposureFormat.shutter(state.displayShutterNs),
                    isActive = state.activeModal == SliderModal.Shutter,
                    onClick = { actions.onModalToggle(SliderModal.Shutter) },
                    enabled = state.exposureControlsEnabled,
                )
                // 3. Focus (displayed in dpt - Diopters)
                ProOvalPill(
                    label = "FOCUS",
                    valueText = state.focusDisplay,
                    isActive = state.activeModal == SliderModal.Focus,
                    onClick = { actions.onModalToggle(SliderModal.Focus) },
                    enabled = state.focusSupported,
                )
                // 4. EV (e.g. +0.0)
                ProOvalPill(
                    label = "EV",
                    valueText = state.evDisplay,
                    isActive = state.activeModal == SliderModal.Ev,
                    onClick = { actions.onModalToggle(SliderModal.Ev) },
                )
                // 5. Temp (e.g. 5200K)
                ProOvalPill(
                    label = "TEMP",
                    valueText = "${state.wbKelvin}K",
                    isActive = state.activeModal == SliderModal.WhiteBalance,
                    onClick = { actions.onModalToggle(SliderModal.WhiteBalance) },
                )
                // 6. Stab (OIS+EIS status, e.g. OIS+EIS)
                ProOvalPill(
                    label = "STAB",
                    valueText = state.stabDisplay,
                    isActive = state.activeModal == SliderModal.Stabilization,
                    onClick = { actions.onModalToggle(SliderModal.Stabilization) },
                )
                // 7. Video Settings & Color Profile / Photo Status
                if (state.captureMode == CaptureMode.Photo) {
                    ProOvalPill(
                        label = "HDR BURST",
                        valueText = if (state.hdrBurstPhotoEnabled) "3-FRAME" else "OFF",
                        isActive = state.hdrBurstPhotoEnabled,
                        onClick = actions.onHdrBurstToggle,
                    )
                } else {
                    ProOvalPill(
                        label = "PROFILE",
                        valueText = if (state.colorProfile == ColorProfile.NativeMinimal) "NATIVE" else "SAFE",
                        isActive = state.activeModal == SliderModal.VideoSettings,
                        onClick = { actions.onModalToggle(SliderModal.VideoSettings) },
                    )
                }
            }

            // Bottom control deck: Clean shutter deck with centered Record/Photo button, Flip, Thumbnail, and mode toggle
            ControlPanel(
                state = state,
                onRecordClick = actions.onRecordClick,
                onPhotoClick = actions.onPhotoClick,
                onFlipCamera = actions.onFlipCamera,
                onThumbnailClick = actions.onThumbnailClick,
                onModeChange = actions.onModeChange,
            )
        }
    }
}

@Composable
private fun PipelineStatusOverlay(
    status: CameraStatus,
    errorText: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = status == CameraStatus.Opening || status == CameraStatus.Error,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            modifier = Modifier
                .background(GlassBlack, shape)
                .border(1.dp, Hairline, shape)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (status == CameraStatus.Error) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = RecordRed, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = errorText ?: "Camera unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy700, contentColor = TextPrimary),
                    modifier = Modifier.testTag("retry_camera"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            } else {
                CircularProgressIndicator(color = NavyGlow, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("INITIALISING SENSOR", style = HudLabel, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: UserMessage?,
    onShown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(2800)
            onShown(message.id)
        }
    }
    // Keep showing the last text while the banner animates out.
    val lastMessage = remember { mutableStateOf(message) }
    if (message != null) lastMessage.value = message
    val shown = lastMessage.value

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(14.dp)
        val accent = if (shown?.isError == true) RecordRed else NavyGlow
        Row(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .background(Navy900.copy(alpha = 0.92f), shape)
                .border(1.dp, accent.copy(alpha = 0.7f), shape)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("message_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
            Text(shown?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@Preview(name = "Standby", widthDp = 891, heightDp = 411)
@Composable
private fun CameraScreenStandbyPreview() {
    MyNativeCameraTheme {
        CameraScreen(
            state = CameraUiState(status = CameraStatus.Ready, hdrSupported = true),
            actions = CameraActions(),
        )
    }
}

@Preview(name = "Recording HDR", widthDp = 891, heightDp = 411)
@Composable
private fun CameraScreenRecordingPreview() {
    MyNativeCameraTheme {
        CameraScreen(
            state = CameraUiState(
                status = CameraStatus.Ready,
                hdrSupported = true,
                hdrEnabled = true,
                isRecording = true,
                recordingElapsedMs = 83_000L,
                message = UserMessage(1, "Saved to DCIM/MyNativeCamera"),
            ),
            actions = CameraActions(),
        )
    }
}
