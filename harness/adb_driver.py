"""AdbDriver: gemelo del AccessibilityDriver, pero desde la PC vía adb.

Sin dependencias externas: solo la stdlib de Python y un `adb` funcional
(teléfono con depuración USB, `adb devices` lo lista).

Comandos usados (ver AGENTS.md §5b):
- captura: `adb exec-out screencap -p`
- tamaño:  `adb shell wm size`
- acciones: `adb shell input tap|swipe|text|keyevent`
- etiquetas: `adb shell uiautomator dump`
"""

import os
import subprocess

# Sello del harness: se imprime al arrancar cada corrida para probar qué
# código corrió. Súbelo con cada cambio al harness.
REV = "2026-10-29i"

# Ruta al binario de adb. Por defecto se busca en el PATH; si Android Studio
# es tu única instalación, apunta aquí:
#   $env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
ADB = os.environ.get("ADB", "adb")


def _run(*args):
    try:
        proc = subprocess.run(
            [ADB, *args], capture_output=True, timeout=30,
        )
    except FileNotFoundError:
        raise RuntimeError(
            f"no se encontró adb ({ADB}). Instala platform-tools o define "
            "la variable de entorno ADB con la ruta completa."
        )
    return proc


def ensure_device():
    """Lanza RuntimeError si no hay exactamente un dispositivo visible."""
    proc = _run("devices")
    lines = [
        line for line in proc.stdout.decode(errors="replace").splitlines()[1:]
        if line.strip() and "\tdevice" in line
    ]
    if len(lines) != 1:
        raise RuntimeError(
            f"se esperaba 1 dispositivo, se vieron {len(lines)} "
            "(revisa `adb devices`)"
        )
    return lines[0].split("\t")[0]


def screen_size():
    """Devuelve (ancho, alto) en píxeles según `wm size`."""
    proc = _run("shell", "wm", "size")
    out = proc.stdout.decode(errors="replace")
    # Formato: "Physical size: 1080x2400"
    dims = out.strip().rsplit(" ", 1)[-1]
    width, height = dims.split("x")
    return int(width), int(height)


def screenshot_png():
    """Devuelve los bytes PNG de la captura actual."""
    proc = _run("exec-out", "screencap", "-p")
    if proc.returncode != 0 or not proc.stdout.startswith(b"\x89PNG"):
        raise RuntimeError("screencap falló o no devolvió PNG")
    return proc.stdout


def phone_state_hint():
    """Una línea con estado de pantalla para diagnosticar taps
    rechazados. Best effort, nunca lanza."""
    try:
        on = screen_on()
        screen = "on" if on is True else ("off" if on is False else "?")
    except Exception:
        screen = "?"
    return f"pantalla={screen}"


def tap(x, y):
    proc = _run("shell", "input", "tap", str(int(x)), str(int(y)))
    if proc.returncode != 0:
        err = (proc.stdout + proc.stderr).decode(
            errors="replace").strip()
        print(f"tap {int(x)} {int(y)} rechazado por el teléfono "
              f"({phone_state_hint()}): {err or '(sin mensaje)'}")
    return proc.returncode == 0


def swipe(x1, y1, x2, y2, duration_ms=300):
    proc = _run(
        "shell", "input", "swipe",
        str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)),
        str(int(duration_ms)),
    )
    if proc.returncode != 0:
        err = (proc.stdout + proc.stderr).decode(
            errors="replace").strip()
        print(f"swipe rechazado por el teléfono "
              f"({phone_state_hint()}): {err or '(sin mensaje)'}")
    return proc.returncode == 0


def input_text(text):
    """Escribe texto. Limitación conocida: `input text` no maneja bien
    espacios ni caracteres no ASCII (ñ, á); los espacios se envían como %s
    y lo demás queda como punto de calibración (ver AGENTS.md §12)."""
    safe = text.replace(" ", "%s")
    proc = _run("shell", "input", "text", safe)
    return proc.returncode == 0


def keyevent(code):
    """`code` es un nombre como KEYCODE_BACK o KEYCODE_ENTER."""
    proc = _run("shell", "input", "keyevent", code)
    return proc.returncode == 0


