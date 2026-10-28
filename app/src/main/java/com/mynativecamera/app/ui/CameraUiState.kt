package com.mynativecamera.app.ui

import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.mynativecamera.app.audio.AudioMeterData
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.camera.DeviceProfile
import com.mynativecamera.app.camera.ExposureStops
import com.mynativecamera.app.camera.FocusMode
import com.mynativecamera.app.camera.LensOption
import com.mynativecamera.app.camera.WbMode

enum class CameraStatus { Idle, Opening, Ready, Error }
enum class CaptureMode { Video, Photo }
enum class SliderModal { None, Iso, Shutter, Focus, WhiteBalance, Ev, Stabilization, VideoSettings, Audio }
enum class ColorProfile { Safe, NativeMinimal }

/** The active recording profile. */
@Immutable
data class VideoSpec(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = ExposureStops.DEFAULT_FPS,
    val bitrateBps: Int = 100_000_000,
    val codecLabel: String = "HEVC",
)

/** One-shot message (saved clip, photo, error). */
@Immutable
data class UserMessage(val id: Long, val text: String, val isError: Boolean = false)

/**
 * Single source of truth for the pro camera screen.
 */
@Immutable
data class CameraUiState(
    val status: CameraStatus = CameraStatus.Idle,
    val errorText: String? = null,
    val captureMode: CaptureMode = CaptureMode.Video,
    val activeModal: SliderModal = SliderModal.None,

    // ---- Lens & Multi-Camera ----
    val lensFacing: Int = CameraMetadata.LENS_FACING_BACK,
    val sensorOrientation: Int = 90,
    val activeLens: LensOption = LensOption.Wide,

    // ---- Exposure Control (Auto-balanced vs Pro Manual) ----
    val manualSensorSupported: Boolean = true,
    val isManualExposure: Boolean = false,
    val liveMeteredIso: Int = 400,
    val liveMeteredShutterNs: Long = ExposureStops.NANOS_PER_SECOND / 60,
    val isoStops: List<Int> = ExposureStops.DEFAULT_ISO_STOPS,
    val isoIndex: Int = ExposureStops.nearestIndex(ExposureStops.DEFAULT_ISO_STOPS, 400),
    val shutterStops: List<Long> = ExposureStops.DEFAULT_SHUTTER_STOPS,
    val shutterIndex: Int = ExposureStops.index180Degree(
        ExposureStops.DEFAULT_SHUTTER_STOPS, ExposureStops.DEFAULT_FPS,
    ),

    // ---- Pro Hardware Controls ----
    val focusMode: FocusMode = FocusMode.Auto,
    val focusDistanceRatio: Float = 0f, // 0 = infinity, 1 = macro
    val focusDiopters: Float = 0f, // 0 = infinity, max = closest macro in diopters (1/m)
    val maxFocusDiopters: Float = 10f,
    val liveFocusDiopters: Float = 0f,
    val focusSupported: Boolean = true,
    val focusTargetPoint: androidx.compose.ui.geometry.Offset? = null,
    val focusLocked: Boolean = false,

    val wbMode: WbMode = WbMode.Auto,
    val wbKelvin: Int = 5600,
    val awbLocked: Boolean = false,
    val awbLockAvailable: Boolean = true,

    val oisSupported: Boolean = false,
    val oisEnabled: Boolean = true,
    val eisSupported: Boolean = false,
    val eisEnabled: Boolean = false,

    val evIndex: Int = 0,
    val evMin: Int = -6,
    val evMax: Int = 6,
    val evStep: Float = 0.333f,

    // ---- Pro Audio Engine ----
    val audioMeter: AudioMeterData = AudioMeterData(),
    val activeAudioSource: AudioSourceType = AudioSourceType.BuiltIn,
    val availableAudioSources: List<AudioSourceType> = listOf(AudioSourceType.BuiltIn),
    val lowCutFilterEnabled: Boolean = false,

    // ---- Pro Photo Mode ----
    val rawSupported: Boolean = false,
    val rawCaptureEnabled: Boolean = true,
    val hdrBurstPhotoEnabled: Boolean = true,

    // ---- Video Settings ----
    val targetResolutionWidth: Int? = null, // null = Max / 1080p / 4K
    val targetFps: Int = 30,
    val targetCodec: Int = MediaRecorder.VideoEncoder.HEVC,
    val customBitrateMbps: Int = 100, // 10 Mbps to 100 Mbps
    val isAv1Supported: Boolean = false,

    // ---- Hardware Profiling & Adaptive Boundaries ----
    val deviceProfile: DeviceProfile? = null,
    val safeBoundsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,

    // ---- Processing & Color Profiles ----
    val colorProfile: ColorProfile = ColorProfile.NativeMinimal,
    val bypassEnabled: Boolean = true,
    val hdrSupported: Boolean = false,
    val hdrEnabled: Boolean = false,

    // ---- Recording ----
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val videoSpec: VideoSpec = VideoSpec(),

    // ---- Viewfinder & Gallery ----
    val previewAspectRatio: Float = 4f / 3f,
    val showGrid: Boolean = true,
    val lastCapturedMediaUri: Uri? = null,

    val message: UserMessage? = null,
) {
    val iso: Int get() = isoStops[isoIndex.coerceIn(0, isoStops.lastIndex)]
    val shutterNs: Long get() = shutterStops[shutterIndex.coerceIn(0, shutterStops.lastIndex)]

    /** Active exposure value: live metered when in Auto, dialed when in Pro Manual */
    val displayIso: Int get() = if (isManualExposure) iso else liveMeteredIso
    val displayShutterNs: Long get() = if (isManualExposure) shutterNs else liveMeteredShutterNs

    val evDisplay: String
        get() {
            val ev = evIndex * evStep
            return if (ev > 0.05f) "+%.1f".format(ev) else if (ev < -0.05f) "%.1f".format(ev) else "0.0"
        }

    val focusDisplay: String
        get() {
            return if (focusMode == FocusMode.Auto) {
                if (liveFocusDiopters > 0.05f) "AF %.1f dpt".format(liveFocusDiopters) else "AF"
            } else {
                "%.1f dpt".format(focusDiopters)
            }
        }

    val stabDisplay: String
        get() {
            val hasOis = oisEnabled && oisSupported
            val hasEis = eisEnabled && eisSupported
            return when {
                hasOis && hasEis -> "OIS+EIS"
                hasOis -> "OIS"
                hasEis -> "EIS"
                else -> "OFF"
            }
        }

    val isFrontCamera: Boolean get() = lensFacing == CameraMetadata.LENS_FACING_FRONT

    val exposureControlsEnabled: Boolean
        get() = manualSensorSupported && status == CameraStatus.Ready

    val hdrToggleEnabled: Boolean
        get() = !isRecording && status == CameraStatus.Ready

    val recordEnabled: Boolean get() = status == CameraStatus.Ready
}
