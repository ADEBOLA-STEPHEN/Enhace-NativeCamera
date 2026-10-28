package com.mynativecamera.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import com.mynativecamera.app.camera.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MediaRecorder wrapper: HEVC (H.265) in MPEG-4 at the configured bitrate (100 Mbps target),
 * AAC 48 kHz stereo audio, written to MediaStore.
 *
 * Design notes
 *  - **Persistent input surface**: the encoder surface is created once per session and
 *    included in the CameraCaptureSession. Starting/stopping a take only changes the repeating
 *    request's targets - no session rebuild, so record start is instant and the preview never
 *    stutters.
 *  - **Threading**: every MediaRecorder / MediaStore call runs on one serial IO lane
 *    ([ioLane]); `prepare()`/`stop()` can block for hundreds of ms and never touch the main or
 *    camera threads.
 *  - **4 GB segmenting**: at 100 Mbps a file reaches 4 GB in ~5 minutes. A max file size is
 *    set and, when approached, recording rolls seamlessly into the next file via
 *    setNextOutputFile() - no dropped frames, every segment is a valid MP4.
 */
class VideoRecorder(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** A rolled-over segment was finalised while recording continues. */
        fun onSegmentSaved(clip: SavedClip)

        /** The recorder hit an unrecoverable error; the engine should stop the take. */
        fun onRecorderFailure(message: String)
    }

    private val store = MediaStoreVideoStore(context)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioLane = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioLane)

    // ---- Confined to ioLane ----
    private var recorder: MediaRecorder? = null
    private var current: PendingVideo? = null
    private var next: PendingVideo? = null
    private var takeStamp = ""
    private var segmentIndex = 0
    private var autoStopped = false

    /**
     * Creates the persistent encoder input surface for [config]. A throw-away recorder is
     * prepared once so the surface acquires the encoder's buffer format/size before it is
     * handed to the camera session.
     */
    suspend fun createPersistentSurface(config: VideoConfig): Surface = withContext(ioLane) {
        val surface = MediaCodec.createPersistentInputSurface()
        // Transient probe target in the app's private cache - never user media.
        val probeTarget = File(context.cacheDir, "encoder_probe.mp4")
        val probe = newMediaRecorder()
        try {
            probe.configure(config, surface, withAudio = false, orientationHint = 0)
            probe.setOutputFile(probeTarget)
            probe.prepare()
            surface
        } catch (e: Exception) {
            surface.release()
            throw e
        } finally {
            probe.release()
            probeTarget.delete()
        }
    }

    /** Starts a take into a new MediaStore entry. Throws if the encoder cannot be prepared. */
    suspend fun start(
        config: VideoConfig,
        surface: Surface,
        orientationHint: Int,
        preferredAudioDevice: android.media.AudioDeviceInfo? = null,
    ) = withContext(ioLane) {
        check(recorder == null) { "Recorder already running" }
        takeStamp = LocalDateTime.now().format(STAMP_FORMAT)
        segmentIndex = 0
        autoStopped = false

        val pending = store.createPending(segmentName(0))
        val mr = newMediaRecorder()
        try {
            mr.configure(config, surface, withAudio = true, orientationHint = orientationHint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredAudioDevice != null) {
                mr.setPreferredDevice(preferredAudioDevice)
            }
            mr.setMaxFileSize(MAX_SEGMENT_BYTES)
            mr.setOutputFile(pending.descriptor.fileDescriptor)
            // Callbacks arrive on the main looper; hop straight back onto the IO lane.
            mr.setOnInfoListener { _, what, _ -> scope.launch { onInfo(what) } }
            mr.setOnErrorListener { _, what, extra ->
                scope.launch { listener.onRecorderFailure("Recorder error ($what/$extra)") }
            }
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            mr.release()
            store.discard(pending)
            throw e
        }
        recorder = mr
        current = pending
    }

    /**
     * Stops the take, finalises the MP4 and publishes it to the gallery.
     * Returns the last clip (earlier segments were already reported via [Listener]).
     */
    suspend fun stop(): SavedClip? = withContext(ioLane) {
        val mr = recorder ?: return@withContext null
        recorder = null
        val stoppedCleanly = try {
            mr.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when no frames were encoded, or when the recorder already auto-stopped.
            Log.w(TAG, "MediaRecorder.stop() failed", e)
            false
        } finally {
            runCatching { mr.reset() }
            mr.release()
        }

        val finished = current
        current = null
        next?.let(store::discard)
        next = null

        when {
            finished == null -> null
            stoppedCleanly || autoStopped -> store.publish(finished)
            else -> {
                store.discard(finished)
                null
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    // ---- Internals --------------------------------------------------------------------------

    private fun onInfo(what: Int) {
        val mr = recorder ?: return
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (next != null) return
                try {
                    val pending = store.createPending(segmentName(segmentIndex + 1))
                    mr.setNextOutputFile(pending.descriptor.fileDescriptor)
                    next = pending
                } catch (e: Exception) {
                    Log.e(TAG, "Could not prepare next segment", e)
                }
            }

            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                val done = current
                current = next
                next = null
                segmentIndex++
                done?.let { listener.onSegmentSaved(store.publish(it)) }
            }

            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
            -> {
                // Rollover failed; the recorder stopped itself but the file is valid.
                autoStopped = true
                listener.onRecorderFailure("File size limit reached - recording stopped")
            }
        }
    }

    private fun segmentName(index: Int): String =
        if (index == 0) {
            "MNC_$takeStamp.mp4"
        } else {
            "MNC_${takeStamp}_${String.format(Locale.US, "%03d", index + 1)}.mp4"
        }

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /**
     * Order matters: sources -> output format -> encoders/params -> input surface.
     * RECORD_AUDIO is verified by the engine before start(); the probe uses no audio.
     */
    @SuppressLint("MissingPermission")
    private fun MediaRecorder.configure(
        cfg: VideoConfig,
        surface: Surface,
        withAudio: Boolean,
        orientationHint: Int,
    ) {
        if (withAudio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        setVideoEncoder(cfg.videoEncoder) // HEVC (H.265)
        setVideoSize(cfg.width, cfg.height)
        setVideoFrameRate(cfg.fps)
        setVideoEncodingBitRate(cfg.bitrateBps) // 100 Mbps (clamped to encoder max)
        if (cfg.encoderProfile != null && cfg.encoderLevel != null) {
            // 10-bit: HEVC Main10 so the HLG10 camera buffers are encoded without truncation.
            setVideoEncodingProfileLevel(cfg.encoderProfile, cfg.encoderLevel)
        }

        if (withAudio) {
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioChannels(2)
            setAudioEncodingBitRate(320_000)
        }

        setOrientationHint(orientationHint)
        setInputSurface(surface)
    }

    private companion object {
        const val TAG = "VideoRecorder"
        /** Just under 4 GiB: keeps every segment FAT32/exFAT and 32-bit-offset safe. */
        const val MAX_SEGMENT_BYTES = 3_900_000_000L
        val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    }
}
