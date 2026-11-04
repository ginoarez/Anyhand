package com.anyhand.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anyhand.app.MainActivity
import com.anyhand.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Escucha en segundo plano (solo con escucha continua activa): centinela
 * con la palabra de activación mientras la app está minimizada. Al
 * despertarla muestra un escaneo azul tenue y el orbe chico flotante
 * (esquina inferior izquierda) y ya se puede dictar la orden, que se
 * ejecuta con el mismo AgentRunner de la pantalla principal.
 *
 * Sin permiso de superposición el orbe no sale, pero la voz funciona
 * igual (la notificación avisa que está escuchando).
 */
class AgentVoiceService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var policy: VoicePolicy
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var running = false
    private var orderJob: kotlinx.coroutines.Job? = null
    private var orbView: OrbView? = null
    private var overlayRoot: android.view.View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var captionView: android.widget.TextView? = null
    private var language = "es"
    private var stopped = false
    // Último parcial oído: si el resultado final viene vacío (pasa
    // seguido en segundo plano), el parcial es la orden.
    private var lastPartial = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("anyhand", MODE_PRIVATE)
        // Etiqueta completa ("es-MX"): con "es" a secas el reconocedor
        // responde CODE_12 (idioma no disponible) y nace sordo.
        language = speechLocale(
            "es", java.util.Locale.getDefault().toLanguageTag())
        val wake = prefs.getString("wake_word", "") ?: ""
        policy = VoicePolicy(wake.ifBlank { WAKE_WORD })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startSentry()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        hideOrb()
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "anyhand_voice"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId, "Anyhand voz",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Anyhand")
            .setContentText("Escuchando en segundo plano")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, 7, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(7, notification)
        }
    }

    private fun newRecognizer(): SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            SpeechRecognizer.createSpeechRecognizer(this)
        } else null

    private var sentryConfigured = false

    private fun startSentry() {
        // El orbe chico vive mientras el servicio vive. OJO: showOrb
        // va DESPUÉS del guardia; antes cada reinicio repintaba el
        // avatar en reposo y pisaba la expresión del agente en curso.
        if (stopped || running || listening) return
        showOrb(active = policy.awaitingOrder)
        if (recognizer == null) recognizer = newRecognizer()
        val rec = recognizer ?: return
        // La política se configura UNA vez: openVoice borra el
        // "ya desperté" (awaitingOrder) y al llamarla en cada reinicio
        // la palabra despertaba y se olvidaba al instante; la orden
        // siguiente se trataba como ruido y nunca se obedecía.
        if (!sentryConfigured) {
            // Con escucha continua el servicio acepta órdenes directas
            // sin repetir la palabra; sin continua, centinela normal.
            val continuous = getSharedPreferences("anyhand", MODE_PRIVATE)
                .getBoolean("continuous", false)
            if (continuous) policy.resumeContinuous()
            else policy.openVoice(requireWake = true, quiet = true)
            sentryConfigured = true
            showOrb(active = policy.awaitingOrder)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                lastPartial = ""
                // Cara de escucha en cuanto el micrófono abre.
                handler.post {
                    if (!running) orbView?.mood = when {
                        policy.awaitingConfirm -> Mood.CONFIRMAR
                        policy.awaitingOrder -> Mood.ESCUCHANDO
                        else -> Mood.DORMIDO
                    }
                }
            }
            override fun onBeginningOfSpeech() {
                Log.d("AnyhandVoice", "fondo voz detectada")
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partial: Bundle?) {
                // Lo que vas diciendo aparece junto al orbe, en vivo:
                // así sabes que sí te está escuchando.
                val text = bestHeard(
                    partial?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION),
                    policy.wakeWord)
                if (!text.isNullOrBlank()) {
                    lastPartial = text
                    // Hablando: la boca se mueve mientras dictas.
                    if (!running) orbView?.mood = Mood.HABLANDO
                    setCaption(text)
                }
            }

            override fun onError(error: Int) {
                listening = false
                // CODE_12: este backend no tiene el idioma pedido.
                // Se reintenta con la etiqueta del teléfono.
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    val deviceTag =
                        java.util.Locale.getDefault().toLanguageTag()
                    if (language != deviceTag) {
                        language = deviceTag
                        Log.d("AnyhandVoice",
                            "fondo CODE_12: reintento con $deviceTag")
                    }
                }
                // En fondo el silencio no se rinde: reintenta con pausa.
                handler.postDelayed({ startSentry() }, 600)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                // Final vacío con parcial oído: vale el parcial (en
                // fondo el finalizador tira el texto con frecuencia;
                // por eso salía «null» aunque sí hubiera voz).
                val heard = bestHeard(
                    results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION),
                    policy.wakeWord)
                    ?: lastPartial.ifBlank { null }
                Log.d("AnyhandVoice", "fondo oyó=«$heard»")
                // Apagado de una palabra: manda sobre todo lo demás.
                if (isStopCommand(heard)) {
                    Log.d("AnyhandVoice", "fondo comando de apagado")
                    stopEverything()
                    return
                }
                if (heard.isNullOrBlank()) {
                    setMood(Mood.CONFUNDIDO, "No te escuché")
                } else {
                    setCaption(heard)
                }
                when (val step = policy.onHeard(heard)) {
                    VoiceStep.ConfirmYes -> executeSendNow()
                    VoiceStep.ConfirmNo -> {
                        setMood(Mood.DORMIDO, "Cancelado; el texto sigue ahí")
                        Log.d("AnyhandVoice", "fondo envío cancelado por voz")
                        restartSoon()
                    }
                    VoiceStep.KeepListening -> restartSoon()
                    VoiceStep.WakeArmed -> {
                        showOrb(active = true)
                        setCaption("Te escucho…")
                        restartSoon()
                    }
                    is VoiceStep.Accept -> {
                        showOrb(active = true)
                        setCaption(step.order)
                        runOrder(step.order)
                    }
                    VoiceStep.GiveUp -> restartSoon()
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // En varios fabricantes (Oplus incluido) la sesión de un
            // servicio sin paquete llamante vuelve vacía siempre.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2000)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                900)
        }
        try {
            rec.startListening(intent)
            listening = true
        } catch (e: Exception) {
            Log.d("AnyhandVoice", "fondo no arrancó: ${e.message}")
            handler.postDelayed({ startSentry() }, 800)
        }
    }

    private fun restartSoon() {
        handler.postDelayed({ startSentry() }, 250)
    }

    /** Abre una app por nombre visible ("abre WhatsApp"). */
    private fun launchAppByName(target: String): Boolean {
        val wanted = normalizeSpeech(target)
            .replace(Regex("[^a-z0-9]"), "")
        if (wanted.isBlank()) return false
        val main = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pkg = packageManager.queryIntentActivities(main, 0)
            .firstOrNull { info ->
                val compact = normalizeSpeech(
                    info.loadLabel(packageManager).toString())
                    .replace(Regex("[^a-z0-9]"), "")
                compact.isNotEmpty() &&
                    (compact.contains(wanted) || wanted.contains(compact))
            }?.activityInfo?.packageName ?: return false
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun runOrder(order: String) {
        running = true
        // "Abre X" es determinista: no pasa por el planificador.
        val openTarget = extractOpenAppTarget(order)
        if (openTarget != null && launchAppByName(openTarget)) {
            running = false
            handler.postDelayed({
                policy.resumeContinuous()
                showOrb(active = true)
                setCaption("Te escucho…")
                startSentry()
            }, 800)
            return
        }
        // Vía rápida: direcciones al instante, sin planificador.
        val quickSwipe = basicSwipeAction(
            order, resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels)
        if (quickSwipe is AgentAction.Swipe) {
            setMood(Mood.TOCANDO, "Deslizando…")
            orderJob = scope.launch {
                val r = AccessibilityDriver().swipe(
                    quickSwipe.x1, quickSwipe.y1,
                    quickSwipe.x2, quickSwipe.y2, quickSwipe.durationMs)
                Log.d("AnyhandVoice",
                    "fondo desliza rápido ok=${r.success}")
                running = false
                handler.postDelayed({
                    policy.resumeContinuous()
                    showOrb(active = true)
                    setCaption("Te escucho…")
                    startSentry()
                }, 300)
            }
            return
        }
        // Borrar el texto del cuadro: "borra lo que escribiste",
        // "borra lo anterior"... Se limpia el campo y listo.
        if (isClearTextTask(order)) {
            orderJob = scope.launch {
                val driver = AccessibilityDriver()
                val center = editableCenter(driver.dumpNodes())
                var ok = false
                if (center != null) {
                    setMood(Mood.TOCANDO, "Borrando…")
                    driver.tap(center.first, center.second)
                    kotlinx.coroutines.delay(300)
                    ok = driver.inputText("").success
                }
                Log.d("AnyhandVoice", "fondo borrar texto ok=$ok")
                if (ok) setMood(Mood.LISTO, "Borrado")
                running = false
                handler.postDelayed({
                    policy.resumeContinuous()
                    showOrb(active = true)
                    setCaption("Te escucho…")
                    startSentry()
                }, 400)
            }
            return
        }
        // Enviar: "manda un hola..." (escribe y envía) o "mándalo"
        // (envía lo escrito). El botón se busca por su etiqueta
        // Enviar/Send; Meta ya no adivina ese toque y lo erraba.
        val sendText = extractSendText(order)
        if (sendText != null || isSendOnlyTask(order)) {
            orderJob = scope.launch {
                val driver = AccessibilityDriver()
                var ok = true
                if (sendText != null) {
                    val ec = editableCenter(driver.dumpNodes())
                    if (ec != null) {
                        setMood(Mood.TOCANDO, "Escribiendo…")
                        driver.tap(ec.first, ec.second)
                        kotlinx.coroutines.delay(600)
                        ok = driver.inputText(sendText).success
                        kotlinx.coroutines.delay(400)
                    }
                }
                if (!ok) {
                    startMetaOrder(order)
                    return@launch
                }
                // Enviar es irreversible: JAMÁS se toca Enviar sin
                // confirmación. Pero la confirmación es POR VOZ y
                // AQUÍ MISMO, sin arrastrar al usuario a la app:
                // el servicio sigue escuchando y tu "sí" envía;
                // tu "no" cancela y el texto se queda en el cuadro.
                policy.expectConfirm()
                Log.d("AnyhandVoice",
                    "fondo esperando sí/no para enviar")
                setMood(Mood.CONFIRMAR, "¿Lo envío? Di sí o no")
                running = false
                handler.postDelayed({
                    policy.resumeContinuous()
                    showOrb(active = true)
                    setCaption(if (policy.awaitingConfirm) "¿Lo envío? Di sí o no" else "Te escucho…")
                    startSentry()
                }, 400)
            }
            return
        }
        // Escribir / abrir el teclado: se toca el campo editable de
        // ESTA pantalla (el cuadro de mensajes en WhatsApp, Instagram
        // o la app que sea) y se escribe. Sin planificador.
        val writeText = extractWriteText(order)
        if (writeText != null || isOpenKeyboardTask(order)) {
            orderJob = scope.launch {
                val driver = AccessibilityDriver()
                val center = editableCenter(driver.dumpNodes())
                if (center != null) {
                    setMood(Mood.TOCANDO, "Abriendo el teclado…")
                    driver.tap(center.first, center.second)
                    kotlinx.coroutines.delay(700)
                    var ok = true
                    if (writeText != null) {
                        setMood(Mood.TOCANDO, "Escribiendo…")
                        ok = driver.inputText(writeText).success
                    }
                    Log.d("AnyhandVoice",
                        "fondo escribir ok=$ok centro=$center")
                    if (!ok) {
                        startMetaOrder(order)
                        return@launch
                    }
                    setMood(Mood.LISTO, "Listo")
                    running = false
                    handler.postDelayed({
                        policy.resumeContinuous()
                        showOrb(active = true)
                        setCaption("Te escucho…")
                        startSentry()
                    }, 400)
                    return@launch
                }
                // Sin campo editable aquí: que decida Meta.
                startMetaOrder(order)
            }
            return
        }
        startMetaOrder(order)
    }

    /** Orden completa con planificador (Meta o stub) y avatar. */
    private fun startMetaOrder(order: String) {
        val prefs = getSharedPreferences("anyhand", MODE_PRIVATE)
        val mode = prefs.getString("planner", "stub") ?: "stub"
        val key = (prefs.getString("meta_key", "") ?: "").trim()
        val planner: Planner =
            if (mode == "meta" && key.isNotBlank()) MetaPlanner(key)
            else StubPlannerAdapter()
        val metrics = resources.displayMetrics
        Log.d("AnyhandVoice",
            "fondo orden=«$order» planificador=$mode")
        orderJob = scope.launch {
            try {
                val sam = SamRefiner.get(this@AgentVoiceService)
                val samRefiner:
                    (suspend (android.graphics.Bitmap, AgentAction.Tap) ->
                        Pair<Float, Float>?)? =
                    sam?.let { s ->
                        { shot, tap -> s.refine(shot, tap.x, tap.y) }
                    }
                val runner = AgentRunner(
                    driver = AccessibilityDriver(),
                    planner = planner,
                    needsConfirmation = {
                        // En fondo no se confirma a ciegas: se trae la
                        // app al frente y esta acción no se ejecuta.
                        bringAppToFront()
                        false
                    },
                    tapRefiner = samRefiner,
                    onState = { state ->
                        when (state) {
                            AgentState.PLANNING ->
                                setMood(Mood.MIRANDO, "Mirando la pantalla…")
                            AgentState.ACTING ->
                                setMood(Mood.TOCANDO, "Tocando…")
                            AgentState.VERIFYING ->
                                setMood(Mood.COMPROBANDO, "Comprobando…")
                            AgentState.AWAITING_CONFIRMATION ->
                                setMood(Mood.CONFIRMAR, "¿Confirmas?")
                            AgentState.DONE ->
                                setMood(Mood.LISTO, "Listo")
                            AgentState.FAILED ->
                                setMood(Mood.ERROR, "Algo salió mal")
                            else -> Unit
                        }
                    },
                )
                val result = runner.run(order, language,
                    metrics.widthPixels, metrics.heightPixels)
                Log.d("AnyhandVoice",
                    "fondo resultado ok=${result.success} " +
                        "pasos=${result.steps.size} " +
                        result.steps.joinToString {
                            "${it.action} ok=${it.success} ${it.detail}"
                        })
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Corte del usuario (doble toque): no es un error.
                Log.d("AnyhandVoice", "orden cancelada por el usuario")
            } catch (e: Throwable) {
                Log.d("AnyhandVoice", "orden de fondo falló: ${e.message}")
                setMood(Mood.ERROR,
                    "Algo salió mal: ${e.message?.take(40) ?: e.javaClass.simpleName}")
            } finally {
                running = false
                handler.postDelayed({
                    // El orbe NO se esconde al terminar: sigue verde
                    // esperando la SIGUIENTE orden directa (continua:
                    // no hay que repetir la palabra tras cada orden).
                    policy.resumeContinuous()
                    showOrb(active = true)
                    setCaption("Te escucho…")
                    startSentry()
                }, 1200)
            }
        }
    }

    /** El usuario dijo "sí": tocar Enviar donde esté el botón. */
    private fun executeSendNow() {
        orderJob = scope.launch {
            val driver = AccessibilityDriver()
            val nodes = driver.dumpNodes()
            val center = sendButtonCenter(nodes)
                ?: editableCenter(nodes)?.let { ec ->
                    (resources.displayMetrics.widthPixels * 0.93f) to
                        ec.second
                }
            if (center != null) {
                setMood(Mood.TOCANDO, "Enviando…")
                driver.tap(center.first, center.second)
                Log.d("AnyhandVoice", "fondo enviado en $center")
                setMood(Mood.LISTO, "Enviado")
            } else {
                Log.d("AnyhandVoice", "fondo sin botón de enviar")
                setMood(Mood.ERROR, "No encontré el botón de enviar")
            }
            handler.postDelayed({
                policy.resumeContinuous()
                showOrb(active = true)
                setCaption("Te escucho…")
                startSentry()
            }, 500)
        }
    }

    /** Apagado total por voz: servicio, orbe y micrófono fuera. */
    private fun stopEverything() {
        stopped = true
        listening = false
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        hideOrb()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun bringAppToFront() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun showOrb(active: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Log.d("AnyhandVoice", "sin permiso de orbe flotante")
            return
        }
        if (overlayRoot != null) {
            orbView?.listening = active
            if (!active) setCaption(waitingCaption())
            return
        }
        // Fila flotante: orbe + texto. El texto dice el estado y lo
        // último oído, junto al orbe, para saber si escucha o no.
        val density = resources.displayMetrics.density
        val orb = OrbView(this)
        val caption = android.widget.TextView(this).apply {
            text = waitingCaption()
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
            maxWidth = (230 * density).toInt()
            val pad = (8 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.argb(178, 10, 18, 38))
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orb, android.widget.LinearLayout.LayoutParams(
                (124 * density).toInt(), (93 * density).toInt()))
            addView(caption, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (6 * density).toInt() })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Sin NOT_TOUCHABLE: se puede mantener presionado y
            // arrastrar el avatar a otra esquina.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (12 * density).toInt()
            y = (16 * density).toInt()
        }
        try {
            getSystemService(WindowManager::class.java).addView(row, params)
            overlayRoot = row
            overlayParams = params
            orbView = orb
            captionView = caption
            orb.listening = active
            attachDrag(row, params)
        } catch (e: Exception) {
            Log.d("AnyhandVoice", "orbe flotante falló: ${e.message}")
        }
    }

    /**
     * Mantener presionado (~450 ms) y arrastrar: el avatar sigue el
     * dedo y al soltar se pega a la esquina más cercana (izquierda o
     * derecha, arriba o abajo). Si queda a la derecha, el texto pasa
     * solo al otro lado para no salirse de la pantalla.
     */
    private fun attachDrag(
        row: android.widget.LinearLayout,
        params: WindowManager.LayoutParams,
    ) {
        val wm = getSystemService(WindowManager::class.java)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val longPress = Runnable { dragging = true }
        row.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    // Pasar a coordenadas absolutas (arriba-izquierda).
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    params.gravity = Gravity.TOP or Gravity.START
                    params.x = loc[0]
                    params.y = loc[1]
                    startX = params.x
                    startY = params.y
                    handler.postDelayed(longPress, 450)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downX) > 24 ||
                            kotlin.math.abs(event.rawY - downY) > 24)
                    ) {
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        dragging = false
                        snapToCorner(row, params)
                    } else if (
                        event.actionMasked ==
                        android.view.MotionEvent.ACTION_UP
                    ) {
                        // Doble toque al avatar: cortar la orden en
                        // curso ya, sin esperar a que Meta responda.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTapAt < 400) {
                            lastTapAt = 0
                            cancelCurrentOrder()
                        } else {
                            lastTapAt = now
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private var lastTapAt = 0L

    /** Doble toque: cancela la orden en curso y vuelve a escuchar. */
    private fun cancelCurrentOrder() {
        Log.d("AnyhandVoice", "orden cortada por doble toque")
        orderJob?.cancel()
        orderJob = null
        running = false
        setMood(Mood.ERROR, "Cancelado")
        handler.postDelayed({
            policy.resumeContinuous()
            showOrb(active = true)
            setCaption("Te escucho…")
            startSentry()
        }, 600)
    }

    /** Pega el avatar a la esquina cercana y voltea el texto. */
    private fun snapToCorner(
        row: android.widget.LinearLayout,
        params: WindowManager.LayoutParams,
    ) {
        val metrics = resources.displayMetrics
        val right = params.x + row.width / 2 > metrics.widthPixels / 2
        val bottom = params.y + row.height / 2 > metrics.heightPixels / 2
        val margin = (12 * metrics.density).toInt()
        params.x = if (right) metrics.widthPixels - row.width - margin
        else margin
        params.y = if (bottom) metrics.heightPixels - row.height - margin
        else margin
        try {
            getSystemService(WindowManager::class.java)
                .updateViewLayout(row, params)
        } catch (_: Exception) {
        }
        // Texto al lado contrario de la esquina: a la derecha de la
        // pantalla el texto va a la izquierda del avatar, y al revés.
        val caption = captionView ?: return
        val atLeft = row.indexOfChild(caption) == 0
        if (right && !atLeft) {
            row.removeView(caption)
            row.addView(caption, 0)
        } else if (!right && atLeft) {
            row.removeView(caption)
            row.addView(caption)
        }
    }

    private fun waitingCaption(): String =
        if (policy.awaitingOrder) "Te escucho…" else "Di «${policy.wakeWord}»"

    private fun setCaption(text: String) {
        handler.post { captionView?.text = text.take(90) }
    }

    /** Cara del avatar + texto según el estado del agente. */
    private fun setMood(mood: Mood, caption: String) {
        handler.post {
            orbView?.mood = mood
            captionView?.text = caption
        }
    }

    private fun hideOrb() {
        overlayRoot?.let { view ->
            try {
                getSystemService(WindowManager::class.java).removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayRoot = null
        overlayParams = null
        orbView = null
        captionView = null
    }

    /** Expresiones del avatar, una por estado del agente (del HTML). */
    enum class Mood {
        DORMIDO, ESCUCHANDO, HABLANDO, MIRANDO, TOCANDO,
        COMPROBANDO, CONFIRMAR, LISTO, ERROR, CONFUNDIDO,
    }

    /**
     * Avatar chico flotante: lazo infinito AZUL siempre, con la cara
     * del estado actual (escuchando, mirando, tocando, comprobando,
     * confirmar, listo, error, dormido).
     */
    private class OrbView(context: android.content.Context) : View(context) {
        var mood: Mood = Mood.DORMIDO
            set(value) {
                field = value
                invalidate()
            }
        var listening = false
            set(value) {
                field = value
                mood = if (value) Mood.ESCUCHANDO else Mood.DORMIDO
                invalidate()
            }

        /** Parámetros faciales por estado, del avatar HTML. */
        private data class Face(
            val pr: Float, val px: Float, val py: Float,
            val browDy: Float, val mouthW: Float, val mouthC: Float,
            val mouthOpen: Float,
        )
        private var phase = 0f
        private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            .apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }
        // Avatar Anyhand (lazo infinito con cara), portado del HTML
        // del usuario a Canvas: mismos trazos y parámetros base.
        private val ink = Color.rgb(20, 33, 61)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
            alpha = 107
        }
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.FILL
        }
        private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            alpha = 26
            style = Paint.Style.FILL
        }
        private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Espacio del avatar en el HTML: 400x300. Se escala y
            // centra; bob suave igual que #bob (respeta la fase).
            val s = minOf(width / 400f, height / 300f)
            val ox = (width - 400f * s) / 2f
            val bobY = if (mood == Mood.DORMIDO) 2f *
                sin(phase * 2 * Math.PI).toFloat() else -3f
            val oy = (height - 300f * s) / 2f + bobY * s * 4f
            fun x(v: Number) = ox + v.toFloat() * s
            fun y(v: Number) = oy + v.toFloat() * s

            // Sombra bajo el lazo.
            canvas.drawOval(
                x(76), y(254), x(324), y(278), shadowPaint)

            // Lazo infinito: dos trazos con degradado AZUL siempre
            // (mismos cúbicos que el SVG); el estado va en la cara.
            val colors = intArrayOf(
                Color.rgb(84, 195, 255), Color.rgb(42, 147, 255),
                Color.rgb(15, 98, 230))
            strokePaint.shader = android.graphics.LinearGradient(
                x(60), y(60), x(340), y(240), colors, null,
                android.graphics.Shader.TileMode.CLAMP)
            strokePaint.strokeWidth = 31f * s
            val left = android.graphics.Path().apply {
                moveTo(x(200), y(150))
                cubicTo(x(150), y(60), x(60), y(60), x(60), y(150))
                cubicTo(x(60), y(240), x(150), y(240), x(200), y(150))
            }
            val right = android.graphics.Path().apply {
                moveTo(x(200), y(150))
                cubicTo(x(250), y(60), x(340), y(60), x(340), y(150))
                cubicTo(x(340), y(240), x(250), y(240), x(200), y(150))
            }
            canvas.drawPath(left, strokePaint)
            canvas.drawPath(right, strokePaint)
            // Brillo superior del trazo.
            shinePaint.strokeWidth = 7f * s
            canvas.save()
            canvas.translate(-5f * s, -6f * s)
            canvas.drawPath(left, shinePaint)
            canvas.drawPath(right, shinePaint)
            canvas.restore()

            // Cara por estado, con los parámetros del HTML:
            // pupila (pr/px/py), cejas (bY/bA) y boca (mw/mc/mo).
            val eyeX = floatArrayOf(122f, 278f)
            browPaint.strokeWidth = 7f * s
            mouthPaint.strokeWidth = 6f * s
            val scan = sin(phase * 2 * Math.PI).toFloat()
            val face = when (mood) {
                Mood.ESCUCHANDO -> Face(13f, 0f, 0f, -4f, 15f, 10f, 0f)
                Mood.HABLANDO -> Face(13f, 0f, 0f, -4f, 12f, 6f, 9f)
                Mood.CONFUNDIDO -> Face(9f, -4f, 1f, -2f, 10f, -4f, 0f)
                Mood.MIRANDO -> Face(11f, scan * 8f, -2f, -7f, 6f, 0f, 10f)
                Mood.TOCANDO -> Face(12f, 5f, 2f, 3f, 12f, 5f, 0f)
                Mood.COMPROBANDO -> Face(9f, -3f, 0f, 0f, 13f, 0f, 0f)
                Mood.CONFIRMAR -> Face(17f, 0f, 2f, -8f, 12f, -8f, 0f)
                Mood.LISTO -> Face(12f, 0f, 0f, -5f, 20f, 22f, 11f)
                Mood.ERROR -> Face(7f, -3f, 3f, -4f, 14f, -14f, 0f)
                Mood.DORMIDO -> Face(12f, 0f, 0f, 2f, 8f, 3f, 0f)
            }
            if (mood == Mood.DORMIDO) {
                for (ex in eyeX) {
                    val lid = android.graphics.Path().apply {
                        moveTo(x(ex - 22), y(150))
                        quadTo(x(ex), y(160), x(ex + 22), y(150))
                    }
                    canvas.drawPath(lid, mouthPaint)
                }
                zPaint.textSize = 30f * s
                canvas.drawText("z", x(322), y(86), zPaint)
                zPaint.textSize = 40f * s
                canvas.drawText("z", x(346), y(56), zPaint)
            } else {
                val pr = face.pr * s * (0.94f + 0.06f * scan)
                for (ex in eyeX) {
                    canvas.drawCircle(
                        x(ex + face.px), y(150 + face.py), pr, facePaint)
                    canvas.drawCircle(
                        x(ex + face.px) + pr * 0.35f,
                        y(150 + face.py) - pr * 0.35f,
                        pr * 0.27f, shinePaint.apply {
                            style = Paint.Style.FILL
                        })
                    shinePaint.style = Paint.Style.STROKE
                }
            }
            // Cejas sobre y=84 (+ desplazamiento del estado).
            canvas.drawLine(
                x(100), y(84 + face.browDy), x(144), y(84 + face.browDy),
                browPaint)
            canvas.drawLine(
                x(256), y(84 + face.browDy), x(300), y(84 + face.browDy),
                browPaint)
            // Boca en (200,152): curva y apertura según el estado.
            val mouth = android.graphics.Path().apply {
                moveTo(x(200 - face.mouthW), y(152))
                quadTo(
                    x(200), y(152 + face.mouthC),
                    x(200 + face.mouthW), y(152))
                if (face.mouthOpen > 1f) {
                    quadTo(
                        x(200), y(152 + face.mouthC + face.mouthOpen),
                        x(200 - face.mouthW), y(152))
                }
            }
            canvas.drawPath(mouth, mouthPaint)
            // Adornos del HTML: "?" al confirmar o confundido,
            // gota en error.
            if (mood == Mood.CONFIRMAR || mood == Mood.CONFUNDIDO) {
                zPaint.textSize = 46f * s
                canvas.drawText("?", x(334), y(74), zPaint)
            }
            if (mood == Mood.ERROR) {
                val drop = android.graphics.Path().apply {
                    moveTo(x(344), y(58))
                    cubicTo(x(331), y(76), x(331), y(80), x(331), y(85))
                    arcTo(
                        x(331), y(72), x(357), y(98),
                        180f, 180f, false)
                    cubicTo(x(357), y(80), x(357), y(76), x(344), y(58))
                }
                canvas.drawPath(drop, shinePaint.apply {
                    color = Color.rgb(127, 200, 255)
                    style = Paint.Style.FILL
                    alpha = 255
                })
                shinePaint.color = Color.WHITE
                shinePaint.alpha = 107
                shinePaint.style = Paint.Style.STROKE
            }
        }
    }
}
