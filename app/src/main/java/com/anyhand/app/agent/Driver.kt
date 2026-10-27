package com.anyhand.app.agent

import android.graphics.Bitmap

/** Resultado de una acción del driver. */
data class ActionResult(
    val success: Boolean,
    val detail: String = "",
)

/**
 * Contrato del driver que ejecuta acciones sobre el teléfono.
 * El [AccessibilityDriverService] es la implementación real; el harness de PC
 * usa esta misma forma con adb (ver carpeta `harness/`).
 */
interface Driver {
    suspend fun tap(x: Float, y: Float): ActionResult
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): ActionResult
    suspend fun inputText(text: String): ActionResult
    suspend fun keyPress(key: String): ActionResult
    suspend fun screenshot(): Bitmap?
    /** Volcado de nodos accesibles (para apps CON etiquetas). Null si no hay raíz. */
    suspend fun dumpNodes(): String?
}

/**
 * Traduce nombres de teclas del modelo a códigos Android.
 * Punto de calibración del driver (ver AGENTS.md): si el modelo envía un
 * nombre no listado aquí, se reporta en [ActionResult.detail].
 */
object KeyMapper {
    fun toKeyCode(key: String): Int? = when (key.trim().lowercase()) {
        "enter", "intro", "return" -> android.view.KeyEvent.KEYCODE_ENTER
        "back", "atrás", "atras", "voltar" -> android.view.KeyEvent.KEYCODE_BACK
        "home", "inicio", "início" -> android.view.KeyEvent.KEYCODE_HOME
        "tab", "tabulador" -> android.view.KeyEvent.KEYCODE_TAB
        "delete", "del", "borrar", "apagar" -> android.view.KeyEvent.KEYCODE_DEL
        "space", "espacio", "espaço" -> android.view.KeyEvent.KEYCODE_SPACE
        else -> null
    }
}

/**
 * ConfirmationGate: decide si una acción necesita confirmación explícita
 * antes de ejecutarse. Toda acción irreversible la requiere.
 */
object ConfirmationGate {
    /** Palabras que marcan una tarea como irreversible (ES/EN/PT). */
    private val irreversibleKeywords = listOf(
        "enviar", "send", "enviar", "pagar", "pay", "pagar",
        "borrar", "delete", "excluir", "eliminar",
        "comprar", "buy", "comprar", "llamar", "call", "ligar",
    )

    fun requiresConfirmation(task: String, action: AgentAction): Boolean {
        if (action is AgentAction.KeyPress) return false
        val lower = task.lowercase()
        return irreversibleKeywords.any { lower.contains(it) }
    }
}
