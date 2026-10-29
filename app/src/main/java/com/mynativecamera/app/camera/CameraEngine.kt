package com.mynativecamera.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.mynativecamera.app.audio.AudioMeterData
import com.mynativecamera.app.audio.AudioSourceType
import com.mynativecamera.app.audio.ProAudioEngine
import com.mynativecamera.app.camera.LensOption
import com.mynativecamera.app.recording.MediaStorePhotoStore
import com.mynativecamera.app.recording.SavedClip
import com.mynativecamera.app.recording.SavedPhoto
import com.mynativecamera.app.recording.VideoRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class FocusMode { Auto, Manual }
enum class WbMode { Auto, Manual }

/**
 * Production-ready Camera2 Engine for pro cinema recording and still photo capture.
 */
class CameraEngine(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCapabilitiesDiscovered(
            capabilities: CameraCapabilities,
            videoConfig: VideoConfig,
            deviceProfile: DeviceProfile,
        )
        fun onSessionConfigured(previewSize: Size)
        fun onSessionError(message: String)
        fun onInitialExposureMetered(iso: Int, shutterNs: Long)
        fun onLiveExposureUpdated(iso: Int, shutterNs: Long)
        fun onLiveFocusDistanceUpdated(diopters: Float)
        fun onFocusLocked()
        fun onRecordingStarted()
        fun onRecordingStopped(savedClip: SavedClip?)
        fun onRecordingFailed(message: String)
        fun onSegmentRolled(clip: SavedClip)
        fun onPhotoCaptureStarted()
        fun onPhotoSaved(savedPhoto: SavedPhoto)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Default)

    private fun ensureScopeActive() {
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.Default)
        }
    }

    // Audio Engine for VU metering, routing and clipping monitoring
    val audioEngine = ProAudioEngine(context)

    // Hardware orientation listener for gravity-aware video and photo capture
    private val orientationListener = DeviceOrientationListener(context)

    // Dedicated Camera2 looper thread
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Photo store
    private val photoStore = MediaStorePhotoStore(context)
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var yuvImageReader: ImageReader? = null
    private var pendingDngImage: android.media.Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var lastCaptureResult: TotalCaptureResult? = null

    // Multi-Frame HDR Computational Photography
    private val isHdrBurstCapturing = AtomicBoolean(false)
    private var hdrBurstPhotoEnabled: Boolean = true
    private val burstFrames = mutableListOf<YuvFrameData>()
    private val burstTimestampMap = ConcurrentHashMap<Long, Float>()
    private var pendingHdrRotation: Int = 0

    // Video recorder wrapper
    private val videoRecorder = VideoRecorder(
        context = context,
        listener = object : VideoRecorder.Listener {
            override fun onSegmentSaved(clip: SavedClip) {
                listener.onSegmentRolled(clip)
            }

            override fun onRecorderFailure(message: String) {
                scope.launch {
                    stopRecordingInternal()
                    listener.onRecordingFailed(message)
                }
            }
        },
    )

    // Hardware and stream state
    private var currentLensFacing: Int = CameraMetadata.LENS_FACING_BACK
    private var characteristics: CameraCharacteristics? = null
    private var capabilities: CameraCapabilities? = null
    private var videoConfig: VideoConfig? = null
    private var deviceProfile: DeviceProfile? = null
    private var previewSize: Size? = null

    val currentCapabilities: CameraCapabilities? get() = capabilities
    val currentVideoConfig: VideoConfig? get() = videoConfig

    var currentTargetAspectRatio: Float = 4f / 3f
        private set

    fun setTargetAspectRatio(aspectRatio: Float) {
        if (kotlin.math.abs(currentTargetAspectRatio - aspectRatio) > 0.02f) {
            currentTargetAspectRatio = aspectRatio
            rebuildSession()
        }
    }

    fun getSurfaceRotation(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm?.defaultDisplay?.rotation
                    ?: (context as? android.app.Activity)?.display?.rotation
                    ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    fun computeJpegOrientation(displayRotationDegreesOverride: Int? = null): Int {
        val caps = capabilities ?: return 0
        val surfaceRotation = getSurfaceRotation()
        val deviceRotationDegrees = displayRotationDegreesOverride ?: (surfaceRotation * 90)
        val sensorOrientation = caps.sensorOrientation
        val isFront = caps.isFrontFacing
        return if (isFront) {
            (sensorOrientation + deviceRotationDegrees) % 360
        } else {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Active surfaces
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    // User-configured parameters
    private var currentIso: Int = 400
    private var currentShutterNs: Long = ExposureStops.NANOS_PER_SECOND / 60
    private var bypassEnabled: Boolean = true
    private var hdrEnabled: Boolean = false
    private var isRecording: Boolean = false
    private var isManualExposure: Boolean = false
    private var lastExposureReportMs: Long = 0L
    private var isTapFocusActive: Boolean = false
    private var focusAfRegions: Array<MeteringRectangle>? = null
    private var focusAeRegions: Array<MeteringRectangle>? = null

    // Pro additions: Focus, WB, Stabilization, EV, AWB Lock, Mirror Selfie, Multi-Lens
    private var focusMode: FocusMode = FocusMode.Auto
    private var manualFocusDiopters: Float = 0f
    private var wbMode: WbMode = WbMode.Auto
    private var wbKelvin: Int = 5600
    private var awbLocked: Boolean = false
    private var oisEnabled: Boolean = true
    private var eisEnabled: Boolean = true
    private var evCompensation: Int = 0
    private var currentZoomRatio: Float = 1.0f
    private var activeLens: LensOption = LensOption.Wide
    private var safeBoundsEnabled: Boolean = true

    // Equilibrium Metering state (Dynamic Auto-Exposure / Auto-Focus)
    private var currentEquilibriumRegion: MeteringRectangle? = null
    private var lastEquilibriumUpdateTimeMs: Long = 0L
    private var isFaceActiveInEquilibrium: Boolean = false

    /**
     * Equilibrium Metering: Computes the metering rectangle based on detected face
     * (sensor coordinates, weight 1000) or fallback to center 30% of active array (weight 800).
     */
    private fun computeEquilibriumMeteringRectangle(
        activeArray: Rect,
        primaryFace: Face?,
    ): MeteringRectangle {
        if (primaryFace != null) {
            val b = primaryFace.bounds
            val left = b.left.coerceIn(activeArray.left, activeArray.right)
            val top = b.top.coerceIn(activeArray.top, activeArray.bottom)
            val right = b.right.coerceIn(activeArray.left, activeArray.right)
            val bottom = b.bottom.coerceIn(activeArray.top, activeArray.bottom)
            val faceWidth = (right - left).coerceAtLeast(1)
            val faceHeight = (bottom - top).coerceAtLeast(1)
            val faceRect = Rect(left, top, left + faceWidth, top + faceHeight)
            return MeteringRectangle(faceRect, 1000)
        }

        // Center-weighted: Middle 30% of sensor active array, weight 800
        val w = activeArray.width()
        val h = activeArray.height()
        val cx = activeArray.centerX()
        val cy = activeArray.centerY()
        val boxW = (w * 0.30f).roundToInt().coerceIn(1, w)
        val boxH = (h * 0.30f).roundToInt().coerceIn(1, h)
        val left = (cx - boxW / 2).coerceIn(activeArray.left, (activeArray.right - boxW).coerceAtLeast(activeArray.left))
        val top = (cy - boxH / 2).coerceIn(activeArray.top, (activeArray.bottom - boxH).coerceAtLeast(activeArray.top))
        val centerRect = Rect(left, top, left + boxW, top + boxH)
        return MeteringRectangle(centerRect, 800)
    }


    // Video settings
    private var targetVideoWidth: Int? = null
    private var targetVideoFps: Int = 30
    private var targetVideoCodec: Int = MediaRecorder.VideoEncoder.HEVC
    private var customBitrateMbps: Int = 100
    private var colorProfile: com.mynativecamera.app.ui.ColorProfile = com.mynativecamera.app.ui.ColorProfile.NativeMinimal

    // Initial 3A metering convergence flag and frame count fallback
    private val hasMeteredInitialExposure = AtomicBoolean(false)
    private var convergenceFrameCount = 0
    private var lockedFocusDistance: Float? = null

    // Flat / Logarithmic Tonemap Curve: lifts crushed shadows and softly rolls off highlights to prevent clipping
    private val cinemaLogTonemapCurve: TonemapCurve by lazy {
        val count = 32
        val points = FloatArray(count * 2)
        val curvature = 9.0 // Logarithmic dynamic range expansion
        for (i in 0 until count) {
            val inVal = i.toFloat() / (count - 1).toFloat()
            // Logarithmic mapping: y = ln(1 + a * x) / ln(1 + a)
            val logVal = (kotlin.math.ln(1.0 + curvature * inVal) / kotlin.math.ln(1.0 + curvature)).toFloat()
            // Deep shadows lifted from 0.02 (preventing crushing), midtones blended, highlights soft-shoulder (0.985 max)
            val outVal = (0.025f + 0.96f * (0.35f * inVal + 0.65f * logVal)).coerceIn(0.0f, 1.0f)
            points[i * 2] = inVal
            points[i * 2 + 1] = outVal
        }
        TonemapCurve(points, points, points)
    }

    private val flatTonemapCurve get() = cinemaLogTonemapCurve
    private val flatLinearTonemapCurve get() = cinemaLogTonemapCurve

    // ---- Lifecycle --------------------------------------------------------------------------

    fun start() {
        ensureScopeActive()
        if (cameraThread == null) {
            val thread = HandlerThread("Camera2EngineThread", Thread.MAX_PRIORITY).apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        audioEngine.startMonitoring()
    }

    /**
     * Called on Lifecycle.Event.ON_PAUSE or ON_STOP:
     * Explicitly closes CameraCaptureSession, CameraDevice, releases readers,
     * and gracefully terminates the background HandlerThread.
     */
    fun onPause() {
        orientationListener.disable()
        audioEngine.stopMonitoring()

        if (isRecording) {
            try {
                runBlocking {
                    stopRecordingInternal()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recorder on pause", e)
            }
        }

        closeSession()
        closeCamera()

        imageReader?.close()
        imageReader = null
        yuvImageReader?.close()
        yuvImageReader = null
        rawImageReader?.close()
        rawImageReader = null
        pendingDngImage?.close()
        pendingDngImage = null
        burstFrames.clear()
        burstTimestampMap.clear()
        isHdrBurstCapturing.set(false)

        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null

        scopeJob.cancelChildren()
    }

    /**
     * Called on Lifecycle.Event.ON_RESUME:
     * Re-launches the background HandlerThread and re-opens the CameraDevice
     * if the preview surface is valid, preventing viewfinder freezes.
     */
    fun onResume() {
        start()
        reopenCameraIfSurfaceAvailable()
    }

    private fun reopenCameraIfSurfaceAvailable() {
        val texture = previewSurfaceTexture ?: return
        if (texture.isReleased) {
            previewSurfaceTexture = null
            previewSurface?.release()
            previewSurface = null
            return
        }
        val handler = cameraHandler ?: return
        handler.post {
            try {
                val cameraId = CameraCapabilities.selectCameraId(cameraManager, currentLensFacing) ?: return@post
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                characteristics = chars
                val caps = CameraCapabilities.from(cameraId, chars)
                capabilities = caps
                val profile = DeviceProfile.detect(context, chars)
                deviceProfile = profile

                val initialHdr = hdrEnabled && caps.hlg10Supported
                val vConfig = VideoConfigSelector.choose(
                    c = caps,
                    hdr = initialHdr,
                    targetWidth = targetVideoWidth,
                    targetFps = targetVideoFps,
                    preferredCodec = targetVideoCodec,
                    profile = profile,
                    safeBoundsEnabled = safeBoundsEnabled,
                    customBitrateBps = customBitrateMbps * 1_000_000,
                    targetAspectRatio = currentTargetAspectRatio,
                )
                videoConfig = vConfig

                val pSize = VideoConfigSelector.choosePreviewSize(caps, vConfig, currentTargetAspectRatio)
                previewSize = pSize
                texture.setDefaultBufferSize(pSize.width, pSize.height)
                previewSurface?.release()
                previewSurface = Surface(texture)

                listener.onCapabilitiesDiscovered(caps, vConfig, profile)
                openCamera(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reopen camera pipeline on resume", e)
                listener.onSessionError(e.message ?: "Failed to reopen camera")
            }
        }
    }

    fun stop() {
        onPause()
        videoRecorder.release()
        scope.cancel()
    }

    fun onPreviewSurfaceAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        start()
        previewSurfaceTexture = surfaceTexture
        val handler = cameraHandler ?: return

        handler.post {
            try {
                val cameraId = CameraCapabilities.selectCameraId(cameraManager, currentLensFacing)
                    ?: throw IllegalStateException("No suitable camera found for facing $currentLensFacing")

                val chars = cameraManager.getCameraCharacteristics(cameraId)
                characteristics = chars
                val caps = CameraCapabilities.from(cameraId, chars)
                capabilities = caps

                val profile = DeviceProfile.detect(context, chars)
                deviceProfile = profile

                val initialHdr = hdrEnabled && caps.hlg10Supported
                val vConfig = VideoConfigSelector.choose(
                    c = caps,
                    hdr = initialHdr,
                    targetWidth = targetVideoWidth,
                    targetFps = targetVideoFps,
                    preferredCodec = targetVideoCodec,
                    profile = profile,
                    safeBoundsEnabled = safeBoundsEnabled,
                    customBitrateBps = customBitrateMbps * 1_000_000,
                    targetAspectRatio = currentTargetAspectRatio,
                )
                videoConfig = vConfig

                val pSize = VideoConfigSelector.choosePreviewSize(caps, vConfig, currentTargetAspectRatio)
                previewSize = pSize

                surfaceTexture.setDefaultBufferSize(pSize.width, pSize.height)
                previewSurface?.release()
                previewSurface = Surface(surfaceTexture)

                listener.onCapabilitiesDiscovered(caps, vConfig, profile)
                openCamera(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera pipeline", e)
                listener.onSessionError(e.message ?: "Failed to open camera")
            }
        }
    }

    fun onPreviewSurfaceDestroyed() {
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                closeSession()
                previewSurface?.release()
                previewSurface = null
                previewSurfaceTexture = null
            }
        }
    }

    // ---- Camera Device Management -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String) {
        val handler = cameraHandler ?: return
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.i(TAG, "CameraDevice $cameraId opened successfully")
                        cameraDevice = camera
                        createCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "CameraDevice $cameraId disconnected")
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "CameraDevice $cameraId error code: $error")
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                        listener.onSessionError("Camera hardware error ($error)")
                    }
                },
                handler,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing", e)
            listener.onSessionError("Camera permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera device", e)
            listener.onSessionError("Failed to open camera: ${e.message}")
        }
    }

    private fun closeCamera() {
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        }
        cameraDevice = null
        currentEquilibriumRegion = null
        lastEquilibriumUpdateTimeMs = 0L
        isFaceActiveInEquilibrium = false
    }

    // ---- Capture Session Lifecycle ----------------------------------------------------------

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val pSurface = previewSurface ?: return
        val vConfig = videoConfig ?: return
        val caps = capabilities ?: return
        val handler = cameraHandler ?: return

        scope.launch {
            try {
                var effectiveConfig = vConfig
                val recSurface = recorderSurface ?: run {
                    val s = try {
                        videoRecorder.createPersistentSurface(vConfig)
                    } catch (e: Exception) {
                        if (vConfig.hdr) {
                            Log.w(TAG, "10-bit HDR persistent surface creation failed, falling back to 8-bit SDR", e)
                            effectiveConfig = VideoConfigSelector.choose(
                                c = caps,
                                hdr = false,
                                targetWidth = targetVideoWidth,
                                targetFps = targetVideoFps,
                                preferredCodec = targetVideoCodec,
                                profile = deviceProfile,
                                safeBoundsEnabled = safeBoundsEnabled,
                                customBitrateBps = customBitrateMbps * 1_000_000,
                            )
                            videoConfig = effectiveConfig
                            videoRecorder.createPersistentSurface(effectiveConfig)
                        } else {
                            throw e
                        }
                    }
                    recorderSurface = s
                    s
                }

                // Setup ImageReader for full-res still photos (JPEG) strictly matching Viewfinder aspect ratio
                val targetAspect = currentTargetAspectRatio
                val photoCandidates = caps.photoSizes.filter {
                    kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspect) < 0.05f
                }
                val photoSize = photoCandidates.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: caps.photoSizes.minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspect) }
                    ?: caps.photoSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(4000, 3000)
                val reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
                imageReader?.close()
                imageReader = reader

                reader.setOnImageAvailableListener({ ir ->
                    val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        scope.launch {
                            try {
                                val saved = photoStore.savePhoto(bytes)
                                listener.onPhotoSaved(saved)
                            } catch (e: Exception) {
                                listener.onSessionError("Photo save error: ${e.message}")
                            }
                        }
                    } finally {
                        image.close()
                    }
                }, handler)

                // Setup ImageReader for Computational HDR Burst Photos strictly matching Viewfinder aspect ratio
                val yuvCandidates = caps.yuvSizes.filter {
                    kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - targetAspect) < 0.05f
                }
                val yuvPool = if (yuvCandidates.isNotEmpty()) yuvCandidates else caps.yuvSizes
                val yuvSize = if (safeBoundsEnabled && (deviceProfile?.tier != DeviceTier.ProFlagship)) {
                    yuvPool.firstOrNull { it.width <= 2560 && it.height <= 1920 }
                        ?: yuvPool.firstOrNull { it.width <= 2048 }
                        ?: yuvPool.firstOrNull { it.width <= 1920 }
                        ?: yuvPool.firstOrNull()
                        ?: Size(1920, 1440)
                } else {
                    yuvPool.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: yuvPool.firstOrNull()
                        ?: Size(1920, 1440)
                }
                val yReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 5)
                yuvImageReader?.close()
                yuvImageReader = yReader

                yReader.setOnImageAvailableListener({ ir ->
                    val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (!isHdrBurstCapturing.get()) {
                            return@setOnImageAvailableListener
                        }
                        val ts = image.timestamp
                        val bias = burstTimestampMap.remove(ts) ?: when (burstFrames.size) {
                            0 -> -2.0f
                            1 -> 0.0f
                            else -> +2.0f
                        }
                        val frameData = YuvFrameData.fromImage(image, bias)
                        burstFrames.add(frameData)

                        if (burstFrames.size >= 3) {
                            val framesToBlend = ArrayList(burstFrames)
                            burstFrames.clear()
                            burstTimestampMap.clear()
                            val rot = pendingHdrRotation
                            scope.launch(Dispatchers.Default) {
                                try {
                                    val blended = ComputationalImageProcessor.blendHdrBurst(
                                        frames = framesToBlend,
                                        rotationDegrees = rot,
                                        mirrorHorizontal = false,
                                    )
                                    val saved = photoStore.saveBitmap(blended, prefix = "MNC_HDR_")
                                    listener.onPhotoSaved(saved)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Computational HDR fusion error", e)
                                    listener.onSessionError("HDR blend failed: ${e.message}")
                                } finally {
                                    isHdrBurstCapturing.set(false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring YUV frame", e)
                    } finally {
                        image.close()
                    }
                }, handler)

                // Setup RAW_SENSOR ImageReader (Adobe DNG) when hardware raw is supported
                val rawSize = caps.largestRawSize
                val rawReader = if (caps.rawSupported && rawSize != null) {
                    ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).also { rr ->
                        rawImageReader?.close()
                        rawImageReader = rr
                        rr.setOnImageAvailableListener({ ir ->
                            val rawImage = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                            val res = pendingCaptureResult
                            val ch = characteristics
                            if (res != null && ch != null) {
                                pendingCaptureResult = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val savedDng = photoStore.saveRawDng(rawImage, ch, res)
                                        listener.onPhotoSaved(savedDng)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to save DNG RAW", e)
                                    } finally {
                                        rawImage.close()
                                    }
                                }
                            } else {
                                pendingDngImage?.close()
                                pendingDngImage = rawImage
                            }
                        }, handler)
                    }
                } else {
                    rawImageReader?.close()
                    rawImageReader = null
                    null
                }

                val rSurface = reader.surface
                val rawSurface = rawReader?.surface
                val yuvSurface = yReader.surface

                handler.post {
                    try {
                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                Log.i(TAG, "CameraCaptureSession successfully configured")
                                captureSession = session
                                listener.onSessionConfigured(previewSize ?: Size(1920, 1080))
                                startRepeatingStream()
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "CameraCaptureSession configuration failed")
                                listener.onSessionError("Failed to configure capture session")
                            }

                            override fun onClosed(session: CameraCaptureSession) {
                                if (captureSession == session) {
                                    captureSession = null
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && effectiveConfig.hdr && caps.hlg10Supported) {
                            try {
                                configure10BitHdrSession(device, pSurface, recSurface, rSurface, rawSurface, yuvSurface, sessionCallback, handler)
                            } catch (e: Exception) {
                                Log.w(TAG, "10-bit HLG10 session creation failed, gracefully falling back to 8-bit SDR", e)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    configureStandardSessionApi28(device, pSurface, recSurface, rSurface, rawSurface, yuvSurface, sessionCallback, handler)
                                } else {
                                    @Suppress("DEPRECATION")
                                    val surfaces = mutableListOf(pSurface, recSurface, rSurface, yuvSurface)
                                    if (rawSurface != null) surfaces.add(rawSurface)
                                    device.createCaptureSession(surfaces, sessionCallback, handler)
                                }
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            configureStandardSessionApi28(device, pSurface, recSurface, rSurface, rawSurface, yuvSurface, sessionCallback, handler)
                        } else {
                            @Suppress("DEPRECATION")
                            val surfaces = mutableListOf(pSurface, recSurface, rSurface, yuvSurface)
                            if (rawSurface != null) surfaces.add(rawSurface)
                            device.createCaptureSession(surfaces, sessionCallback, handler)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception creating capture session", e)
                        listener.onSessionError("Failed to build capture session: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create persistent recorder surface", e)
                listener.onSessionError("Failed to prepare recorder encoder: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun configure10BitHdrSession(
        device: CameraDevice,
        preview: Surface,
        recorder: Surface,
        photoSurface: Surface,
        rawSurface: Surface?,
        yuvSurface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler,
    ) {
        val previewConfig = OutputConfiguration(preview).apply {
            dynamicRangeProfile = DynamicRangeProfiles.HLG10
        }
        val recorderConfig = OutputConfiguration(recorder).apply {
            dynamicRangeProfile = DynamicRangeProfiles.HLG10
        }
        val photoConfig = OutputConfiguration(photoSurface)
        val yuvConfig = OutputConfiguration(yuvSurface)
        val configs = mutableListOf(previewConfig, recorderConfig, photoConfig, yuvConfig)
        if (rawSurface != null) {
            configs.add(OutputConfiguration(rawSurface))
        }

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            configs,
            Executor { command -> handler.post(command) },
            callback,
        )
        device.createCaptureSession(sessionConfiguration)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun configureStandardSessionApi28(
        device: CameraDevice,
        preview: Surface,
        recorder: Surface,
        photoSurface: Surface,
        rawSurface: Surface?,
        yuvSurface: Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler,
    ) {
        val previewConfig = OutputConfiguration(preview)
        val recorderConfig = OutputConfiguration(recorder)
        val photoConfig = OutputConfiguration(photoSurface)
        val yuvConfig = OutputConfiguration(yuvSurface)
        val configs = mutableListOf(previewConfig, recorderConfig, photoConfig, yuvConfig)
        if (rawSurface != null) {
            configs.add(OutputConfiguration(rawSurface))
        }

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            configs,
            Executor { command -> handler.post(command) },
            callback,
        )
        device.createCaptureSession(sessionConfiguration)
    }

    private fun closeSession() {
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        }
        captureSession = null
    }

    // ---- Repeating Capture Request & Parameter Building -------------------------------------

    // Reusable capture callback that continuously reports live metered exposure and AF lock
    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastCaptureResult = result
            val meteredIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val meteredExpNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)

            if (!hasMeteredInitialExposure.get()) {
                processInitialConvergence(result)
            } else if (!isManualExposure && meteredIso != null && meteredExpNs != null) {
                val now = System.currentTimeMillis()
                if (now - lastExposureReportMs > 240) {
                    lastExposureReportMs = now
                    listener.onLiveExposureUpdated(meteredIso, meteredExpNs)
                }
            }

            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (focusDist != null) {
                listener.onLiveFocusDistanceUpdated(focusDist)
            }

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (isTapFocusActive && afState != null) {
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    isTapFocusActive = false
                    listener.onFocusLocked()
                }
            }

            // Dynamic Equilibrium Metering: continuous monitoring loop
            val caps = capabilities
            if (focusAeRegions == null && caps != null) {
                val activeArray = caps.activeArraySize ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val faces = result.get(CaptureResult.STATISTICS_FACES)
                    val primaryFace = faces?.filter { it.score >= 15 && it.bounds.width() > 0 && it.bounds.height() > 0 }
                        ?.maxByOrNull { it.bounds.width() * it.bounds.height() }

                    val hasFace = primaryFace != null
                    val candidate = computeEquilibriumMeteringRectangle(activeArray, primaryFace)
                    val nowMs = android.os.SystemClock.elapsedRealtime()

                    val shouldUpdate = when {
                        currentEquilibriumRegion == null -> true
                        isFaceActiveInEquilibrium != hasFace -> (nowMs - lastEquilibriumUpdateTimeMs > 200L)
                        hasFace -> {
                            // Face tracking hysteresis: update only if moved significantly (> 4% of sensor) and throttled
                            val prev = currentEquilibriumRegion!!.rect
                            val curr = candidate.rect
                            val dx = kotlin.math.abs(prev.centerX() - curr.centerX())
                            val dy = kotlin.math.abs(prev.centerY() - curr.centerY())
                            val thresholdX = (activeArray.width() * 0.04f).toInt()
                            val thresholdY = (activeArray.height() * 0.04f).toInt()
                            (dx > thresholdX || dy > thresholdY) && (nowMs - lastEquilibriumUpdateTimeMs > 250L)
                        }
                        else -> false
                    }

                    if (shouldUpdate) {
                        lastEquilibriumUpdateTimeMs = nowMs
                        currentEquilibriumRegion = candidate
                        isFaceActiveInEquilibrium = hasFace
                        updateRepeatingRequest()
                    }
                }
            }
        }
    }

    // ---- Repeating Capture Request & Parameter Building -------------------------------------

    private fun startRepeatingStream() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val handler = cameraHandler ?: return
        val pSurface = previewSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(pSurface)

            if (isRecording) {
                recorderSurface?.let { builder.addTarget(it) }
            }

            if (!hasMeteredInitialExposure.get()) {
                setupInitialMetering(builder)
            } else {
                applyProControls(builder)
            }

            session.setRepeatingRequest(
                builder.build(),
                repeatingCaptureCallback,
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting repeating stream", e)
        }
    }

    private fun setupInitialMetering(builder: CaptureRequest.Builder) {
        val caps = capabilities
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // Enable Hardware Face Detection
        val faceDetectMode = if (caps != null && caps.bestFaceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
            caps.bestFaceDetectMode
        } else {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
        }
        try {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
        } catch (_: Exception) {}

        // Equilibrium initial center metering (Middle 30%, weight 800)
        if (caps != null) {
            val activeArray = caps.activeArraySize ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null) {
                val equilibrium = currentEquilibriumRegion ?: computeEquilibriumMeteringRectangle(activeArray, null).also {
                    currentEquilibriumRegion = it
                }
                if (caps.maxRegionsAe > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(equilibrium))
                }
                if (caps.maxRegionsAf > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(equilibrium))
                }
            }
        }
    }

    private fun processInitialConvergence(result: TotalCaptureResult) {
        val caps = capabilities ?: return
        convergenceFrameCount++
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)

        val meteredIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val meteredExpNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)

        val converged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            convergenceFrameCount >= 5

        if (converged) {
            if (hasMeteredInitialExposure.compareAndSet(false, true)) {
                if (meteredIso != null) {
                    currentIso = meteredIso.coerceIn(caps.isoMin, caps.isoMax)
                }
                if (meteredExpNs != null) {
                    currentShutterNs = meteredExpNs.coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
                }
                lockedFocusDistance = focusDist
                listener.onInitialExposureMetered(currentIso, currentShutterNs)
                updateRepeatingRequest()
            }
        }
    }

    /**
     * Applies optical pipeline corrections, dynamic auto/manual exposure,
     * ISO, Shutter, Focus, WB Kelvin, Stabilization (OIS/EIS), and EV.
     */
    private fun applyProControls(builder: CaptureRequest.Builder) {
        val caps = capabilities ?: return
        val vConfig = videoConfig ?: return

        // 1. Hardware Control Mode:
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Continuous Auto Exposure vs Locked Manual Exposure
        if (isManualExposure && caps.manualSensor) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(caps.isoMin, caps.isoMax))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs.coerceIn(caps.exposureMinNs, caps.exposureMaxNs))
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, vConfig.frameDurationNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // EV compensation (active in Auto Exposure mode)
        builder.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            evCompensation.coerceIn(caps.aeCompMin, caps.aeCompMax),
        )

        // Focus: Manual vs Auto
        if (focusMode == FocusMode.Manual && caps.manualFocusSupported) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                manualFocusDiopters.coerceIn(0f, caps.minFocusDistance),
            )
        } else {
            if (isRecording || caps.continuousVideoAf) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }

        // Tap-to-Focus and Metering Regions (with Dynamic Equilibrium Metering fallback)
        val activeArray = caps.activeArraySize ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val equilibrium = currentEquilibriumRegion ?: if (activeArray != null) {
            computeEquilibriumMeteringRectangle(activeArray, null).also { currentEquilibriumRegion = it }
        } else null

        val afRegs = focusAfRegions ?: if (equilibrium != null && caps.maxRegionsAf > 0) arrayOf(equilibrium) else null
        if (afRegs != null && caps.maxRegionsAf > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegs)
        }
        val aeRegs = focusAeRegions ?: if (equilibrium != null && caps.maxRegionsAe > 0) arrayOf(equilibrium) else null
        if (aeRegs != null && caps.maxRegionsAe > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegs)
        }

        // Enable Hardware Face Detection for Pixel-like Face Priority Metering
        val faceDetectMode = if (caps.bestFaceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
            caps.bestFaceDetectMode
        } else {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
        }
        try {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
        } catch (_: Exception) {}

        // White Balance: Manual Kelvin vs Auto / Lock
        if (wbMode == WbMode.Manual) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRgbGains(wbKelvin))
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, kelvinToColorTransform(wbKelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (caps.awbLockAvailable) {
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, awbLocked)
            }
        }

        // Multi-Lens & Optical/Digital Zoom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            val activeArray = caps.activeArraySize ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null && currentZoomRatio > 1.0f) {
                val cropW = (activeArray.width() / currentZoomRatio).toInt()
                val cropH = (activeArray.height() / currentZoomRatio).toInt()
                val cropX = (activeArray.width() - cropW) / 2
                val cropY = (activeArray.height() - cropH) / 2
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH),
                )
            }
        }

        // Stabilization: OIS & EIS
        // To enable EIS successfully, set LENS_OPTICAL_STABILIZATION_MODE to OFF
        // (as they often conflict) and set CONTROL_VIDEO_STABILIZATION_MODE to ON (or PREVIEW_STABILIZATION if available)
        if (eisEnabled && caps.videoStabilizationSupported) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && caps.previewStabilizationSupported) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            } else {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (oisEnabled && caps.opticalStabilizationSupported) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } else {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
        }

        val profile = deviceProfile
        val isLiteOrBalanced = safeBoundsEnabled && (profile == null || profile.tier == DeviceTier.Lite || profile.tier == DeviceTier.Balanced)

        // 2. Optical Pipeline Corrections:
        // Mid-tier/budget ISP optimization: Use FAST modes on repeating video/preview frames
        // to prevent ISP frame dropping and thermal throttling, while keeping HIGH_QUALITY for still photos.
        if (isLiteOrBalanced) {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST)
            builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_FAST)
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_FAST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
            }
        } else {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            }
        }

        // 3. Image Filtering & Tonemapping: Logarithmic tone curve to fix crushed shadows & prevent blown highlights
        if (caps.tonemapModes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            builder.set(CaptureRequest.TONEMAP_CURVE, cinemaLogTonemapCurve)
        } else if (caps.tonemapModes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }

        if (colorProfile == com.mynativecamera.app.ui.ColorProfile.NativeMinimal || bypassEnabled) {
            if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            } else if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
            } else if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }

            if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            } else if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG)
            } else if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_FAST)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            }
        } else {
            // Safe: High Quality processing with OEM tone reproduction, noise reduction, and edge sharpening
            if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            }
        }
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val handler = cameraHandler ?: return
        val pSurface = previewSurface ?: return

        handler.post {
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(pSurface)
                if (isRecording) {
                    recorderSurface?.let { builder.addTarget(it) }
                }
                applyProControls(builder)
                session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update repeating request", e)
            }
        }
    }

    // ---- Tap to Focus & Meter ---------------------------------------------------------------

    fun tapToFocusAndMeter(normalizedX: Float, normalizedY: Float) {
        val caps = capabilities ?: return
        val activeArray = caps.activeArraySize ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val handler = cameraHandler ?: return

        // Map normalized screen coordinates to sensor active array
        val sensorOrientation = caps.sensorOrientation
        val isFront = caps.isFrontFacing

        val (sensorX, sensorY) = when (sensorOrientation) {
            90 -> {
                if (isFront) {
                    Pair(
                        (normalizedY * activeArray.width()).toInt(),
                        (normalizedX * activeArray.height()).toInt(),
                    )
                } else {
                    Pair(
                        (normalizedY * activeArray.width()).toInt(),
                        ((1f - normalizedX) * activeArray.height()).toInt(),
                    )
                }
            }
            270 -> {
                Pair(
                    ((1f - normalizedY) * activeArray.width()).toInt(),
                    (normalizedX * activeArray.height()).toInt(),
                )
            }
            else -> {
                Pair(
                    (normalizedX * activeArray.width()).toInt(),
                    (normalizedY * activeArray.height()).toInt(),
                )
            }
        }

        val boxWidth = (activeArray.width() * 0.16f).toInt()
        val boxHeight = (activeArray.height() * 0.16f).toInt()
        val left = (sensorX - boxWidth / 2).coerceIn(0, (activeArray.width() - boxWidth).coerceAtLeast(0))
        val top = (sensorY - boxHeight / 2).coerceIn(0, (activeArray.height() - boxHeight).coerceAtLeast(0))
        val rect = android.graphics.Rect(left, top, left + boxWidth, top + boxHeight)

        val meteringRect = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        focusAfRegions = arrayOf(meteringRect)
        focusAeRegions = arrayOf(meteringRect)
        isTapFocusActive = true

        handler.post {
            try {
                val session = captureSession ?: return@post
                val device = cameraDevice ?: return@post
                val pSurface = previewSurface ?: return@post

                val triggerBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                triggerBuilder.addTarget(pSurface)
                recorderSurface?.let { if (isRecording) triggerBuilder.addTarget(it) }
                applyProControls(triggerBuilder)
                triggerBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                if (!isManualExposure) {
                    triggerBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                session.capture(triggerBuilder.build(), repeatingCaptureCallback, handler)

                updateRepeatingRequest()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute tap to focus", e)
            }
        }
    }

    // ---- Public Control Modifiers -----------------------------------------------------------

    fun setAutoExposure() {
        isManualExposure = false
        focusAeRegions = null
        focusAfRegions = null
        cameraHandler?.post { updateRepeatingRequest() }
    }

    fun resetFocusAndMetering() {
        focusAeRegions = null
        focusAfRegions = null
        currentEquilibriumRegion = null
        updateRepeatingRequest()
    }

    fun isAutoExposure(): Boolean = !isManualExposure



    fun setSensitivity(iso: Int) {
        currentIso = iso
        isManualExposure = true
        hasMeteredInitialExposure.set(true)
        updateRepeatingRequest()
    }

    fun setExposureTime(shutterNs: Long) {
        currentShutterNs = shutterNs
        isManualExposure = true
        hasMeteredInitialExposure.set(true)
        updateRepeatingRequest()
    }

    fun lockCurrentAutoExposure(iso: Int, shutterNs: Long) {
        currentIso = iso
        currentShutterNs = shutterNs
        isManualExposure = true
        updateRepeatingRequest()
    }

    fun setBypassEnabled(enabled: Boolean) {
        bypassEnabled = enabled
        updateRepeatingRequest()
    }

    fun setHdrEnabled(enabled: Boolean) {
        if (isRecording) {
            Log.w(TAG, "Cannot switch HDR mode while recording is active")
            return
        }
        val caps = capabilities ?: return
        hdrEnabled = enabled

        if (!caps.hlg10Supported) {
            Log.i(TAG, "Applying Cinema Flat / Log tone curve bypass: $enabled")
            updateRepeatingRequest()
            return
        }

        rebuildSession()
    }

    fun setFocus(mode: FocusMode, diopters: Float = 0f) {
        focusMode = mode
        manualFocusDiopters = diopters
        updateRepeatingRequest()
    }

    fun setWhiteBalance(mode: WbMode, kelvin: Int = 5600) {
        wbMode = mode
        wbKelvin = kelvin
        updateRepeatingRequest()
    }

    fun setStabilization(ois: Boolean, eis: Boolean) {
        oisEnabled = ois
        eisEnabled = eis
        updateRepeatingRequest()
    }

    fun setEvCompensation(ev: Int) {
        evCompensation = ev
        updateRepeatingRequest()
    }

    fun setVideoFormat(width: Int?, fps: Int, codec: Int, bitrateMbps: Int = customBitrateMbps) {
        if (isRecording) return
        targetVideoWidth = width
        targetVideoFps = fps
        targetVideoCodec = codec
        customBitrateMbps = bitrateMbps.coerceIn(10, 100)
        rebuildSession()
    }

    fun setCustomBitrate(bitrateMbps: Int) {
        if (isRecording) return
        customBitrateMbps = bitrateMbps.coerceIn(10, 100)
        rebuildSession()
    }

    fun setColorProfile(profile: com.mynativecamera.app.ui.ColorProfile) {
        colorProfile = profile
        updateRepeatingRequest()
    }

    fun getColorProfile(): com.mynativecamera.app.ui.ColorProfile = colorProfile

    fun setFocusDiopters(diopters: Float) {
        focusMode = FocusMode.Manual
        manualFocusDiopters = diopters
        updateRepeatingRequest()
    }

    fun toggleLensFacing() {
        if (isRecording) return
        val desired = if (currentLensFacing == CameraMetadata.LENS_FACING_BACK) {
            CameraMetadata.LENS_FACING_FRONT
        } else {
            CameraMetadata.LENS_FACING_BACK
        }
        currentLensFacing = desired

        val handler = cameraHandler ?: return
        handler.post {
            focusAfRegions = null
            focusAeRegions = null
            currentEquilibriumRegion = null
            lastEquilibriumUpdateTimeMs = 0L
            isFaceActiveInEquilibrium = false
            closeSession()
            closeCamera()
            val surface = previewSurfaceTexture ?: return@post
            val cameraId = CameraCapabilities.selectCameraId(cameraManager, currentLensFacing) ?: return@post
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val caps = CameraCapabilities.from(cameraId, chars)
            capabilities = caps

            val profile = DeviceProfile.detect(context, chars)
            deviceProfile = profile
            characteristics = chars

            val initialHdr = hdrEnabled && caps.hlg10Supported
            val vConfig = VideoConfigSelector.choose(
                c = caps,
                hdr = initialHdr,
                targetWidth = targetVideoWidth,
                targetFps = targetVideoFps,
                preferredCodec = targetVideoCodec,
                profile = profile,
                safeBoundsEnabled = safeBoundsEnabled,
                customBitrateBps = customBitrateMbps * 1_000_000,
                targetAspectRatio = currentTargetAspectRatio,
            )
            videoConfig = vConfig
            val pSize = VideoConfigSelector.choosePreviewSize(caps, vConfig, currentTargetAspectRatio)
            previewSize = pSize
            surface.setDefaultBufferSize(pSize.width, pSize.height)

            recorderSurface?.release()
            recorderSurface = null

            listener.onCapabilitiesDiscovered(caps, vConfig, profile)
            openCamera(cameraId)
        }
    }

    fun setSafeBoundsEnabled(enabled: Boolean) {
        if (safeBoundsEnabled != enabled) {
            safeBoundsEnabled = enabled
            rebuildSession()
        }
    }

    fun isSafeBoundsEnabled(): Boolean = safeBoundsEnabled

    fun reMeter() {
        hasMeteredInitialExposure.set(false)
        convergenceFrameCount = 0
        val handler = cameraHandler ?: return
        handler.post { startRepeatingStream() }
    }

    private fun rebuildSession() {
        val caps = capabilities ?: return
        val chars = characteristics ?: return
        val handler = cameraHandler ?: return
        handler.post {
            closeSession()
            val profile = deviceProfile ?: DeviceProfile.detect(context, chars).also { deviceProfile = it }
            val vConfig = VideoConfigSelector.choose(
                c = caps,
                hdr = hdrEnabled && caps.hlg10Supported,
                targetWidth = targetVideoWidth,
                targetFps = targetVideoFps,
                preferredCodec = targetVideoCodec,
                profile = profile,
                safeBoundsEnabled = safeBoundsEnabled,
                customBitrateBps = customBitrateMbps * 1_000_000,
                targetAspectRatio = currentTargetAspectRatio,
            )
            videoConfig = vConfig
            val pSize = VideoConfigSelector.choosePreviewSize(caps, vConfig, currentTargetAspectRatio)
            previewSize = pSize
            previewSurfaceTexture?.setDefaultBufferSize(pSize.width, pSize.height)

            recorderSurface?.release()
            recorderSurface = null

            listener.onCapabilitiesDiscovered(caps, vConfig, profile)
            createCaptureSession()
        }
    }

    // ---- Still Photo Capture (Computational HDR Burst & RAW DNG / JPEG) --------------------

    fun setHdrBurstPhotoEnabled(enabled: Boolean) {
        hdrBurstPhotoEnabled = enabled
    }

    fun isHdrBurstPhotoEnabled(): Boolean = hdrBurstPhotoEnabled

    fun takePhoto(displayRotationDegrees: Int) {
        if (hdrBurstPhotoEnabled) {
            takeHdrBurstPhoto(displayRotationDegrees)
        } else {
            takeSinglePhoto(displayRotationDegrees)
        }
    }

    private fun takeSinglePhoto(displayRotationDegrees: Int) {
        val reader = imageReader ?: run {
            listener.onSessionError("Photo reader not initialized")
            return
        }
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val caps = capabilities ?: return
        val handler = cameraHandler ?: return

        try {
            val template = if (isRecording) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else CameraDevice.TEMPLATE_STILL_CAPTURE
            val builder = device.createCaptureRequest(template)
            builder.addTarget(reader.surface)
            rawImageReader?.surface?.let { builder.addTarget(it) }
            previewSurface?.let { builder.addTarget(it) }

            applyProControls(builder)

            // High Quality Still Photo ISP Pipeline:
            // Maintain pristine optical sharpness, lens shading correction, distortion elimination and detail
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            }
            if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            }
            if (caps.tonemapModes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
            }

            // Calculate rotation for JPEG output based on Surface rotation & sensor orientation
            val jpegRotation = computeJpegOrientation(displayRotationDegrees)
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation)
            builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                        listener.onPhotoCaptureStarted()
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val rawImg = pendingDngImage
                        val ch = characteristics
                        if (rawImg != null && ch != null) {
                            pendingDngImage = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val savedDng = photoStore.saveRawDng(rawImg, ch, result)
                                    listener.onPhotoSaved(savedDng)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save RAW DNG", e)
                                } finally {
                                    rawImg.close()
                                }
                            }
                        } else {
                            pendingCaptureResult = result
                        }
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        Log.e(TAG, "Still photo capture failed: ${failure.reason}")
                        listener.onSessionError("Still capture failed (${failure.reason})")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            listener.onSessionError("Error capturing photo: ${e.message}")
        }
    }

    fun takeHdrBurstPhoto(displayRotationDegrees: Int) {
        val yReader = yuvImageReader ?: run {
            Log.w(TAG, "YUV ImageReader not available, falling back to single photo")
            takeSinglePhoto(displayRotationDegrees)
            return
        }
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val caps = capabilities ?: return
        val handler = cameraHandler ?: return

        if (!isHdrBurstCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "HDR burst capture already in progress")
            return
        }

        burstFrames.clear()
        burstTimestampMap.clear()

        val jpegRotation = computeJpegOrientation(displayRotationDegrees)
        pendingHdrRotation = jpegRotation

        try {
            val stepsPerEv = if (caps.aeCompStep > 0f) (1f / caps.aeCompStep).roundToInt() else 3
            val underComp = (-2 * stepsPerEv).coerceIn(caps.aeCompMin, caps.aeCompMax)
            val normalComp = evCompensation.coerceIn(caps.aeCompMin, caps.aeCompMax)
            val overComp = (+2 * stepsPerEv).coerceIn(caps.aeCompMin, caps.aeCompMax)

            val underShutter = (currentShutterNs / 4L).coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
            val normalShutter = currentShutterNs.coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
            val overShutter = (currentShutterNs * 4L).coerceIn(caps.exposureMinNs, caps.exposureMaxNs)

            data class BracketSpec(val biasEv: Float, val aeComp: Int, val shutterNs: Long)
            val bracketList = listOf(
                BracketSpec(biasEv = -2.0f, aeComp = underComp, shutterNs = underShutter),
                BracketSpec(biasEv = 0.0f, aeComp = normalComp, shutterNs = normalShutter),
                BracketSpec(biasEv = +2.0f, aeComp = overComp, shutterNs = overShutter),
            )

            val requests = bracketList.map { spec ->
                val template = if (isRecording) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else CameraDevice.TEMPLATE_STILL_CAPTURE
                val builder = device.createCaptureRequest(template)
                builder.addTarget(yReader.surface)
                previewSurface?.let { builder.addTarget(it) }

                applyProControls(builder)
                builder.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation)

                if ((isManualExposure || caps.aeCompMin == caps.aeCompMax) && caps.manualSensor) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(caps.isoMin, caps.isoMax))
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, spec.shutterNs)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, spec.aeComp)
                }

                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                }

                // Phase 4: Disable artificial ISP noise reduction & sharpening for pristine unsharpened RAW capture
                if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                } else if (caps.noiseReductionModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)) {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                }
                if (caps.edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)) {
                    builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                }
                if (caps.tonemapModes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.set(CaptureRequest.TONEMAP_CURVE, flatLinearTonemapCurve)
                }

                builder.setTag(spec.biasEv)
                builder.build()
            }

            // Launch timeout watchdog
            scope.launch {
                delay(4000)
                if (isHdrBurstCapturing.compareAndSet(true, false)) {
                    Log.w(TAG, "HDR burst timeout watchdog fired")
                    burstFrames.clear()
                    burstTimestampMap.clear()
                }
            }

            session.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long,
                    ) {
                        val bias = request.tag as? Float ?: 0.0f
                        burstTimestampMap[timestamp] = bias
                        if (burstFrames.isEmpty()) {
                            listener.onPhotoCaptureStarted()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        Log.e(TAG, "Burst frame failed: reason=${failure.reason}")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing HDR burst", e)
            isHdrBurstCapturing.set(false)
            listener.onSessionError("HDR burst failed: ${e.message}")
        }
    }

    // ---- Recording Flow (HEVC MediaRecorder -> MediaStore) ----------------------------------

    fun startRecording(displayRotationDegrees: Int) {
        if (isRecording) return
        val vConfig = videoConfig ?: run {
            listener.onRecordingFailed("Video configuration not ready")
            return
        }
        val rSurface = recorderSurface ?: run {
            listener.onRecordingFailed("Recorder input surface not ready")
            return
        }
        val caps = capabilities ?: return

        isRecording = true
        scope.launch {
            try {
                val orientationHint = computeJpegOrientation(displayRotationDegrees)

                audioEngine.pauseForRecording()
                val preferredMic = audioEngine.getPreferredAudioDeviceInfo()
                videoRecorder.start(vConfig, rSurface, orientationHint, preferredMic)

                cameraHandler?.post {
                    updateRepeatingRequest()
                    listener.onRecordingStarted()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video recording", e)
                isRecording = false
                audioEngine.resumeAfterRecording()
                cameraHandler?.post { updateRepeatingRequest() }
                listener.onRecordingFailed("Failed to start recorder: ${e.message}")
            }
        }
    }

    // ---- Advanced Pro Controls: AWB Lock, Multi-Lens & Mirror Selfie ------------------------

    fun setAwbLocked(locked: Boolean) {
        awbLocked = locked
        cameraHandler?.post { updateRepeatingRequest() }
    }

    fun isAwbLocked(): Boolean = awbLocked

    fun setZoomRatio(ratio: Float) {
        val caps = capabilities ?: return
        currentZoomRatio = ratio.coerceIn(caps.zoomRatioRange.lower, caps.zoomRatioRange.upper)
        cameraHandler?.post { updateRepeatingRequest() }
    }

    fun switchLens(lens: LensOption) {
        activeLens = lens
        val caps = capabilities ?: return
        val manager = cameraManager
        when (lens) {
            LensOption.UltraWide -> {
                val ultraWideId = manager.cameraIdList.firstOrNull { id ->
                    val ch = manager.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 99f
                    facing == CameraMetadata.LENS_FACING_BACK && (focal < 2.5f || id == "4")
                }
                if (ultraWideId != null && ultraWideId != caps.cameraId) {
                    switchCameraId(ultraWideId)
                } else {
                    setZoomRatio(0.5f)
                }
            }
            LensOption.Wide -> {
                val wideId = manager.cameraIdList.firstOrNull { id ->
                    val ch = manager.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    facing == CameraMetadata.LENS_FACING_BACK && id == "0"
                } ?: CameraCapabilities.selectCameraId(manager, CameraMetadata.LENS_FACING_BACK)
                if (wideId != null && wideId != caps.cameraId) {
                    switchCameraId(wideId)
                }
                setZoomRatio(1.0f)
            }
            LensOption.TwoX -> {
                setZoomRatio(2.0f)
            }
            LensOption.Telephoto -> {
                setZoomRatio(3.0f)
            }
        }
    }

    private fun switchCameraId(newCameraId: String) {
        val handler = cameraHandler ?: return
        handler.post {
            closeSession()
            closeCamera()
            val ch = cameraManager.getCameraCharacteristics(newCameraId)
            val caps = CameraCapabilities.from(newCameraId, ch)
            characteristics = ch
            capabilities = caps
            currentLensFacing = caps.lensFacing
            val profile = deviceProfile ?: DeviceProfile.detect(context, ch).also { deviceProfile = it }
            val vConfig = VideoConfigSelector.choose(
                c = caps,
                hdr = hdrEnabled && caps.hlg10Supported,
                targetWidth = targetVideoWidth,
                targetFps = targetVideoFps,
                preferredCodec = targetVideoCodec,
                profile = profile,
                safeBoundsEnabled = safeBoundsEnabled,
                customBitrateBps = customBitrateMbps * 1_000_000,
            )
            videoConfig = vConfig
            val pSize = VideoConfigSelector.choosePreviewSize(caps, vConfig)
            previewSize = pSize
            previewSurfaceTexture?.setDefaultBufferSize(pSize.width, pSize.height)
            recorderSurface?.release()
            recorderSurface = null
            listener.onCapabilitiesDiscovered(caps, vConfig, profile)
            openCamera(newCameraId)
        }
    }

    fun setAudioSource(source: AudioSourceType) {
        audioEngine.setAudioSource(source)
    }

    fun setLowCutFilter(enabled: Boolean) {
        audioEngine.setLowCut(enabled)
    }

    fun stopRecording() {
        if (!isRecording) return
        scope.launch {
            stopRecordingInternal()
        }
    }

    private suspend fun stopRecordingInternal() {
        if (!isRecording) return
        isRecording = false

        cameraHandler?.post { updateRepeatingRequest() }

        try {
            val savedClip = videoRecorder.stop()
            listener.onRecordingStopped(savedClip)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
            listener.onRecordingFailed("Error finalising clip: ${e.message}")
        } finally {
            audioEngine.resumeAfterRecording()
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun kelvinToRgbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.toDouble() / 100.0
        val r: Double
        val g: Double
        val b: Double

        r = if (temp <= 66) 255.0 else 329.698727446 * Math.pow(temp - 60, -0.1332047592)
        g = if (temp <= 66) 99.4708025861 * Math.log(temp) - 161.1195681661
            else 288.1221695283 * Math.pow(temp - 60, -0.0755148492)
        b = if (temp >= 66) 255.0
            else if (temp <= 19) 0.0
            else 138.5177312231 * Math.log(temp - 10) - 305.0447927307

        val normR = (r.coerceIn(0.0, 255.0) / 255.0).coerceAtLeast(0.01)
        val normG = (g.coerceIn(0.0, 255.0) / 255.0).coerceAtLeast(0.01)
        val normB = (b.coerceIn(0.0, 255.0) / 255.0).coerceAtLeast(0.01)

        val rGain = (normG / normR).toFloat().coerceIn(0.8f, 3.8f)
        val bGain = (normG / normB).toFloat().coerceIn(0.8f, 3.8f)
        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }

    private fun kelvinToColorTransform(kelvin: Int): ColorSpaceTransform {
        // Standard sRGB identity color space transform matrix (3x3 Rational elements: 9 numerator/denominator pairs)
        return ColorSpaceTransform(intArrayOf(
            1, 1,  0, 1,  0, 1,
            0, 1,  1, 1,  0, 1,
            0, 1,  0, 1,  1, 1,
        ))
    }



    companion object {
        private const val TAG = "CameraEngine"
    }
}
