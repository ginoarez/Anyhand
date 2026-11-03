"""Etiqueta registros viejos de runs.jsonl con planner y model.

De una sola vez (ya corrido el 2026-11-02): los registros sin campo
`planner` se infieren por los tokens (tok>0 en algún paso = Meta real).
Idempotente: si ya traen planner, no se tocan.
"""
import json

rows = [json.loads(l) for l in open("results/runs.jsonl", encoding="utf-8") if l.strip()]
for r in rows:
    if not r.get("planner"):
        real = any(s.get("tok") for s in r.get("steps", []))
        r["planner"] = "meta" if real else "stub"
        r["model"] = "muse-spark-1.3" if real else "stub"
with open("results/runs.jsonl", "w", encoding="utf-8") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print(f"etiquetados: {len(rows)}")
