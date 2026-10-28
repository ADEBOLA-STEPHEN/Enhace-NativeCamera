package com.mynativecamera.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Core palette: Deep Black & Navy Blue (#0A2342 to #1A365D) ----
/** Strictly Deep Black (#000000) for backgrounds and negative space. */
val DeepSpaceBlack = Color(0xFF000000)
val CineBlack = DeepSpaceBlack

/** Navy Blue palette: #0A2342 to #1A365D for panels, cards, and active accents. */
val NavyBase = Color(0xFF0A2342) // #0A2342 deep navy base
val NavySurface = Color(0xFF0E2A4D) // Surface background
val NavyPanel = Color(0xFF13325B) // Floating panels & cards
val NavyAccent = Color(0xFF1A365D) // #1A365D active & accent state
val NavyActive = Color(0xFF1D4ED8) // Luminous Navy Blue for active pills and sliders
val NavyGlow = Color(0xFF2563EB) // Bright navy active glow

// Backwards-compatible aliases pointing strictly to Navy palette (All pink/magenta removed)
val DarkPurpleNavy = NavySurface
val DarkPurpleNavyTop = Color(0xD90E2A4D)
val DarkPurpleCard = NavyPanel
val Navy900 = NavyBase
val Navy800 = Color(0xFF071B33)
val Navy700 = NavyAccent

val VibrantPink = NavyActive
val VibrantPurple = NavyAccent
val AccentPinkPurple = NavyActive
val NeonPurpleGlow = NavyGlow

// ---- Text -----------------------------------------------------------------------------------
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF64748B)

// ---- Semantic -------------------------------------------------------------------------------
/** Universal tally light red - reserved strictly for recording state. */
val RecordRed = Color(0xFFDC2626)
val PhotoWhite = Color(0xFFFFFFFF)
val WarningAmber = Color(0xFFFFB547)
val ReadyGreen = Color(0xFF3DDC97)
val ElectricCyan = Color(0xFF38BDF8) // Clean ice blue

// ---- Surfaces / glass ----------------------------------------------------------------------
val PanelNavy = NavySurface.copy(alpha = 0.92f)
val PanelNavyTop = NavyBase.copy(alpha = 0.85f)
val GlassBlack = Color(0xE6050F1D)
val Hairline = Color(0x401A365D)
