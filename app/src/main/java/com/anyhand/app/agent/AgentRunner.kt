package com.anyhand.app.agent

import android.os.SystemClock

/**
 * Ciclo del agente: planifica, pide confirmación si toca, actúa y verifica.
 *
 * @param needsConfirmation se llama cuando el gate exige confirmación;
 *   devuelve true si la persona usuaria aprobó la acción.
 * @param onState difunde el estado para la UI. Se invoca en el hilo que
 *   llama a [run]; la UI debe dirigirlo a su scope.
 */
class AgentRunner(
    private val driver: Driver,
    private val needsConfirmation: suspend (AgentAction) -> Boolean = { false },
    private val onState: (AgentState) -> Unit = {},
) {
    suspend fun run(task: String, language: String, screenW: Int, screenH: Int): AgentRun {
        val steps = mutableListOf<AgentStep>()
        onState(AgentState.PLANNING)
        val plan = StubPlanner.plan(task, screenW, screenH)

        for ((index, action) in plan.withIndex()) {
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
            val start = SystemClock.elapsedRealtime()
            val result = execute(action)
            val latency = SystemClock.elapsedRealtime() - start

            onState(AgentState.VERIFYING)
            val after = driver.screenshot()
            val verified = if (before != null && after != null) {
                Verifier.changedEnough(before, after)
            } else {
                // Sin capturas (servicio no activo) se acepta el resultado del driver.
                result.success
            }
            val ok = result.success && verified
            steps.add(
                AgentStep(
                    index = index,
                    action = action,
                    latencyMs = latency,
                    success = ok,
                    detail = result.detail,
                ),
            )
            if (!ok) {
                onState(AgentState.FAILED)
                return AgentRun(task, language, steps, success = false)
            }
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
