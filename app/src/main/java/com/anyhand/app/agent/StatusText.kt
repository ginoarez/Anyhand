package com.anyhand.app.agent

/**
 * Texto y color por estado del agente (DESIGN.md §3), en los tres idiomas.
 * Lógica pura para poder probarla; la UI suma el ícono.
 *
 * Colores (hex ARGB, paleta §4): el estado siempre lleva texto, nunca
 * solo color. TODO(i18n): mover estas cadenas a recursos es/en/pt.
 */
object StatusText {

    fun text(state: AgentState, lang: String): String {
        val es = lang == "es"
        val pt = lang == "pt"
        return when (state) {
            AgentState.IDLE -> if (es) "Listo" else if (pt) "Pronto" else "Ready"
            AgentState.LISTENING ->
                if (es) "Escuchando" else if (pt) "Ouvindo" else "Listening"
            AgentState.PLANNING ->
                if (es) "Mirando la pantalla" else if (pt) "Olhando a tela"
                else "Looking at the screen"
            AgentState.ACTING ->
                if (es) "Tocando" else if (pt) "Tocando" else "Tapping"
            AgentState.VERIFYING ->
                if (es) "Comprobando el resultado" else if (pt) "Verificando o resultado"
                else "Checking the result"
            AgentState.AWAITING_CONFIRMATION ->
                if (es) "Esperando tu confirmación" else if (pt) "Aguardando confirmação"
                else "Waiting for confirmation"
            AgentState.DONE ->
                if (es) "Listo" else if (pt) "Pronto" else "Done"
            AgentState.FAILED ->
                if (es) "Error" else if (pt) "Erro" else "Error"
        }
    }

    /** Subtítulo fijo bajo el estado ("Dime qué quieres hacer"). */
    fun hint(lang: String): String = when (lang) {
        "pt" -> "Diga o que fazer"
        "en" -> "Tell me what to do"
        else -> "Dime qué quieres hacer"
    }

    /** Color de la paleta §4 como ARGB. */
    fun colorHex(state: AgentState): Long = when (state) {
        AgentState.LISTENING -> 0xFF1F5BE0
        AgentState.PLANNING -> 0xFF3A4A66
        AgentState.ACTING -> 0xFFF2A33A
        AgentState.VERIFYING -> 0xFFF2A33A
        AgentState.AWAITING_CONFIRMATION -> 0xFFD6455D
        AgentState.DONE -> 0xFF2E7D32
        AgentState.FAILED -> 0xFFD6455D
        AgentState.IDLE -> 0xFF2E7D32
    }
}
