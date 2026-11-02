"""Evaluación formal v1: grupos con/sin etiquetas, tabla de resultados.

Uso:
    python harness/eval.py

Grupo sin etiquetas: nuestra app de prueba (propia, construida en el evento).
Grupo con etiquetas: app del sistema (Ajustes), con conteo de nodos como
evidencia de que las etiquetas existen.
Grupo app-real: Instagram de verdad (excepción pedida por el usuario a la
regla de "nada comercial" de AGENTS.md §8): abrir, entrar a mensajes,
buscar "meta" y enviar "Hellooo, First test". Solo corre con
META_PLANNER=meta (el stub no sabe navegar apps reales); con stub se omite
sin contar en el total, así el 8/8 de calibración no cambia.

Cada tarea registra en results/runs.jsonl el veredicto automático (cambio
visible) Y el manual (persona mirando la pantalla). Al final se escribe
results/summary.md con la tabla por grupo.

Nota honesta: con el planificador stub esto mide la fiabilidad del driver,
no la inteligencia del modelo. La evaluación de inteligencia llega con la
herramienta `computer` de Muse Spark (TODO en agent_loop.py).
"""

import argparse
import datetime
import os
import time

import adb_driver
import agent_loop

SETTLE_SECONDS = 2.0

TEST_PACKAGE = "com.anyhand.app"
TEST_ACTIVITY = ".unlabeled.UnlabeledTestActivity"
SETTINGS_PACKAGE = "com.android.settings"
SETTINGS_ACTIVITY = ".Settings"
INSTAGRAM_PACKAGE = "com.instagram.android"

# (tarea, idioma, grupo, setup, app_para_abrir)
TAP_NEXT = ("tap", 0.5, 0.82)  # proporciones; se escalan a la pantalla


def scaled_setup(width, height, actions):
    out = []
    for action in actions:
        if action[0] == "tap":
            _, fx, fy = action
            out.append(("tap", fx * width, fy * height))
        else:
            out.append(action)
    return out


