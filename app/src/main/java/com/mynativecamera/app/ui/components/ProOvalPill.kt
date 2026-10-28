package com.mynativecamera.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.NavyAccent
import com.mynativecamera.app.ui.theme.NavyActive
import com.mynativecamera.app.ui.theme.NavySurface
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary

/**
 * Sleek, cinematic pill button styled in Navy Blue (#0A2342 to #1A365D).
 * Tapping toggles its corresponding manual slider modal.
 */
@Composable
fun ProOvalPill(
    label: String,
    valueText: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) NavyActive else Hairline,
        animationSpec = tween(180),
        label = "pillBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isActive) NavyAccent.copy(alpha = 0.95f) else NavySurface.copy(alpha = 0.85f),
        animationSpec = tween(180),
        label = "pillBg",
    )

    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) NavyActive else TextSecondary,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = valueText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.5f),
        )
    }
}
