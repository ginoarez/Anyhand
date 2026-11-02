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
    if any(w in lower for w in ("cierra", "cerrar", "close", "feche",
                                "fechar", "salir", "sair", "exit")):
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
# Las 8 tareas de calibración no las contienen, así que ese conteo no
# cambia; el gate salta con tareas nuevas irreversibles (como el mensaje
# de Instagram) y pregunta una sola vez por tarea.
IRREVERSIBLE = ("enviar", "send", "pagar", "pay", "borrar", "delete",
                "excluir", "eliminar", "comprar", "buy", "llamar", "call",
                "ligar", "manda", "mande", "mandar", "mensaje", "mensagem",
                "message")

MAX_ATTEMPTS = 2

# Replanes por tarea: cuando el toque entra (adb ok) pero no cambia nada,
# se pide OTRA acción al modelo en vez de re-tocar el punto muerto.
# Acotado para no quemar tokens en bucle.
MAX_REPLANS = 2


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
    # La pantalla no debe apagarse a media corrida larga: se sube el
    # timeout a 10 min y se restaura al terminar (best effort).
    prev_timeout = adb_driver.keep_awake()
    for action in setup:
        ok, detail = execute(action)
        print(f"setup: {detail} ok={ok}")
    meta_mode = os.environ.get("META_PLANNER", "stub").lower() == "meta"
    logger = RunLogger(
        planner="meta" if meta_mode else "stub",
        model=meta_client.MODEL if meta_mode else "stub-local")
    logger.task_start()
    success = True
    stuck = 0
    hist = []
    replans = 0
    planned = plan(task, width, height)
    if requires_confirmation(task):
        # Una sola aprobación por tarea: ya no se pregunta en cada paso.
        print(f"«{task}» incluye una acción posiblemente irreversible.")
        if not ask_gate(task, ("tarea completa",)):
            record = logger.task_end(
                task, lang, False,
                extra={**(extra or {}), "auto": False, "manual": False})
            print(f"resultado: FALLO (bloqueado por el gate "
                  f"{record['seconds']}s)")
            adb_driver.restore_awake(prev_timeout)
            return False
    if meta_mode:
        print(f"planificador: Meta Model API ({meta_client.MODEL})")
    def do_attempt(action, in_tok, out_tok, tag):
        """Ejecuta una acción y la verifica. Devuelve (step_ok, ok, frac).

        `ok` es éxito adb (el toque entró); `step_ok` exige además cambio
        visible. Se separan en el log: mezclarlos escondió puntos muertos.
        En modo Meta se reutiliza la captura del modelo como "antes".
        """
        nonlocal hist
        try:
            before = (model_shot if meta_mode
                      else adb_driver.screenshot_png())
        except RuntimeError:
            before = None
        # Wake pegado a la acción: la llamada al modelo tarda decenas de
        # segundos y la pantalla puede haberse dormido en ese hueco.
        adb_driver.wake()
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
        logger.step(index, step_ok, latency_ms,
                    tokens=in_tok + out_tok,
                    detail=f"{detail} cambio={frac:.4f}{tag}")
        print(f"paso {index}{tag}: {detail} adb={ok} "
              f"{latency_ms:.0f}ms cambio={frac:.4f} "
              f"tok={in_tok + out_tok} -> "
              f"{'CAMBIO' if step_ok else 'SIN_CAMBIO'}")
        # Los cambios mínimos (<0.01) suelen ser ruido (videos, shimmer):
        # se marcan para que el modelo no insista en ese punto.
        noise = " (apenas ruido)" if frac < 0.01 else ""
        hist.append(f"{detail} cambio={frac:.4f}{noise}")
        hist = hist[-10:]
        return step_ok, ok, frac

    index = 0
    while True:
        # Las corridas con Meta duran minutos y la pantalla puede
        # apagarse a media tarea. Despertarla en cada paso es barato
        # e inofensivo si ya está on.
        adb_driver.wake()
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
                dump = adb_driver.uia_dump()
            except RuntimeError:
                dump = None
            ui_elements = (adb_driver.labeled_clickables(dump, 30)
                           if dump else [])
            if ui_elements:
                print(f"elementos con etiqueta: {len(ui_elements)}")
            try:
                action, in_tok, out_tok = meta_client.plan_step(
                    task, lang, model_shot, width, height,
                    step=index, cap=META_STEPS_CAP, history=hist,
                    ui_elements=ui_elements)
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
        step_ok = False
        ok = False
        frac = 0.0
        for attempt in range(1, MAX_ATTEMPTS + 1):
            tag = "" if attempt == 1 else " (reintento)"
            step_ok, ok, frac = do_attempt(action, in_tok, out_tok, tag)
            if step_ok:
                break
            if not ok:
                # Fallo a nivel adb (el toque ni entró): primero ver si
                # el teléfono sigue ahí. Un cable flojo a media corrida
                # larga tumba comandos sueltos; abortar de frente ahorra
                # tokens en vez de reintentar contra un teléfono ido.
                try:
                    adb_driver.ensure_device()
                except RuntimeError as exc:
                    print(f"se perdió el teléfono a media tarea ({exc}); "
                          f"revisa el cable y vuelve a correr")
                    logger.step(index, False, 0,
                                detail="sin teléfono")
                    success = False
                    break
                # Sigue ahí: pausa para que un hipo transitorio del cable
                # o del demonio se despeje antes del reintento.
                time.sleep(2)
            if attempt == 1:
                print(f"paso {index}: reintentando una vez")
        if (not step_ok and ok and meta_mode
                and replans < MAX_REPLANS):
            # El toque entró pero no cambió nada (punto muerto): no se
            # re-toca lo mismo, se pide OTRA acción al modelo con captura
            # fresca y el historial del punto muerto ya anotado.
            replans += 1
            print(f"paso {index}: sin cambio; replan {replans}/"
                  f"{MAX_REPLANS} con el modelo")
            try:
                model_shot = adb_driver.screenshot_png()
            except RuntimeError:
                model_shot = None
            if model_shot is None:
                print("sin captura para replanear; tarea fallida")
                success = False
            else:
                try:
                    dump = adb_driver.uia_dump()
                except RuntimeError:
                    dump = None
                ui_elements = (adb_driver.labeled_clickables(dump, 30)
                               if dump else [])
                try:
                    action, in_tok, out_tok = meta_client.plan_step(
                        task, lang, model_shot, width, height,
                        step=index, cap=META_STEPS_CAP, history=hist,
                        ui_elements=ui_elements)
                except meta_client.MetaError as exc:
                    print(f"Meta falló en el replan ({exc}); tarea fallida")
                    success = False
                else:
                    if action[0] == "done":
                        print("el modelo declara la tarea cumplida")
                        break
                    step_ok, ok, frac = do_attempt(
                        action, in_tok, out_tok, " (replan)")
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
    adb_driver.restore_awake(prev_timeout)
    return success


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("task")
    parser.add_argument("--lang", default="es")
    args = parser.parse_args()
    print(f"harness rev {adb_driver.REV}")
    run_task(args.task, args.lang)
