package com.anyhand.app.agent

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Planificador real con Meta Model API (sin dependencias: HttpURLConnection
 * + org.json del SDK). Gemelo de harness/meta_client.py.
 *
 * POST https://api.meta.ai/v1/responses, muse-spark-1.3, Bearer MODEL_API_KEY,
 * herramienta `computer.computer`, coordenadas en píxeles re-escaladas.
 * La clave vive en preferencias del teléfono, nunca en el repo.
 */
/**
 * Prompt del planificador: captura + elementos con etiqueta y centro
 * tocable + historial. Reglas explícitas para que piense sobre ESTA
 * pantalla (volver atrás, no declarar fin sin verlo, no repetir lo
 * que ya falló). Puro para poder probarlo en JVM.
 */
fun buildPlannerPrompt(
    task: String,
    lang: String,
    step: Int,
    shotW: Int,
    shotH: Int,
    nodes: String?,
    history: String,
): String {
    val sb = StringBuilder()
    sb.append("Tarea ($lang): $task\n")
    sb.append("Paso ${step + 1}. La captura mide ${shotW}x$shotH píxeles: ")
    sb.append("da las coordenadas en ESA escala.\n")
    sb.append("Piensa sobre lo que se ve AHORA en la pantalla antes de actuar. ")
    sb.append("Si la tarea pide volver, salir de una conversación o ver la ")
    sb.append("lista anterior, usa key Back o toca la flecha de arriba a la ")
    sb.append("izquierda. Si un elemento con etiqueta sirve, toca su centro ")
    sb.append("exacto de la lista. No repitas una acción que ya falló. ")
    sb.append("Usa action=done SOLO si la pantalla ya muestra la tarea ")
    sb.append("cumplida. Devuelve UNA llamada a computer.computer.\n")
    if (history.isNotBlank()) {
        sb.append("Ya se hizo:\n").append(history).append('\n')
    }
    if (!nodes.isNullOrBlank()) {
        sb.append("Elementos en pantalla (centro en píxeles reales, ")
        sb.append("re-escala a la captura):\n")
        sb.append(nodes.take(3500)).append('\n')
    }
    return sb.toString()
}

class MetaPlanner(
    private val apiKey: String,
    private val model: String = "muse-spark-1.3",
) : Planner {

    override suspend fun next(
        task: String,
        lang: String,
        shot: Bitmap?,
        screenW: Int,
        screenH: Int,
        step: Int,
        nodes: String?,
        history: String,
    ): Planner.Next = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext StubPlannerAdapter().next(
                task, lang, shot, screenW, screenH, step, nodes, history)
        }
        // Captura reducida a la mitad: ~55% menos tokens de entrada.
        val small = if (shot != null) {
            Bitmap.createScaledBitmap(shot, shot.width / 2, shot.height / 2, true)
        } else null
        val b64 = small?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } ?: throw IllegalStateException("sin captura para el modelo")
        val prompt = buildPlannerPrompt(
            task, lang, step, small!!.width, small.height, nodes, history)
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("parallel_tool_calls", false)
            .put("tools", JSONArray().put(toolDefinition()))
            .put("input", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject()
                            .put("type", "input_text")
                            .put("text", prompt))
                        .put(JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64,$b64"))),
            ))
        val conn = (URL("https://api.meta.ai/v1/responses").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 120_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IllegalStateException("Meta HTTP $code: $raw")
            parse(JSONObject(raw), small.width, small.height, screenW, screenH)
        } finally {
            conn.disconnect()
        }
    }

    private fun toolDefinition(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", "computer.computer")
        .put("description", "UNA acción GUI sobre la pantalla, en píxeles de la captura.")
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("action", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("tap", "swipe", "type", "key", "done"))))
                .put("x", JSONObject().put("type", "number"))
                .put("y", JSONObject().put("type", "number"))
                .put("x1", JSONObject().put("type", "number"))
                .put("y1", JSONObject().put("type", "number"))
                .put("x2", JSONObject().put("type", "number"))
                .put("y2", JSONObject().put("type", "number"))
                .put("text", JSONObject().put("type", "string"))
                .put("key", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action"))))

    private fun parse(
        payload: JSONObject,
        smallW: Int,
        smallH: Int,
        screenW: Int,
        screenH: Int,
    ): Planner.Next {
        val usage = payload.optJSONObject("usage")
        val inTok = usage?.optInt("input_tokens") ?: 0
        val outTok = usage?.optInt("output_tokens") ?: 0
        val output = payload.optJSONArray("output") ?: throw
            IllegalStateException("sin output: $payload")
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "function_call") continue
            val args = JSONObject(item.optString("arguments", "{}"))
            val map = mutableMapOf<String, Any?>()
            for (key in listOf("action", "x", "y", "x1", "y1", "x2", "y2", "text", "key")) {
                if (args.has(key)) map[key] = args.get(key)
            }
            val rawAction = (map["action"] as? String)?.lowercase()
            if (rawAction == "done") {
                return Planner.Next(null, done = true, inTok, outTok)
            }
            val action = ActionMapper.map(map)
                ?: throw IllegalStateException("acción inválida: $map")
            val upX = screenW.toDouble() / smallW
            val upY = screenH.toDouble() / smallH
            val scaled = when (action) {
                is AgentAction.Tap ->
                    action.copy(x = (action.x * upX).toFloat(), y = (action.y * upY).toFloat())
                is AgentAction.Swipe -> action.copy(
                    x1 = (action.x1 * upX).toFloat(), y1 = (action.y1 * upY).toFloat(),
                    x2 = (action.x2 * upX).toFloat(), y2 = (action.y2 * upY).toFloat())
                else -> action
            }
            return Planner.Next(scaled, done = false, inTok, outTok)
        }
        // Sin llamada: el modelo declara fin implícito solo si lo dijo en texto.
        return Planner.Next(null, done = true, inTok, outTok)
    }
}
