package com.anyhand.app.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anyhand.app.agent.AccessibilityDriver
import com.anyhand.app.agent.AccessibilityDriverService
import com.anyhand.app.agent.AgentAction
import com.anyhand.app.agent.AgentRunner
import com.anyhand.app.agent.AgentState
import com.anyhand.app.unlabeled.TestState
import com.anyhand.app.unlabeled.UnlabeledTestActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pantalla principal: comando por voz o texto -> acción -> verificación. */
@Composable
fun AnyhandApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("es") }
    var state by remember { mutableStateOf(AgentState.IDLE) }
    var lastResult by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<AgentAction?>(null) }
    var gate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var serviceOn by remember { mutableStateOf(AccessibilityDriverService.isEnabled()) }

    // Relee el estado del servicio cada segundo: al volver de Ajustes
    // la pantalla se entera sin necesidad de reiniciar la app.
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = AccessibilityDriverService.isEnabled()
            delay(1000)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heard = matches?.firstOrNull()
            if (heard != null) command = heard
        }
    }

    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el comando")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            lastResult = "Sin reconocedor de voz en este dispositivo; escribe el comando."
        }
    }

    fun startRun() {
        if (command.isBlank()) return
        val metrics = context.resources.displayMetrics
        val task = command
        val lang = language
        job?.cancel()
        job = scope.launch {
            val runner = AgentRunner(
                driver = AccessibilityDriver(),
                needsConfirmation = { action ->
                    val deferred = CompletableDeferred<Boolean>()
                    pendingAction = action
                    gate = deferred
                    val approved = deferred.await()
                    pendingAction = null
                    gate = null
                    approved
                },
                onState = { state = it },
            )
            state = AgentState.LISTENING
            try {
                val run = runner.run(task, lang, metrics.widthPixels, metrics.heightPixels)
                val done = TestState.isTaskDone(task)
                lastResult = if (run.success && done) {
                    "Éxito: ${run.steps.size} pasos"
                } else {
                    "Falló o sin verificar (pasos: ${run.steps.size})"
                }
                log.add(0, run.toJsonLine())
            } catch (_: Exception) {
                state = AgentState.FAILED
                lastResult = "Error de ejecución"
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServiceStatusRow(
                enabled = serviceOn,
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )

            LanguageRow(language = language, onChange = { language = it })

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Comando (escribe o usa la voz)") },
                placeholder = { Text("p. ej. ve a la pantalla siguiente") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::launchVoiceInput) { Text("Voz") }
                Button(
                    onClick = ::startRun,
                    enabled = state !in listOf(
                        AgentState.ACTING,
                        AgentState.VERIFYING,
                        AgentState.AWAITING_CONFIRMATION,
                    ),
                ) { Text("Ejecutar") }
                TextButton(onClick = { job?.cancel(); state = AgentState.IDLE }) {
                    Text("Detener")
                }
            }

            Text("Estado: ${state.name}", style = MaterialTheme.typography.titleMedium)
            if (lastResult.isNotEmpty()) Text(lastResult)

            Button(onClick = {
                context.startActivity(Intent(context, UnlabeledTestActivity::class.java))
            }) {
                Text("Abrir app de prueba sin etiquetas")
            }

            Text("Registro (runs.jsonl):", style = MaterialTheme.typography.titleSmall)
            log.take(20).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    val actionToConfirm = pendingAction
    if (actionToConfirm != null) {
        AlertDialog(
            onDismissRequest = { gate?.complete(false) },
            title = { Text("Confirmar acción") },
            text = { Text("Esta acción puede ser irreversible:\n${describe(actionToConfirm)}") },
            confirmButton = {
                TextButton(onClick = { gate?.complete(true) }) { Text("Permitir") }
            },
            dismissButton = {
                TextButton(onClick = { gate?.complete(false) }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun ServiceStatusRow(enabled: Boolean, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (enabled) "Servicio: ACTIVO" else "Servicio: apagado",
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onClick = onOpenSettings) { Text("Ajustes") }
    }
    if (!enabled) {
        Text(
            "Activa Anyhand en Ajustes > Accesibilidad para que el agente pueda actuar.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LanguageRow(language: String, onChange: (String) -> Unit) {
    val options = listOf("es" to "Español", "en" to "English", "pt" to "Português")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Idioma:", style = MaterialTheme.typography.titleSmall)
        options.forEach { (code, label) ->
            if (code == language) {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label, maxLines = 1) }
            } else {
                TextButton(
                    onClick = { onChange(code) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

private fun describe(action: AgentAction): String = when (action) {
    is AgentAction.Tap -> "Tocar en (${action.x.toInt()}, ${action.y.toInt()})"
    is AgentAction.Swipe -> "Deslizar de (${action.x1.toInt()}, ${action.y1.toInt()}) " +
        "a (${action.x2.toInt()}, ${action.y2.toInt()})"
    is AgentAction.InputText -> "Escribir \"${action.text}\""
    is AgentAction.KeyPress -> "Tecla ${action.key}"
    is AgentAction.Scroll -> "Desplazar (${action.dx.toInt()}, ${action.dy.toInt()})"
}
