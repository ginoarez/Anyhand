"""Evaluador: corre una lista de tareas y resume éxito y latencia.

Uso:
    python harness/eval.py

Cada tarea abre la app de prueba propia y ejecuta el ciclo. El criterio de
éxito automático es el cambio visible verificado; además se pide confirmación
manual (s/n) porque el criterio sin ambigüedad lo define la persona que mira
la pantalla. Todo queda en results/runs.jsonl.

Conjunto de tareas (ver AGENTS.md §8): empezar con nuestra app de prueba,
luego apps de código abierto y del sistema. Nada comercial, nada político
ni de guerra.
"""

import adb_driver
import agent_loop

TEST_PACKAGE = "com.anyhand.app"
TEST_ACTIVITY = ".unlabeled.UnlabeledTestActivity"

TASKS = [
    # (tarea, idioma)
    ("ve a la pantalla siguiente", "es"),
    ("go to the next screen", "en"),
    ("avançar para a próxima tela", "pt"),
]


def ask_success(task):
    while True:
        answer = input(f"¿se cumplió «{task}»? [s/n]: ").strip().lower()
        if answer in ("s", "y", "sim", "yes"):
            return True
        if answer in ("n", "no", "não"):
            return False


def main():
    adb_driver.ensure_device()
    results = []
    for task, lang in TASKS:
        adb_driver.am_start(TEST_PACKAGE, TEST_ACTIVITY)
        print(f"\n=== tarea: {task} [{lang}] ===")
        auto = agent_loop.run_task(task, lang)
        manual = ask_success(task)
        success = auto and manual
        results.append((task, lang, success))
        print(f"tarea: {'ÉXITO' if success else 'FALLO'} "
              f"(auto={auto} manual={manual})")
    print("\n=== resumen ===")
    ok = sum(1 for _, _, s in results if s)
    print(f"éxito: {ok}/{len(results)}")
    for task, lang, success in results:
        print(f"  [{'x' if success else ' '}] [{lang}] {task}")


if __name__ == "__main__":
    main()
