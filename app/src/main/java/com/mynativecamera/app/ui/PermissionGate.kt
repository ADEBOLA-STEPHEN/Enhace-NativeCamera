package com.mynativecamera.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mynativecamera.app.ui.theme.CineBlack
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.MyNativeCameraTheme
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.Navy900
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.RecordRed
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)

/**
 * Shows [content] only once CAMERA and RECORD_AUDIO are granted. Handles first request,
 * rationale, and the "don't ask again" case (deep link to app settings), and re-checks on
 * every resume so returning from Settings unlocks the camera immediately.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAllPermissions()) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        granted = results.values.all { it } && context.hasAllPermissions()
        if (!granted) {
            val activity = context.findActivity()
            // After a denial, no rationale => the system will no longer show the dialog.
            permanentlyDenied = activity != null && REQUIRED_PERMISSIONS.any { perm ->
                results[perm] == false && !activity.shouldShowRequestPermissionRationale(perm)
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = context.hasAllPermissions()
        if (!granted && !requestedOnce) {
            requestedOnce = true
            launcher.launch(REQUIRED_PERMISSIONS)
        }
    }

    if (granted) {
        content()
    } else {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onGrant = { launcher.launch(REQUIRED_PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Navy700.copy(alpha = 0.55f), Navy900.copy(alpha = 0.35f), CineBlack),
                    radius = 1400f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LensMark()
            Spacer(Modifier.height(20.dp))
            Text("PRO CINEMA CAMERA", style = HudLabel, color = NavyGlow)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Camera & microphone access",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (permanentlyDenied) {
                    "Access was denied. Enable Camera and Microphone in Settings to start shooting."
                } else {
                    "Direct sensor control needs the camera, and synced audio needs the microphone. " +
                        "Clips are saved to DCIM/MyNativeCamera - no storage permission required."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = if (permanentlyDenied) onOpenSettings else onGrant,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy700, contentColor = TextPrimary),
                    modifier = Modifier
                        .height(48.dp)
                        .border(1.dp, NavyGlow.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .testTag("grant_permissions"),
                ) {
                    Text(if (permanentlyDenied) "Open Settings" else "Grant Access", style = MaterialTheme.typography.labelLarge)
                }
                if (!permanentlyDenied) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.height(48.dp)) {
                        Text("App Settings", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun LensMark() {
    Box(
        modifier = Modifier
            .size(84.dp)
            .border(3.dp, TextPrimary, CircleShape)
            .padding(12.dp)
            .border(1.5.dp, Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).background(RecordRed, CircleShape))
    }
}

private fun Context.hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(widthDp = 891, heightDp = 411, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PermissionScreenPreview() {
    MyNativeCameraTheme {
        PermissionScreen(permanentlyDenied = false, onGrant = {}, onOpenSettings = {})
    }
}
