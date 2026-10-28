package com.mynativecamera.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mynativecamera.app.ui.CameraUiState
import com.mynativecamera.app.ui.CaptureMode
import com.mynativecamera.app.ui.theme.DeepSpaceBlack
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.NavyAccent
import com.mynativecamera.app.ui.theme.NavyActive
import com.mynativecamera.app.ui.theme.NavySurface
import com.mynativecamera.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Redesigned Cinema Bottom Deck:
 * - Above Shutter: Clean icon toggle for Photo / Video mode.
 * - Center: The main Red Record button (Video) or White Shutter button (Photo).
 * - Left side of Shutter: Camera Flip icon button (Icons.Default.FlipCameraAndroid) to toggle Front/Back cameras.
 * - Right side of Shutter: Circular Image Thumbnail showing last taken photo/video.
 *   When tapped, fires an Intent(Intent.ACTION_VIEW) with the file's URI to open the native Gallery app.
 */
@Composable
fun ControlPanel(
    state: CameraUiState,
    onRecordClick: () -> Unit,
    onPhotoClick: () -> Unit,
    onModeChange: (CaptureMode) -> Unit,
    onFlipCamera: () -> Unit,
    onThumbnailClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DeepSpaceBlack)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Above Shutter: Clean icon toggle for Photo / Video mode
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(NavySurface)
                .border(1.dp, NavyAccent, CircleShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Photo Mode Toggle
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (state.captureMode == CaptureMode.Photo) NavyActive else Color.Transparent)
                    .clickable(enabled = !state.isRecording) { onModeChange(CaptureMode.Photo) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Photo Mode",
                        tint = if (state.captureMode == CaptureMode.Photo) Color.White else TextSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "PHOTO",
                        style = HudLabel,
                        fontSize = 11.sp,
                        color = if (state.captureMode == CaptureMode.Photo) Color.White else TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Video Mode Toggle
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (state.captureMode == CaptureMode.Video) NavyActive else Color.Transparent)
                    .clickable(enabled = !state.isRecording) { onModeChange(CaptureMode.Video) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video Mode",
                        tint = if (state.captureMode == CaptureMode.Video) Color.White else TextSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "VIDEO",
                        style = HudLabel,
                        fontSize = 11.sp,
                        color = if (state.captureMode == CaptureMode.Video) Color.White else TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. Main Shutter Deck Row: Flip Camera (Left) | Shutter / Record (Center) | Thumbnail (Right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Camera Flip icon button (Icons.Default.FlipCameraAndroid)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                IconButton(
                    onClick = onFlipCamera,
                    enabled = !state.isRecording,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(NavySurface)
                        .border(1.5.dp, NavyAccent, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Flip Front/Back Camera",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // Center: The main Red Record button (Video) or White Shutter button (Photo)
            Box(
                contentAlignment = Alignment.Center,
            ) {
                if (state.captureMode == CaptureMode.Photo) {
                    PhotoShutterButton(
                        enabled = state.recordEnabled,
                        onClick = onPhotoClick,
                    )
                } else {
                    RecordButton(
                        isRecording = state.isRecording,
                        enabled = state.recordEnabled,
                        onClick = onRecordClick,
                    )
                }
            }

            // Right: Circular Image Thumbnail showing the last taken photo/video
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                MediaThumbnailButton(
                    mediaUri = state.lastCapturedMediaUri,
                    onClick = onThumbnailClick,
                    enabled = !state.isRecording,
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnailButton(
    mediaUri: Uri?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = mediaUri) {
        if (mediaUri == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(mediaUri, Size(128, 128), null)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(NavySurface)
            .border(1.5.dp, NavyAccent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = bitmapState.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Last Captured Media",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Gallery",
                tint = TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
