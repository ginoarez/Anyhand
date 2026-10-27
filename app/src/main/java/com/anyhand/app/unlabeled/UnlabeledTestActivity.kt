package com.anyhand.app.unlabeled

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anyhand.app.ui.theme.AnyhandTheme

/**
 * App de prueba SIN etiquetas de accesibilidad (construida durante el evento).
 *
 * Todo está dibujado en un [Canvas]: no hay `contentDescription`, ni textos
 * de Compose, ni nodos útiles en el árbol de accesibilidad. La línea base con
 * árbol de accesibilidad falla aquí; el agente por visión funciona.
 *
 * Pantallas: 0 = inicio, 1 = formulario, 2 = fin.
 * Criterio de éxito sin ambigüedad por tarea en [TestState.isTaskDone].
 */
object TestState {
    var screen by mutableIntStateOf(0)
    var taps by mutableIntStateOf(0)
    var enteredText by mutableStateOf("")

    fun reset() {
        screen = 0
        taps = 0
        enteredText = ""
    }

    fun isTaskDone(task: String): Boolean {
        val lower = task.lowercase()
        return when {
            containsAny(lower, "siguiente", "next", "próximo", "proximo", "avançar", "avancar") ->
                screen >= 1
            containsAny(lower, "escribe", "escribir", "type", "digite", "escreva") ->
                enteredText.isNotEmpty()
            containsAny(lower, "fin", "done", "fim", "terminar") -> screen >= 2
            else -> false
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }
}

class UnlabeledTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TestState.reset()
        setContent {
            AnyhandTheme {
                UnlabeledScreen()
            }
        }
    }
}

@Composable
fun UnlabeledScreen() {
    when (TestState.screen) {
        0 -> HomeCanvas()
        1 -> FormCanvas()
        else -> DoneCanvas()
    }
}

/**
 * Texto pintado como píxeles: lo ve la persona y lo ve el modelo de visión,
 * pero NO crea nodos de accesibilidad. La pantalla sigue "sin etiquetas".
 */
private fun DrawScope.drawLabel(text: String, cx: Float, cy: Float, sizePx: Float) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = sizePx
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, cx, cy + sizePx * 0.35f, paint)
}

/** Inicio: zona central (contador) y botón inferior (siguiente), todo dibujado. */
@Composable
private fun HomeCanvas() {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    TestState.taps++
                    val h = size.height.toFloat()
                    if (offset.y > h * 0.70f) {
                        TestState.screen = 1
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF101418))
        // Círculo contador (sin etiqueta).
        drawCircle(
            color = Color(0xFF7C4DFF),
            radius = w * 0.22f,
            center = Offset(w / 2f, h * 0.38f),
        )
        // Botón "siguiente" dibujado abajo (sin etiqueta).
        drawRoundRect(
            color = Color(0xFF03DAC6),
            topLeft = Offset(w * 0.15f, h * 0.72f),
            size = Size(w * 0.70f, h * 0.10f),
            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
        )
        drawLabel("toca aquí", w / 2f, h * 0.38f, 44.dp.toPx())
        drawLabel("siguiente", w / 2f, h * 0.77f, 52.dp.toPx())
    }
}

/**
 * Formulario: campo de texto real sin etiqueta + botón dibujado "ok".
 * El campo se deja como nodo editable para que `inputText` tenga dónde
 * escribir; los botones siguen sin etiquetas.
 */
@Composable
private fun FormCanvas() {
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        TestState.taps++
                        val h = size.height.toFloat()
                        if (offset.y > h * 0.70f) {
                            TestState.screen = 2
                        }
                    }
                },
        ) {
            // NOTE: keep in sync with the overlay column below.
            val w = size.width
            val h = size.height
            drawRect(Color(0xFF101418))
            // Recuadro del campo (el BasicTextField va encima, invisible al árbol con nombre).
            drawRoundRect(
                color = Color(0xFF2A2F3A),
                topLeft = Offset(w * 0.10f, h * 0.28f),
                size = Size(w * 0.80f, h * 0.09f),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF03DAC6),
                topLeft = Offset(w * 0.15f, h * 0.72f),
                size = Size(w * 0.70f, h * 0.10f),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
            )
            drawLabel("escribe aquí", w / 2f, h * 0.325f, 44.dp.toPx())
            drawLabel("ok", w / 2f, h * 0.77f, 52.dp.toPx())
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.28f),
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.09f),
            ) {
                BasicTextField(
                    value = TestState.enteredText,
                    onValueChange = { TestState.enteredText = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.63f),
            )
        }
    }
}

/** Pantalla final: círculo verde dibujado. */
@Composable
private fun DoneCanvas() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Color(0xFF101418))
        drawCircle(
            color = Color(0xFF4CAF50),
            radius = size.width * 0.25f,
            center = Offset(size.width / 2f, size.height * 0.45f),
        )
        drawLabel("fin", size.width / 2f, size.height * 0.45f, 52.dp.toPx())
    }
}