def uia_dump():
    """Volcado del árbol de accesibilidad (para apps CON etiquetas).
    Devuelve el XML como texto, o None si falló (p. ej. captura bloqueada)."""
    proc = _run("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    if proc.returncode != 0:
        return None
    pull = _run("pull", "/sdcard/window_dump.xml")
    if pull.returncode != 0:
        return None
    try:
        with open("window_dump.xml", encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return None


def labeled_clickables(dump, max_items=30):
    """(etiqueta, x, y) de nodos tocables con texto del volcado de
    uiautomator, con el centro en píxeles reales. Best effort: lista
    vacía si el XML no se entiende. Sirve para que el modelo elija por
    nombre ("Direct", "Buscar") en vez de adivinar píxeles."""
    import re
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(dump)
    except Exception:
        return []
    bounds_re = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    out = []
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        label = (node.get("content-desc") or node.get("text") or "").strip()
        if not label:
            continue
        match = bounds_re.search(node.get("bounds") or "")
        if not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
        if len(out) >= max_items:
            break
    return out


def am_start(package, activity):
    """Abre una actividad, p. ej. nuestra app de prueba.

    `am start` devuelve 0 incluso cuando falla (p. ej. actividad no
    exportada), así que se revisa la salida: sin "Starting:" no abrió.
    """
    proc = _run("shell", "am", "start", "-n", f"{package}/{activity}")
    out = (proc.stdout + proc.stderr).decode(errors="replace")
    if proc.returncode != 0 or "Starting:" not in out:
        print(f"ERROR al abrir {package}/{activity}:\n{out.strip()}")
        return False
    return True


def launch_package(package):
    """Abre la actividad principal de un paquete instalado (p. ej.
    Instagram), sin nombrar su actividad interna, que cambia por versión.
    Devuelve True si el sistema aceptó el lanzamiento."""
    proc = _run("shell", "monkey", "-p", package,
                "-c", "android.intent.category.LAUNCHER", "1")
    out = (proc.stdout + proc.stderr).decode(errors="replace")
    if proc.returncode != 0 or "No activities found" in out:
        print(f"ERROR al abrir {package}:\n{out.strip()}")
        return False
    return True


def wait_focused(package, timeout_s=20):
    """Espera (best effort) a que el paquete tenga el foco antes de actuar.
    Sin esto, los primeros taps caen durante el arranque en frío y no hacen
    nada. Si el formato de dumpsys no se reconoce, espera igual el timeout
    y devuelve False, pero NO bloquea: quien llama decide si sigue."""
    import time
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        proc = _run("shell", "dumpsys", "window")
        out = proc.stdout.decode(errors="replace")
        for line in out.splitlines():
            if "mCurrentFocus" in line and package in line:
                return True
        time.sleep(1)
    return False


def force_stop(package):
    """Mata el proceso para que el siguiente `am start` arranque en estado
    fresco. Sin esto, la app de prueba retoma la pantalla 'fin' y los taps
    de la siguiente tarea caen en una pantalla sin botones."""
    _run("shell", "am", "force-stop", package)


def keep_awake(timeout_ms=600000):
    """Sube el apagado de pantalla a timeout_ms (best effort) y devuelve
    el valor anterior (o None). Para corridas largas con Meta: si la
    pantalla se apaga durante una llamada al modelo (30-60 s), el tap
    siguiente falla a nivel adb con ok=False."""
    try:
        current = _run("shell", "settings", "get", "system",
                       "screen_off_timeout")
        prev = current.stdout.decode(errors="replace").strip() or None
        _run("shell", "settings", "put", "system", "screen_off_timeout",
             str(timeout_ms))
        return prev
    except Exception:
        return None


def restore_awake(previous):
    """Restaura el timeout guardado por keep_awake. Best effort."""
    if previous is None:
        return
    try:
        _run("shell", "settings", "put", "system", "screen_off_timeout",
             previous)
    except Exception:
        pass


def wake():
    """Enciende la pantalla e intenta quitar el bloqueo (best effort).
    Sin esto, los taps caen en la pantalla de bloqueo y las capturas
    salen idénticas: cambio=0.0000 en todos los pasos."""
    _run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    _run("shell", "wm", "dismiss-keyguard")


def screen_on():
    """True/False según el display, None si no se pudo saber."""
    proc = _run("shell", "dumpsys", "display")
    out = proc.stdout.decode(errors="replace")
    if "mScreenState=ON" in out:
        return True
    if "mScreenState=OFF" in out:
        return False
    return None
