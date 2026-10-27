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


def tap(x, y):
    proc = _run("shell", "input", "tap", str(int(x)), str(int(y)))
    return proc.returncode == 0


def swipe(x1, y1, x2, y2, duration_ms=300):
    proc = _run(
        "shell", "input", "swipe",
        str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)),
        str(int(duration_ms)),
    )
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


def am_start(package, activity):
    """Abre una actividad, p. ej. nuestra app de prueba."""
    proc = _run("shell", "am", "start", "-n", f"{package}/{activity}")
    return proc.returncode == 0
