package com.anyhand.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import com.anyhand.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anyhand.app.agent.AccessibilityDriver
import com.anyhand.app.agent.AccessibilityDriverService
import com.anyhand.app.agent.AgentAction
import com.anyhand.app.agent.AgentRunner
import com.anyhand.app.agent.AgentState
import com.anyhand.app.agent.MetaPlanner
import com.anyhand.app.agent.Planner
import com.anyhand.app.agent.StatusText
import com.anyhand.app.agent.StubPlannerAdapter
import com.anyhand.app.agent.VoicePolicy
import com.anyhand.app.agent.VoiceStep
import com.anyhand.app.agent.WAKE_WORD
import com.anyhand.app.ui.theme.AnyhandTheme
import com.anyhand.app.unlabeled.TestState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla principal mínima (mockup + DESIGN.md §2.3): encabezado, estado,
 * orbe, última orden, Detener y aviso de privacidad. Voz primero: tocar el
 * orbe dicta la orden. Sin paneles de desarrollo.
 */
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
    var showSettings by remember { mutableStateOf(false) }
    var chainVoice by remember { mutableStateOf(false) }
    // Micrófono abierto: la política (VoicePolicy) decide qué vale como
    // orden; la UI solo la obedece y refleja.
    var voiceActive by remember { mutableStateOf(false) }
    val policy = remember { VoicePolicy() }
    // El reconocedor es UNA sesión: si se pide escuchar mientras la
    // anterior sigue viva responde BUSY. Se rastrea la sesión y los
    // reintentos llevan pausa en el hilo principal.
    val mainHandler = remember {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    var sessionAlive by remember { mutableStateOf(false) }
    var busyTries by remember { mutableStateOf(0) }
    // Un cancel() nacido por nosotros genera su propio onError: se consume
    // sin reintentar, porque el reintento con pausa ya va en camino.
    var suppressNextError by remember { mutableStateOf(false) }
    // Un solo turno para todos los reintentos con pausa (errores, vacíos,
    // ocupado): cada evento nuevo invalida lo programado.
    var restartGen by remember { mutableStateOf(0) }
    // Espejos de la política para la UI (la política no es estado Compose):
    // se actualizan con syncPolicy() tras cada cambio.
    // Centinela: micrófono abierto esperando SOLO la palabra. Se muestra
    // como Listo con orbe quieto, nunca como Escuchando.
    var sentry by remember { mutableStateOf(false) }
    // true = ya se oyó la palabra sola; lo siguiente es la orden directa.
    var expectOrder by remember { mutableStateOf(false) }

    /** Refleja la política en la UI. Llamar tras cada cambio. */
    fun syncPolicy() {
        sentry = voiceActive && policy.quiet
        expectOrder = voiceActive && policy.awaitingOrder
    }
    // Detener deja nota visible ("Detenido") hasta la próxima orden.
    var stoppedNote by remember { mutableStateOf(false) }
    var serviceOn by remember { mutableStateOf(AccessibilityDriverService.isEnabled()) }
    val prefs = remember {
        context.getSharedPreferences("anyhand", android.content.Context.MODE_PRIVATE)
    }
    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var continuous by remember { mutableStateOf(prefs.getBoolean("continuous", false)) }
    // Último texto oído (aunque se ignore por falta de la palabra): así el
    // micrófono abierto nunca parece muerto.
    var heardLive by remember { mutableStateOf("") }
    var plannerMode by remember { mutableStateOf(prefs.getString("planner", "stub") ?: "stub") }
    var metaKey by remember { mutableStateOf(prefs.getString("meta_key", "") ?: "") }
    // Palabra de activación ajustable (vacía = la de siempre, "Anyhand").
    var wakeWord by remember {
        mutableStateOf(prefs.getString("wake_word", "") ?: "")
    }
    val wakeShown = wakeWord.ifBlank { WAKE_WORD }
    LaunchedEffect(wakeWord) {
        policy.wakeWord = wakeShown
    }
    // El centinela debe revivir solo: quien dicta la orden no tiene manos
    // para tocar el orbe y reabrir el micrófono si la escucha cae.
    var sentryDesired by remember { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    var dark by remember {
        mutableStateOf(prefs.getBoolean("dark", systemDark))
    }
    // DESIGN.md §5: sin animaciones del sistema, el orbe se queda quieto.
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    // DESIGN.md §5: el color del estado anima, no salta.
    // El servicio se relee cada segundo: al volver de Ajustes la pantalla
    // se entera sola, y el onboarding se cierra cuando el acceso activa.
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = AccessibilityDriverService.isEnabled()
            delay(1000)
        }
    }
    LaunchedEffect(serviceOn) {
        if (serviceOn && !onboarded) {
            onboarded = true
            prefs.edit().putBoolean("onboarded", true).apply()
        }
    }
    val stateColor by animateColorAsState(
        Color(StatusText.colorHex(state)), label = "colorEstado")
    val orbActive = state == AgentState.LISTENING || state == AgentState.PLANNING ||
        state == AgentState.ACTING || state == AgentState.VERIFYING

    fun startRun(task: String) {
        if (task.isBlank()) return
        val metrics = context.resources.displayMetrics
        val lang = language
        job?.cancel()
        job = scope.launch {
            // Sin clave no hay Meta: el runner usa el stub gratis.
            // La clave vive solo en este teléfono, nunca en el repo.
            val planner: Planner = if (plannerMode == "meta" && metaKey.isNotBlank()) {
                MetaPlanner(metaKey.trim())
            } else StubPlannerAdapter()
            val runner = AgentRunner(
                driver = AccessibilityDriver(),
                planner = planner,
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
                val serviceDown = run.steps.any { "disabled" in it.detail }
                val done = TestState.isTaskDone(task) ||
                    (TestState.isExitTask(task) && run.success)
                if (run.success && done && continuous && voiceActive) {
                    chainVoice = true
                } else if (!(run.success && done)) {
                    state = AgentState.FAILED
                    lastResult = if (serviceDown) {
                        t(lang, "El servicio está apagado. Actívalo en Ajustes e inténtalo de nuevo.",
                            "The service is off. Enable it in Settings and try again.",
                            "O serviço está desativado. Ative em Ajustes e tente de novo.")
                    } else {
                        t(lang, "No pasó nada en la pantalla. Abre la app objetivo e inténtalo de nuevo.",
                            "Nothing changed on screen. Open the target app and try again.",
                            "Nada mudou na tela. Abra o app de destino e tente de novo.")
                    }
                }
            } catch (e: Exception) {
                state = AgentState.FAILED
                // En modo Meta se muestra la causa real (sin la clave) para
                // poder diagnosticar: HTTP, red o parseo.
                val cause = if (plannerMode == "meta") " (${e.message})" else ""
                lastResult = t(lang, "Error de ejecución$cause. Revisa el servicio e inténtalo de nuevo.",
                    "Execution error$cause. Check the service and try again.",
                    "Erro de execução$cause. Verifique o serviço e tente de novo.")
            }
        }
    }

    // Reconocimiento DENTRO de la app (ya no abre la pantalla de Google):
    // el orbe escucha, el texto parcial aparece en vivo y al terminar
    // arranca la orden solo. UNA sola instancia: destruir/crear en cada
    // arranque causaba ERROR_CLIENT por carrera.
    fun newRecognizer(): SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    var recognizer by remember { mutableStateOf(newRecognizer()) }
    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }

    /** Reconocedor nuevo: tras rachas de error el viejo queda sordo. */
    fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = newRecognizer()
        sessionAlive = false
        android.util.Log.d("AnyhandVoice", "reconocedor recreado")
    }

    fun hearError(): String = if (voiceActive) {
        t(language, "No te escuché, repite la orden.",
            "I didn't catch that, repeat your order.",
            "Não entendi, repita o pedido.")
    } else {
        t(language, "No te escuché. Toca el orbe e inténtalo de nuevo.",
            "I didn't catch that. Tap the orb and try again.",
            "Não entendi. Toque o orbe e tente de novo.")
    }

    fun startListeningInternal() {
        if (recognizer == null) {
            voiceActive = false
            policy.stop()
            syncPolicy()
            state = AgentState.FAILED
            lastResult = hearError()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Tiempos de silencio más altos: evita que corte la frase a
            // media voz fuerte o pausada. No existe control de ganancia.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)
        }
        recognizer?.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // En centinela se muestra Listo: el micrófono está abierto
                    // pero la app está apagada en apariencia hasta la palabra.
                    // Tras oír la palabra, sí se ve Escuchando: es la señal
                    // de que despertó y lo siguiente es la orden directa.
                    state = if (sentry && !expectOrder) AgentState.IDLE
                    else AgentState.LISTENING
                    busyTries = 0
                    sessionAlive = true
                    android.util.Log.d("AnyhandVoice",
                        "mic abierto (lang=$language sentry=$sentry)")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    // La sesión terminó (con error o cancelación).
                    sessionAlive = false
                    // Eco de nuestro propio cancel(): se consume, el
                    // reintento con pausa ya va en camino.
                    if (suppressNextError) {
                        suppressNextError = false
                        android.util.Log.d("AnyhandVoice", "eco de cancel propio")
                        return
                    }
                    val name = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISO"
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                        else -> "CODE_$error"
                    }
                    android.util.Log.d("AnyhandVoice",
                        "error=$name racha=${policy.streak}")
                    // Ocupado por la sesión anterior: no es fallo de
                    // escucha, se reintenta con pausa sin gastar la racha.
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        busyTries++
                        if (voiceActive && busyTries <= 5) {
                            // Reintento con pausa en línea: la lambda vive
                            // dentro de esta misma función, así que sí
                            // puede llamarla para reabrir.
                            restartGen += 1
                            val busyGen = restartGen
                            mainHandler.postDelayed({
                                if (busyGen == restartGen && voiceActive) {
                                    startListeningInternal()
                                }
                            }, 500)
                        } else {
                            voiceActive = false
                            policy.stop()
                            syncPolicy()
                            state = AgentState.FAILED
                            lastResult = hearError() + " (BUSY)"
                            // Sesión trabada: reconocedor nuevo y el
                            // vigilante reabre el centinela solo.
                            resetRecognizer()
                        }
                        return
                    }
                    // La política decide: reintentar o rendirse.
                    when (policy.onFailure()) {
                        VoiceStep.KeepListening ->
                            if (voiceActive) {
                                restartGen += 1
                                val errGen = restartGen
                                mainHandler.postDelayed({
                                    if (errGen == restartGen && voiceActive) {
                                        startListeningInternal()
                                    }
                                }, 200)
                            }
                        VoiceStep.GiveUp -> {
                            voiceActive = false
                            syncPolicy()
                            state = AgentState.FAILED
                            lastResult = hearError() + " ($name)"
                            // Reconocedor nuevo y el vigilante reabre el
                            // centinela solo: nadie toca el orbe.
                            resetRecognizer()
                        }
                        else -> Unit
                    }
                }

                override fun onResults(results: Bundle?) {
                    // La sesión terminó con resultado.
                    sessionAlive = false
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    android.util.Log.d("AnyhandVoice", "oído=«$heard»")
                    // La política decide; la UI solo obedece.
                    when (val step = policy.onHeard(heard)) {
                        VoiceStep.KeepListening, VoiceStep.WakeArmed -> {
                            if (heard != null) heardLive = heard
                            syncPolicy()
                            if (voiceActive) {
                                restartGen += 1
                                val resGen = restartGen
                                mainHandler.postDelayed({
                                    if (resGen == restartGen && voiceActive) {
                                        startListeningInternal()
                                    }
                                }, 200)
                            }
                        }
                        is VoiceStep.Accept -> {
                            stoppedNote = false
                            voiceActive = continuous
                            syncPolicy()
                            command = step.order
                            startRun(step.order)
                        }
                        VoiceStep.GiveUp -> {
                            // Solo llega aquí con resultado vacío y la
                            // racha agotada (onHeard(null) → onFailure).
                            voiceActive = false
                            syncPolicy()
                            state = AgentState.FAILED
                            lastResult = hearError() + " (VACIO)"
                            resetRecognizer()
                        }
                    }
                }

                override fun onPartialResults(partial: Bundle?) {
                    // El parcial solo alimenta la línea "Oí:": la tarjeta de
                    // última orden muestra únicamente órdenes aceptadas.
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { heardLive = it }
                }
            })
            try {
                if (sessionAlive) {
                    // Sesión anterior aún viva: se cancela y se reabre
                    // con pausa en vez de chocar (evita el bucle BUSY).
                    // El eco del cancel se consume en onError.
                    suppressNextError = true
                    cancel()
                    restartGen += 1
                    val clashGen = restartGen
                    mainHandler.postDelayed({
                        if (clashGen == restartGen && voiceActive) {
                            startListeningInternal()
                        }
                    }, 500)
                } else {
                    // Se marca desde el arranque: cierra la ventana entre
                    // startListening y onReadyForSpeech donde otro arranque
                    // colado responde BUSY.
                    sessionAlive = true
                    startListening(intent)
                }
            } catch (_: Exception) {
                sessionAlive = false
                state = AgentState.FAILED
                lastResult = hearError()
            }
        }
    }

    // Sin pausa por audio externo: apagaba el micrófono sin aviso.

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Primer permiso: queda el centinela armado para hablar sin
            // tocar nada, y el vigilante lo mantiene vivo.
            sentryDesired = true
            policy.openVoice(requireWake = true, quiet = true)
            voiceActive = true
            syncPolicy()
            startListeningInternal()
        } else {
            voiceActive = false
            policy.stop()
            syncPolicy()
            state = AgentState.FAILED
            lastResult = t(language, "Sin permiso de micrófono no puedo escucharte.",
                "Without microphone permission I cannot hear you.",
                "Sem permissão de microfone não posso ouvir você.")
        }
    }

    fun launchVoiceInput(auto: Boolean = false) {
        if (state == AgentState.ACTING || state == AgentState.VERIFYING ||
            state == AgentState.AWAITING_CONFIRMATION) return
        // Cerrar solo si se está DICTANDO (toque abierto sin centinela).
        // Con centinela activo el toque pasa a dictado directo: ya no
        // apaga a la primera como antes.
        val dictating = voiceActive && !auto && !policy.quiet
        if (dictating) {
            // Segundo toque dictando: cierra el micrófono. El eco del
            // cancel se consume para no pintar un error al apagar a
            // propósito. El vigilante no lo resucita: fue tu toque.
            voiceActive = false
            policy.stop()
            syncPolicy()
            sentryDesired = false
            sessionAlive = false
            suppressNextError = true
            recognizer?.stopListening()
            recognizer?.cancel()
            state = AgentState.IDLE
            return
        }
        // Toque: dictar directo sin palabra. Auto (cadena): la palabra se
        // exige por orden, como siempre en manos-libres.
        if (auto) policy.openVoice(requireWake = true, quiet = false)
        else policy.openTap()
        voiceActive = true
        syncPolicy()
        stoppedNote = false
        restartGen += 1
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListeningInternal()
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Escucha continua: al terminar una orden, vuelve a escuchar solo.
    // Interino hasta la palabra de activación real (§7): un toque inicial
    // (o ayuda) arranca la cadena y después todo es por voz.
    LaunchedEffect(chainVoice) {
        if (chainVoice) {
            chainVoice = false
            launchVoiceInput(auto = true)
        }
    }
    // Centinela al abrir: si el permiso ya está dado, el micrófono se abre
    // mostrando "Listo" y solo despierta con la palabra. Sin motor de
    // palabra clave en el teléfono no hay otra forma de cacharla; el punto
    // verde del sistema delatará el micrófono abierto, eso no se puede
    // ocultar. Si no hay permiso, no se abre nada hasta el primer toque.
    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            voiceActive = true
            sentryDesired = true
            policy.openVoice(requireWake = true, quiet = true)
            syncPolicy()
            startListeningInternal()
        }
    }
    // Vigilante del centinela: sin brazos no hay forma de revivir el
    // micrófono si muere. Si el centinela está armado y la escucha cayó
    // (racha de errores, reconocedor recreado), lo reabre solo. No actúa
    // mientras una tarea corre ni durante la pausa por audio externo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (sentryDesired && !voiceActive && job?.isActive != true &&
                state != AgentState.AWAITING_CONFIRMATION
            ) {
                android.util.Log.d("AnyhandVoice", "vigilante reabre centinela")
                policy.openVoice(requireWake = true, quiet = true)
                voiceActive = true
                syncPolicy()
                startListeningInternal()
            }
        }
    }

    AnyhandTheme(darkTheme = dark) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_lock),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        t(language, "Las capturas se envían a Meta para decidir cada paso.",
                            "Screenshots are sent to Meta to decide each step.",
                            "Capturas são enviadas à Meta para decidir cada passo."),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxHeight()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showSettings) {
                    SettingsScreen(
                        language = language,
                        onLanguage = { language = it },
                        wakeWord = wakeWord,
                        onWakeWord = {
                            wakeWord = it
                            prefs.edit().putString("wake_word", it).apply()
                        },
                        serviceOn = serviceOn,
                        continuous = continuous,
                        onContinuous = {
                            continuous = it
                            prefs.edit().putBoolean("continuous", it).apply()
                        },
                        plannerMode = plannerMode,
                        onPlannerMode = {
                            plannerMode = it
                            prefs.edit().putString("planner", it).apply()
                        },
                        metaKey = metaKey,
                        onMetaKey = {
                            metaKey = it
                            prefs.edit().putString("meta_key", it).apply()
                        },
                        onOpenAccessibility = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                if (!onboarded && !serviceOn) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lucide_hand),
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp),
                                )
                                Text(
                                    t(language, "Para empezar, activa el acceso",
                                        "To begin, enable access", "Para começar, ative o acesso"),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                            Text(
                                t(language, "Anyhand necesita el servicio de accesibilidad para actuar. " +
                                    "Se activa a mano una sola vez; después todo es por voz.",
                                    "Anyhand needs the accessibility service to act. " +
                                    "Enable it by hand once; afterwards everything is voice.",
                                    "Anyhand precisa do serviço de acessibilidade para agir. " +
                                    "Ative manualmente uma vez; depois tudo é por voz."),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(t(language, "Activar acceso",
                                    "Enable access", "Ativar acesso"))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Anyhand", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                dark = !dark
                                prefs.edit().putBoolean("dark", dark).apply()
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (dark) R.drawable.ic_lucide_sun
                                    else R.drawable.ic_lucide_moon),
                                contentDescription = if (dark)
                                    t(language, "Cambiar a modo claro",
                                        "Switch to light mode", "Mudar para modo claro")
                                else t(language, "Cambiar a modo oscuro",
                                    "Switch to dark mode", "Mudar para modo escuro"),
                            )
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_settings),
                                contentDescription = t(language, "Ajustes",
                                    "Settings", "Configurações"),
                            )
                        }
                    }
                }

                VoiceOrb(
                    color = stateColor,
                    active = orbActive,
                    animationsEnabled = animationsEnabled,
                    modifier = Modifier
                        .size(160.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = StatusText.hint(language),
                        ) { launchVoiceInput() },
                )
                Crossfade(targetState = state, label = "estado") { current ->
                    // Contraste §4: el naranja sobre fondo claro no llega a
                    // 4.5:1; en esos estados el texto va en tinta y el color
                    // queda en el ícono y el orbe (el color nunca es la
                    // única señal, pero el texto siempre debe leerse).
                    val textColor = if (!dark && (current == AgentState.ACTING ||
                            current == AgentState.VERIFYING)) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        Color(StatusText.colorHex(current))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StateIcon(
                                state = current,
                                color = Color(StatusText.colorHex(current)),
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                if (stoppedNote && current == AgentState.IDLE)
                                    t(language, "Detenido",
                                        "Stopped", "Interrompido")
                                else StatusText.text(current, language),
                                style = MaterialTheme.typography.displayLarge,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Text(
                            StatusText.hint(language),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (!voiceActive) t(language, "Mic apagado",
                                "Mic off", "Mic desligado")
                            else if (expectOrder) t(language,
                                "Mic: escuchando, dicta tu orden",
                                "Mic: listening, say your order",
                                "Mic: ouvindo, dite seu pedido")
                            else if (sentry) t(language,
                                "Mic: esperando «$wakeShown»",
                                "Mic: waiting for “$wakeShown”",
                                "Mic: aguardando “$wakeShown”")
                            else t(language, "Mic: escuchando",
                                "Mic: listening", "Mic: ouvindo"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (heardLive.isNotEmpty()) {
                            val gated = voiceActive && policy.requireWake &&
                                !expectOrder
                            // Esperando la orden tras la palabra: se dice
                            // fuerte para que no parezca muerta.
                            val suffix = when {
                                expectOrder -> t(language,
                                    " — te escuché, dime la orden.",
                                    " — I hear you, tell me the order.",
                                    " — estou ouvindo, diga o pedido.")
                                gated -> t(language,
                                    " — di $wakeShown + tu orden.",
                                    " — say $wakeShown + your order.",
                                    " — diga $wakeShown + seu pedido.")
                                else -> "."
                            }
                            Text(
                                t(language, "Oí: “$heardLive”$suffix",
                                    "Heard: “$heardLive”$suffix",
                                    "Ouvi: “$heardLive”$suffix"),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (command.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                if (language == "en") "Your last order"
                                else if (language == "pt") "Seu último pedido"
                                else "Tu última orden",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "“$command”",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        // Detener para de todo: tarea y micrófono. El
                        // vigilante no resucita tras un paro deliberado.
                        job?.cancel()
                        voiceActive = false
                        policy.stop()
                        syncPolicy()
                        sentryDesired = false
                        sessionAlive = false
                        suppressNextError = true
                        restartGen += 1
                        recognizer?.cancel()
                        stoppedNote = true
                        state = AgentState.IDLE
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dark) Color(0xFFF5F7FA)
                        else Color(0xFF14213D),
                        contentColor = if (dark) Color(0xFF14213D)
                        else Color(0xFFF5F7FA),
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_square),
                        contentDescription = null,
                    )
                    Text(t(language, "Detener", "Stop", "Parar"))
                }
                if (state == AgentState.FAILED && lastResult.isNotEmpty()) {
                    Text(
                        lastResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = stateColor,
                        textAlign = TextAlign.Center,
                    )
                }

            }
                }
            }
        }
    }

    val actionToConfirm = pendingAction
    if (actionToConfirm != null) {
        AlertDialog(
            onDismissRequest = { gate?.complete(false) },
            title = { Text(t(language, "Confirmar acción", "Confirm action", "Confirmar ação")) },
            text = {
                Text(t(language, "Vas a realizar esta acción:\n${describe(actionToConfirm)}",
                    "You are about to do this:\n${describe(actionToConfirm)}",
                    "Você vai fazer isto:\n${describe(actionToConfirm)}"))
            },
            confirmButton = {
                TextButton(onClick = { gate?.complete(true) }) {
                    Text(t(language, "Sí, continuar", "Yes, continue", "Sim, continuar"))
                }
            },
            dismissButton = {
                TextButton(onClick = { gate?.complete(false) }) {
                    Text(t(language, "Cancelar", "Cancel", "Cancelar"))
                }
            },
        )
    }
}

