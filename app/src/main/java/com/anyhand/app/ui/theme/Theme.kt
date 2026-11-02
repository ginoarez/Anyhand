package com.anyhand.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta de DESIGN.md §4 y §9. Sin morados por defecto.
val Ink = Color(0xFF14213D)
val Paper = Color(0xFFF5F7FA)
val Listening = Color(0xFF1F5BE0)
val Acting = Color(0xFFF2A33A)
val Confirm = Color(0xFFD6455D)
val SecondaryText = Color(0xFF3A4A66)
val Success = Color(0xFF2E7D32)

val NightPaper = Color(0xFF0F1A2E)
val NightInk = Color(0xFFF5F7FA)
val NightSecondary = Color(0xFFAEBBD3)
val NightListening = Color(0xFF3F73F0)
val NightCard = Color(0xFF17233B)
val NightCardBorder = Color(0xFF2A3A5C)

private val LightScheme = lightColorScheme(
    primary = Listening,
    secondary = Acting,
    error = Confirm,
    background = Paper,
    surface = Paper,
    onPrimary = Color.White,
    onSecondary = Ink,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = SecondaryText,
)

private val DarkScheme = darkColorScheme(
    primary = NightListening,
    secondary = Acting,
    error = Confirm,
    background = NightPaper,
    surface = NightCard,
    onPrimary = Color.White,
    onSecondary = Ink,
    onBackground = NightInk,
    onSurface = NightInk,
    onSurfaceVariant = NightSecondary,
    outline = NightCardBorder,
)

@Composable
fun AnyhandTheme(
    darkTheme: Boolean,
    // Sin color dinámico: la paleta propia manda (DESIGN.md §4).
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography,
        content = content
    )
}
