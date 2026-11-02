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
 * Etiqueta de idioma COMPLETA para el reconocedor ("es-MX", no "es"):
 * con el código a secas algunos teléfonos responden
 * ERROR_LANGUAGE_UNAVAILABLE (CODE_12) y la sesión nace sorda.
 * Si el teléfono ya habla ese idioma se respeta su región; si no, se
 * usa la región principal de cada idioma de la app.
 */
fun speechLocale(language: String, deviceTag: String): String {
    val device = deviceTag.trim()
    val lang = language.trim().lowercase()
    if (device.isNotEmpty() &&
        device.lowercase().substringBefore('-') == lang
    ) {
        return device
    }
    return when (lang) {
        "es" -> "es-MX"
        "en" -> "en-US"
        "pt" -> "pt-BR"
        else -> device.ifEmpty { "es-MX" }
    }
}

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

/**
 * Orden por voz para cambiar la palabra ("cambia la palabra a luna"):
 * devuelve la palabra nueva o null si no es esa orden.
 */
fun extractWakeCommand(heard: String): String? {
    val lower = normalizeSpeech(heard)
    val markers = listOf(
        "cambia la palabra a ", "cambiar la palabra a ",
        "pon la palabra ", "nueva palabra ",
        "change wake word to ", "new wake word ",
        "muda a palavra para ", "nova palavra ",
    )
    for (marker in markers) {
        val i = lower.indexOf(marker)
        if (i >= 0) {
            val raw = heard.substring(
                minOf(heard.length, i + marker.length))
                .trim().trim('"', '.', ',')
            val word = raw.split(Regex("\\s+")).firstOrNull().orEmpty()
                .trim('"', '.', ',')
            return word.ifBlank { null }
        }
    }
    return null
}

/**
 * Comando de apagado total de UNA sola palabra ("apágate", "detente",
 * "stop"...). Solo dispara si TODA la frase es esa palabra: "manda un
 * mensaje de apagar la luz" son seis palabras y NO apaga nada; es una
 * orden normal para el planificador.
 */
fun isStopCommand(heard: String?): Boolean {
    if (heard.isNullOrBlank()) return false
    val words = normalizeSpeech(heard)
        .replace(Regex("[^a-z ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (words.size != 1) return false
    return words[0] in setOf(
        // es
        "apagate", "detente", "detenete", "parate", "callate", "silencio",
        // en
        "stop", "silence", "quiet",
        // pt
        "pare", "desligue", "calete",
    )
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

    /**
     * Escucha continua tras ejecutar una orden: sigue despierto
     * esperando la SIGUIENTE orden directa, sin exigir la palabra
     * otra vez. Solo Detener (o apagar la continua) sale de aquí.
     */
    fun resumeContinuous() {
        requireWake = true
        quiet = false
        awaitingOrder = true
        streak = 0
    }

    /** Cierre total: apaga exigencia, espera y racha. */
    fun stop() {
        requireWake = false
        quiet = false
        awaitingOrder = false
        streak = 0
    }

    /** Fallos tolerados antes de rendirse: tras despertar se espera la
     * orden con paciencia larga (30 silencios); en centinela el ruido no
     * quema la racha tan rápido (12); dictando, 3. */
    private fun cap() = when {
        awaitingOrder -> 30
        quiet -> 12
        else -> 3
    }

    /**
     * Texto oído (null = resultado vacío). Nunca lanza: siempre devuelve
     * el paso a seguir.
     */
    fun onHeard(heard: String?): VoiceStep {
        if (heard == null) return onFailure()
        if (requireWake && !awaitingOrder && !containsWakeWord(heard, wakeWord)) {
            // Se oyó habla (aunque sin la palabra): el micrófono vive,
            // la racha de fallos se reinicia.
            streak = 0
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
