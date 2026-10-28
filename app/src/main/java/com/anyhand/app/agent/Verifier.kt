package com.anyhand.app.agent

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Verificador: compara la pantalla antes/después de cada acción.
 * Devuelve true si hubo un cambio visible suficiente.
 */
object Verifier {
    /**
     * Fracción de píxeles distintos entre dos capturas (0.0 a 1.0).
     * Compara como máximo [maxPixels] muestras para no bloquear la UI.
     */
    fun changeFraction(before: Bitmap, after: Bitmap, maxPixels: Int = 20_000): Double {
        val w = minOf(before.width, after.width)
        val h = minOf(before.height, after.height)
        if (w <= 0 || h <= 0) return 0.0
        val stepX = maxOf(1, w / 141)
        val stepY = maxOf(1, (w / stepX * h) / maxPixels + 1)
        var different = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                total++
                if (before.getPixel(x, y) != after.getPixel(x, y)) different++
                x += stepX
            }
            y += stepY
        }
        if (total == 0) return 0.0
        return different.toDouble() / total
    }

    fun changedEnough(before: Bitmap, after: Bitmap, threshold: Double = 0.005): Boolean =
        changeFraction(before, after) >= threshold

    /** Luminancia media 0..255, útil como chequeo barato de "algo cambió". */
    fun meanLuminance(bitmap: Bitmap): Double {
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, bitmap.width / 100)
        val stepY = maxOf(1, bitmap.height / 100)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }
}

/**
 * Planificador local por palabras clave (ES/EN/PT).
 *
 * STUB de demostración: genera un plan determinista contra la app de prueba
 * sin etiquetas. En el producto, este punto lo ocupa la herramienta
 * `computer` de Muse Spark vía Meta Model API; el stub existe solo para
 * calibrar el driver y la verificación sin gastar créditos.
 */
object StubPlanner {
    fun plan(task: String, screenW: Int, screenH: Int): List<AgentAction> {
        val lower = task.lowercase()
        val cx = screenW / 2f
        return when {
            containsAny(lower, "siguiente", "next", "próximo", "proximo", "avançar", "avancar") ->
                listOf(AgentAction.Tap(cx, screenH * 0.82f))
            containsAny(lower, "atrás", "atras", "back", "voltar") ->
                listOf(AgentAction.KeyPress("Back"))
            containsAny(lower, "ok", "terminar", "finish", "finalizar",
                "concluir", "fin", "done", "fim") ->
                listOf(AgentAction.Tap(cx, screenH * 0.82f))
            containsAny(lower, "escribe", "escribir", "type", "digite", "digitar", "escreva") ->
                listOf(AgentAction.Tap(cx, screenH * 0.35f), AgentAction.InputText(extractQuoted(task)))
            containsAny(lower, "arriba", "up", "cima", "subir", "scroll") ->
                listOf(AgentAction.Swipe(cx, screenH * 0.7f, cx, screenH * 0.3f, 300))
            containsAny(lower, "abajo", "down", "baixo", "descer") ->
                listOf(AgentAction.Swipe(cx, screenH * 0.3f, cx, screenH * 0.7f, 300))
            else -> listOf(AgentAction.Tap(cx, screenH * 0.5f))
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }

    /** Texto entre comillas en el comando; si no hay, un texto por defecto. */
    fun extractQuoted(task: String): String {
        val first = task.indexOf('"')
        val last = task.lastIndexOf('"')
        if (first >= 0 && last > first) return task.substring(first + 1, last)
        return "hola"
    }
}
