package com.mynativecamera.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CineColorScheme = darkColorScheme(
    primary = Navy700,
    onPrimary = TextPrimary,
    primaryContainer = Navy900,
    onPrimaryContainer = TextPrimary,
    secondary = NavyGlow,
    onSecondary = TextPrimary,
    background = CineBlack,
    onBackground = TextPrimary,
    surface = CineBlack,
    onSurface = TextPrimary,
    surfaceVariant = Navy900,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    outlineVariant = Hairline,
    error = RecordRed,
    onError = TextPrimary,
)

/**
 * The app is always dark: a camera UI must never flash white in a dark venue,
 * so the system light/dark setting and dynamic color are deliberately ignored.
 */
@Composable
fun MyNativeCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CineColorScheme,
        typography = CineTypography,
        content = content,
    )
}
