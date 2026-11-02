package com.anyhand.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.anyhand.app.R
import com.anyhand.app.agent.AgentState

/**
 * Íconos Lucide (DESIGN.md §10) importados como VectorDrawable. Todo el
 * cambio de iconos vive en este archivo.
 */
@DrawableRes
fun stateDrawable(state: AgentState): Int = when (state) {
    AgentState.LISTENING -> R.drawable.ic_lucide_mic
    AgentState.PLANNING -> R.drawable.ic_lucide_eye
    AgentState.ACTING -> R.drawable.ic_lucide_mouse_pointer_click
    AgentState.VERIFYING -> R.drawable.ic_lucide_scan_text
    AgentState.AWAITING_CONFIRMATION -> R.drawable.ic_lucide_hand
    AgentState.DONE -> R.drawable.ic_lucide_circle_check
    AgentState.FAILED -> R.drawable.ic_lucide_badge_alert
    AgentState.IDLE -> R.drawable.ic_lucide_circle_check
}

@Composable
fun StateIcon(
    state: AgentState,
    color: Color,
    modifier: Modifier = Modifier,
    // TODO(i18n): contentDescription por idioma desde recursos.
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(stateDrawable(state)),
        contentDescription = contentDescription,
        tint = color,
        modifier = modifier,
    )
}
