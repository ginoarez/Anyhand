package com.anyhand.app

import android.view.KeyEvent
import com.anyhand.app.agent.AgentAction
import com.anyhand.app.agent.AgentRun
import com.anyhand.app.agent.AgentStep
import com.anyhand.app.agent.ConfirmationGate
import com.anyhand.app.agent.ActionMapper
import com.anyhand.app.agent.AgentState
import com.anyhand.app.agent.KeyMapper
import com.anyhand.app.agent.containsWakeWord
import com.anyhand.app.agent.stripWakeWord
import com.anyhand.app.agent.StatusText
import com.anyhand.app.agent.StubPlanner
import com.anyhand.app.agent.VoicePolicy
import com.anyhand.app.agent.VoiceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica pura del agente: sin framework Android, corre en la PC (host). */
class AgentLogicTest {

    @Test
    fun keyMapper_translatesModelKeyNames() {
        assertEquals(KeyEvent.KEYCODE_ENTER, KeyMapper.toKeyCode("Enter"))
        assertEquals(KeyEvent.KEYCODE_BACK, KeyMapper.toKeyCode("atrás"))
        assertEquals(KeyEvent.KEYCODE_BACK, KeyMapper.toKeyCode("Back"))
        assertEquals(KeyEvent.KEYCODE_HOME, KeyMapper.toKeyCode("home"))
    }

    @Test
    fun keyMapper_returnsNullForTouchOnlyButtons() {
        // Calibración del driver: el modelo no debe usar estos en UI táctil.
        assertEquals(null, KeyMapper.toKeyCode("right"))
        assertEquals(null, KeyMapper.toKeyCode("wheel"))
    }

    @Test
    fun confirmationGate_flagsIrreversibleTasks() {
        val tap = AgentAction.Tap(100f, 200f)
        assertTrue(ConfirmationGate.requiresConfirmation("enviar el mensaje", tap))
        assertTrue(ConfirmationGate.requiresConfirmation("pay the bill", tap))
        assertTrue(ConfirmationGate.requiresConfirmation("borrar la foto", tap))
        assertFalse(ConfirmationGate.requiresConfirmation("ve a la pantalla siguiente", tap))
    }

    @Test
    fun stubPlanner_buildsNextScreenPlan() {
        val plan = StubPlanner.plan("ve a la pantalla siguiente", 1080, 2400)
        assertEquals(1, plan.size)
        val tap = plan.first() as AgentAction.Tap
        assertEquals(540f, tap.x)
    }

    @Test
    fun stubPlanner_buildsWritePlanAcrossLanguages() {
        val es = StubPlanner.plan("escribe \"hola\"", 1080, 2400)
        assertTrue(es.any { it is AgentAction.InputText })
        val en = StubPlanner.plan("type \"hello\"", 1080, 2400)
        assertTrue(en.any { it is AgentAction.InputText })
        val pt = StubPlanner.plan("digite \"oi\"", 1080, 2400)
        assertTrue(pt.any { it is AgentAction.InputText })
    }

    @Test
    fun stubPlanner_buildsClosePlan() {
        val plan = StubPlanner.plan("cierra la aplicación", 1080, 2400)
        assertEquals(1, plan.size)
        assertEquals(AgentAction.KeyPress("Back"), plan.first())
    }

    @Test
    fun actionMapper_mapsModelArgs() {
        assertEquals(AgentAction.Tap(100f, 200f),
            ActionMapper.map(mapOf("action" to "tap", "x" to 100.0, "y" to 200.0)))
        assertEquals(AgentAction.InputText("hola"),
            ActionMapper.map(mapOf("action" to "type", "text" to "hola")))
        assertEquals(AgentAction.KeyPress("Back"),
            ActionMapper.map(mapOf("action" to "key", "key" to "Back")))
        assertEquals(null, ActionMapper.map(mapOf("action" to "done")))
        assertEquals(null, ActionMapper.map(mapOf("action" to "volar")))
        assertEquals(null, ActionMapper.map(mapOf("action" to "tap")))
    }

    @Test
    fun wakeWord_detectsVariantsAndStripsOrder() {
        assertTrue(containsWakeWord("Anyhand ve a la pantalla siguiente"))
        assertTrue(containsWakeWord("oye ani jand abre ajustes"))
        assertFalse(containsWakeWord("ve a la pantalla siguiente"))
        assertEquals("ve a la pantalla siguiente",
            stripWakeWord("Anyhand ve a la pantalla siguiente"))
        assertEquals("", stripWakeWord("Anyhand"))
        // Parcial cortado por el reconocedor ("anyhan"): también vale.
        assertTrue(containsWakeWord("Anyhan cierra la aplicación"))
        assertEquals("cierra la aplicación",
            stripWakeWord("Anyhan cierra la aplicación"))
    }