TASKS = [
    # Grupo sin etiquetas: app de prueba propia.
    {"task": "ve a la pantalla siguiente", "lang": "es",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": []},
    {"task": "go to the next screen", "lang": "en",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": []},
    {"task": "avançar para a próxima tela", "lang": "pt",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": []},
    {"task": 'escribe "hola mundo"', "lang": "es",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": [TAP_NEXT]},
    {"task": 'type "hello"', "lang": "en",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": [TAP_NEXT]},
    {"task": "pulsa ok para terminar", "lang": "es",
     "group": "sin-etiquetas", "app": (TEST_PACKAGE, TEST_ACTIVITY),
     "setup": [TAP_NEXT]},
    # Grupo con etiquetas: Ajustes del sistema (inofensivo, verificable).
    {"task": "vuelve atrás", "lang": "es",
     "group": "con-etiquetas", "app": (SETTINGS_PACKAGE, SETTINGS_ACTIVITY),
     "setup": []},
    {"task": "go back", "lang": "en",
     "group": "con-etiquetas", "app": (SETTINGS_PACKAGE, SETTINGS_ACTIVITY),
     "setup": []},
    # Grupo app-real: Instagram de verdad. Requiere sesión iniciada en el
    # teléfono y META_PLANNER=meta con MODEL_API_KEY. El gate pregunta una
    # vez por tarea porque el mensaje sí se envía de tu cuenta.
    {"task": 'abre Instagram, entra a mensajes, busca "meta" y mándale '
             '"Hellooo, First test"',
     "lang": "es", "group": "app-real",
     "app": (INSTAGRAM_PACKAGE, None),
     "setup": [], "needs_meta": True},
]


def ask_success(task):
    while True:
        answer = input(f"¿se cumplió «{task}»? [s/n]: ").strip().lower()
        if answer in ("s", "y", "sim", "yes"):
            return True
        if answer in ("n", "no", "não", "not"):
            return False


def label_count():
    """Nodos del árbol como evidencia de etiquetas (None si no hay raíz)."""
    dump = adb_driver.uia_dump()
    if dump is None:
        return None
    return dump.count("<node")


def _planner_sections(rows):
    """rows: (tarea, idioma, grupo, éxito, nodos, planificador)."""
    sections = {}
    for row in rows:
        sections.setdefault(row[5], []).append(row)
    return sections


def write_summary(rows, path="results/summary.md"):
    """Dos tablas separadas por planificador: la calibración stub y
    los resultados reales de Meta no se mezclan ni se promedian."""
    lines = ["# Resumen de evaluación",
             "",
             f"Fecha: {datetime.date.today().isoformat()}",
             ""]
    for planner, prows in _planner_sections(rows).items():
        title = ("Planificador Meta (Meta Model API)"
                 if planner == "meta" else
                 "Planificador stub (calibración local)")
        lines += [f"## {title}", ""]
        groups = {}
        for _, _, group, success, _, _ in prows:
            groups.setdefault(group, []).append(success)
        lines += ["| grupo | éxito | total |", "|---|---|---|"]
        total_ok = total_n = 0
        for group, results in groups.items():
            ok = sum(1 for s in results if s)
            lines.append(f"| {group} | {ok} | {len(results)} |")
            total_ok += ok
            total_n += len(results)
        lines += ["",
                  f"Total {planner}: {total_ok}/{total_n}",
                  "",
                  "| tarea | idioma | grupo | planificador | éxito | nodos |",
                  "|---|---|---|---|---|---|"]
        for task, lang, group, success, labels, pl in prows:
            mark = "x" if success else " "
            lines.append(f"| {task} | {lang} | {group} | {pl} | [{mark}] | "
                         f"{labels if labels is not None else '-'} |")
        lines.append("")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"tabla escrita en {path}")


def summarize_history(log_path="results/runs.jsonl",
                      path="results/summary.md"):
    """Resumen histórico desde runs.jsonl, separado por planificador.

    Los registros viejos no traen campo planner: se infiere por los
    tokens (tok>0 = Meta). Los nuevos lo traen explícito.
    """
    import json as _json
    rows = []
    if os.path.exists(log_path):
        with open(log_path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                r = _json.loads(line)
                planner = r.get("planner")
                if not planner:
                    toks = sum(s.get("tok", 0) for s in r.get("steps", []))
                    planner = "meta" if toks > 0 else "stub"
                rows.append((r.get("task", ""), r.get("lang", ""),
                             r.get("group", "app"), bool(r.get("success")),
                             r.get("labels"), planner))
    write_summary(rows, path)
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--solo", default=None,
                        help="corre solo un grupo (p. ej. --solo app-real)")
    parser.add_argument("--resumen", action="store_true",
                        help="solo regenera results/summary.md desde "
                             "runs.jsonl, separado por planificador")
    args = parser.parse_args()
    if args.resumen:
        summarize_history()
        return
    planner = ("meta" if os.environ.get("META_PLANNER", "stub").lower()
               == "meta" else "stub")
    entries = [e for e in TASKS
               if args.solo is None or e["group"] == args.solo]
    if args.solo is not None and not entries:
        print(f"no hay tareas del grupo «{args.solo}»")
        return
    adb_driver.ensure_device()
    adb_driver.wake()
    if adb_driver.screen_on() is False:
        print("aviso: la pantalla sigue apagada; sube el tiempo de "
              "suspensión en Ajustes > Pantalla o mantenla despierta.")
    width, height = adb_driver.screen_size()
    print(f"harness rev {adb_driver.REV}")
    rows = []
    for entry in entries:
        package, activity = entry["app"]
        if entry.get("needs_meta") and os.environ.get(
                "META_PLANNER", "stub").lower() != "meta":
            print(f"tarea omitida (necesita META_PLANNER=meta): "
                  f"{entry['task']}")
            continue
        adb_driver.wake()
        adb_driver.force_stop(package)
        if activity is None:
            opened = adb_driver.launch_package(package)
        else:
            opened = adb_driver.am_start(package, activity)
        if not opened:
            print(f"tarea omitida (no abrió {package}/{activity})")
            rows.append((entry["task"], entry["lang"], entry["group"],
                         False, None, planner))
            continue
        if not adb_driver.wait_focused(package):
            print("aviso: la app no tomó el foco a tiempo; "
                  "los primeros taps pueden fallar")
        time.sleep(SETTLE_SECONDS)
        labels = label_count()
        print(f"\n=== tarea: {entry['task']} [{entry['lang']}] "
              f"({entry['group']}, nodos={labels}) ===")
        success = agent_loop.run_task(
            entry["task"], entry["lang"],
            setup=scaled_setup(width, height, entry["setup"]),
            extra={"group": entry["group"], "labels": labels},
            confirm=ask_success,
        )
        rows.append((entry["task"], entry["lang"], entry["group"],
                     success, labels, planner))
    print("\n=== resumen ===")
    ok = sum(1 for _, _, _, s, _, _ in rows if s)
    print(f"éxito: {ok}/{len(rows)}")
    write_summary(rows)
    try:
        os.remove("window_dump.xml")
    except OSError:
        pass


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\ninterrumpido con Ctrl+C; lo corrido quedó en "
              "results/runs.jsonl")
