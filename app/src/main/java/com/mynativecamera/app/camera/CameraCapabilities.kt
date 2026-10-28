package com.mynativecamera.app.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi

enum class LensOption(val label: String, val factor: Float) {
    UltraWide(".5x", 0.5f),
    Wide("1x", 1.0f),
    TwoX("2x", 2.0f),
    Telephoto("3x", 3.0f),
}

/**
 * Immutable snapshot of everything the engine needs from [CameraCharacteristics].
 * Built once per camera (characteristics never change at runtime) on a background thread.
 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val sensorOrientation: Int,
    val hardwareLevel: Int,
    /** Direct ISO / exposure control. */
    val manualSensor: Boolean,
    /** Manual color correction transform matrix & WB gains. */
    val manualPostProcessing: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    /** 0 = fixed-focus lens. */
    val minFocusDistance: Float,
    val afModes: Set<Int>,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val awbModes: Set<Int>,
    val noiseReductionModes: Set<Int>,
    val edgeModes: Set<Int>,
    val tonemapModes: Set<Int>,
    val aeFpsRanges: List<Range<Int>>,
    /** Optical Image Stabilization support. */
    val opticalStabilizationSupported: Boolean,
    /** Electronic / Video Image Stabilization support. */
    val videoStabilizationSupported: Boolean,
    /** Android 13+ PREVIEW_STABILIZATION support. */
    val previewStabilizationSupported: Boolean = false,
    /** Available Face Detection modes & maximum face count. */
    val faceDetectModes: Set<Int> = emptySet(),
    val maxFaceCount: Int = 0,
    val bestFaceDetectMode: Int = 0,
    /** Auto exposure compensation range & step. */
    val aeCompMin: Int,
    val aeCompMax: Int,
    val aeCompStep: Float,
    /** Maximum digital / optical zoom ratio. */
    val maxZoom: Float,
    val zoomRatioRange: Range<Float>,
    /** RAW_SENSOR capture support (DNG) */
    val rawSupported: Boolean,
    val rawSizes: List<Size>,
    /** Physical backing camera IDs for multi-camera logical sensors (API 28+) */
    val physicalCameraIds: Set<String>,
    /** Sensor can stream HLG10 (REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT + HLG10 profile). */
    val hlg10Supported: Boolean,
    val maxRegionsAe: Int = 0,
    val maxRegionsAf: Int = 0,
    val activeArraySize: android.graphics.Rect? = null,
    val recorderSizes: List<Size>,
    val previewSizes: List<Size>,
    val photoSizes: List<Size>,
    val yuvSizes: List<Size> = emptyList(),
    val recorderMinFrameDurationsNs: Map<Size, Long>,
) {
    val manualFocusSupported: Boolean get() = minFocusDistance > 0f
    val continuousVideoAf: Boolean
        get() = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes
    val isFrontFacing: Boolean
        get() = lensFacing == CameraMetadata.LENS_FACING_FRONT
    val largestRawSize: Size? get() = rawSizes.firstOrNull()
    val nativeSensorAspectRatio: Float
        get() {
            val rect = activeArraySize
            return if (rect != null && rect.height() > 0) {
                rect.width().toFloat() / rect.height().toFloat()
            } else {
                4f / 3f
            }
        }

    companion object {

        fun selectCameraId(
            manager: CameraManager,
            desiredFacing: Int = CameraMetadata.LENS_FACING_BACK,
        ): String? {
            val ids = manager.cameraIdList
            return ids.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == desiredFacing
            } ?: ids.firstOrNull()
        }

        fun hasFacing(manager: CameraManager, facing: Int): Boolean {
            return manager.cameraIdList.any {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            }
        }

        fun from(id: String, ch: CameraCharacteristics): CameraCapabilities {
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("Camera $id exposes no stream configuration map")

            val recorderSizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val photoSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                .sortedByDescending { it.width.toLong() * it.height }
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                .sortedByDescending { it.width.toLong() * it.height }

            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val hasExplicitManual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            val manualSensorSupported = hasExplicitManual || (isoRange != null && expRange != null) || true

            val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty()
            val eisModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
            val previewStabSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in eisModes
            val videoStabSupported = (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes) || previewStabSupported

            val faceDetectModes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.toSet().orEmpty()
            val maxFaceCount = ch.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
            val bestFaceDetectMode = when {
                maxFaceCount > 0 && CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in faceDetectModes ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                maxFaceCount > 0 && CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in faceDetectModes ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }

            val aeCompRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
            val aeCompStepRational = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val aeCompStep = if (aeCompStepRational != null && aeCompStepRational.denominator != 0) {
                aeCompStepRational.numerator.toFloat() / aeCompStepRational.denominator.toFloat()
            } else {
                1f / 3f
            }

            val maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1.0f, maxZoom)
            } else {
                Range(1.0f, maxZoom)
            }

            val rawSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
            val rawSizes = if (rawSupported) {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
                    .sortedByDescending { it.width.toLong() * it.height }
            } else emptyList()

            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ch.physicalCameraIds
            } else emptySet()

            return CameraCapabilities(
                cameraId = id,
                lensFacing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                manualSensor = manualSensorSupported,
                manualPostProcessing =
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
                isoMin = isoRange?.lower ?: 50,
                isoMax = isoRange?.upper ?: 3200,
                exposureMinNs = expRange?.lower ?: 100_000L,
                exposureMaxNs = expRange?.upper ?: ExposureStops.NANOS_PER_SECOND,
                minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty(),
                aeLockAvailable = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
                awbLockAvailable = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
                awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
                noiseReductionModes = ch.get(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                )?.toSet().orEmpty(),
                edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty(),
                tonemapModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty(),
                aeFpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
                opticalStabilizationSupported = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes,
                videoStabilizationSupported = videoStabSupported,
                previewStabilizationSupported = previewStabSupported,
                faceDetectModes = faceDetectModes,
                maxFaceCount = maxFaceCount,
                bestFaceDetectMode = bestFaceDetectMode,
                aeCompMin = aeCompRange.lower,
                aeCompMax = aeCompRange.upper,
                aeCompStep = aeCompStep,
                maxZoom = maxZoom,
                zoomRatioRange = zoomRatioRange,
                rawSupported = rawSupported,
                rawSizes = rawSizes,
                physicalCameraIds = physicalCameraIds,
                hlg10Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasHlg10(ch, caps),
                maxRegionsAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                maxRegionsAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                activeArraySize = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                recorderSizes = recorderSizes,
                previewSizes = previewSizes,
                photoSizes = photoSizes,
                yuvSizes = yuvSizes,
                recorderMinFrameDurationsNs = recorderSizes.associateWith {
                    map.getOutputMinFrameDuration(MediaRecorder::class.java, it)
                },
            )
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun hasHlg10(ch: CameraCharacteristics, caps: Set<Int>): Boolean {
            val hasTenBitCap = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in caps
            val profiles = try {
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            } catch (_: Exception) {
                null
            }
            val hasHlgInProfiles = profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            // Aggressive probe: support if declared in capabilities OR found in dynamic range profiles map
            return hasTenBitCap || hasHlgInProfiles
        }
    }
}
