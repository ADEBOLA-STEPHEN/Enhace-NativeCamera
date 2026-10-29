package com.mynativecamera.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.photo.Photo
import java.util.ArrayList
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * In-memory copy of an ImageFormat.YUV_420_888 frame.
 * Extracting planes into ByteArrays allows releasing the android.media.Image immediately
 * on the Camera2 handler thread, completely eliminating ImageReader buffer stalls.
 */
data class YuvFrameData(
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val yRowStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
    val exposureBiasEv: Float, // -2.0f, 0.0f, +2.0f
) {
    companion object {
        fun fromImage(image: Image, exposureBiasEv: Float): YuvFrameData {
            val w = image.width
            val h = image.height
            val planes = image.planes

            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer

            val yBytes = ByteArray(yBuf.remaining())
            yBuf.get(yBytes)

            val uBytes = ByteArray(uBuf.remaining())
            uBuf.get(uBytes)

            val vBytes = ByteArray(vBuf.remaining())
            vBuf.get(vBytes)

            return YuvFrameData(
                width = w,
                height = h,
                yPlane = yBytes,
                uPlane = uBytes,
                vPlane = vBytes,
                yRowStride = planes[0].rowStride,
                uRowStride = planes[1].rowStride,
                vRowStride = planes[2].rowStride,
                uPixelStride = planes[1].pixelStride,
                vPixelStride = planes[2].pixelStride,
                exposureBiasEv = exposureBiasEv,
            )
        }
    }
}

/**
 * Computational Image Processor:
 * Performs multi-frame computational photography by optically aligning 3 bracketed exposures
 * (-2 EV highlight recovery, 0 EV base exposure, +2 EV shadow detail) using OpenCV AlignMTB
 * (Median Threshold Bitmap) and fusing them with Mertens exposure fusion to eliminate ghosting.
 */
object ComputationalImageProcessor {

    private const val TAG = "ComputationalImageProc"

    private val isOpencvReady: Boolean by lazy {
        try {
            val ok = OpenCVLoader.initLocal() || OpenCVLoader.initDebug()
            Log.i(TAG, "OpenCV initialized in ComputationalImageProcessor: $ok")
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV failed to initialize, will use fallback engine", e)
            false
        }
    }

    // Precomputed Gaussian well-exposedness lookup table centered at 128 (8-bit midtone)
    // with sigma = 48.0f to prioritize balanced luminance and avoid clipped pixels.
    private val WEIGHT_LUT = FloatArray(256) { y ->
        val diff = (y - 128f) / 48f
        exp(-0.5f * diff * diff).coerceAtLeast(0.005f)
    }

    data class AlignmentOffset(val dx: Int, val dy: Int)