/** Cadena trilingüe inline. TODO(i18n): mover a recursos es/en/pt. */
private fun t(lang: String, es: String, en: String, pt: String): String =
    if (lang == "en") en else if (lang == "pt") pt else es

// La palabra de activación vive en VoicePolicy (agent): la UI y las
// pruebas la usan desde ahí.

@Composable
private fun SettingsScreen(
    language: String,
    onLanguage: (String) -> Unit,
    wakeWord: String,
    onWakeWord: (String) -> Unit,
    serviceOn: Boolean,
    continuous: Boolean,
    onContinuous: (Boolean) -> Unit,
    plannerMode: String,
    onPlannerMode: (String) -> Unit,
    metaKey: String,
    onMetaKey: (String) -> Unit,
    onOpenAccessibility: () -> Unit,
    onBack: () -> Unit,
) {
    val wakeShown = wakeWord.ifBlank { WAKE_WORD }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = t(language, "Volver", "Back", "Voltar"),
                )
            }
            Text(
                t(language, "Ajustes", "Settings", "Configurações"),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        // Voz: idioma, palabra de activación y escucha continua.
        SettingsSection(t(language, "Voz", "Voice", "Voz")) {
            SettingsRow(
                icon = R.drawable.ic_lucide_languages,
                title = t(language, "Idioma", "Language", "Idioma"),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("es" to "ES", "en" to "EN", "pt" to "PT").forEach { (code, label) ->
                        val selected = code == language
                        Surface(
                            onClick = { onLanguage(code) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = wakeWord,
                onValueChange = onWakeWord,
                label = {
                    Text(t(language, "Palabra de activación",
                        "Wake word", "Palavra de ativação"))
                },
                placeholder = { Text(WAKE_WORD) },
                supportingText = {
                    Text(t(language,
                        "Di «$wakeShown» + tu orden. Vacío usa «$WAKE_WORD».",
                        "Say “$wakeShown” + your order. Empty uses “$WAKE_WORD”.",
                        "Diga “$wakeShown” + seu pedido. Vazio usa “$WAKE_WORD”."))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsRow(
                icon = R.drawable.ic_lucide_mic,
                title = t(language, "Escucha continua",
                    "Continuous listening", "Escuta contínua"),
                subtitle = t(language, "Vuelve a escuchar tras cada orden.",
                    "Listens again after each order.",
                    "Volta a ouvir após cada pedido."),
            ) {
                Switch(checked = continuous, onCheckedChange = onContinuous)
            }
        }

        // Planificador: gratis (stub) o Meta con clave en el teléfono.
        SettingsSection(t(language, "Planificador", "Planner", "Planejador")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val stubFirst = plannerMode != "meta"
                Surface(
                    onClick = { onPlannerMode("stub") },
                    shape = RoundedCornerShape(16.dp),
                    color = if (stubFirst) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (stubFirst) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        t(language, "Gratis", "Free", "Grátis"),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Surface(
                    onClick = { onPlannerMode("meta") },
                    shape = RoundedCornerShape(16.dp),
                    color = if (!stubFirst) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (!stubFirst) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "Meta",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            if (plannerMode == "meta") {
                OutlinedTextField(
                    value = metaKey,
                    onValueChange = onMetaKey,
                    label = { Text("MODEL_API_KEY") },
                    placeholder = { Text("LLM|…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    t(language, "La clave vive solo en este teléfono.",
                        "The key stays on this phone only.",
                        "A chave fica só neste telefone."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Acceso: servicio de accesibilidad (se activa a mano una vez).
        SettingsSection(t(language, "Acceso", "Access", "Acesso")) {
            SettingsRow(
                icon = R.drawable.ic_lucide_shield_check,
                title = t(language, "Accesibilidad", "Accessibility", "Acessibilidade"),
                subtitle = if (serviceOn) t(language, "Servicio activo.",
                    "Service on.", "Serviço ativo.")
                else t(language, "Apagado. Se activa a mano una sola vez.",
                    "Off. Enable it by hand once.",
                    "Desativado. Ative manualmente uma vez."),
            ) {
                Icon(
                    painter = painterResource(
                        if (serviceOn) R.drawable.ic_lucide_circle_check
                        else R.drawable.ic_lucide_badge_alert),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            Button(
                onClick = onOpenAccessibility,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(t(language, "Abrir ajustes de accesibilidad",
                    "Open accessibility settings", "Abrir configurações de acessibilidade"))
            }
        }

        Text(
            t(language, "Privacidad: las capturas se envían a Meta Model API solo en modo Meta.",
                "Privacy: screenshots reach Meta Model API only in Meta mode.",
                "Privacidade: capturas chegam à Meta Model API só no modo Meta."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Sección de ajustes: etiqueta chica + tarjeta suave con filas. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}

/** Fila mínima: ícono Lucide, título (+subtítulo) y control a la derecha. */
@Composable
private fun SettingsRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing()
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