    @Test
    fun voicePolicy_handsFreeArmsOnWakeThenAcceptsOrder() {
        val policy = VoicePolicy()
        policy.openVoice(requireWake = true, quiet = true)
        // Habla sin la palabra: se ignora y se sigue escuchando.
        assertEquals(VoiceStep.KeepListening, policy.onHeard("hola qué tal"))
        assertFalse(policy.awaitingOrder)
        // Solo la palabra: se arma y espera la orden directa.
        assertEquals(VoiceStep.WakeArmed, policy.onHeard("Any Hand"))
        assertTrue(policy.awaitingOrder)
        // La orden directa entra sin repetir la palabra.
        assertEquals(
            VoiceStep.Accept("cierra la aplicación"),
            policy.onHeard("cierra la aplicación"))
        assertFalse(policy.awaitingOrder)
    }

    @Test
    fun voicePolicy_tapNeedsNoWakeWord() {
        val policy = VoicePolicy()
        policy.openTap()
        assertEquals(
            VoiceStep.Accept("cierra la aplicación"),
            policy.onHeard("cierra la aplicación"))
        assertEquals(
            VoiceStep.Accept("cierra la aplicación"),
            policy.onHeard("Anyhan cierra la aplicación"))
    }

    @Test
    fun voicePolicy_givesUpAfterThreeFailures() {
        val policy = VoicePolicy()
        policy.openTap()
        assertEquals(VoiceStep.KeepListening, policy.onFailure())
        assertEquals(VoiceStep.KeepListening, policy.onFailure())
        assertEquals(VoiceStep.KeepListening, policy.onFailure())
        assertEquals(VoiceStep.GiveUp, policy.onFailure())
        assertEquals(VoiceStep.GiveUp, policy.onHeard(null))
    }

    @Test
    fun wakeWord_customWordVariantsAndStrip() {
        assertTrue(containsWakeWord("oye ana abre ajustes", "Ana"))
        assertFalse(containsWakeWord("abre ajustes", "Ana"))
        assertEquals("abre ajustes",
            stripWakeWord("Ana abre ajustes", "Ana"))
        // Forma con espacio de la palabra por defecto.
        assertEquals("cierra la aplicación",
            stripWakeWord("Any hand cierra la aplicación"))
    }

    @Test
    fun voicePolicy_customWakeWordArmsAndAccepts() {
        val policy = VoicePolicy("computadora")
        policy.openVoice(requireWake = true, quiet = true)
        assertEquals(VoiceStep.KeepListening, policy.onHeard("hola"))
        assertEquals(VoiceStep.WakeArmed, policy.onHeard("computadora"))
        assertEquals(
            VoiceStep.Accept("cierra la aplicación"),
            policy.onHeard("cierra la aplicación"))
    }

    @Test
    fun voicePolicy_awaitingOrderToleratesMoreFailures() {
        val policy = VoicePolicy()
        policy.openVoice(requireWake = true, quiet = true)
        assertEquals(VoiceStep.WakeArmed, policy.onHeard("Anyhand"))
        repeat(8) {
            assertEquals(VoiceStep.KeepListening, policy.onFailure())
        }
        assertEquals(VoiceStep.GiveUp, policy.onFailure())
    }

    @Test
    fun stubPlanner_buildsFinishPlan() {
        val plan = StubPlanner.plan("pulsa ok para terminar", 1080, 2400)
        assertEquals(1, plan.size)
        val tap = plan.first() as AgentAction.Tap
        assertEquals(540f, tap.x, 0.01f)
        assertEquals(2400 * 0.82f, tap.y, 0.01f)
    }

    @Test
    fun statusText_coversAllStatesAndLanguages() {
        for (state in AgentState.values()) {
            assertTrue(StatusText.text(state, "es").isNotEmpty())
            assertTrue(StatusText.text(state, "en").isNotEmpty())
            assertTrue(StatusText.text(state, "pt").isNotEmpty())
            assertTrue(StatusText.colorHex(state) != 0L)
        }
        assertEquals("Escuchando", StatusText.text(AgentState.LISTENING, "es"))
        assertEquals("Listening", StatusText.text(AgentState.LISTENING, "en"))
        assertEquals("Ouvindo", StatusText.text(AgentState.LISTENING, "pt"))
        assertEquals("Dime qué quieres hacer", StatusText.hint("es"))
        assertEquals("Tell me what to do", StatusText.hint("en"))
        assertEquals("Diga o que fazer", StatusText.hint("pt"))
    }

    @Test
    fun runLog_emitsOneJsonLine() {
        val run = AgentRun(
            task = "next",
            language = "en",
            steps = listOf(
                AgentStep(0, AgentAction.Tap(1f, 2f), latencyMs = 120, tokensUsed = 300, success = true),
            ),
            success = true,
        )
        val line = run.toJsonLine()
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertTrue("\"task\":\"next\"" in line)
        assertFalse('\n' in line) // una sola línea: es JSONL
    }
}
