package com.mynativecamera.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mynativecamera.app.camera.LensOption
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.NavyAccent
import com.mynativecamera.app.ui.theme.NavyActive
import com.mynativecamera.app.ui.theme.NavySurface
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary

/**
 * Premium glassmorphic Multi-Lens switcher capsule (.5x, 1x, 2x, 3x)
 * mapping seamlessly to physical backing sensor IDs and zoom ratios in Navy Blue.
 */
@Composable
fun LensSwitcher(
    activeLens: LensOption,
    onLensSelected: (LensOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(NavySurface.copy(alpha = 0.90f))
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            LensOption.UltraWide to ".5",
            LensOption.Wide to "1x",
            LensOption.TwoX to "2x",
            LensOption.Telephoto to "3x",
        ).forEach { (lens, label) ->
            val isSelected = activeLens == lens
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.10f else 1.0f,
                animationSpec = spring(dampingRatio = 0.65f),
                label = "lensScale",
            )
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) NavyActive else Color.Transparent,
                label = "lensBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color.White else TextSecondary,
                label = "lensText",
            )

            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(
                        1.dp,
                        if (isSelected) NavyAccent else Color.Transparent,
                        CircleShape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onLensSelected(lens) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                )
            }
        }
    }
}