    /**
     * Blends 3 bracketed YUV frames (-2 EV, 0 EV, +2 EV) into a single high dynamic range Bitmap.
     * Keeps all image processing operations strictly on Dispatchers.Default off the main UI thread.
     *
     * Phase 2 Permanent Un-Mirroring:
     * When isFrontCamera is true, applies a hardware Matrix flip (scaleX = -1f) permanently flipping pixels
     * so that the saved image matches what the user saw in the viewfinder without relying on EXIF tags.
     */
    suspend fun blendHdrBurst(
        frames: List<YuvFrameData>,
        rotationDegrees: Int = 0,
        mirrorHorizontal: Boolean = false,
        isFrontCamera: Boolean = false,
    ): Bitmap = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "Frames list cannot be empty" }
        val sorted = frames.sortedBy { it.exposureBiasEv }

        val fusedBitmap = if (frames.size == 1) {
            yuvToBitmap(frames[0], rotationDegrees, mirrorHorizontal)
        } else if (isOpencvReady) {
            try {
                blendWithOpenCv(sorted, rotationDegrees, mirrorHorizontal)
            } catch (e: Throwable) {
                Log.w(TAG, "OpenCV Mertens fusion error, falling back to CPU engine", e)
                blendFallback(sorted, rotationDegrees, mirrorHorizontal)
            }
        } else {
            blendFallback(sorted, rotationDegrees, mirrorHorizontal)
        }

        if (isFrontCamera) {
            val flipMatrix = Matrix().apply { postScale(-1f, 1f) }
            val flipped = Bitmap.createBitmap(fusedBitmap, 0, 0, fusedBitmap.width, fusedBitmap.height, flipMatrix, true)
            if (flipped != fusedBitmap) {
                fusedBitmap.recycle()
            }
            flipped
        } else {
            fusedBitmap
        }
    }

    /**
     * Permanent Un-Mirroring helper for still photos:
     * If active camera is LENS_FACING_FRONT, applies a hardware Matrix flip:
     * Matrix().apply { postScale(-1f, 1f) } and Bitmap.createBitmap(...) to permanently flip pixels horizontally.
     */
    fun applyFrontCameraUnMirror(original: Bitmap, isFrontCamera: Boolean): Bitmap {
        if (!isFrontCamera) return original
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        if (flipped != original) {
            original.recycle()
        }
        return flipped
    }

    /**
     * Optical Alignment (AlignMTB) and De-ghosting Exposure Fusion (MergeMertens) using OpenCV.
     */
    private fun blendWithOpenCv(
        sorted: List<YuvFrameData>,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
    ): Bitmap {
        val fUnder = sorted.first()
        val fNormal = if (sorted.size >= 3) sorted[1] else sorted.first()
        val fOver = sorted.last()

        val width = fNormal.width
        val height = fNormal.height

        // 1. Convert frames to OpenCV Mats (CV_8UC3 RGB)
        val matUnder = yuvToRgbMat(fUnder)
        val matNormal = yuvToRgbMat(fNormal) // 0 EV Anchor frame
        val matOver = yuvToRgbMat(fOver)

        val alignedUnder = Mat()
        val alignedOver = Mat()
        val fusedMat = Mat()
        val ldrMat = Mat()

        try {
            // 2. Align -2 EV and +2 EV frames to the 0 EV Anchor frame using AlignMTB
            val alignMTB = Photo.createAlignMTB(6, 4, false)
            val shiftUnder = alignMTB.calculateShift(matNormal, matUnder)
            val shiftOver = alignMTB.calculateShift(matNormal, matOver)

            Log.i(TAG, "AlignMTB optical shift relative to 0 EV anchor: -2EV=(${shiftUnder.x}, ${shiftUnder.y}), +2EV=(${shiftOver.x}, ${shiftOver.y})")

            alignMTB.shiftMat(matUnder, alignedUnder, shiftUnder)
            alignMTB.shiftMat(matOver, alignedOver, shiftOver)

            val alignedList = ArrayList<Mat>(3).apply {
                add(alignedUnder)
                add(matNormal) // 0 EV Anchor
                add(alignedOver)
            }

            // 3. Exposure fusion & de-ghosting via MergeMertens
            val mergeMertens = Photo.createMergeMertens(1.0f, 1.0f, 0.0f)
            mergeMertens.process(alignedList, fusedMat)

            // 4. MergeMertens produces 32-bit float [0..1]. Scale by 255.0 to 8-bit LDR
            Core.multiply(fusedMat, Scalar(255.0, 255.0, 255.0), ldrMat)
            ldrMat.convertTo(ldrMat, CvType.CV_8UC3)

            val fusedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(ldrMat, fusedBitmap)

            // 5. Apply orientation and mirror matrices
            val matrix = Matrix()
            if (mirrorHorizontal) {
                matrix.postScale(-1f, 1f, width / 2f, height / 2f)
            }
            if (rotationDegrees % 360 != 0) {
                matrix.postRotate(rotationDegrees.toFloat())
            }

            return if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(fusedBitmap, 0, 0, width, height, matrix, true)
                if (transformed != fusedBitmap) fusedBitmap.recycle()
                transformed
            } else {
                fusedBitmap
            }
        } finally {
            matUnder.release()
            matNormal.release()
            matOver.release()
            alignedUnder.release()
            alignedOver.release()
            fusedMat.release()
            ldrMat.release()
        }
    }

    /**
     * Converts YuvFrameData into a 3-channel 8-bit RGB OpenCV Mat (CvType.CV_8UC3).
     */
    private fun yuvToRgbMat(frame: YuvFrameData): Mat {
        val width = frame.width
        val height = frame.height
        val mat = Mat(height, width, CvType.CV_8UC3)
        val rgbBytes = ByteArray(width * height * 3)

        val yPlane = frame.yPlane
        val uPlane = frame.uPlane
        val vPlane = frame.vPlane
        val yStride = frame.yRowStride
        val uStride = frame.uRowStride
        val vStride = frame.vRowStride
        val uPixelStride = frame.uPixelStride
        val vPixelStride = frame.vPixelStride

        var outIdx = 0
        for (row in 0 until height) {
            val yRowOffset = row * yStride
            val uvRowOffset = (row shr 1) * uStride
            val vRowOffset = (row shr 1) * vStride
            for (col in 0 until width) {
                val y = yPlane[yRowOffset + col].toInt() and 0xFF
                val uvColOffset = (col shr 1)
                val u = (uPlane[uvRowOffset + uvColOffset * uPixelStride].toInt() and 0xFF) - 128
                val v = (vPlane[vRowOffset + uvColOffset * vPixelStride].toInt() and 0xFF) - 128

                val r = (y + (1.402f * v)).roundToInt().coerceIn(0, 255).toByte()
                val g = (y - (0.344136f * u) - (0.714136f * v)).roundToInt().coerceIn(0, 255).toByte()
                val b = (y + (1.772f * u)).roundToInt().coerceIn(0, 255).toByte()

                rgbBytes[outIdx++] = r
                rgbBytes[outIdx++] = g
                rgbBytes[outIdx++] = b
            }
        }
        mat.put(0, 0, rgbBytes)
        return mat
    }

    private fun computeAlignment(
        base: YuvFrameData,
        target: YuvFrameData,
        maxShift: Int = 12,
        step: Int = 10,
    ): AlignmentOffset {
        val w = base.width
        val h = base.height
        val baseY = base.yPlane
        val targetY = target.yPlane
        val baseStride = base.yRowStride
        val targetStride = target.yRowStride

        val startX = w / 4
        val endX = w * 3 / 4
        val startY = h / 4
        val endY = h * 3 / 4

        var bestDx = 0
        var bestDy = 0
        var minSad = Long.MAX_VALUE

        for (dy in -maxShift..maxShift step 2) {
            for (dx in -maxShift..maxShift step 2) {
                var sad = 0L
                for (y in startY until endY step step) {
                    val baseRow = y * baseStride
                    val targetYPos = (y + dy).coerceIn(0, h - 1)
                    val targetRow = targetYPos * targetStride
                    for (x in startX until endX step step) {
                        val targetXPos = (x + dx).coerceIn(0, w - 1)
                        val valBase = baseY[baseRow + x].toInt() and 0xFF
                        val valTarget = targetY[targetRow + targetXPos].toInt() and 0xFF
                        sad += kotlin.math.abs(valBase - valTarget)
                    }
                }
                if (sad < minSad) {
                    minSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return AlignmentOffset(bestDx, bestDy)
    }

    private suspend fun blendFallback(
        sorted: List<YuvFrameData>,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
    ): Bitmap = coroutineScope {
        val fUnder = sorted.first()
        val fNormal = if (sorted.size >= 3) sorted[1] else sorted.first()
        val fOver = sorted.last()

        val width = fNormal.width
        val height = fNormal.height
        val outPixels = IntArray(width * height)

        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val sliceHeight = (height + cpuCores - 1) / cpuCores

        val alignUnder = async { computeAlignment(fNormal, fUnder) }
        val alignOver = async { computeAlignment(fNormal, fOver) }
        val offsetUnder = alignUnder.await()
        val offsetOver = alignOver.await()

        val jobs = (0 until cpuCores).map { sliceIdx ->
            val startY = sliceIdx * sliceHeight
            val endY = minOf(startY + sliceHeight, height)
            async {
                processSlice(
                    startY = startY,
                    endY = endY,
                    width = width,
                    height = height,
                    fUnder = fUnder,
                    fNormal = fNormal,
                    fOver = fOver,
                    offsetUnder = offsetUnder,
                    offsetOver = offsetOver,
                    outPixels = outPixels,
                )
            }
        }
        jobs.awaitAll()

        val bitmap = Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)

        val matrix = Matrix()
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, width / 2f, height / 2f)
        }
        if (rotationDegrees % 360 != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } else {
            bitmap
        }
    }

    private fun processSlice(
        startY: Int,
        endY: Int,
        width: Int,
        height: Int,
        fUnder: YuvFrameData,
        fNormal: YuvFrameData,
        fOver: YuvFrameData,
        offsetUnder: AlignmentOffset,
        offsetOver: AlignmentOffset,
        outPixels: IntArray,
    ) {
        val yPlaneUnder = fUnder.yPlane
        val yPlaneNormal = fNormal.yPlane
        val yPlaneOver = fOver.yPlane

        val yStrideUnder = fUnder.yRowStride
        val yStrideNormal = fNormal.yRowStride
        val yStrideOver = fOver.yRowStride

        val uPlane = fNormal.uPlane
        val vPlane = fNormal.vPlane
        val uStride = fNormal.uRowStride
        val vStride = fNormal.vRowStride
        val uPixelStride = fNormal.uPixelStride
        val vPixelStride = fNormal.vPixelStride

        val dxU = offsetUnder.dx
        val dyU = offsetUnder.dy
        val dxO = offsetOver.dx
        val dyO = offsetOver.dy

        for (row in startY until endY) {
            val yOffsetNormal = row * yStrideNormal
            val rowUnderClamped = (row + dyU).coerceIn(0, height - 1)
            val yOffsetUnder = rowUnderClamped * yStrideUnder
            val rowOverClamped = (row + dyO).coerceIn(0, height - 1)
            val yOffsetOver = rowOverClamped * yStrideOver

            val uvRowOffset = (row shr 1) * uStride
            val vRowOffset = (row shr 1) * vStride
            val outRowOffset = row * width

            for (col in 0 until width) {
                val colUnderClamped = (col + dxU).coerceIn(0, width - 1)
                val colOverClamped = (col + dxO).coerceIn(0, width - 1)

                val yU = yPlaneUnder[yOffsetUnder + colUnderClamped].toInt() and 0xFF
                val yN = yPlaneNormal[yOffsetNormal + col].toInt() and 0xFF
                val yO = yPlaneOver[yOffsetOver + colOverClamped].toInt() and 0xFF

                var wU = WEIGHT_LUT[yU]
                var wN = WEIGHT_LUT[yN]
                var wO = WEIGHT_LUT[yO]

                if (yN > 170) {
                    val highlightFactor = (yN - 170) / 85f
                    wU += highlightFactor * 3.5f
                    wN *= (1f - highlightFactor * 0.7f)
                }

                if (yN < 90) {
                    val shadowFactor = (90 - yN) / 90f
                    wO += shadowFactor * 3.0f
                    wN *= (1f - shadowFactor * 0.5f)
                }

                val sumW = wU + wN + wO + 0.0001f
                val blendedY = ((wU * (yU * 1.1f) + wN * yN + wO * (yO * 0.95f)) / sumW).roundToInt().coerceIn(0, 255)

                val uvColOffset = (col shr 1)
                val u = (uPlane[uvRowOffset + uvColOffset * uPixelStride].toInt() and 0xFF) - 128
                val v = (vPlane[vRowOffset + uvColOffset * vPixelStride].toInt() and 0xFF) - 128

                val r = (blendedY + (1.402f * v)).roundToInt().coerceIn(0, 255)
                val g = (blendedY - (0.344136f * u) - (0.714136f * v)).roundToInt().coerceIn(0, 255)
                val b = (blendedY + (1.772f * u)).roundToInt().coerceIn(0, 255)

                outPixels[outRowOffset + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun yuvToBitmap(frame: YuvFrameData, rotationDegrees: Int, mirrorHorizontal: Boolean = false): Bitmap {
        val width = frame.width
        val height = frame.height
        val outPixels = IntArray(width * height)

        val yPlane = frame.yPlane
        val uPlane = frame.uPlane
        val vPlane = frame.vPlane

        val yStride = frame.yRowStride
        val uStride = frame.uRowStride
        val vStride = frame.vRowStride
        val uPixelStride = frame.uPixelStride
        val vPixelStride = frame.vPixelStride

        for (row in 0 until height) {
            val yRowOffset = row * yStride
            val uvRowOffset = (row shr 1) * uStride
            val vRowOffset = (row shr 1) * vStride
            val outRowOffset = row * width

            for (col in 0 until width) {
                val y = yPlane[yRowOffset + col].toInt() and 0xFF
                val uvColOffset = (col shr 1)
                val u = (uPlane[uvRowOffset + uvColOffset * uPixelStride].toInt() and 0xFF) - 128
                val v = (vPlane[vRowOffset + uvColOffset * vPixelStride].toInt() and 0xFF) - 128

                val r = (y + (1.402f * v)).roundToInt().coerceIn(0, 255)
                val g = (y - (0.344136f * u) - (0.714136f * v)).roundToInt().coerceIn(0, 255)
                val b = (y + (1.772f * u)).roundToInt().coerceIn(0, 255)

                outPixels[outRowOffset + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, width / 2f, height / 2f)
        }
        if (rotationDegrees % 360 != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        return if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    }
}
