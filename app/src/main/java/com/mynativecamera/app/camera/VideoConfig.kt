package com.mynativecamera.app.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.abs

/** The exact recording profile handed to MediaRecorder and to the capture session. */
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val videoEncoder: Int,
    val codecLabel: String,
    val hdr: Boolean,
    /** Only set for 10-bit: HEVC Main10 profile + the highest level the encoder supports. */
    val encoderProfile: Int? = null,
    val encoderLevel: Int? = null,
) {
    val frameDurationNs: Long get() = ExposureStops.NANOS_PER_SECOND / fps
    val aspectRatio: Float get() = width.toFloat() / height
}

/**
 * Selects the optimal or user-specified recording profile:
 * Supports 4K UHD, 1080p FHD, 720p HD at 24p, 30p, or 60p with HEVC or H.264,
 * and high cinema bitrates (up to 100 Mbps clamped to hardware capabilities).
 */
object VideoConfigSelector {

    const val DEFAULT_FPS = 30
    const val TARGET_BITRATE_HEVC = 100_000_000
    const val TARGET_BITRATE_AVC = 60_000_000
    private const val MAX_WIDTH = 3840
    private const val MAX_PREVIEW_W = 1920
    private const val MAX_PREVIEW_H = 1080

    fun choose(
        c: CameraCapabilities,
        hdr: Boolean = false,
        targetWidth: Int? = null,
        targetFps: Int = DEFAULT_FPS,
        preferredCodec: Int = MediaRecorder.VideoEncoder.HEVC,
        profile: DeviceProfile? = null,
        safeBoundsEnabled: Boolean = true,
        customBitrateBps: Int? = null,
    ): VideoConfig {
        val safeWidth = if (safeBoundsEnabled && profile != null && profile.isClampedTo1080p && (targetWidth == null || targetWidth > profile.safeMaxVideoWidth)) {
            profile.safeMaxVideoWidth
        } else {
            targetWidth
        }

        val safeFps = if (safeBoundsEnabled && profile != null && targetFps > profile.safeMaxFps) {
            profile.safeMaxFps
        } else {
            targetFps
        }

        val targetBitrateHevc = customBitrateBps ?: if (safeBoundsEnabled) (profile?.safeBitrateBps ?: TARGET_BITRATE_HEVC) else TARGET_BITRATE_HEVC
        val targetBitrateAvc = customBitrateBps ?: if (safeBoundsEnabled) ((profile?.safeBitrateBps?.times(0.75f)?.toInt()) ?: TARGET_BITRATE_AVC) else TARGET_BITRATE_AVC
        val targetBitrateAv1 = customBitrateBps ?: targetBitrateHevc

        val frameNs = ExposureStops.NANOS_PER_SECOND / safeFps.coerceAtLeast(1)
        val sizes = c.recorderSizes.filter {
            it.width <= MAX_WIDTH && (c.recorderMinFrameDurationsNs[it] ?: 0L) <= frameNs
        }

        val nativeAspect = c.nativeSensorAspectRatio
        // Align recorder surfaces with native sensor aspect ratio (usually 4:3) to prevent stretching/cropping
        val nativeAspectSizes = sizes.filter { abs(it.width.toFloat() / it.height.toFloat() - nativeAspect) < 0.05f }
        val pool = if (nativeAspectSizes.isNotEmpty()) nativeAspectSizes else sizes

        val targetSizes = if (safeWidth != null) {
            pool.filter { it.width == safeWidth }.ifEmpty { pool }
        } else {
            pool
        }.sortedByDescending { it.width.toLong() * it.height }

        // If user wants AV1 (Android 14+ / API 34+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            preferredCodec == MediaRecorder.VideoEncoder.AV1
        ) {
            val av1 = EncoderSupport.encodersFor(MediaFormat.MIMETYPE_VIDEO_AV1)
            for (size in targetSizes) {
                val enc = av1.firstOrNull { EncoderSupport.supports(it, size, safeFps) } ?: continue
                return VideoConfig(
                    width = size.width,
                    height = size.height,
                    fps = safeFps,
                    bitrateBps = EncoderSupport.clampBitrate(enc, targetBitrateAv1),
                    videoEncoder = MediaRecorder.VideoEncoder.AV1,
                    codecLabel = "AV1",
                    hdr = false,
                )
            }
        }

