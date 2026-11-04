package com.anyhand.app.agent

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Ciclo del agente: planifica, pide confirmación si toca, actúa y verifica.
 *
 * @param needsConfirmation se llama cuando el gate exige confirmación;
 *   devuelve true si la persona usuaria aprobó la acción.
 * @param onState difunde el estado para la UI. Se invoca en el hilo que
 *   llama a [run]; la UI debe dirigirlo a su scope.
 */
/**
 * ¿El punto cae (casi) sobre el centro de un elemento tocable con
 * etiqueta? Entonces Meta ya fue preciso y SAM sobra.
 */
private fun nearLabeledCenter(
    nodes: String?,
    x: Float,
    y: Float,
    radius: Float = 60f,
): Boolean {
    if (nodes.isNullOrBlank()) return false
    val regex = Regex("""centro=\[(\d+),(\d+)]""")
    for (line in nodes.lineSequence()) {
        if ("tocable=si" !in line) continue
        val m = regex.find(line) ?: continue
        val cx = m.groupValues[1].toFloat()
        val cy = m.groupValues[2].toFloat()
        val dx = cx - x
        val dy = cy - y
        if (dx * dx + dy * dy <= radius * radius) return true
    }
    return false
}

class AgentRunner(
    private val driver: Driver,
    private val needsConfirmation: suspend (AgentAction) -> Boolean = { false },
    private val onState: (AgentState) -> Unit = {},
    private val planner: Planner = StubPlannerAdapter(),
    private val stepCap: Int = 10,
    /**
     * Refinador opcional de toques (SAM): recibe la captura y el tap
     * propuesto y puede devolver el punto corregido. Null = sin
     * refinar (el tap va tal cual).
     */
    private val tapRefiner: (suspend (Bitmap, AgentAction.Tap) ->
        Pair<Float, Float>?)? = null,
) {
    suspend fun run(task: String, language: String, screenW: Int, screenH: Int): AgentRun {
        val steps = mutableListOf<AgentStep>()
        var index = 0
        var deadSteps = 0
        while (true) {
            onState(AgentState.PLANNING)
            if (index >= stepCap) {
                onState(AgentState.FAILED)
                return AgentRun(task, language, steps, success = false)
            }
            // La captura y los elementos con etiqueta alimentan al
            // planificador (el stub los ignora): Meta piensa sobre lo
            // que hay en pantalla, no a ciegas.
            val shot = driver.screenshot()
            val nodes = driver.dumpNodes()
            val history = steps.joinToString("\n") {
                "paso ${it.index + 1}: ${it.action} ok=${it.success}"
            }
            val next = planner.next(
                task, language, shot, screenW, screenH, index,
                nodes, history)
            if (next.done) {
                onState(AgentState.DONE)
                return AgentRun(task, language, steps, success = true)
            }
            val action = next.action ?: break
            // Guardarraíl anti-deshacer: tras un paso exitoso ya
            // verificado, si el modelo pide ir ATRÁS (tecla Back o la
            // flecha superior izquierda), es que ya está en el
            // destino y no supo declarar done: se toma como cumplido
            // y NO se ejecuta. Tres registros seguidos mostraron ese
            // bucle (abrir el contacto y devolverse al instante).
            val isBackAction = when (action) {
                is AgentAction.KeyPress ->
                    action.key.equals("Back", ignoreCase = true)
                is AgentAction.Tap ->
                    action.x < screenW * 0.15f &&
                        action.y < screenH * 0.12f
                else -> false
            }
            if (isBackAction && steps.any { it.success }) {
                onState(AgentState.DONE)
                return AgentRun(task, language, steps, success = true)
            }
            val tokens = next.inTokens + next.outTokens
            if (ConfirmationGate.requiresConfirmation(task, action)) {
                onState(AgentState.AWAITING_CONFIRMATION)
                val approved = needsConfirmation(action)
                if (!approved) {
                    onState(AgentState.FAILED)
                    return AgentRun(task, language, steps, success = false)
                }
            }
            onState(AgentState.ACTING)
            val before = driver.screenshot()
            // SAM refina el toque: Meta propone el punto, la máscara
            // del objeto manda y el tap cae en su centro real. PERO
            // si el toque ya cae sobre un elemento CON etiqueta, SAM
            // no aporta nada y cuesta segundos: se salta. SAM es
            // para pantallas sin etiquetas (juegos, canvas).
            var toExecute = action
            // SAM solo donde no hay etiquetas que valgan (juegos,
            // canvas): en pantallas con varios elementos tocables
            // etiquetados solo estorba (segmenta media pantalla y
            // quema segundos por toque).
            val labeledClickables = nodes?.lineSequence()
                ?.count { "tocable=si" in it } ?: 0
            if (tapRefiner != null && action is AgentAction.Tap &&
                before != null && labeledClickables <= 2 &&
                !nearLabeledCenter(nodes, action.x, action.y)
            ) {
                val refined = tapRefiner.invoke(before, action)
                if (refined != null) {
                    toExecute = action.copy(
                        x = refined.first, y = refined.second)
                }
            }
            val start = SystemClock.elapsedRealtime()
            val result = execute(toExecute)
            val latency = SystemClock.elapsedRealtime() - start

            onState(AgentState.VERIFYING)
            // Espera a que la pantalla reaccione (animaciones, teclado,
            // transiciones): comparar en frío marcaba como fallo
            // acciones que sí funcionaron ("algo salió mal" falso).
            delay(300)
            var after = driver.screenshot()
            var verified = if (before != null && after != null) {
                Verifier.changedEnough(before, after)
            } else {
                // Sin capturas (servicio no activo) se acepta el resultado del driver.
                result.success
            }
            if (!verified && result.success && before != null) {
                // Segunda oportunidad: cambios lentos (teclado que
                // sube, app que abre) tardan más de medio segundo.
                delay(600)
                after = driver.screenshot()
                verified = after != null &&
                    Verifier.changedEnough(before, after)
            }
            val ok = result.success && verified
            steps.add(
                AgentStep(
                    index = index,
                    action = toExecute,
                    latencyMs = latency,
                    tokensUsed = tokens,
                    success = ok,
                    detail = if (ok) result.detail
                    else "sin cambio visible o driver falló: ${result.detail}",
                ),
            )
            if (!ok) {
                // Un paso sin cambio NO mata la tarea: tocar el cuadro
                // de mensajes ya enfocado (o una pestaña ya activa) no
                // cambia píxeles y aun así es correcto. El planificador
                // ve la pantalla siguiente y decide (escribir, done).
                // Solo se rinde tras 3 pasos muertos seguidos.
                deadSteps++
                if (deadSteps >= 3) {
                    onState(AgentState.FAILED)
                    return AgentRun(task, language, steps, success = false)
                }
            } else {
                deadSteps = 0
                // Corte de un solo toque: si la orden NO es secuencial
                // (sin "entra y busca", "y escribe"...) y el primer
                // toque cayó en un elemento etiquetado con cambio
                // verificado, la tarea YA terminó. Pedirle a Meta que
                // lo confirme solo lograba que tocara la flecha de
                // atrás y repitiera todo (visto en registros).
                val sequential = Regex(
                    "\\b(y|e|luego|despues|entra|entrar|busca|buscar|" +
                        "manda|mandar|envia|enviar|escribe|escribir|" +
                        "and|then|depois)\\b",
                ).containsMatchIn(task.lowercase())
                if (index == 0 && toExecute is AgentAction.Tap &&
                    !sequential &&
                    nearLabeledCenter(nodes, toExecute.x, toExecute.y)
                ) {
                    onState(AgentState.DONE)
                    return AgentRun(task, language, steps, success = true)
                }
            }
            index++
        }
        onState(AgentState.DONE)
        return AgentRun(task, language, steps, success = true)
    }

    private suspend fun execute(action: AgentAction): ActionResult = when (action) {
        is AgentAction.Tap -> driver.tap(action.x, action.y)
        is AgentAction.Swipe -> driver.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
        is AgentAction.InputText -> driver.inputText(action.text)
        is AgentAction.KeyPress -> driver.keyPress(action.key)
        is AgentAction.Scroll -> driver.swipe(
            500f, 500f, 500f + action.dx, 500f + action.dy, 300,
        )
    }
}
