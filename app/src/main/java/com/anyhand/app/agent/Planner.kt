package com.anyhand.app.agent

import android.graphics.Bitmap

/**
 * Planificador intercambiable. El stub calibra gratis; el MetaPlanner
 * piensa de verdad con Muse Spark. El runner no distingue.
 */
interface Planner {
    data class Next(
        val action: AgentAction?,
        /** true = el modelo declara la tarea cumplida, sin más pasos. */
        val done: Boolean,
        val inTokens: Int = 0,
        val outTokens: Int = 0,
    )

    suspend fun next(
        task: String,
        lang: String,
        shot: Bitmap?,
        screenW: Int,
        screenH: Int,
        step: Int,
        nodes: String? = null,
        history: String = "",
    ): Next
}

/**
 * Orden directa "abre WhatsApp" / "open WhatsApp": devuelve el nombre
 * de la app pedida o null si no es una orden de abrir. Abrir una app
 * por nombre es determinista (lo resuelve el teléfono); no necesita
 * planificador ni toques a ciegas.
 */
fun extractOpenAppTarget(task: String): String? {
    val lower = normalizeSpeech(task).trim()
    val verbs = listOf(
        "abre ", "abrir ", "abra ", "open ", "abra o ", "abre o ",
    )
    for (verb in verbs) {
        if (!lower.startsWith(verb)) continue
        var target = lower.removePrefix(verb).trim()
        for (article in listOf(
            "la aplicacion ", "la app ", "el aplicativo ",
            "la ", "el ", "o ", "the ",
        )) {
            if (target.startsWith(article)) {
                target = target.removePrefix(article).trim()
                break
            }
        }
        return target.ifBlank { null }
    }
    return null
}

/**
 * Vía rápida de direcciones: "desliza hacia arriba", "swipe left",
 * "deslize para baixo" o solo "arriba" / "up" / "cima". Son órdenes
 * mecánicas: no necesitan planificador, captura ni verificación, se
 * ejecutan al instante. Solo dispara si, quitando el verbo y las
 * preposiciones, queda EXACTAMENTE una dirección ("abre el primer
 * contacto de arriba" no es un deslizamiento).
 */
