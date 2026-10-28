package com.mynativecamera.app.ui.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Tactile feedback manager delivering gentle micro-vibrations for slider ticks,
 * button presses, lens switches, tap-to-focus lock, and record start/stop.
 * Designed to feel crisp and refined without overwhelming device vibration motors.
 */
class ProHaptics(context: Context) {
    var isHapticsEnabled: Boolean = true

    private var lastTickMs = 0L

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Subtle mechanical tick when sliding past exposure / kelvin / focus stops (rate-limited) */
    fun tick(view: View? = null) {
        if (!isHapticsEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickMs < 50) return
        lastTickMs = now

        val handled = view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) ?: false
        if (!handled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(6)
            }
        }
    }

    /** Crisp click when tapping oval pills, modal toggles, or lens switcher buttons */
    fun click(view: View? = null) {
        if (!isHapticsEnabled) return
        val handled = view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) ?: false
        if (!handled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12)
            }
        }
    }

    /** Refined mechanical shutter release feel when capturing a still photo */
    fun shutterRelease(view: View? = null) {
        if (!isHapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(25)
        }
    }

    /** Gentle double pulse when starting recording */
    fun recordStart() {
        if (!isHapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 25, 50, 30)
            val amplitudes = intArrayOf(0, 140, 0, 200)
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 20, 40, 25), -1)
        }
    }

    /** Single definitive vibration when stopping recording */
    fun recordStop() {
        if (!isHapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(35)
        }
    }

    /** Quick single pulse exclusively on explicit tap-to-focus confirmation */
    fun focusLocked() {
        if (!isHapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(12)
        }
    }
}

