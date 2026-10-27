"""Registro de ejecuciones en formato JSONL (ver results/runs.jsonl).

Cada línea es un JSON con: task, lang, success y steps
[{i, ok, ms, tok}]. Sin dependencias.
"""

import json
import os
import time


class RunLogger:
    def __init__(self, path="results/runs.jsonl"):
        self.path = path
        self.steps = []
        self.started = None

    def task_start(self):
        self.steps = []
        self.started = time.time()

    def step(self, index, ok, latency_ms, tokens=0, detail=""):
        self.steps.append(
            {"i": index, "ok": bool(ok), "ms": int(latency_ms),
             "tok": int(tokens), "detail": detail}
        )

    def task_end(self, task, lang, success):
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        record = {
            "task": task,
            "lang": lang,
            "success": bool(success),
            "seconds": round(time.time() - self.started, 2),
            "steps": self.steps,
        }
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        return record
