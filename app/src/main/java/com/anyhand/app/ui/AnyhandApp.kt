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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anyhand.app.agent.AccessibilityDriver
import com.anyhand.app.agent.AccessibilityDriverService
import com.anyhand.app.agent.AgentAction
import com.anyhand.app.agent.AgentVoiceService
import com.anyhand.app.agent.extractWakeCommand
import com.anyhand.app.agent.extractOpenAppTarget
import com.anyhand.app.agent.AgentRunner
import com.anyhand.app.agent.AgentState
import com.anyhand.app.agent.MetaPlanner
import com.anyhand.app.agent.Planner
import com.anyhand.app.agent.StatusText
import com.anyhand.app.agent.StubPlannerAdapter
import com.anyhand.app.agent.VoicePolicy
import com.anyhand.app.agent.VoiceStep
import com.anyhand.app.agent.speechLocale
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
    val activity = remember { context as? androidx.activity.ComponentActivity }
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("es") }
    var state by remember { mutableStateOf(AgentState.IDLE) }
    var lastResult by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<AgentAction?>(null) }
    // Puerta de envío: espera tu sí (voz o toque) antes de Enviar.
    var sendGate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
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
    // Último parcial oído en la sesión: respaldo si el final viene vacío.
    var lastPartial by remember { mutableStateOf("") }
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
    var overlayAllowed by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
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
    // ¿La app está en primer plano? El vigilante no pelea con el servicio
    // de fondo cuando la app está minimizada.
    var foreground by remember { mutableStateOf(true) }
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
            overlayAllowed = Settings.canDrawOverlays(context)
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

    /** Pasa la escucha al servicio de fondo (escucha continua). */
    fun startBackgroundService() {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, AgentVoiceService::class.java))
        } catch (e: Exception) {
            android.util.Log.d("AnyhandVoice",
                "servicio de fondo no arrancó: ${e.message}")
        }
    }


    /** Abre una app por su nombre visible. Devuelve el nombre o null. */
    fun launchAppByName(target: String): String? {
        val wanted = com.anyhand.app.agent.normalizeSpeech(target)
            .replace(Regex("[^a-z0-9]"), "")
        if (wanted.isBlank()) return null
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val matches = pm.queryIntentActivities(main, 0).mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val compact = com.anyhand.app.agent.normalizeSpeech(label)
                .replace(Regex("[^a-z0-9]"), "")
            val pkg = info.activityInfo.packageName
            if (compact.isNotEmpty() &&
                (compact.contains(wanted) || wanted.contains(compact))
            ) label to pkg else null
        }
        val (label, pkg) = matches.firstOrNull() ?: return null
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            startBackgroundService()
            label
        } catch (_: Exception) { null }
    }

    fun startRun(task: String) {
        if (task.isBlank()) return
        val metrics = context.resources.displayMetrics
        val lang = language
        job?.cancel()
        // "Abre WhatsApp" no pasa por el planificador: el teléfono
        // busca la app por su nombre visible y la abre directo.
        val openTarget = extractOpenAppTarget(task)
        if (openTarget != null) {
            val launched = launchAppByName(openTarget)
            if (launched != null) {
                state = AgentState.DONE
                lastResult = t(lang, "Abriendo $launched",
                    "Opening $launched", "Abrindo $launched")
                return
            }
            lastResult = t(lang,
                "No encontré la app pedida entre tus apps.",
                "I could not find that app among your apps.",
                "Não encontrei esse app entre seus apps.")
            // Sin coincidencia sigue el flujo normal (Meta puede
            // buscarla en pantalla); no se corta la orden aquí.
        }
        // Con escucha continua, Anyhand se quita de en medio: la orden se
        // ejecuta sobre la app que quede al frente (si no, el agente se
        // toca a sí mismo y "no pasa nada en la pantalla"). Al minimizar,
        // el servicio de fondo toma la escucha con su orbe chico.
        // Vía rápida: direcciones (desliza/arriba/swipe up...) se
        // ejecutan al instante, sin planificador ni capturas.
        val quickSwipe = com.anyhand.app.agent.basicSwipeAction(
            task, metrics.widthPixels, metrics.heightPixels)
        if (quickSwipe is com.anyhand.app.agent.AgentAction.Swipe) {
            val leaving = continuous || plannerMode == "meta"
            if (leaving) {
                startBackgroundService()
                activity?.moveTaskToBack(true)
            }
            job = scope.launch {
                if (leaving) kotlinx.coroutines.delay(350)
                val r = AccessibilityDriver().swipe(
                    quickSwipe.x1, quickSwipe.y1,
                    quickSwipe.x2, quickSwipe.y2, quickSwipe.durationMs)
                state = if (r.success) AgentState.DONE else AgentState.FAILED
                lastResult = if (r.success) t(lang, "Hecho", "Done", "Feito")
                    else t(lang, "No se pudo deslizar.",
                        "Could not swipe.", "Não consegui deslizar.")
                if (continuous && !stoppedNote &&
                    kotlinx.coroutines.currentCoroutineContext()
                        [kotlinx.coroutines.Job] == job) {
                    if (foreground) chainVoice = true
                    else startBackgroundService()
                }
            }
            return
        }
        if (continuous || plannerMode == "meta" || TestState.isExitTask(task)) {
            startBackgroundService()
            activity?.moveTaskToBack(true)
        }
        job = scope.launch {
            // Borrar el texto del cuadro ("borra lo anterior").
            if (com.anyhand.app.agent.isClearTextTask(task)) {
                val cDriver = AccessibilityDriver()
                val cc = com.anyhand.app.agent.editableCenter(
                    cDriver.dumpNodes())
                if (cc != null) {
                    cDriver.tap(cc.first, cc.second)
                    kotlinx.coroutines.delay(300)
                    val okC = cDriver.inputText("").success
                    state = if (okC) AgentState.DONE else AgentState.FAILED
                    lastResult = if (okC) t(lang, "Borrado", "Deleted", "Apagado")
                        else t(lang, "No se pudo borrar.",
                            "Could not delete.", "Não consegui apagar.")
                    if (continuous && !stoppedNote &&
                        kotlinx.coroutines.currentCoroutineContext()
                            [kotlinx.coroutines.Job] == job) {
                        if (foreground) chainVoice = true
                        else startBackgroundService()
                    }
                    return@launch
                }
            }
            // Enviar CON confirmación: "manda un hola..." escribe el
            // texto (reversible) y luego pide el sí (voz o toque).
            // Sin sí, Enviar no se toca jamás.
            val sendText = com.anyhand.app.agent.extractSendText(task)
            if (sendText != null ||
                com.anyhand.app.agent.isSendOnlyTask(task)) {
                val sDriver = AccessibilityDriver()
                var okS = true
                if (sendText != null) {
                    val ec = com.anyhand.app.agent.editableCenter(
                        sDriver.dumpNodes())
                    if (ec != null) {
                        sDriver.tap(ec.first, ec.second)
                        kotlinx.coroutines.delay(600)
                        okS = sDriver.inputText(sendText).success
                        kotlinx.coroutines.delay(400)
                    }
                }
                if (okS) {
                    state = AgentState.AWAITING_CONFIRMATION
                    // La app viene al frente a pedir el sí.
                    val front = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                    if (front != null) {
                        front.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(front) } catch (_: Exception) {}
                    }
                    val deferred = CompletableDeferred<Boolean>()
                    sendGate = deferred
                    val approved = deferred.await()
                    sendGate = null
                    if (approved) {
                        // De vuelta a la app objetivo y tocar Enviar
                        // (centro recién calculado, no el viejo).
                        activity?.moveTaskToBack(true)
                        kotlinx.coroutines.delay(500)
                        val nodesS = sDriver.dumpNodes()
                        val sendCenter = com.anyhand.app.agent
                            .sendButtonCenter(nodesS)
                            ?: com.anyhand.app.agent.editableCenter(nodesS)?.let { ec ->
                                (metrics.widthPixels * 0.93f) to ec.second
                            }
                        if (sendCenter != null) {
                            sDriver.tap(sendCenter.first, sendCenter.second)
                            state = AgentState.DONE
                            lastResult = t(lang, "Enviado", "Sent", "Enviado")
                        } else {
                            state = AgentState.FAILED
                            lastResult = t(lang, "No encontré el botón de enviar.",
                                "Send button not found.", "Botão de enviar não encontrado.")
                        }
                    } else {
                        state = AgentState.IDLE
                        lastResult = t(lang, "Envío cancelado. El texto sigue en el cuadro.",
                            "Send cancelled. The text is still in the box.",
                            "Envio cancelado. O texto continua na caixa.")
                    }
                    if (continuous && !stoppedNote &&
                        kotlinx.coroutines.currentCoroutineContext()
                            [kotlinx.coroutines.Job] == job) {
                        if (foreground) chainVoice = true
                        else startBackgroundService()
                    }
                    return@launch
                }
                // Si ni el texto agarró: sigue Meta con la pantalla.
            }
            // Escribir / abrir el teclado sin planificador: tocar el
            // campo editable de esta pantalla y escribir. Si esta
            // pantalla no tiene campo, sigue el flujo normal (Meta).
            val writeText = com.anyhand.app.agent.extractWriteText(task)
            if (writeText != null ||
                com.anyhand.app.agent.isOpenKeyboardTask(task)) {
                val wDriver = AccessibilityDriver()
                val center = com.anyhand.app.agent.editableCenter(
                    wDriver.dumpNodes())
                if (center != null) {
                    wDriver.tap(center.first, center.second)
                    kotlinx.coroutines.delay(700)
                    var okW = true
                    if (writeText != null) {
                        okW = wDriver.inputText(writeText).success
                    }
                    state = if (okW) AgentState.DONE else AgentState.FAILED
                    lastResult = if (okW) t(lang, "Hecho", "Done", "Feito")
                        else t(lang, "No se pudo escribir.",
                            "Could not type.", "Não consegui escrever.")
                    if (continuous && !stoppedNote &&
                        kotlinx.coroutines.currentCoroutineContext()
                            [kotlinx.coroutines.Job] == job) {
                        if (foreground) chainVoice = true
                        else startBackgroundService()
                    }
                    return@launch
                }
            }
            // Sin clave no hay Meta: el runner usa el stub gratis.
            // La clave vive solo en este teléfono, nunca en el repo.
            val planner: Planner = if (plannerMode == "meta" && metaKey.isNotBlank()) {
                MetaPlanner(metaKey.trim())
            } else StubPlannerAdapter()
            val sam = com.anyhand.app.agent.SamRefiner.get(context)
            val samRefiner:
                (suspend (android.graphics.Bitmap, com.anyhand.app.agent.AgentAction.Tap) ->
                    Pair<Float, Float>?)? =
                sam?.let { s -> { shot, tap -> s.refine(shot, tap.x, tap.y) } }
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
                tapRefiner = samRefiner,
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
            // Escucha continua de verdad: al terminar la orden (salga
            // bien o mal) la escucha vuelve sola. En primer plano como
            // centinela con la palabra; minimizada la toma el servicio.
            if (continuous && !stoppedNote &&
                kotlinx.coroutines.currentCoroutineContext()
                    [kotlinx.coroutines.Job] == job) {
                if (foreground) {
                    // La cadena existente reabre la escucha manos
                    // libres al volver (sin llamar hacia adelante).
                    chainVoice = true
                } else {
                    startBackgroundService()
                }
            }
        }
    }

    // Reconocimiento DENTRO de la app (ya no abre la pantalla de Google):
    // el orbe escucha, el texto parcial aparece en vivo y al terminar
    // arranca la orden solo. UNA sola instancia: destruir/crear en cada
    // arranque causaba ERROR_CLIENT por carrera.
    // Si el servicio de reconocimiento falla seguido, se cambia al
    // reconocedor en el dispositivo (Android 12+): otro backend, sin red.
    var useOnDevice by remember { mutableStateOf(false) }
    var onDeviceBanned by remember { mutableStateOf(false) }
    var giveUpCount by remember { mutableStateOf(0) }
    // CODE_12 (idioma no disponible): reintento con la etiqueta completa
    // del teléfono. null = usar speechLocale(language, teléfono).
    var speechTagOverride by remember { mutableStateOf<String?>(null) }
    // La marca "ignora el próximo error" (eco de nuestro cancel) caduca:
    // sin caducidad se quedaba pegada y se tragaba el error real
    // siguiente, dejando el micrófono muerto sin reintento.
    var suppressErrorUntil by remember { mutableStateOf(0L) }
    fun newRecognizer(): SpeechRecognizer? {
        val onDevice = useOnDevice &&
            android.os.Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }
    var recognizer by remember { mutableStateOf(newRecognizer()) }
    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }

    /** Recupera la escucha del servicio de fondo al volver. */
    fun stopBackgroundService() {
        try {
            context.stopService(Intent(context, AgentVoiceService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Reconocedor nuevo: tras rachas de error el viejo queda sordo. */
    fun resetRecognizer() {
        giveUpCount++
        if (giveUpCount >= 2 && !useOnDevice && !onDeviceBanned) {
            useOnDevice = true
            android.util.Log.d("AnyhandVoice",
                "cambiando a reconocedor en el dispositivo")
        }
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

    /** Rendirse tras la racha: en centinela el silencio NO es error (el
     * vigilante reabre sin pintar el orbe rojo); dictando sí se muestra. */
    fun showGiveUp(suffix: String) {
        if (!sentryDesired) {
            state = AgentState.FAILED
            lastResult = hearError() + suffix
        }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                speechTagOverride ?: speechLocale(
                    language,
                    java.util.Locale.getDefault().toLanguageTag()))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Tiempos de silencio más altos: evita que corte la frase a
            // media voz fuerte o pausada. No existe control de ganancia.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
            // Sin EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS: con 2000 ms
            // las órdenes cortas ("cierra la aplicación") se descartaban
            // como "muy cortas" y no se oía nada (NO_MATCH en bucle).
        }
        lastPartial = ""
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

                override fun onBeginningOfSpeech() {
                    // Prueba de vida en el registro: el micrófono SÍ oye
                    // voz (si nunca aparece, el problema es el backend).
                    android.util.Log.d("AnyhandVoice", "voz detectada")
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    // La sesión terminó (con error o cancelación).
                    sessionAlive = false
                    // Eco de nuestro propio cancel(): se consume SOLO
                    // si llega enseguida. Si la marca caducó, este error
                    // es real y debe reintentar: antes la marca se
                    // quedaba pegada, se tragaba un silencio normal y el
                    // micrófono moría sin que nadie lo reabriera.
                    if (suppressNextError &&
                        android.os.SystemClock.uptimeMillis() <= suppressErrorUntil
                    ) {
                        suppressNextError = false
                        android.util.Log.d("AnyhandVoice", "eco de cancel propio")
                        return
                    }
                    suppressNextError = false
                    // CODE_12 = idioma no disponible en ESTE backend.
                    // Escalera de respaldo: 1) etiqueta completa del
                    // teléfono, 2) reconocedor en el dispositivo,
                    // 3) volver al de siempre ya prohibido el anterior.
                    // Antes solo se atendía en el dispositivo y el de
                    // siempre reintentaba sordo para siempre.
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        val deviceTag =
                            java.util.Locale.getDefault().toLanguageTag()
                        val currentTag = speechTagOverride ?: speechLocale(
                            language, deviceTag)
                        when {
                            useOnDevice -> {
                                useOnDevice = false
                                onDeviceBanned = true
                                android.util.Log.d("AnyhandVoice",
                                    "CODE_12 en dispositivo: prohibido, " +
                                        "vuelvo al de siempre")
                                resetRecognizer()
                            }
                            currentTag != deviceTag -> {
                                speechTagOverride = deviceTag
                                android.util.Log.d("AnyhandVoice",
                                    "CODE_12: reintento con idioma del " +
                                        "teléfono ($deviceTag)")
                            }
                            !onDeviceBanned &&
                                android.os.Build.VERSION.SDK_INT >= 31 &&
                                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                                useOnDevice = true
                                android.util.Log.d("AnyhandVoice",
                                    "CODE_12: cambio a reconocedor en el dispositivo")
                                resetRecognizer()
                            }
                            else -> {
                                voiceActive = false
                                policy.stop()
                                syncPolicy()
                                showGiveUp(" (CODE_12)")
                                android.util.Log.d("AnyhandVoice",
                                    "CODE_12 sin respaldo disponible")
                                return
                            }
                        }
                        if (voiceActive) {
                            restartGen += 1
                            val langGen = restartGen
                            mainHandler.postDelayed({
                                if (langGen == restartGen && voiceActive) {
                                    startListeningInternal()
                                }
                            }, 300)
                        }
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
                            showGiveUp(" (BUSY)")
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
                            showGiveUp(" ($name)")
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
                    // Si el resultado final viene vacío pero hubo parcial,
                    // vale el parcial: el finalizador a veces lo tira.
                    val heard = com.anyhand.app.agent.bestHeard(
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
                        policy.wakeWord)
                        ?: lastPartial.ifBlank { null }
                    if (heard != null) giveUpCount = 0
                    android.util.Log.d("AnyhandVoice", "oído=«$heard»")
                    // Apagado de una palabra ("apágate"): manda sobre
                    // todo. Seis palabras como "apagar la luz" no.
                    if (com.anyhand.app.agent.isStopCommand(heard)) {
                        job?.cancel()
                        voiceActive = false
                        sentryDesired = false
                        policy.stop()
                        syncPolicy()
                        sessionAlive = false
                        suppressNextError = true
                        suppressErrorUntil = android.os.SystemClock.uptimeMillis() + 2000
                        restartGen += 1
                        recognizer?.cancel()
                        stopBackgroundService()
                        stoppedNote = true
                        state = AgentState.IDLE
                        lastResult = t(language, "Apagado.", "Off.", "Desligado.")
                        return
                    }
                    // La política decide; la UI solo obedece.
                    // Si hay una confirmación de envío esperando, tu
                    // voz ES la respuesta: sí/no manda sobre órdenes.
                    val gateNow = sendGate
                    if (gateNow != null) {
                        val answer = com.anyhand.app.agent
                            .confirmAnswer(heard)
                        if (answer != null) {
                            gateNow.complete(answer)
                        }
                        return
                    }
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
                            val newWake = extractWakeCommand(step.order)
                            if (newWake != null) {
                                // Cambiar la palabra por voz: se guarda
                                // y la política la usa desde ya.
                                wakeWord = newWake
                                prefs.edit()
                                    .putString("wake_word", newWake).apply()
                                policy.wakeWord = newWake
                                heardLive = heard ?: ""
                                lastResult = t(language,
                                    "Palabra de activación: «$newWake»",
                                    "Wake word: “$newWake”",
                                    "Palavra de ativação: “$newWake”")
                                voiceActive = sentryDesired || continuous
                                if (voiceActive) {
                                    policy.openVoice(
                                        requireWake = true, quiet = true)
                                    syncPolicy()
                                    startListeningInternal()
                                } else {
                                    syncPolicy()
                                }
                            } else {
                                stoppedNote = false
                                voiceActive = continuous
                                syncPolicy()
                                command = step.order
                                startRun(step.order)
                            }
                        }
                        VoiceStep.GiveUp -> {
                            // Solo llega aquí con resultado vacío y la
                            // racha agotada (onHeard(null) → onFailure).
                            voiceActive = false
                            syncPolicy()
                            showGiveUp(" (VACIO)")
                            resetRecognizer()
                        }
                    }
                }

                override fun onPartialResults(partial: Bundle?) {
                    // El parcial alimenta la línea "Oí:" y queda de respaldo
                    // si el resultado final viene vacío.
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let {
                            heardLive = it
                            lastPartial = it
                        }
                }
            })
            try {
                if (sessionAlive) {
                    // Sesión anterior aún viva = YA está escuchando:
                    // no hay nada que reabrir. Antes se cancelaba y se
                    // reabría, y el eco de ese cancel dejaba la marca de
                    // "ignora el próximo error" pegada: se tragaba el
                    // silencio real siguiente y el micrófono moría.
                    android.util.Log.d("AnyhandVoice",
                        "ya escuchando: no se toca la sesión")
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
            suppressErrorUntil = android.os.SystemClock.uptimeMillis() + 2000
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
            // Continua: la siguiente orden va directa, sin repetir
            // la palabra tras cada instrucción.
            if (continuous) policy.resumeContinuous()
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
            val confirmWaiting = sendGate != null && !sessionAlive
            if (foreground &&
                ((sentryDesired && !voiceActive && job?.isActive != true &&
                    state != AgentState.AWAITING_CONFIRMATION) ||
                    confirmWaiting)
            ) {
                android.util.Log.d("AnyhandVoice", "vigilante reabre centinela")
                policy.openVoice(requireWake = true, quiet = true)
                voiceActive = true
                syncPolicy()
                startListeningInternal()
            }
        }
    }
    // Ciclo de vida: al minimizar con escucha continua, el servicio de
    // fondo toma el micrófono (este se suelta para no pelear); al volver,
    // la app lo recupera y re-engancha el centinela si no había nada.
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    // ¿El servicio dejó un envío esperando tu sí?
                    val pendingAt = prefs.getLong("pending_send", 0L)
                    if (pendingAt > 0 &&
                        System.currentTimeMillis() - pendingAt < 300_000) {
                        prefs.edit().remove("pending_send").apply()
                        scope.launch {
                            val d = CompletableDeferred<Boolean>()
                            sendGate = d
                            state = AgentState.AWAITING_CONFIRMATION
                            val okSend = d.await()
                            sendGate = null
                            if (okSend) {
                                val m = context.resources.displayMetrics
                                activity?.moveTaskToBack(true)
                                kotlinx.coroutines.delay(500)
                                val drv = AccessibilityDriver()
                                val nodesP = drv.dumpNodes()
                                val sc = com.anyhand.app.agent
                                    .sendButtonCenter(nodesP)
                                    ?: com.anyhand.app.agent
                                        .editableCenter(nodesP)?.let { ec ->
                                            (m.widthPixels * 0.93f) to ec.second
                                        }
                                if (sc != null) {
                                    drv.tap(sc.first, sc.second)
                                    lastResult = t(language, "Enviado", "Sent", "Enviado")
                                } else {
                                    lastResult = t(language, "No encontré el botón de enviar.",
                                        "Send button not found.", "Botão de enviar não encontrado.")
                                }
                                state = AgentState.DONE
                            } else {
                                state = AgentState.IDLE
                                lastResult = t(language, "Envío cancelado. El texto sigue en el cuadro.",
                                    "Send cancelled. The text is still in the box.",
                                    "Envio cancelado. O texto continua na caixa.")
                            }
                        }
                    }
                    stopBackgroundService()
                    if (!voiceActive && job?.isActive != true &&
                        state != AgentState.ACTING &&
                        state != AgentState.VERIFYING &&
                        state != AgentState.AWAITING_CONFIRMATION &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        sentryDesired = true
                        policy.openVoice(requireWake = true, quiet = true)
                        voiceActive = true
                        syncPolicy()
                        startListeningInternal()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    foreground = false
                    if (continuous && voiceActive) {
                        startBackgroundService()
                        voiceActive = false
                        policy.stop()
                        syncPolicy()
                        sessionAlive = false
                        suppressNextError = true
                        suppressErrorUntil = android.os.SystemClock.uptimeMillis() + 2000
                        recognizer?.cancel()
                    }
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    AnyhandTheme(darkTheme = dark) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Column {
                    // Detener hasta el borde (full width, sin esquinas):
                    // ícono alineado con latido sutil mientras trabaja.
                    if (!showSettings) {
                        val working = state == AgentState.PLANNING ||
                            state == AgentState.ACTING ||
                            state == AgentState.VERIFYING
                        val stopTransition =
                            rememberInfiniteTransition(label = "detener")
                        val stopScale by stopTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "stopScale",
                        )
                        Button(
                            onClick = {
                                // Detener para de todo: tarea y micrófono.
                                job?.cancel()
                                voiceActive = false
                                policy.stop()
                                syncPolicy()
                                sentryDesired = false
                                sessionAlive = false
                                suppressNextError = true
                                suppressErrorUntil = android.os.SystemClock.uptimeMillis() + 2000
                                restartGen += 1
                                recognizer?.cancel()
                                stopBackgroundService()
                                stoppedNote = true
                                state = AgentState.IDLE
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (dark) Color(0xFFF5F7FA)
                                else Color(0xFF14213D),
                                contentColor = if (dark) Color(0xFF14213D)
                                else Color(0xFFF5F7FA),
                            ),
                        ) {
                            Icon(
                                painter = painterResource(
                                    R.drawable.ic_lucide_square),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .then(
                                        if (working && animationsEnabled)
                                            Modifier.scale(stopScale)
                                        else Modifier),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t(language, "Detener", "Stop", "Parar"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_lock),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t(language, "Las capturas se envían a Meta para decidir cada paso.",
                                "Screenshots are sent to Meta to decide each step.",
                                "Capturas são enviadas à Meta para decidir cada passo."),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
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
                        overlayAllowed = overlayAllowed,
                        onOpenOverlay = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse(
                                        "package:${context.packageName}"),
                                ))
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
                                Text(
                                    t(language, "Activar acceso",
                                        "Enable access", "Ativar acesso"),
                                    style = MaterialTheme.typography.labelMedium,
                                )
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

    if (sendGate != null) {
        AlertDialog(
            onDismissRequest = { sendGate?.complete(false) },
            title = { Text(t(language, "¿Enviar el mensaje?", "Send the message?", "Enviar a mensagem?")) },
            text = {
                Text(t(language, "Di «sí» para enviar o «no» para cancelar, o toca un botón.",
                    "Say “yes” to send or “no” to cancel, or tap a button.",
                    "Diga “sim” para enviar ou “não” para cancelar, ou toque um botão."))
            },
            confirmButton = {
                TextButton(onClick = { sendGate?.complete(true) }) {
                    Text(t(language, "Sí, enviar", "Yes, send", "Sim, enviar"))
                }
            },
            dismissButton = {
                TextButton(onClick = { sendGate?.complete(false) }) {
                    Text(t(language, "Cancelar", "Cancel", "Cancelar"))
                }
            },
        )
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
    overlayAllowed: Boolean,
    onOpenOverlay: () -> Unit,
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
                                style = MaterialTheme.typography.labelMedium,
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
                        style = MaterialTheme.typography.labelMedium,
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
                        style = MaterialTheme.typography.labelMedium,
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
                Text(
                    t(language, "Abrir ajustes de accesibilidad",
                        "Open accessibility settings", "Abrir configurações de acessibilidade"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            SettingsRow(
                icon = R.drawable.ic_lucide_eye,
                title = t(language, "Orbe flotante",
                    "Floating orb", "Orbe flutuante"),
                subtitle = if (overlayAllowed) t(language,
                    "Permitido: sale al despertar en segundo plano.",
                    "Allowed: appears on wake in background.",
                    "Permitido: aparece ao acordar em segundo plano.")
                else t(language, "Sin permiso: la voz igual funciona.",
                    "Not allowed: voice still works.",
                    "Sem permissão: a voz funciona igual."),
            ) {
                if (!overlayAllowed) {
                    TextButton(onClick = onOpenOverlay) {
                        Text(
                            t(language, "Permitir", "Allow", "Permitir"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
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
