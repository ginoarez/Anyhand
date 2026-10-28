"""Evaluación formal v1: grupos con/sin etiquetas, tabla de resultados.

Uso:
    python harness/eval.py

Grupo sin etiquetas: nuestra app de prueba (propia, construida en el evento).
Grupo con etiquetas: app del sistema (Ajustes), con conteo de nodos como
evidencia de que las etiquetas existen. Nada comercial, nada político
ni de guerra (ver AGENTS.md §8).

Cada tarea registra en results/runs.jsonl el veredicto automático (cambio
visible) Y el manual (persona mirando la pantalla). Al final se escribe
results/summary.md con la tabla por grupo.

Nota honesta: con el planificador stub esto mide la fiabilidad del driver,
no la inteligencia del modelo. La evaluación de inteligencia llega con la
herramienta `computer` de Muse Spark (TODO en agent_loop.py).
"""

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


def write_summary(rows, path="results/summary.md"):
    groups = {}
    for task, lang, group, success, labels in rows:
        groups.setdefault(group, []).append(success)
    lines = ["# Resumen de evaluación",
             "",
             f"Fecha: {datetime.date.today().isoformat()}",
             "",
             "| grupo | éxito | total |",
             "|---|---|---|"]
    total_ok = total_n = 0
    for group, results in groups.items():
        ok = sum(1 for s in results if s)
        lines.append(f"| {group} | {ok} | {len(results)} |")
        total_ok += ok
        total_n += len(results)
    lines += ["",
              f"Total: {total_ok}/{total_n}",
              "",
              "## Por tarea",
              "",
              "| tarea | idioma | grupo | éxito | nodos |",
              "|---|---|---|---|---|"]
    for task, lang, group, success, labels in rows:
        mark = "x" if success else " "
        lines.append(f"| {task} | {lang} | {group} | [{mark}] | "
                     f"{labels if labels is not None else '-'} |")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"tabla escrita en {path}")


def main():
    adb_driver.ensure_device()
    adb_driver.wake()
    if adb_driver.screen_on() is False:
        print("aviso: la pantalla sigue apagada; sube el tiempo de "
              "suspensión en Ajustes > Pantalla o mantenla despierta.")
    width, height = adb_driver.screen_size()
    rows = []
    for entry in TASKS:
        package, activity = entry["app"]
        adb_driver.wake()
        adb_driver.force_stop(package)
        if not adb_driver.am_start(package, activity):
            print(f"tarea omitida (no abrió {package}/{activity})")
            rows.append((entry["task"], entry["lang"], entry["group"],
                         False, None))
            continue
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
                     success, labels))
    print("\n=== resumen ===")
    ok = sum(1 for _, _, _, s, _ in rows if s)
    print(f"éxito: {ok}/{len(rows)}")
    write_summary(rows)
    try:
        os.remove("window_dump.xml")
    except OSError:
        pass


if __name__ == "__main__":
    main()
