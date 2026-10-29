package com.mynativecamera.app.ui

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.camera.CameraCapabilities
import com.mynativecamera.app.camera.CameraEngine
import com.mynativecamera.app.camera.DeviceProfile
import com.mynativecamera.app.camera.ExposureFormat
import com.mynativecamera.app.camera.ExposureStops
import com.mynativecamera.app.camera.FocusMode
import com.mynativecamera.app.camera.LensOption
import com.mynativecamera.app.camera.VideoConfig
import com.mynativecamera.app.camera.WbMode
import com.mynativecamera.app.recording.SavedClip
import com.mynativecamera.app.recording.SavedPhoto
import com.mynativecamera.app.ui.haptics.ProHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Screen-level state holder bridging Compose UI with low-level Camera2 [CameraEngine].
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val haptics = ProHaptics(application)
    private val _uiState = MutableStateFlow(CameraUiState(status = CameraStatus.Opening))
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var nextMessageId = 0L

    private val engineListener = object : CameraEngine.Listener {
        override fun onCapabilitiesDiscovered(
            capabilities: CameraCapabilities,
            videoConfig: VideoConfig,
            deviceProfile: DeviceProfile,
        ) {
            val isoStops = ExposureStops.isoStopsFor(capabilities.isoMin, capabilities.isoMax)
            val shutterStops = ExposureStops.shutterStopsFor(
                capabilities.exposureMinNs,
                capabilities.exposureMaxNs,
                videoConfig.frameDurationNs,
            )

            logDiagnostic("Hardware Profile: ${deviceProfile.model} (${deviceProfile.tier.name} · ${deviceProfile.ramGb}GB RAM · ${deviceProfile.cpuCores} cores)")
            logDiagnostic("Sensor ${capabilities.cameraId} (${if (capabilities.isFrontFacing) "FRONT" else "BACK"}): HLG10=${capabilities.hlg10Supported}, OIS=${capabilities.opticalStabilizationSupported}, EIS=${capabilities.videoStabilizationSupported}")
            logDiagnostic("Active Profile: ${videoConfig.codecLabel} ${videoConfig.width}x${videoConfig.height} @ ${videoConfig.fps}p (${videoConfig.bitrateBps / 1_000_000} Mbps)")

            _uiState.update { current ->
                current.copy(
                    deviceProfile = deviceProfile,
                    lensFacing = capabilities.lensFacing,
                    sensorOrientation = capabilities.sensorOrientation,
                    manualSensorSupported = capabilities.manualSensor,
                    isoStops = isoStops,
                    isoIndex = current.isoIndex.coerceIn(0, isoStops.lastIndex),
                    shutterStops = shutterStops,
                    shutterIndex = current.shutterIndex.coerceIn(0, shutterStops.lastIndex),
                    focusSupported = capabilities.manualFocusSupported,
                    maxFocusDiopters = if (capabilities.minFocusDistance > 0f) capabilities.minFocusDistance else 10f,
                    isAv1Supported = com.mynativecamera.app.camera.EncoderSupport.hasAv1(),
                    oisSupported = capabilities.opticalStabilizationSupported,
                    oisEnabled = capabilities.opticalStabilizationSupported,
                    eisSupported = capabilities.videoStabilizationSupported,
                    eisEnabled = capabilities.videoStabilizationSupported,
                    evMin = capabilities.aeCompMin,
                    evMax = capabilities.aeCompMax,
                    evStep = capabilities.aeCompStep,
                    awbLockAvailable = capabilities.awbLockAvailable,
                    rawSupported = capabilities.rawSupported,
                    hdrSupported = capabilities.hlg10Supported,
                    hdrEnabled = videoConfig.hdr,
                    previewAspectRatio = capabilities.nativeSensorAspectRatio,
                    videoSpec = VideoSpec(
                        width = videoConfig.width,
                        height = videoConfig.height,
                        fps = videoConfig.fps,
                        bitrateBps = videoConfig.bitrateBps,
                        codecLabel = videoConfig.codecLabel,
                    ),
                )
            }
        }

        override fun onSessionConfigured(previewSize: Size) {
            logDiagnostic("Session active: preview ${previewSize.width}x${previewSize.height}")
            _uiState.update { it.copy(status = CameraStatus.Ready, errorText = null) }
        }

        override fun onSessionError(message: String) {
            logDiagnostic("Session error: $message")
            _uiState.update { it.copy(status = CameraStatus.Error, errorText = message) }
        }

        override fun onInitialExposureMetered(iso: Int, shutterNs: Long) {
            logDiagnostic("AE converged: ISO $iso, Shutter ${ExposureFormat.shutter(shutterNs)}")
            _uiState.update { current ->
                val newIsoIdx = ExposureStops.nearestIndex(current.isoStops, iso)
                val newShutterIdx = ExposureStops.nearestIndex(current.shutterStops, shutterNs)
                current.copy(
                    liveMeteredIso = iso,
                    liveMeteredShutterNs = shutterNs,
                    isoIndex = newIsoIdx,
                    shutterIndex = newShutterIdx,
                )
            }
        }

        override fun onLiveExposureUpdated(iso: Int, shutterNs: Long) {
            _uiState.update { current ->
                if (!current.isManualExposure) {
                    val newIsoIdx = ExposureStops.nearestIndex(current.isoStops, iso)
                    val newShutterIdx = ExposureStops.nearestIndex(current.shutterStops, shutterNs)
                    current.copy(
                        liveMeteredIso = iso,
                        liveMeteredShutterNs = shutterNs,
                        isoIndex = newIsoIdx,
                        shutterIndex = newShutterIdx,
                    )
                } else {
                    current.copy(
                        liveMeteredIso = iso,
                        liveMeteredShutterNs = shutterNs,
                    )
                }
            }
        }

        override fun onLiveFocusDistanceUpdated(diopters: Float) {
            _uiState.update { it.copy(liveFocusDiopters = diopters) }
        }

        override fun onFocusLocked() {
            _uiState.update { it.copy(focusLocked = true) }
            haptics.focusLocked()
        }

        override fun onRecordingStarted() {
            logDiagnostic("Take started -> encoding to MediaStore")
            _uiState.update { it.copy(isRecording = true, recordingElapsedMs = 0L) }
            startTimer()
        }

        override fun onRecordingStopped(savedClip: SavedClip?) {
            stopTimer()
            _uiState.update { it.copy(isRecording = false) }
            if (savedClip != null) {
                _uiState.update { it.copy(lastCapturedMediaUri = savedClip.uri) }
                logDiagnostic("Take finalized: ${savedClip.displayName}")
                postMessage("Saved ${savedClip.displayName} to DCIM/MyNativeCamera")
            }
        }

        override fun onRecordingFailed(message: String) {
            stopTimer()
            logDiagnostic("Recording FAILED: $message")
            _uiState.update { it.copy(isRecording = false) }
            postMessage(message, isError = true)
        }

        override fun onSegmentRolled(clip: SavedClip) {
            logDiagnostic("4GB rollover: ${clip.displayName}")
            postMessage("Segment saved: ${clip.displayName}")
        }

        override fun onPhotoCaptureStarted() {
            logDiagnostic("Shutter capture started")
        }

        override fun onPhotoSaved(savedPhoto: SavedPhoto) {
            _uiState.update { it.copy(lastCapturedMediaUri = savedPhoto.uri) }
            logDiagnostic("Photo saved: ${savedPhoto.displayName}")
            postMessage("Saved photo ${savedPhoto.displayName}")
        }
    }

    private val engine = CameraEngine(application, engineListener)

    init {
        engine.start()
        viewModelScope.launch {
            loadLatestMediaUri()?.let { uri ->
                _uiState.update { it.copy(lastCapturedMediaUri = uri) }
            }
        }
        viewModelScope.launch {
            engine.audioEngine.meterState.collect { meter ->
                _uiState.update {
                    it.copy(
                        audioMeter = meter,
                        activeAudioSource = meter.activeSource,
                        availableAudioSources = meter.availableSources,
                        lowCutFilterEnabled = meter.lowCutEnabled,
                    )
                }
            }
        }
    }

    // ---- Lifecycle / Surface -------------------------------------------------------------

    fun onResume() {
        _uiState.update { it.copy(status = CameraStatus.Opening, errorText = null) }
        engine.onResume()
    }

    fun onPause() {
        if (_uiState.value.isRecording) {
            engine.stopRecording()
        }
        engine.onPause()
        _uiState.update { it.copy(status = CameraStatus.Idle) }
    }

    fun onStop() {
        if (_uiState.value.isRecording) {
            engine.stopRecording()
        }
        engine.onPause()
        _uiState.update { it.copy(status = CameraStatus.Idle) }
    }

    fun onDestroy() {
        engine.stop()
    }

    fun onHostStarted() {
        onResume()
    }

    fun onHostStopped() {
        onStop()
    }

    fun onPreviewSurfaceAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        _uiState.update { it.copy(status = CameraStatus.Opening, errorText = null) }
        engine.onPreviewSurfaceAvailable(surface, width, height)
    }

    fun onPreviewSurfaceSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    fun onPreviewSurfaceDestroyed() {
        if (_uiState.value.isRecording) {
            engine.stopRecording()
        }
        engine.onPreviewSurfaceDestroyed()
        _uiState.update { it.copy(status = CameraStatus.Idle) }
    }

    fun onRetryClicked() {
        _uiState.update { it.copy(status = CameraStatus.Opening, errorText = null) }
        engine.reMeter()
    }

    // ---- Mode & Modal Controls -----------------------------------------------------------

    // ---- Mode & Modal Controls -----------------------------------------------------------

    fun onCaptureModeChanged(mode: CaptureMode) {
        haptics.click()
        val caps = engine.currentCapabilities
        val targetAspect = when (mode) {
            CaptureMode.Photo -> caps?.nativeSensorAspectRatio ?: (4f / 3f)
            CaptureMode.Video -> {
                val spec = _uiState.value.videoSpec
                if (spec.height > 0) spec.width.toFloat() / spec.height.toFloat() else (16f / 9f)
            }
        }
        _uiState.update { it.copy(captureMode = mode, activeModal = SliderModal.None, previewAspectRatio = targetAspect) }
        engine.setTargetAspectRatio(targetAspect)
    }

    fun onActiveModalToggled(modal: SliderModal) {
        haptics.click()
        _uiState.update { current ->
            val next = if (current.activeModal == modal) SliderModal.None else modal
            current.copy(activeModal = next)
        }
    }

    fun onDismissModal() {
        haptics.click()
        _uiState.update { it.copy(activeModal = SliderModal.None) }
    }

    fun onFlipCamera() {
        if (_uiState.value.isRecording) return
        haptics.click()
        logDiagnostic("Switching camera lens...")
        _uiState.update { it.copy(status = CameraStatus.Opening) }
        engine.toggleLensFacing()
    }

    // ---- Tap to Focus & Meter ------------------------------------------------------------

    fun onTapToFocus(offset: Offset, viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val normX = (offset.x / viewWidth).coerceIn(0f, 1f)
        val normY = (offset.y / viewHeight).coerceIn(0f, 1f)
        haptics.tick()
        _uiState.update { it.copy(focusTargetPoint = offset, focusLocked = false) }
        engine.tapToFocusAndMeter(normX, normY)
        logDiagnostic("Tap to focus & meter at (${"%.2f".format(normX)}, ${"%.2f".format(normY)})")
    }

    // ---- Auto Exposure & Manual Controls -------------------------------------------------

    fun onAutoExposureToggled() {
        haptics.click()
        val nextManual = !_uiState.value.isManualExposure
        if (nextManual) {
            onMatchAutoExposure()
        } else {
            engine.setAutoExposure()
            _uiState.update { it.copy(isManualExposure = false) }
            logDiagnostic("Exposure mode: AUTO BALANCED")
        }
    }

    fun onMatchAutoExposure() {
        haptics.click()
        val curIso = _uiState.value.liveMeteredIso
        val curShutter = _uiState.value.liveMeteredShutterNs
        val newIsoIdx = ExposureStops.nearestIndex(_uiState.value.isoStops, curIso)
        val newShutterIdx = ExposureStops.nearestIndex(_uiState.value.shutterStops, curShutter)
        _uiState.update { it.copy(isManualExposure = true, isoIndex = newIsoIdx, shutterIndex = newShutterIdx) }
        engine.lockCurrentAutoExposure(curIso, curShutter)
        logDiagnostic("Exposure locked: ISO $curIso, Shutter ${ExposureFormat.shutter(curShutter)}")
    }

    fun onIsoIndexChanged(index: Int) {
        haptics.tick()
        val stops = _uiState.value.isoStops
        val validIndex = index.coerceIn(0, stops.lastIndex)
        _uiState.update { it.copy(isoIndex = validIndex, isManualExposure = true) }
        engine.setSensitivity(stops[validIndex])
    }

    fun onShutterIndexChanged(index: Int) {
        haptics.tick()
        val stops = _uiState.value.shutterStops
        val validIndex = index.coerceIn(0, stops.lastIndex)
        _uiState.update { it.copy(shutterIndex = validIndex, isManualExposure = true) }
        engine.setExposureTime(stops[validIndex])
    }

    fun onFocusModeChanged(mode: FocusMode) {
        haptics.click()
        _uiState.update { it.copy(focusMode = mode) }
        val diopters = _uiState.value.focusDistanceRatio * 10f
        engine.setFocus(mode, diopters)
    }

    fun onFocusRatioChanged(ratio: Float) {
        haptics.tick()
        val maxDpt = _uiState.value.maxFocusDiopters
        val diopters = ratio * maxDpt
        _uiState.update { it.copy(focusDistanceRatio = ratio, focusDiopters = diopters, focusMode = FocusMode.Manual) }
        engine.setFocus(FocusMode.Manual, diopters)
    }

    fun onFocusDioptersChanged(diopters: Float) {
        haptics.tick()
        val valid = diopters.coerceIn(0f, _uiState.value.maxFocusDiopters)
        val ratio = if (_uiState.value.maxFocusDiopters > 0f) valid / _uiState.value.maxFocusDiopters else 0f
        _uiState.update { it.copy(focusDiopters = valid, focusDistanceRatio = ratio, focusMode = FocusMode.Manual) }
        engine.setFocusDiopters(valid)
    }

    fun onColorProfileChanged(profile: ColorProfile) {
        haptics.click()
        _uiState.update { it.copy(colorProfile = profile) }
        engine.setColorProfile(profile)
        logDiagnostic("Color Profile: ${if (profile == ColorProfile.NativeMinimal) "NATIVE / MINIMAL (FLAT LOG CURVE)" else "SAFE (HIGH QUALITY)"}")
    }

    fun onBitrateChanged(bitrateMbps: Int) {
        haptics.tick()
        val valid = bitrateMbps.coerceIn(10, 100)
        _uiState.update { it.copy(customBitrateMbps = valid) }
        engine.setCustomBitrate(valid)
        logDiagnostic("Custom Bitrate: $valid Mbps")
    }

    fun onWbModeChanged(mode: WbMode) {
        haptics.click()
        _uiState.update { it.copy(wbMode = mode) }
        engine.setWhiteBalance(mode, _uiState.value.wbKelvin)
    }

    fun onWbKelvinChanged(kelvin: Int) {
        haptics.tick()
        _uiState.update { it.copy(wbKelvin = kelvin, wbMode = WbMode.Manual) }
        engine.setWhiteBalance(WbMode.Manual, kelvin)
    }

    fun onOisToggled() {
        haptics.click()
        val next = !_uiState.value.oisEnabled
        _uiState.update { it.copy(oisEnabled = next) }
        engine.setStabilization(next, _uiState.value.eisEnabled)
    }

    fun onEisToggled() {
        haptics.click()
        val next = !_uiState.value.eisEnabled
        _uiState.update { it.copy(eisEnabled = next) }
        engine.setStabilization(_uiState.value.oisEnabled, next)
    }

    fun onEvIndexChanged(ev: Int) {
        haptics.tick()
        val valid = ev.coerceIn(_uiState.value.evMin, _uiState.value.evMax)
        _uiState.update { it.copy(evIndex = valid) }
        engine.setEvCompensation(valid)
    }

    fun onAwbLockToggled() {
        haptics.click()
        val next = !_uiState.value.awbLocked
        _uiState.update { it.copy(awbLocked = next) }
        engine.setAwbLocked(next)
        logDiagnostic("AWB Lock: ${if (next) "LOCKED" else "UNLOCKED"}")
    }

    fun onLensSelected(lens: LensOption) {
        haptics.click()
        _uiState.update { it.copy(activeLens = lens) }
        engine.switchLens(lens)
        logDiagnostic("Lens selected: ${lens.label}")
    }

    fun onAudioSourceChanged(source: AudioSourceType) {
        haptics.click()
        _uiState.update { it.copy(activeAudioSource = source) }
        engine.setAudioSource(source)
        logDiagnostic("Audio source: ${source.name}")
    }

    fun onLowCutToggled() {
        haptics.click()
        val next = !_uiState.value.lowCutFilterEnabled
        _uiState.update { it.copy(lowCutFilterEnabled = next) }
        engine.setLowCutFilter(next)
        logDiagnostic("Low-Cut / NoiseSuppressor: ${if (next) "ON" else "OFF"}")
    }

    fun onRawCaptureToggled() {
        haptics.click()
        val next = !_uiState.value.rawCaptureEnabled
        _uiState.update { it.copy(rawCaptureEnabled = next) }
        logDiagnostic("RAW DNG capture: ${if (next) "ENABLED" else "DISABLED"}")
    }

    fun onHdrBurstPhotoToggled() {
        haptics.click()
        val next = !_uiState.value.hdrBurstPhotoEnabled
        _uiState.update { it.copy(hdrBurstPhotoEnabled = next) }
        engine.setHdrBurstPhotoEnabled(next)
        logDiagnostic("Photo Pipeline: ${if (next) "3-FRAME HDR BURST (COMPUTATIONAL FUSION)" else "SINGLE SHOT"}")
    }

    // ---- Video Targets (Resolution / FPS / Codec) ----------------------------------------

    fun onVideoConfigChanged(width: Int?, fps: Int, codec: Int) {
        haptics.click()
        val profile = _uiState.value.deviceProfile
        val safeBounds = _uiState.value.safeBoundsEnabled

        val effectiveWidth = if (safeBounds && profile != null && width != null && width > profile.safeMaxVideoWidth) {
            logDiagnostic("Safe Bounds clamped resolution to ${profile.safeMaxVideoWidth}p for ${profile.model}")
            profile.safeMaxVideoWidth
        } else {
            width
        }

        val effectiveFps = if (safeBounds && profile != null && fps > profile.safeMaxFps) {
            logDiagnostic("Safe Bounds clamped framerate to ${profile.safeMaxFps}fps for ${profile.model}")
            profile.safeMaxFps
        } else {
            fps
        }

        _uiState.update {
            it.copy(
                targetResolutionWidth = width,
                targetFps = fps,
                targetCodec = codec,
                status = CameraStatus.Opening,
            )
        }
        engine.setVideoFormat(effectiveWidth, effectiveFps, codec)
    }

    fun onSafeBoundsToggled() {
        haptics.click()
        val next = !_uiState.value.safeBoundsEnabled
        _uiState.update { it.copy(safeBoundsEnabled = next) }
        engine.setSafeBoundsEnabled(next)
        logDiagnostic("Safe Hardware Bounds: ${if (next) "ON (Hardware Protected)" else "OFF (DISREGARDED - UNRESTRICTED)"}")
        val cur = _uiState.value
        onVideoConfigChanged(cur.targetResolutionWidth, cur.targetFps, cur.targetCodec)
    }

    fun onHapticsToggled() {
        val next = !_uiState.value.hapticsEnabled
        _uiState.update { it.copy(hapticsEnabled = next) }
        haptics.isHapticsEnabled = next
        if (next) haptics.click()
        logDiagnostic("Haptics: ${if (next) "ENABLED" else "DISABLED"}")
    }

    // ---- Processing Toggles --------------------------------------------------------------

    fun onBypassToggled(enabled: Boolean) {
        haptics.click()
        _uiState.update { it.copy(bypassEnabled = enabled) }
        engine.setBypassEnabled(enabled)
    }

    fun onHdrToggled(enabled: Boolean) {
        val state = _uiState.value
        if (!state.hdrToggleEnabled) return
        haptics.click()
        _uiState.update { it.copy(hdrEnabled = enabled) }
        engine.setHdrEnabled(enabled)
    }

    fun onGridToggled() {
        haptics.click()
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun onThumbnailClick(context: Context) {
        val uri = _uiState.value.lastCapturedMediaUri ?: return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            postMessage("Unable to open gallery: ${e.message}", isError = true)
        }
    }

    private fun loadLatestMediaUri(): Uri? {
        return try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
            )
            val queryUri = MediaStore.Files.getContentUri("external")
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = getApplication<Application>().contentResolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(queryUri, id)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun logDiagnostic(msg: String) {
        android.util.Log.d("CameraViewModel", msg)
    }

    // ---- Capture Flow (Photo & Video) ----------------------------------------------------

    fun onTakePhoto(rotationDegrees: Int = 0) {
        haptics.shutterRelease()
        logDiagnostic("Take photo tapped (rot=$rotationDegrees°)")
        engine.takePhoto(rotationDegrees)
    }

    fun onRecordClicked(rotationDegreesOverride: Int? = null) {
        val state = _uiState.value
        if (!state.recordEnabled) return

        if (state.isRecording) {
            haptics.recordStop()
            logDiagnostic("User requested STOP recording")
            engine.stopRecording()
        } else {
            haptics.recordStart()
            val rotationDegrees = rotationDegreesOverride ?: getDisplayRotationDegrees()
            logDiagnostic("User requested START recording (rotation=${rotationDegrees}°)")
            engine.startRecording(rotationDegrees)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                _uiState.update { it.copy(recordingElapsedMs = SystemClock.elapsedRealtime() - start) }
                delay(TIMER_TICK_MS)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ---- User Messages -------------------------------------------------------------------

    fun onMessageShown(id: Long) {
        _uiState.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun postMessage(text: String, isError: Boolean = false) {
        _uiState.update { it.copy(message = UserMessage(nextMessageId++, text, isError)) }
    }

    private fun getDisplayRotationDegrees(): Int {
        return try {
            val app = getApplication<Application>()
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val rotation = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    override fun onCleared() {
        stopTimer()
        engine.stop()
        super.onCleared()
    }

    private companion object {
        const val TIMER_TICK_MS = 250L
    }
}
