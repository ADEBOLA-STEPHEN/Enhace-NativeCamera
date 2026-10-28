package com.mynativecamera.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = FontFamily.Default // Roboto / Roboto Flex on modern Android

val CineTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Base,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Base,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Base,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Base,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)

/** Small all-caps HUD labels ("ISO", "SHUTTER", "PRO BYPASS"). */
val HudLabel = TextStyle(
    fontFamily = Base,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 12.sp,
    letterSpacing = 1.4.sp,
)

/** Secondary HUD captions under a label. */
val HudCaption = TextStyle(
    fontFamily = Base,
    fontWeight = FontWeight.Medium,
    fontSize = 9.5.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.3.sp,
)

/**
 * Numeric readouts. Tabular figures ("tnum") keep digits fixed-width so values
 * don't jitter horizontally while a slider is dragged or the timecode runs.
 */
val HudReadout = TextStyle(
    fontFamily = Base,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum",
)

val HudReadoutSmall = HudReadout.copy(fontSize = 13.sp, lineHeight = 16.sp)

val Timecode = TextStyle(
    fontFamily = Base,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    letterSpacing = 1.sp,
    fontFeatureSettings = "tnum",
)
