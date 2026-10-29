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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    // Micrófono abierto (modo continuo): se ignora todo lo que no traiga
    // la palabra de activación y se sigue escuchando.
    var voiceActive by remember { mutableStateOf(false) }
    var errorStreak by remember { mutableStateOf(0) }
    // true = el micrófono se abrió solo (cadena/auto); ahí sí se exige la
    // palabra. Si lo abrió un toque, la palabra sobra.
    var handsFree by remember { mutableStateOf(false) }
    // true = ya se oyó la palabra sola; lo siguiente es la orden directa.
    var expectOrder by remember { mutableStateOf(false) }
    // Centinela: micrófono abierto esperando SOLO la palabra. Se muestra
    // como Listo con orbe quieto, nunca como Escuchando.
    var sentry by remember { mutableStateOf(false) }
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
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }
    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }
    // El micrófono no pisa YouTube, audios ni llamadas: mientras otro audio
    // suena, la escucha se pausa sola y vuelve al terminar.
    var audioDucked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(
            android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val callback = object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(
                configs: List<android.media.AudioPlaybackConfiguration>
            ) {
                val otherAudio = configs.any { it.isActive }
                if (otherAudio && voiceActive && !audioDucked) {
                    audioDucked = true
                    android.util.Log.d("AnyhandVoice", "pausa por audio externo")
                    try {
                        recognizer?.stopListening()
                    } catch (_: Exception) {
                    }
                } else if (!otherAudio && audioDucked && voiceActive) {
                    audioDucked = false
                    android.util.Log.d("AnyhandVoice", "vuelve tras audio externo")
                    startListeningInternal()
                }
            }
        }
        manager.registerAudioPlaybackCallback(callback, null)
        onDispose { manager.unregisterAudioPlaybackCallback(callback) }
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
            handsFree = false
            sentry = false
            state = AgentState.FAILED
            lastResult = hearError()
            return
        }
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // Sin escucha previa: nada que cancelar.
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
                    state = if (sentry) AgentState.IDLE else AgentState.LISTENING
                    errorStreak = 0
                    android.util.Log.d("AnyhandVoice",
                        "mic abierto (lang=$language sentry=$sentry)")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    // Pausado por audio externo: el error por stop es
                    // esperado y no cuenta en la racha.
                    if (audioDucked) return
                    // En modo continuo con micrófono abierto, un error
                    // transitorio reabre el micrófono; tras 3 seguidos se
                    // rinde con mensaje (evita drenar la batería).
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
                    android.util.Log.d("AnyhandVoice", "error=$name racha=$errorStreak")
                    errorStreak++
                    if (voiceActive && errorStreak <= 3) {
                        startListeningInternal()
                    } else {
                        voiceActive = false
                        handsFree = false
                        sentry = false
                        state = AgentState.FAILED
                        lastResult = hearError() + " ($name)"
                    }
                }

                override fun onResults(results: Bundle?) {
                    // Llegó tarde durante la pausa: se descarta.
                    if (audioDucked) return
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    android.util.Log.d("AnyhandVoice", "oído=«$heard»")
                    if (heard == null) {
                        // Resultado vacío (silencio): como error transitorio,
                        // no mata la cadena manos-libres.
                        errorStreak++
                        if (voiceActive && errorStreak <= 3) {
                            startListeningInternal()
                        } else {
                            voiceActive = false
                            handsFree = false
                            sentry = false
                            state = AgentState.FAILED
                            lastResult = hearError() + " (VACIO)"
                        }
                        return
                    }
                    // La palabra solo se exige cuando el micrófono se abrió
                    // solo (centinela/cadena). Si lo abrió un toque, sobra.
                    // Tras oír la palabra sola, lo siguiente es la orden.
                    val needWake = voiceActive && handsFree && !expectOrder
                    if (needWake && !containsWakeWord(heard)) {
                        // Habla sin la palabra: se muestra, se ignora la orden
                        // y se sigue escuchando.
                        heardLive = heard
                        startListeningInternal()
                        return
                    }
                    val order = if (containsWakeWord(heard)) {
                        stripWakeWord(heard)
                    } else heard
                    if (order.isBlank()) {
                        // Solo dijo la palabra: avisa y espera la orden directa.
                        heardLive = heard
                        expectOrder = true
                        startListeningInternal()
                        return
                    }
                    expectOrder = false
                    sentry = false
                    stoppedNote = false
                    voiceActive = continuous
                    command = order
                    startRun(order)
                }

                override fun onPartialResults(partial: Bundle?) {
                    // El parcial solo alimenta la línea "Oí:": la tarjeta de
                    // última orden muestra únicamente órdenes aceptadas.
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { heardLive = it }
                }
            })
            try {
                startListening(intent)
            } catch (_: Exception) {
                state = AgentState.FAILED
                lastResult = hearError()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListeningInternal()
        else {
            voiceActive = false
            state = AgentState.FAILED
            lastResult = t(language, "Sin permiso de micrófono no puedo escucharte.",
                "Without microphone permission I cannot hear you.",
                "Sem permissão de microfone não posso ouvir você.")
        }
    }

    fun launchVoiceInput(auto: Boolean = false) {
        if (state == AgentState.ACTING || state == AgentState.VERIFYING ||
            state == AgentState.AWAITING_CONFIRMATION) return
        if (voiceActive && !auto) {
            // Segundo toque: cierra el micrófono.
            voiceActive = false
            handsFree = false
            recognizer?.stopListening()
            recognizer?.cancel()
            state = AgentState.IDLE
            return
        }
        voiceActive = true
        handsFree = auto
        expectOrder = false
        sentry = false
        stoppedNote = false
        audioDucked = false
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
            handsFree = true
            sentry = true
            startListeningInternal()
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                t(language, "Para empezar, activa el acceso",
                                    "To begin, enable access", "Para começar, ative o acesso"),
                                style = MaterialTheme.typography.headlineMedium,
                            )
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
                                modifier = Modifier.fillMaxWidth(),
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
                            else if (sentry) t(language, "Mic: esperando «Anyhand»",
                                "Mic: waiting for “Anyhand”",
                                "Mic: aguardando “Anyhand”")
                            else t(language, "Mic: escuchando",
                                "Mic: listening", "Mic: ouvindo"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (heardLive.isNotEmpty()) {
                            val gated = voiceActive && handsFree && !expectOrder
                            Text(
                                t(language, "Oí: “$heardLive”" +
                                    if (gated) " — di Anyhand + tu orden." else ".",
                                    "Heard: “$heardLive”" +
                                    if (gated) " — say Anyhand + your order." else ".",
                                    "Ouvi: “$heardLive”" +
                                    if (gated) " — diga Anyhand + seu pedido." else "."),
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
                        // Detener frena la tarea pero deja la voz viva: con
                        // escucha continua sigue escuchando, sin ella queda
                        // el centinela con la palabra de activación. Sin
                        // toques: la próxima orden entra sola por voz.
                        handsFree = continuous
                        sentry = !continuous
                        expectOrder = false
                        audioDucked = false
                        stoppedNote = true
                        voiceActive = true
                        startListeningInternal()
                        recognizer?.cancel()
                        job?.cancel()
                        state = AgentState.IDLE
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                    shape = RoundedCornerShape(16.dp),
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

/**
 * Palabra de activación elegida (§7 DESIGN.md).
 * La pronunciación cambia por región e idioma ("ani-jand", "eni-jand",
 * "any-hand"): el futuro detector por palabra clave debe aceptar
 * variantes, no coincidencia exacta. Hoy la escucha continua encadena
 * sin detector; esta constante nombra el contrato.
 */
const val WAKE_WORD = "Anyhand"

/** Variantes de pronunciación por región/idioma (minúsculas, con espacio o guion). */
private val WAKE_VARIANTS = listOf(
    "anyhand", "any hand", "any-hand",
    "anihand", "ani hand", "anijand", "ani jand",
    "enihand", "eni hand", "enijand", "eni jand",
    // Parcial cortado ("anyhan"): el reconocedor a veces entrega la
    // palabra sin la última letra; se acepta y se recorta igual.
    "anyhan",
)

private fun normalizeSpeech(s: String): String =
    s.lowercase()
        .replace("á", "a").replace("é", "e").replace("í", "i")
        .replace("ó", "o").replace("ú", "u").replace("ñ", "n")

/** ¿Contiene la palabra de activación? */
fun containsWakeWord(heard: String): Boolean {
    val compact = normalizeSpeech(heard).replace(Regex("[^a-z]"), "")
    if ("anyhand" in compact) return true
    val spaced = normalizeSpeech(heard)
    return WAKE_VARIANTS.any { it.replace(" ", "") in compact || it in spaced }
}

/** Quita la palabra de activación y devuelve la orden (o "" si solo la dijo). */
fun stripWakeWord(heard: String): String {
    var rest = heard
    for (variant in WAKE_VARIANTS.sortedByDescending { it.length }) {
        rest = rest.replace(
            Regex("(?i)\\b" + Regex.escape(variant).replace(" ", "[- ]?") + "\\b"), " ")
    }
    return rest.replace(Regex("\\s+"), " ").trim()
}

@Composable
private fun SettingsScreen(
    language: String,
    onLanguage: (String) -> Unit,
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text(
            t(language, "Idioma", "Language", "Idioma"),
            style = MaterialTheme.typography.titleSmall,
        )
        val options = listOf("es" to "Español", "en" to "English", "pt" to "Português")
        options.forEach { (code, label) ->
            if (code == language) {
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(label, maxLines = 1)
                }
            } else {
                TextButton(
                    onClick = { onLanguage(code) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label, maxLines = 1) }
            }
        }
        Text(
            t(language, "Escucha", "Listening", "Escuta"),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t(language, "Escucha continua", "Continuous listening", "Escuta contínua"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = continuous, onCheckedChange = onContinuous)
        }
        Text(
            t(language, "Al terminar una orden, vuelve a escuchar solo. " +
                "Interino hasta la palabra de activación.",
                "After each order, it listens again on its own. " +
                "Stand-in until the wake word.",
                "Após cada pedido, volta a ouvir sozinho. " +
                "Provisório até a palavra de ativação."),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            t(language, "Planificador", "Planner", "Planejador"),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stubFirst = plannerMode != "meta"
            Button(
                onClick = { onPlannerMode("stub") },
                enabled = !stubFirst,
                modifier = Modifier.weight(1f),
            ) { Text(t(language, "Gratis", "Free", "Grátis")) }
            Button(
                onClick = { onPlannerMode("meta") },
                enabled = stubFirst,
                modifier = Modifier.weight(1f),
            ) { Text("Meta") }
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
        Text(
            t(language, "Acceso", "Access", "Acesso"),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (serviceOn) t(language, "Servicio de accesibilidad: activo.",
                "Accessibility service: on.", "Serviço de acessibilidade: ativo.")
            else t(language, "Servicio de accesibilidad: apagado. Se activa a mano una sola vez.",
                "Accessibility service: off. Enable it by hand once.",
                "Serviço de acessibilidade: desativado. Ative manualmente uma vez."),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(t(language, "Abrir ajustes de accesibilidad",
                "Open accessibility settings", "Abrir configurações de acessibilidade"))
        }
        Text(
            t(language, "Privacidad: las capturas se envían a Meta Model API solo en modo Meta.",
                "Privacy: screenshots reach Meta Model API only in Meta mode.",
                "Privacidade: capturas chegam à Meta Model API só no modo Meta."),
            style = MaterialTheme.typography.bodySmall,
        )
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
