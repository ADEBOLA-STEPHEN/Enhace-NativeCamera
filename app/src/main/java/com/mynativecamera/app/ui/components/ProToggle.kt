package com.mynativecamera.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynativecamera.app.ui.theme.Hairline
import com.mynativecamera.app.ui.theme.HudCaption
import com.mynativecamera.app.ui.theme.HudLabel
import com.mynativecamera.app.ui.theme.Navy700
import com.mynativecamera.app.ui.theme.NavyGlow
import com.mynativecamera.app.ui.theme.TextPrimary
import com.mynativecamera.app.ui.theme.TextSecondary

private val ToggleShape = RoundedCornerShape(12.dp)

/**
 * Compact labelled switch for the control panel. The whole pill is the touch target
 * (48dp+ wide, 44dp tall) and exposes Role.Switch to accessibility services.
 */
@Composable
fun ProToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = title,
) {
    val container by animateColorAsState(
        targetValue = if (checked) Navy700 else Color.White.copy(alpha = 0.06f),
        animationSpec = tween(200),
        label = "toggleContainer",
    )
    val outline by animateColorAsState(
        targetValue = if (checked) NavyGlow.copy(alpha = 0.75f) else Hairline,
        animationSpec = tween(200),
        label = "toggleOutline",
    )

    Row(
        modifier = modifier
            .width(212.dp)
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(ToggleShape)
            .background(container)
            .border(1.dp, outline, ToggleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag("toggle_$testTag")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HudLabel, color = TextPrimary, maxLines = 1)
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = HudCaption,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        MiniSwitch(checked)
    }
}

@Composable
private fun MiniSwitch(checked: Boolean) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 14.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "switchThumb",
    )
    val track by animateColorAsState(
        targetValue = if (checked) NavyGlow else Color.White.copy(alpha = 0.2f),
        animationSpec = tween(200),
        label = "switchTrack",
    )
    Box(
        Modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(CircleShape)
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(14.dp)
                .background(Color.White, CircleShape),
        )
    }
}