        // If user wants HEVC
        if (preferredCodec == MediaRecorder.VideoEncoder.HEVC) {
            val hevc = EncoderSupport.encodersFor(MediaFormat.MIMETYPE_VIDEO_HEVC)
            for (size in targetSizes) {
                for (enc in hevc) {
                    if (!EncoderSupport.supports(enc, size, safeFps)) continue
                    val level = if (hdr) {
                        EncoderSupport.maxLevel(enc, CodecProfileLevel.HEVCProfileMain10) ?: continue
                    } else {
                        null
                    }
                    return VideoConfig(
                        width = size.width,
                        height = size.height,
                        fps = safeFps,
                        bitrateBps = EncoderSupport.clampBitrate(enc, targetBitrateHevc),
                        videoEncoder = MediaRecorder.VideoEncoder.HEVC,
                        codecLabel = if (hdr) "HEVC MAIN10" else "HEVC",
                        hdr = hdr,
                        encoderProfile = if (hdr) CodecProfileLevel.HEVCProfileMain10 else null,
                        encoderLevel = level,
                    )
                }
            }
        }

        // Fallback or explicit AVC (H.264)
        val avc = EncoderSupport.encodersFor(MediaFormat.MIMETYPE_VIDEO_AVC)
        for (size in targetSizes) {
            val enc = avc.firstOrNull { EncoderSupport.supports(it, size, safeFps) } ?: continue
            return VideoConfig(
                width = size.width,
                height = size.height,
                fps = safeFps,
                bitrateBps = EncoderSupport.clampBitrate(enc, targetBitrateAvc),
                videoEncoder = MediaRecorder.VideoEncoder.H264,
                codecLabel = "H.264",
                hdr = false,
            )
        }

        // If high FPS failed, retry with 30fps
        if (safeFps > 30) {
            return choose(c, hdr, safeWidth, 30, preferredCodec, profile, safeBoundsEnabled, customBitrateBps)
        }
        // If 4K failed, retry with 1080p
        if (safeWidth != null && safeWidth > 1920) {
            return choose(c, hdr, 1920, 30, preferredCodec, profile, safeBoundsEnabled, customBitrateBps)
        }

        // Absolute fallback: pick largest available size
        val fallbackSize = pool.firstOrNull() ?: Size(1920, 1080)
        return VideoConfig(
            width = fallbackSize.width,
            height = fallbackSize.height,
            fps = 30,
            bitrateBps = customBitrateBps ?: 30_000_000,
            videoEncoder = MediaRecorder.VideoEncoder.H264,
            codecLabel = "H.264",
            hdr = false,
        )
    }

    /** Preview stream: same aspect as the recording (WYSIWYG framing), capped at 1080p for GPU cost. */
    fun choosePreviewSize(c: CameraCapabilities, video: VideoConfig): Size {
        val target = video.aspectRatio
        val fitting = c.previewSizes.filter { it.width <= MAX_PREVIEW_W && it.height <= MAX_PREVIEW_H }
        return fitting
            .filter { abs(it.width.toFloat() / it.height - target) < 0.01f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: fitting.minByOrNull { abs(it.width.toFloat() / it.height - target) }
            ?: Size(MAX_PREVIEW_W, MAX_PREVIEW_H)
    }
}

/** MediaCodecList queries (hardware encoders first). */
object EncoderSupport {

    private val codecInfos: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
    }

    fun encodersFor(mime: String): List<MediaCodecInfo.CodecCapabilities> =
        codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }

    fun supports(caps: MediaCodecInfo.CodecCapabilities, size: Size, fps: Int): Boolean =
        caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true

    fun maxLevel(caps: MediaCodecInfo.CodecCapabilities, profile: Int): Int? =
        caps.profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level }

    fun clampBitrate(caps: MediaCodecInfo.CodecCapabilities, target: Int): Int =
        caps.videoCapabilities?.bitrateRange?.let { target.coerceIn(it.lower, it.upper) } ?: target

    fun hasHevcMain10(): Boolean =
        encodersFor(MediaFormat.MIMETYPE_VIDEO_HEVC)
            .any { maxLevel(it, CodecProfileLevel.HEVCProfileMain10) != null }

    fun hasAv1(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        encodersFor(MediaFormat.MIMETYPE_VIDEO_AV1).isNotEmpty()
}
