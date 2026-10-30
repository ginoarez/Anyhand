package com.anyhand.app.agent

/**
 * Palabra de activación por defecto (§7 DESIGN.md).
 * La pronunciación cambia por región e idioma ("ani-jand", "eni-jand",
 * "any-hand"): el detector acepta variantes, no coincidencia exacta.
 * El usuario puede cambiarla en Ajustes (se guarda en el teléfono).
 */
const val WAKE_WORD = "Anyhand"

fun normalizeSpeech(s: String): String =
    s.lowercase()
        .replace("á", "a").replace("é", "e").replace("í", "i")
        .replace("ó", "o").replace("ú", "u").replace("ñ", "n")

/**
 * Variantes aceptadas de la palabra: forma pegada, forma con espacio,
 * parcial sin la última letra y (para Anyhand) las regionales conocidas.
 */
private fun wakeVariants(wakeWord: String): List<String> {
    val base = normalizeSpeech(wakeWord).trim()
    val compact = base.replace(Regex("[^a-z]"), "")
    val out = linkedSetOf<String>()
    if (compact.isEmpty()) return emptyList()
    out += compact
    out += base
    if (compact.length >= 5) {
        // Parcial cortado por el reconocedor ("anyhan").
        out += compact.dropLast(1)
        // Forma con espacio antes de las últimas 4 letras ("any hand").
        out += compact.dropLast(4) + " " + compact.takeLast(4)
    }
    if (compact == "anyhand") {
        out += listOf(
            "anihand", "ani hand", "anijand", "ani jand",
            "enihand", "eni hand", "enijand", "eni jand",
        )
    }
    return out.sortedByDescending { it.length }
}

/** ¿Contiene la palabra de activación (o una variante)? */
fun containsWakeWord(heard: String, wakeWord: String = WAKE_WORD): Boolean {
    val compactHeard = normalizeSpeech(heard).replace(Regex("[^a-z]"), "")
    if (compactHeard.isEmpty()) return false
    val base = normalizeSpeech(wakeWord).replace(Regex("[^a-z]"), "")
    if (base.isNotEmpty() && base in compactHeard) return true
    val spaced = normalizeSpeech(heard)
    return wakeVariants(wakeWord).any { variant ->
        variant.replace(" ", "") in compactHeard || variant in spaced
    }
}

/** Quita la palabra de activación y devuelve la orden (o "" si solo la dijo). */
fun stripWakeWord(heard: String, wakeWord: String = WAKE_WORD): String {
    var rest = heard
    for (variant in wakeVariants(wakeWord)) {
        // Regex.escape cita el texto completo (\Q...\E): se escapa por
        // palabra y se une con separador flexible (espacio o guion).
        val words = variant.split(" ").joinToString("[- ]?") { Regex.escape(it) }
        rest = rest.replace(Regex("(?i)\\b$words\\b"), " ")
    }
    return rest.replace(Regex("\\s+"), " ").trim()
}

/**
 * Qué hace la política de voz con cada evento. La UI solo la obedece:
 * abrir/cerrar el micrófono y mostrar el texto que corresponda.
 */
sealed interface VoiceStep {
    /** Seguir escuchando sin actuar (habla sin la palabra, fallo leve). */
    data object KeepListening : VoiceStep
    /** Solo dijo la palabra: esperar la orden directa con más paciencia. */
    data object WakeArmed : VoiceStep
    /** Orden lista para ejecutar. */
    data class Accept(val order: String) : VoiceStep
    /** Demasiados fallos seguidos: apagar el micrófono con mensaje. */
    data object GiveUp : VoiceStep
}

/**
 * Política de diálogo de voz, lógica pura y probada.
 *
 * Modos: toque (toda habla es orden, la palabra sobra) y manos-libres
 * (se exige la palabra salvo que ya se haya armado con ella). El
 * centinela es manos-libres silencioso: misma exigencia, otra pantalla.
 */
class VoicePolicy(wakeWord: String = WAKE_WORD) {

    /** Palabra de activación vigente (ajustable en Ajustes). */
    var wakeWord: String = wakeWord.ifBlank { WAKE_WORD }

    /** ¿Se exige la palabra de activación para aceptar una orden? */
    var requireWake = false
        private set

    /** ¿Micrófono abierto discreto (se muestra Listo, no Escuchando)? */
    var quiet = false
        private set

    /** ¿Ya se oyó la palabra sola? Lo siguiente es la orden directa. */
    var awaitingOrder = false
        private set

    /** Fallos seguidos (error o vacío) desde el último éxito. */
    var streak = 0
        private set

    /** Toque al orbe: dictar directo, sin palabra. */
    fun openTap() {
        requireWake = false
        quiet = false
        awaitingOrder = false
        streak = 0
    }

    /** Apertura por voz (cadena, centinela, Detener): configura exigencia. */
    fun openVoice(requireWake: Boolean, quiet: Boolean) {
        this.requireWake = requireWake
        this.quiet = quiet
        awaitingOrder = false
        streak = 0
    }

    /** Cierre total: apaga exigencia, espera y racha. */
    fun stop() {
        requireWake = false
        quiet = false
        awaitingOrder = false
        streak = 0
    }

    /** Fallos tolerados antes de rendirse: más esperando la orden. */
    private fun cap() = if (awaitingOrder) 8 else 3

    /**
     * Texto oído (null = resultado vacío). Nunca lanza: siempre devuelve
     * el paso a seguir.
     */
    fun onHeard(heard: String?): VoiceStep {
        if (heard == null) return onFailure()
        if (requireWake && !awaitingOrder && !containsWakeWord(heard, wakeWord)) {
            return VoiceStep.KeepListening
        }
        val order = if (containsWakeWord(heard, wakeWord)) {
            stripWakeWord(heard, wakeWord)
        } else heard
        if (order.isBlank()) {
            awaitingOrder = true
            streak = 0
            return VoiceStep.WakeArmed
        }
        awaitingOrder = false
        quiet = false
        return VoiceStep.Accept(order)
    }

    /** Error del reconocedor o silencio: reintentar o rendirse. */
    fun onFailure(): VoiceStep {
        streak++
        return if (streak <= cap()) VoiceStep.KeepListening else VoiceStep.GiveUp
    }
}
