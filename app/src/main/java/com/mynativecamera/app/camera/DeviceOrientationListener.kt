package com.mynativecamera.app.camera

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface

/**
 * Tracks the physical device rotation using the accelerometer so video recordings
 * and still photo captures always know the true gravity orientation, even when
 * MainActivity is locked to portrait mode.
 */
class DeviceOrientationListener(context: Context) : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {

    @Volatile
    var currentRotationDegrees: Int = 0
        private set

    override fun onOrientationChanged(orientation: Int) {
        if (orientation == ORIENTATION_UNKNOWN) return

        // Map sensor continuous angles (0-360) to 0, 90, 180, 270 degrees
        currentRotationDegrees = when (orientation) {
            in 45..134 -> 270  // Landscape turned right
            in 135..224 -> 180 // Portrait upside down
            in 225..314 -> 90  // Landscape turned left
            else -> 0          // Portrait upright (315..360 or 0..44)
        }
    }

    companion object {
        /**
         * Calculates the correct orientation hint for MediaRecorder.setOrientationHint()
         * based on camera sensor orientation and physical device rotation.
         */
        fun computeOrientationHint(
            sensorOrientation: Int,
            deviceRotationDegrees: Int,
            isFrontFacing: Boolean,
        ): Int {
            return if (isFrontFacing) {
                (sensorOrientation + deviceRotationDegrees) % 360
            } else {
                (sensorOrientation - deviceRotationDegrees + 360) % 360
            }
        }
    }
}
