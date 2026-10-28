package com.mynativecamera.app.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.mynativecamera.app.ui.theme.CineBlack
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.Navy900
import com.mynativecamera.app.ui.theme.RecordRed

/**
 * Full-screen viewfinder.
 *
 * A [TextureView] (not SurfaceView) is used because it composes correctly with Compose
 * overlays (glass panels, alpha, animation) and supports a transform matrix, which the
 * engine uses to correct sensor orientation / aspect without stretching.
 *
 * The preview is letterboxed/pillarboxed to [aspectRatio] (the recording aspect) so what
 * you see is exactly what is recorded - critical for cinema framing.
 */
@Composable
fun Viewfinder(
    aspectRatio: Float,
    showGrid: Boolean,
    isRecording: Boolean,
    onSurfaceAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onSurfaceSizeChanged: (SurfaceTexture, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    isFrontCamera: Boolean = false,
    focusTargetPoint: Offset? = null,
    focusLocked: Boolean = false,
    onTapToFocus: ((Offset, Float, Float) -> Unit)? = null,
) {
    // The TextureView listener is created once in `factory`; rememberUpdatedState makes it
    // always invoke the latest lambdas without recreating the view (which would kill the stream).
    val latestAvailable by rememberUpdatedState(onSurfaceAvailable)
    val latestSizeChanged by rememberUpdatedState(onSurfaceSizeChanged)
    val latestDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val latestTapToFocus by rememberUpdatedState(onTapToFocus)

    val tallyAlpha by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "tallyAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CineBlack),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .testTag("viewfinder")
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        latestTapToFocus?.invoke(offset, size.width.toFloat(), size.height.toFloat())
                    }
                },
        ) {
            if (LocalInspectionMode.current) {
                // Compose @Preview cannot host a live TextureView - draw a stand-in frame.
                PreviewStandIn(Modifier.fillMaxSize())
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        TextureView(context).apply {
                            isOpaque = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture, width: Int, height: Int,
                                ) {
                                    applyTransform(this@apply, width, height, aspectRatio, isFrontCamera)
                                    latestAvailable(surface, width, height)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture, width: Int, height: Int,
                                ) {
                                    applyTransform(this@apply, width, height, aspectRatio, isFrontCamera)
                                    latestSizeChanged(surface, width, height)
                                }

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    latestDestroyed()
                                    return true // let the TextureView release the SurfaceTexture
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { textureView ->
                        if (textureView.isAvailable) {
                            applyTransform(textureView, textureView.width, textureView.height, aspectRatio, isFrontCamera)
                        }
                    },
                )
            }

            if (showGrid) FrameGuides(Modifier.fillMaxSize())

            // Cinema tap-to-focus and metering reticle
            FocusReticle(targetPoint = focusTargetPoint, isLocked = focusLocked)

            // Red tally border while recording (like a cinema monitor).
            if (tallyAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, RecordRed.copy(alpha = 0.85f * tallyAlpha)),
                )
            }
        }
    }
}

/** Rule-of-thirds lines plus a small center cross. */
@Composable
private fun FrameGuides(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val line = Color.White.copy(alpha = 0.22f)
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, h), stroke)
            drawLine(line, Offset(0f, y), Offset(w, y), stroke)
        }
        val c = Offset(w / 2f, h / 2f)
        val arm = 10.dp.toPx()
        val cross = Color.White.copy(alpha = 0.55f)
        drawLine(cross, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke)
        drawLine(cross, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke)
    }
}

@Composable
private fun PreviewStandIn(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Navy700, Navy900, CineBlack)),
        ),
    )
}

private fun applyTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    aspectRatio: Float = 4f / 3f,
    isFrontCamera: Boolean = false,
) {
    if (viewWidth == 0 || viewHeight == 0) return
    val context = textureView.context
    val rotation = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // Calculate buffer aspect ratio based on orientation (sensor is natively landscape 4:3 or 16:9)
    val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val rawAspect = if (aspectRatio > 0f) aspectRatio else (4f / 3f)
    val bufferAspect = if (isLandscape) {
        if (rawAspect > 1f) rawAspect else 1f / rawAspect
    } else {
        if (rawAspect > 1f) 1f / rawAspect else rawAspect
    }

    // View aspect ratio of the TextureView on screen
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val scaleX: Float
    val scaleY: Float
    if (viewAspect < bufferAspect) {
        // Screen is taller than buffer: expand width so height covers cleanly (center-crop)
        scaleX = bufferAspect / viewAspect
        scaleY = 1f
    } else {
        // Screen is wider than buffer: expand height so width covers cleanly (center-crop)
        scaleX = 1f
        scaleY = viewAspect / bufferAspect
    }

    if (isLandscape) {
        val bufferRect = RectF(0f, 0f, viewHeight.toFloat(), viewWidth.toFloat())
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        matrix.postScale(scaleX, scaleY, centerX, centerY)
        matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else {
        if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        matrix.postScale(scaleX, scaleY, centerX, centerY)
    }

    if (isFrontCamera) {
        // Natural mirror flip for selfie/front camera view
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    // Apply the center-crop scale and rotation transform matrix to the TextureView
    textureView.setTransform(matrix)
}
