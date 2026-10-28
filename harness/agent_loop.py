"""Ciclo del agente desde la PC: planificar -> actuar -> verificar.

Uso:
    python harness/agent_loop.py "ve a la pantalla siguiente" --lang es

Es calibración del driver, no prueba de viabilidad (ver AGENTS.md §5b):
ajusta nombres de teclas, signo del scroll y tamaño de captura, y registra
latencia por paso en results/runs.jsonl.

TODO: reemplazar plan() por la herramienta `computer` de Muse Spark vía
Meta Model API. El punto de integración es la función plan().
"""

import argparse
import os
import time

import adb_driver
import meta_client
import verify
from runlog import RunLogger

META_STEPS_CAP = 10


def plan(task, width, height):
    """Planificador local por palabras clave (ES/EN/PT).

    STUB de demostración, gemelo de StubPlanner en la app. Solo sirve para
    calibrar el driver sin gastar créditos.
    """
    lower = task.lower()
    cx = width / 2
    if any(w in lower for w in ("siguiente", "next", "proximo",
                                "próximo", "avancar", "avançar")):
        return [("tap", cx, height * 0.82)]
    if any(w in lower for w in ("atrás", "atras", "back", "voltar")):
        return [("key", "KEYCODE_BACK")]
    if any(w in lower for w in ("escribe", "escribir", "type", "digite",
                                "escreva", "digitar")):
        return [("tap", cx, height * 0.35), ("text", extract_quoted(task))]
    if any(w in lower for w in ("ok", "terminar", "finish", "finalizar",
                                "concluir", "fin", "done", "fim")):
        return [("tap", cx, height * 0.82)]
    if any(w in lower for w in ("arriba", "up", "cima", "subir")):
        return [("swipe", cx, height * 0.7, cx, height * 0.3, 300)]
    if any(w in lower for w in ("abajo", "down", "baixo", "descer")):
        return [("swipe", cx, height * 0.3, cx, height * 0.7, 300)]
    return [("tap", cx, height * 0.5)]


def extract_quoted(task):
    first = task.find('"')
    last = task.rfind('"')
    if 0 <= first < last:
        return task[first + 1:last]
    return "hola"


def execute(action):
    kind = action[0]
    if kind == "tap":
        _, x, y = action
        return adb_driver.tap(x, y), f"tap {int(x)} {int(y)}"
    if kind == "swipe":
        _, x1, y1, x2, y2, ms = action
        return adb_driver.swipe(x1, y1, x2, y2, ms), "swipe"
    if kind == "text":
        ok = adb_driver.input_text(action[1])
        return ok, f"text {action[1]!r}"
    if kind == "key":
        ok = adb_driver.keyevent(action[1])
        return ok, f"key {action[1]}"
    return False, f"acción desconocida: {kind}"


# Gemelo de ConfirmationGate en la app: mismas palabras, mismo criterio.
# Ninguna tarea de la evaluación formal las contiene, así que el 8/8 no
# cambia; el gate solo salta con tareas nuevas irreversibles.
IRREVERSIBLE = ("enviar", "send", "pagar", "pay", "borrar", "delete",
                "excluir", "eliminar", "comprar", "buy", "llamar", "call",
                "ligar")

MAX_ATTEMPTS = 2


def requires_confirmation(task):
    lower = task.lower()
    return any(word in lower for word in IRREVERSIBLE)


def ask_gate(task, action):
    while True:
        answer = input(f"acción posiblemente irreversible en «{task}» "
                       f"({action[0]}). ¿permitir? [s/n]: ").strip().lower()
        if answer in ("s", "y", "sim", "yes"):
            return True
        if answer in ("n", "no", "não", "not"):
            return False