fun basicSwipeAction(task: String, screenW: Int, screenH: Int): AgentAction? {
    val direction = mapOf(
        "arriba" to "up", "up" to "up", "cima" to "up",
        "subir" to "up", "sube" to "up", "suba" to "up",
        "abajo" to "down", "down" to "down", "baixo" to "down",
        "bajar" to "down", "baja" to "down",
        "descer" to "down", "desce" to "down",
        "izquierda" to "left", "left" to "left", "esquerda" to "left",
        "derecha" to "right", "right" to "right", "direita" to "right",
    )
    val filler = setOf(
        "desliza", "deslizar", "deslize", "desplaza", "desplazar",
        "arrastra", "arraste", "swipe", "scroll", "mueve", "mover",
        "hacia", "para", "pra", "a", "la", "el", "the", "por", "favor",
    )
    val words = normalizeSpeech(task)
        .replace(Regex("[^a-z ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in filler }
    if (words.size != 1) return null
    val cx = screenW / 2f
    val cy = screenH / 2f
    return when (direction[words[0]]) {
        "up" -> AgentAction.Swipe(cx, screenH * 0.7f, cx, screenH * 0.3f, 250)
        "down" -> AgentAction.Swipe(cx, screenH * 0.3f, cx, screenH * 0.7f, 250)
        "left" -> AgentAction.Swipe(screenW * 0.7f, cy, screenW * 0.3f, cy, 250)
        "right" -> AgentAction.Swipe(screenW * 0.3f, cy, screenW * 0.7f, cy, 250)
        else -> null
    }
}

/**
 * Orden de escribir: "escribe hola", "write hello", "escreva oi".
 * Devuelve el texto a escribir o null. Escribir en el cuadro de
 * mensajes (WhatsApp, Instagram, la app que sea) no necesita
 * planificador: se toca el campo editable y se escribe.
 */
fun extractWriteText(task: String): String? {
    val lower = normalizeSpeech(task).trim()
    for (verb in listOf(
        "escribe ", "escribir ", "escribi ", "write ", "type ",
        "escreva ", "escrever ", "digita ", "digite ",
    )) {
        if (lower.startsWith(verb)) {
            val text = task.trim()
                .substring(minOf(task.trim().length, verb.length))
                .trim().trim('"')
            return text.ifBlank { null }
        }
    }
    return null
}

/**
 * "Manda un hola como estas" / "send hello": devuelve el texto a
 * escribir Y enviar, o null. "Manda" a secas no trae texto: eso es
 * solo enviar lo ya escrito (ver isSendOnlyTask).
 */
fun extractSendText(task: String): String? {
    val lower = normalizeSpeech(task).trim()
    for (verb in listOf(
        "manda un ", "envia un ", "manda ", "mandar ", "envia ",
        "enviar ", "send ",
    )) {
        if (lower.startsWith(verb)) {
            val text = task.trim()
                .substring(minOf(task.trim().length, verb.length))
                .trim().trim('"')
            val norm = normalizeSpeech(text)
            if (norm in setOf(
                    "lo", "la", "eso", "esto", "it", "that", "o", "a mensagem",
                )) return null
            return text.ifBlank { null }
        }
    }
    return null
}

/** ¿La orden es solo ENVIAR lo que ya está escrito en el cuadro? */
fun isSendOnlyTask(task: String): Boolean {
    val lower = normalizeSpeech(task)
        .replace(Regex("[^a-z ]"), " ")
        .replace(Regex("\\s+"), " ").trim()
    return lower in setOf(
        "mandalo", "mandarlo", "envialo", "enviarlo", "manda", "envia",
        "enviar", "mandar", "send", "send it", "envia isso", "mande",
    )
}

/**
 * Centro del botón de enviar en el volcado: elemento tocable cuya
 * etiqueta dice enviar/send (en WhatsApp/Instagram está abajo a la
 * derecha del cuadro). Si hay varios, el de más a la derecha.
 */
fun sendButtonCenter(nodes: String?): Pair<Float, Float>? {
    if (nodes.isNullOrBlank()) return null
    val centerRegex = Regex("""centro=\[(\d+),(\d+)]""")
    var best: Pair<Float, Float>? = null
    for (line in nodes.lineSequence()) {
        if ("tocable=si" !in line) continue
        val norm = normalizeSpeech(line)
        if ("enviar" !in norm && "send" !in norm && "envia" !in norm) {
            continue
        }
        val m = centerRegex.find(line) ?: continue
        val point = m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
        if (best == null || point.first > best.first) best = point
    }
    return best
}

/**
 * "Borra lo que escribiste", "borra lo anterior", "delete that":
 * limpiar el cuadro de texto actual. Sin planificador.
 */
fun isClearTextTask(task: String): Boolean {
    val lower = normalizeSpeech(task)
    return listOf(
        "borra lo que escribiste", "borra lo escrito", "borra el texto",
        "borra lo del cuadro", "borra lo anterior", "borra todo",
        "borrar lo anterior", "delete that", "delete the text",
        "clear the text", "clear text", "apaga o texto",
        "apague o texto", "limpa o texto", "limpar o texto",
    ).any { lower.contains(it) }
}

/** ¿La orden es solo abrir el teclado / tocar el cuadro de texto? */
fun isOpenKeyboardTask(task: String): Boolean {
    val lower = normalizeSpeech(task).trim()
    return lower in setOf(
        "abre el teclado", "abrir el teclado", "open keyboard",
        "abrir o teclado", "abre o teclado",
        "abre el cuadro de mensajes", "toca el cuadro de mensajes",
    )
}

/**
 * Centro del primer campo editable en el volcado de nodos (líneas
 * con editable=si y centro=[x,y]), o null si esta pantalla no tiene.
 */
fun editableCenter(nodes: String?): Pair<Float, Float>? {
    if (nodes.isNullOrBlank()) return null
    val regex = Regex("""centro=\[(\d+),(\d+)]""")
    for (line in nodes.lineSequence()) {
        if ("editable=si" !in line) continue
        val m = regex.find(line) ?: continue
        return m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
    }
    return null
}

/** Adaptador del StubPlanner por palabras clave (sin costo). */
class StubPlannerAdapter : Planner {
    override suspend fun next(
        task: String,
        lang: String,
        shot: Bitmap?,
        screenW: Int,
        screenH: Int,
        step: Int,
        nodes: String?,
        history: String,
    ): Planner.Next {
        val plan = StubPlanner.plan(task, screenW, screenH)
        val action = plan.getOrNull(step) ?: return Planner.Next(null, done = true)
        return Planner.Next(action, done = false)
    }
}

/**
 * Mapeo puro argumentos del modelo -> acción (probable en JVM).
 * Las claves numéricas llegan como Double por JSON.
 */
object ActionMapper {
    fun map(args: Map<String, Any?>): AgentAction? {
        return when ((args["action"] as? String)?.lowercase()) {
            "tap" -> AgentAction.Tap(
                (args["x"] as? Number)?.toFloat() ?: return null,
                (args["y"] as? Number)?.toFloat() ?: return null,
            )
            "swipe" -> AgentAction.Swipe(
                (args["x1"] as? Number)?.toFloat() ?: return null,
                (args["y1"] as? Number)?.toFloat() ?: return null,
                (args["x2"] as? Number)?.toFloat() ?: return null,
                (args["y2"] as? Number)?.toFloat() ?: return null,
                300,
            )
            "type", "text" -> AgentAction.InputText(args["text"] as? String ?: "")
            "key", "keypress" -> AgentAction.KeyPress(args["key"] as? String ?: "Back")
            "done" -> null
            else -> null
        }
    }
}
