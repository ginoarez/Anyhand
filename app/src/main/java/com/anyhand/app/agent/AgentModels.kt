package com.anyhand.app.agent

/** Acciones que el agente puede ejecutar sobre el teléfono. */
sealed interface AgentAction {
    data class Tap(val x: Float, val y: Float) : AgentAction
    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Int = 300,
    ) : AgentAction
    data class InputText(val text: String) : AgentAction
    /** Nombre de tecla tal como lo envía el modelo (p. ej. "Enter", "Back"). */
    data class KeyPress(val key: String) : AgentAction
    data class Scroll(val dx: Float, val dy: Float) : AgentAction
}

/** Estado del ciclo del agente (voz -> acción -> verificación). */
enum class AgentState {
    IDLE,
    LISTENING,
    PLANNING,
    AWAITING_CONFIRMATION,
    ACTING,
    VERIFYING,
    DONE,
    FAILED,
}

/** Un paso ejecutado, con métricas para runs.jsonl. */
data class AgentStep(
    val index: Int,
    val action: AgentAction,
    val latencyMs: Long,
    val tokensUsed: Int = 0,
    val success: Boolean,
    val detail: String = "",
)

/** Registro de una ejecución completa. */
data class AgentRun(
    val task: String,
    val language: String,
    val steps: List<AgentStep>,
    val success: Boolean,
) {
    /** Una línea JSONL con el resumen de la ejecución. */
    fun toJsonLine(): String {
        val stepsJson = steps.joinToString(",") {
            """{"i":${it.index},"ok":${it.success},"ms":${it.latencyMs},"tok":${it.tokensUsed}}"""
        }
        val taskEscaped = task.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"task":"$taskEscaped","lang":"$language","success":$success,"steps":[$stepsJson]}"""
    }
}
