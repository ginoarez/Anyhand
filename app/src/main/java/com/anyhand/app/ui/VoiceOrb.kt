package com.anyhand.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Orbe de voz (mockup + DESIGN.md §1, §5): círculo con barras blancas de
 * onda de voz y halo. Pulso mientras el agente trabaja; quieto en reposo
 * o si Android tiene las animaciones desactivadas. Todo con Canvas.
 */
@Composable
fun VoiceOrb(
    color: Color,
    active: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (active && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "orb")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        val fade by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Restart,
            ),
            label = "fade",
        )
        // Barras con vida: suben y bajan suave en onda, como voz real.
        // Sutil a propósito (se nota el movimiento, no el truco).
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        Canvas(modifier = modifier) {
            drawCircle(color.copy(alpha = fade),
                radius = size.minDimension / 2f * pulse)
            orbBody(color, phase = phase)
        }
    } else {
        Canvas(modifier = modifier) {
            orbBody(color, halo = false)
        }
    }
}

private fun DrawScope.orbBody(
    color: Color,
    halo: Boolean = true,
    phase: Float = 0f,
) {
    val r = size.minDimension / 2f
    if (halo) {
        drawCircle(color.copy(alpha = 0.12f), radius = r)
    }
    val inner = r * 0.72f
    drawCircle(color, radius = inner)
    // Barras de onda de voz (mockup): 7 barras blancas redondeadas.
    // Con fase, cada barra respira en onda (amplitud chica, sutil).
    val fractions = listOf(0.32f, 0.52f, 0.72f, 0.92f, 0.72f, 0.52f, 0.32f)
    val barW = inner * 0.13f
    val gap = inner * 0.09f
    val totalW = fractions.size * barW + (fractions.size - 1) * gap
    var x = size.width / 2f - totalW / 2f
    val white = Color.White
    fractions.forEachIndexed { index, fraction ->
        val scale = if (phase == 0f) 1f
        else 1f + 0.2f * sin(phase + index * 0.7f)
        val h = inner * 1.5f * fraction * scale
        drawRoundRect(
            color = white,
            topLeft = Offset(x, size.height / 2f - h / 2f),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
        )
        x += barW + gap
    }
}
