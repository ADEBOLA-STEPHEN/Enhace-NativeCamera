package com.mynativecamera.app

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mynativecamera.app.ui.CameraRoute
import com.mynativecamera.app.ui.PermissionGate
import com.mynativecamera.app.ui.theme.MyNativeCameraTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            if (!OpenCVLoader.initLocal()) {
                OpenCVLoader.initDebug()
            }
            Log.i("MainActivity", "OpenCV initialized successfully")
        } catch (e: Throwable) {
            Log.w("MainActivity", "OpenCV initialization note", e)
        }

        // A camera must never dim/sleep mid-take.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            MyNativeCameraTheme {
                PermissionGate {
                    CameraRoute()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-enter immersive mode after dialogs / notification shade.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
