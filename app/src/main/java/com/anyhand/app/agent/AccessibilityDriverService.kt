package com.anyhand.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * AccessibilityDriver: implementación real del [Driver] sobre la pantalla.
 *
 * Capacidades:
 * - tap / swipe con `dispatchGesture`
 * - escritura en el campo enfocado con `ACTION_SET_TEXT`
 * - captura con `takeScreenshot` (API 30+; minSdk 30)
 * - volcado de nodos para apps CON etiquetas
 * - Back/Home con acciones globales
 *
 * No se construyó hasta tener el ciclo del harness funcionando (AGENTS.md §5b):
 * este servicio es el gemelo del AdbDriver de `harness/`.
 */
class AccessibilityDriverService : AccessibilityService() {

    companion object {
        var instance: AccessibilityDriverService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    suspend fun tap(x: Float, y: Float): ActionResult =
        gesture(Path().apply { moveTo(x, y) }, 50L, 50L)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): ActionResult {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gesture(path, 0L, durationMs.coerceAtLeast(1).toLong())
    }

    private suspend fun gesture(path: Path, startDelay: Long, duration: Long): ActionResult =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, startDelay, duration)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(ActionResult(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(ActionResult(false, "gesture cancelled"))
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(ActionResult(false, "dispatchGesture rejected"))
        }

    fun inputTextBlocking(text: String): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult(false, "no active window")
        val target = findEditable(root) ?: return ActionResult(false, "no editable node")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        return if (ok) ActionResult(true) else ActionResult(false, "ACTION_SET_TEXT failed")
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    fun keyPressBlocking(key: String): ActionResult {
        val code = KeyMapper.toKeyCode(key)
            ?: return ActionResult(false, "unsupported key: $key")
        val global = when (code) {
            android.view.KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            android.view.KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            else -> -1
        }
        if (global != -1) {
            val ok = performGlobalAction(global)
            return if (ok) ActionResult(true) else ActionResult(false, "global action failed")
        }
        // Teclado físico/Enter: sin inyección de teclas en una UI táctil.
        // Punto de calibración: se reporta, no se finge.
        return ActionResult(false, "key has no touch equivalent: $key")
    }

    suspend fun takeScreenshotBlocking(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }

    fun dumpNodesBlocking(): String? {
        val root = rootInActiveWindow ?: return null
        val out = StringBuilder()
        dumpNode(root, 0, out)
        root.recycle()
        return out.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, out: StringBuilder) {
        if (depth > 12) return
        repeat(depth) { out.append("  ") }
        out.append(node.className ?: "?")
            .append(" text=").append(node.text ?: "-")
            .append(" desc=").append(node.contentDescription ?: "-")
            .append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, out)
            child.recycle()
        }
    }
}

/** Adaptador [Driver] sobre el servicio (o fallo claro si está desactivado). */
class AccessibilityDriver : Driver {
    private fun service(): AccessibilityDriverService? =
        AccessibilityDriverService.instance

    override suspend fun tap(x: Float, y: Float): ActionResult =
        service()?.tap(x, y) ?: ActionResult(false, "accessibility service disabled")

    override suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Int,
    ): ActionResult =
        service()?.swipe(x1, y1, x2, y2, durationMs)
            ?: ActionResult(false, "accessibility service disabled")

    override suspend fun inputText(text: String): ActionResult =
        service()?.inputTextBlocking(text) ?: ActionResult(false, "accessibility service disabled")

    override suspend fun keyPress(key: String): ActionResult =
        service()?.keyPressBlocking(key) ?: ActionResult(false, "accessibility service disabled")

    override suspend fun screenshot(): Bitmap? =
        service()?.takeScreenshotBlocking()

    override suspend fun dumpNodes(): String? =
        service()?.dumpNodesBlocking()
}