def run_task(task, lang="es", setup=(), extra=None, confirm=None):
    """Ejecuta una tarea. `setup` son acciones previas (no verificadas) para
    dejar la pantalla lista. `confirm(task)` pide el veredicto manual; el
    éxito final es auto Y manual. Cada paso fallido se reintenta una vez."""
    adb_driver.ensure_device()
    adb_driver.wake()
    width, height = adb_driver.screen_size()
    print(f"pantalla: {width}x{height}")
    for action in setup:
        ok, detail = execute(action)
        print(f"setup: {detail} ok={ok}")
    logger = RunLogger()
    logger.task_start()
    success = True
    stuck = 0
    meta_mode = os.environ.get("META_PLANNER", "stub").lower() == "meta"
    planned = plan(task, width, height)
    if meta_mode:
        print(f"planificador: Meta Model API ({meta_client.MODEL})")
    index = 0
    while True:
        if meta_mode:
            if index >= META_STEPS_CAP:
                print(f"límite de {META_STEPS_CAP} pasos con Meta")
                success = False
                break
            try:
                model_shot = adb_driver.screenshot_png()
            except RuntimeError:
                model_shot = None
            if model_shot is None:
                print("sin captura para el modelo; tarea fallida")
                success = False
                break
            try:
                action, in_tok, out_tok = meta_client.plan_step(
                    task, lang, model_shot, width, height,
                    step=index, cap=META_STEPS_CAP)
            except meta_client.MetaError as exc:
                print(f"Meta falló ({exc}); fallback al stub en este paso")
                if index < len(planned):
                    action = planned[index]
                else:
                    action = ("tap", width / 2, height / 2)
                in_tok = out_tok = 0
            if action[0] == "done":
                print("el modelo declara la tarea cumplida")
                break
        else:
            if index >= len(planned):
                break
            action = planned[index]
            in_tok = out_tok = 0
        if requires_confirmation(task) and not ask_gate(task, action):
            logger.step(index, False, 0,
                        detail=f"{action[0]} bloqueado por el gate")
            print(f"paso {index}: bloqueado por el gate")
            success = False
            break
        step_ok = False
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                before = adb_driver.screenshot_png()
            except RuntimeError:
                before = None
            start = time.time()
            ok, detail = execute(action)
            latency_ms = (time.time() - start) * 1000
            try:
                after = adb_driver.screenshot_png()
            except RuntimeError:
                after = None
            if before is not None and after is not None:
                if before == after:
                    print("aviso: capturas idénticas byte a byte "
                          "(¿pantalla bloqueada o app congelada?)")
                changed = verify.changed_enough(before, after)
                frac = verify.change_fraction(before, after)
            else:
                changed, frac = ok, 0.0
            step_ok = ok and changed
            tag = "" if attempt == 1 else " (reintento)"
            logger.step(index, step_ok, latency_ms,
                        tokens=in_tok + out_tok,
                        detail=f"{detail} cambio={frac:.4f}{tag}")
            print(f"paso {index}{tag}: {detail} ok={step_ok} "
                  f"{latency_ms:.0f}ms cambio={frac:.4f} "
                  f"tok={in_tok + out_tok}")
            if step_ok:
                break
            if attempt == 1:
                print(f"paso {index}: reintentando una vez")
        index += 1
        if not step_ok and frac == 0.0:
            stuck += 1
        else:
            stuck = 0
        if stuck >= 2:
            print("dos pasos seguidos sin ningún cambio: pantalla "
                  "atascada, se aborta para no gastar más tokens")
            success = False
            break
        if not step_ok:
            success = False
            break
    auto = success
    manual = confirm(task) if confirm else True
    success = auto and manual
    record = logger.task_end(task, lang, success,
                             extra={**(extra or {}), "auto": auto,
                                    "manual": manual})
    print(f"resultado: {'ÉXITO' if success else 'FALLO'} "
          f"(auto={auto} manual={manual} {record['seconds']}s)")
    return success


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("task")
    parser.add_argument("--lang", default="es")
    args = parser.parse_args()
    run_task(args.task, args.lang)
