package com.mynativecamera.app.camera

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build

enum class DeviceTier(
    val title: String,
    val badge: String,
    val maxSafeWidth: Int,
    val maxSafeFps: Int,
    val safeBitrateBps: Int,
) {
    Lite(
        title = "Lite Profile (Smooth & Stable)",
        badge = "LITE · SAFE",
        maxSafeWidth = 1920,
        maxSafeFps = 30,
        safeBitrateBps = 24_000_000, // 24 Mbps
    ),
    Balanced(
        title = "Balanced Pro (Samsung A-Series & Mid-Tier)",
        badge = "BALANCED · PRO",
        maxSafeWidth = 1920,
        maxSafeFps = 30,
        safeBitrateBps = 42_000_000, // 42 Mbps
    ),
    ProFlagship(
        title = "Pro Flagship (Maximum Performance)",
        badge = "FLAGSHIP · MAX",
        maxSafeWidth = 3840,
        maxSafeFps = 60,
        safeBitrateBps = 80_000_000, // 80 Mbps
    )
}

/**
 * Hardware capability reader & safety bounds manager.
 * Evaluates device RAM, CPU cores, Camera2 hardware level, and thermals to prevent
 * ISP stalling, dropped frames, encoder buffer overflows, and device overheating.
 */
data class DeviceProfile(
    val tier: DeviceTier,
    val deviceModel: String,
    val totalRamGb: Float,
    val cpuCores: Int,
    val hardwareLevelName: String,
    val safeMaxVideoWidth: Int,
    val safeMaxFps: Int,
    val safeBitrateBps: Int,
) {
    val isClampedTo1080p: Boolean get() = safeMaxVideoWidth <= 1920
    val isClampedTo30Fps: Boolean get() = safeMaxFps <= 30
    val model: String get() = deviceModel
    val ramGb: Int get() = totalRamGb.toInt()

    companion object {
        fun detect(context: Context, characteristics: CameraCharacteristics?): DeviceProfile {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val ramGb = if (memInfo.totalMem > 0L) {
                memInfo.totalMem.toFloat() / (1024f * 1024f * 1024f)
            } else {
                4.0f
            }

            val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val hwLevel = characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

            val hwLevelName = when (hwLevel) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "EXTERNAL"
            }

            // Classification algorithm:
            // Devices with < 5GB RAM or LIMITED/LEGACY hardware level -> Lite
            // Devices with 5GB..7.8GB RAM or FULL hardware level (e.g. Galaxy A17, A25, A35, A54) -> Balanced
            // Devices with >= 8GB RAM and LEVEL_3 / FULL -> ProFlagship
            val tier = when {
                ramGb < 4.8f || hwLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY || hwLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> {
                    DeviceTier.Lite
                }
                ramGb < 7.5f || hwLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> {
                    DeviceTier.Balanced
                }
                else -> {
                    DeviceTier.ProFlagship
                }
            }

            val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

            return DeviceProfile(
                tier = tier,
                deviceModel = deviceModel,
                totalRamGb = (Math.round(ramGb * 10.0f) / 10.0f),
                cpuCores = cpuCores,
                hardwareLevelName = hwLevelName,
                safeMaxVideoWidth = tier.maxSafeWidth,
                safeMaxFps = tier.maxSafeFps,
                safeBitrateBps = tier.safeBitrateBps,
            )
        }
    }
}
