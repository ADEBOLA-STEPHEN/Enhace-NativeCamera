package com.mynativecamera.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

enum class AudioSourceType {
    BuiltIn,
    Usb,
    Bluetooth
}

data class AudioMeterData(
    val peakNormalized: Float = 0f, // 0.0f .. 1.0f
    val peakDb: Float = -60f,       // -60 dBFS .. 0 dBFS
    val rmsDb: Float = -60f,
    val isClipping: Boolean = false,
    val activeSource: AudioSourceType = AudioSourceType.BuiltIn,
    val availableSources: List<AudioSourceType> = listOf(AudioSourceType.BuiltIn),
    val lowCutEnabled: Boolean = false,
)

/**
 * Pro Audio Engine:
 * - Real-time AudioRecord metering (peak, RMS, clipping detection).
 * - Hardware NoiseSuppressor low-cut/noise filter activation.
 * - Hardware audio routing to Built-in Mic, USB Mic, or Bluetooth SCO.
 * - Clipping monitoring and gain limiter warning.
 */
class ProAudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _meterState = MutableStateFlow(AudioMeterData())
    val meterState: StateFlow<AudioMeterData> = _meterState.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingJob: Job? = null

    private var currentSource = AudioSourceType.BuiltIn
    private var lowCutActive = false

    @Volatile
    private var isMonitoring = false

    init {
        refreshAvailableSources()
    }

    fun refreshAvailableSources() {
        val sources = mutableListOf(AudioSourceType.BuiltIn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                        if (!sources.contains(AudioSourceType.Usb)) sources.add(AudioSourceType.Usb)
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        if (!sources.contains(AudioSourceType.Bluetooth)) sources.add(AudioSourceType.Bluetooth)
                    }
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            if (!sources.contains(AudioSourceType.Bluetooth)) sources.add(AudioSourceType.Bluetooth)
                        }
                    }
                }
            }
        }
        _meterState.update { it.copy(availableSources = sources) }
    }

    fun setAudioSource(source: AudioSourceType) {
        if (currentSource == source) return
        currentSource = source
        _meterState.update { it.copy(activeSource = source) }
        applyHardwareAudioRouting(source)
        restartMonitoring()
    }

    private fun applyHardwareAudioRouting(source: AudioSourceType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredDev = getPreferredAudioDeviceInfo()
            if (preferredDev != null && (source == AudioSourceType.Bluetooth || source == AudioSourceType.Usb)) {
                try {
                    audioManager.setCommunicationDevice(preferredDev)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setCommunicationDevice", e)
                }
            } else {
                try {
                    audioManager.clearCommunicationDevice()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clearCommunicationDevice", e)
                }
            }
        } else {
            if (source == AudioSourceType.Bluetooth) {
                try {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to startBluetoothSco", e)
                }
            } else {
                try {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stopBluetoothSco", e)
                }
            }
        }
    }

    fun setLowCut(enabled: Boolean) {
        lowCutActive = enabled
        _meterState.update { it.copy(lowCutEnabled = enabled) }
        try {
            noiseSuppressor?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Cannot update NoiseSuppressor enabled state", e)
        }
    }

    fun getPreferredAudioDeviceInfo(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return when (currentSource) {
            AudioSourceType.Usb -> devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            AudioSourceType.Bluetooth -> devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            AudioSourceType.BuiltIn -> devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
        }
    }

    fun pauseForRecording() {
        stopMonitoring()
    }

    fun resumeAfterRecording() {
        startMonitoring()
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioRecord for monitoring", e)
                return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return@launch
            }

            // Route to preferred hardware mic if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getPreferredAudioDeviceInfo()?.let { dev ->
                    record.setPreferredDevice(dev)
                }
            }

            // Attach NoiseSuppressor for low-cut / rumble filtering
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = lowCutActive
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor creation failed", e)
                }
            }

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start AudioRecord", e)
                record.release()
                return@launch
            }

            audioRecord = record
            val shortBuffer = ShortArray(bufferSize / 2)

            while (isActive && isMonitoring) {
                val read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    var maxAmp = 0
                    var sumSq = 0.0

                    for (i in 0 until read) {
                        val sample = shortBuffer[i].toInt()
                        val abs = kotlin.math.abs(sample)
                        if (abs > maxAmp) maxAmp = abs
                        sumSq += sample.toDouble() * sample.toDouble()
                    }

                    val peakNorm = (maxAmp / 32767.0f).coerceIn(0f, 1f)
                    val rms = sqrt(sumSq / read) / 32767.0f
                    val peakDb = if (peakNorm > 0.00001f) (20.0 * log10(peakNorm.toDouble())).toFloat() else -60f
                    val rmsDb = if (rms > 0.00001f) (20.0 * log10(rms.toDouble())).toFloat() else -60f

                    // Clipping threshold: > -0.2 dBFS (or amplitude > 32400)
                    val isClipping = maxAmp >= 32400

                    _meterState.update {
                        it.copy(
                            peakNormalized = peakNorm,
                            peakDb = peakDb.coerceIn(-60f, 0f),
                            rmsDb = rmsDb.coerceIn(-60f, 0f),
                            isClipping = isClipping,
                        )
                    }
                }
            }

            try {
                record.stop()
            } catch (_: Exception) {}
            record.release()
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun restartMonitoring() {
        if (isMonitoring) {
            stopMonitoring()
            startMonitoring()
        }
    }

    fun release() {
        stopMonitoring()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ProAudioEngine"
    }
}
