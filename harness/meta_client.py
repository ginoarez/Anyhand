"""Cliente de Meta Model API para el planificador real.

Contrato (receta oficial `metacua` del cookbook de Meta + docs de
https://dev.meta.ai):
- Base URL: https://api.meta.ai/v1 (o META_BASE_URL)
- Modelo: muse-spark-1.3 (o META_MODEL)
- Auth: Authorization: Bearer MODEL_API_KEY (formato LLM|id|secreto)
- POST /responses con parallel_tool_calls=false y store=false: las
  acciones de computadora van en serie y con estado.
- Herramienta `computer.computer`: el modelo ve la captura y devuelve
  UNA llamada con la acción GUI; el harness la ejecuta.

La clave NUNCA se commitea: solo vive en la variable de entorno
MODEL_API_KEY (ver .gitignore: .env). Sin clave, el ciclo usa el stub
local y lo registra (ver agent_loop.py).

Verificado contra docs el 28 oct 2026 vía cookbook público; si
dev.meta.ai cambia el esquema, este módulo es el único que se toca.
"""

import base64
import json
import os
import urllib.request

BASE_URL = os.environ.get("META_BASE_URL", "https://api.meta.ai/v1")
MODEL = os.environ.get("META_MODEL", "muse-spark-1.3")


class MetaError(RuntimeError):
    pass


def api_key():
    key = os.environ.get("MODEL_API_KEY", "")
    if not key:
        raise MetaError(
            "falta MODEL_API_KEY (crea la clave en dev.meta.ai y "
            "expórtala en tu terminal, nunca en un archivo commiteado)"
        )
    return key


COMPUTER_TOOL = {
    "type": "function",
    "name": "computer.computer",
    "description": (
        "Realiza UNA acción sobre la pantalla del teléfono y devuelve "
        "el control. Coordenadas en PÍXELES de la captura adjunta."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["tap", "swipe", "type", "key", "done"],
                "description": (
                    "tap: tocar un punto. swipe: deslizar. type: escribir "
                    "texto en el campo enfocado. key: tecla (Back). done: "
                    "la tarea ya está cumplida."
                ),
            },
            "x": {"type": "number", "description": "píxel X para tap"},
            "y": {"type": "number", "description": "píxel Y para tap"},
            "x1": {"type": "number", "description": "origen X del swipe"},
            "y1": {"type": "number", "description": "origen Y del swipe"},
            "x2": {"type": "number", "description": "destino X del swipe"},
            "y2": {"type": "number", "description": "destino Y del swipe"},
            "text": {"type": "string", "description": "texto para type"},
            "key": {"type": "string", "description": "tecla para key"},
        },
        "required": ["action"],
    },
}


def plan_step(task, lang, png_bytes, width, height, timeout_s=120):
    """Un paso look-act-look: captura + tarea -> (acción, in_tok, out_tok).

    Devuelve acción en el formato del stub: ("tap", x, y), ("swipe", ...),
    ("text", s), ("key", code) o ("done",). Lanza MetaError si la API
    falla; quien llama decide el fallback.
    """
    image_b64 = base64.b64encode(png_bytes).decode("ascii")
    prompt = (
        f"Tarea ({lang}): {task}\n"
        f"La captura mide {width}x{height} píxeles. Devuelve UNA llamada "
        f"a computer.computer con la siguiente acción física que cumple "
        f"la tarea. Si la pantalla ya muestra la tarea cumplida, usa "
        f"action=done. Responde SOLO con la llamada a la herramienta."
    )
    body = {
        "model": MODEL,
        "store": False,
        "parallel_tool_calls": False,
        "tools": [COMPUTER_TOOL],
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt},
                    {"type": "input_image",
                     "image_url": f"data:image/png;base64,{image_b64}"},
                ],
            }
        ],
    }
    request = urllib.request.Request(
        BASE_URL + "/responses",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + api_key(),
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        raise MetaError(f"la API no respondió: {exc}") from exc
    return parse_response(payload)


def parse_response(payload):
    """Extrae (acción, in_tok, out_tok) de una respuesta /responses."""
    in_tok = out_tok = 0
    usage = payload.get("usage") or {}
    in_tok = int(usage.get("input_tokens") or 0)
    out_tok = int(usage.get("output_tokens") or 0)
    for item in payload.get("output") or []:
        if isinstance(item, dict) and item.get("type") == "function_call":
            args = json.loads(item.get("arguments") or "{}")
            return map_action(args), in_tok, out_tok
    raise MetaError(f"sin function_call en la respuesta: {payload}")


def map_action(args):
    """Argumentos del modelo -> tupla de acción del harness."""
    action = (args.get("action") or "").strip().lower()
    if action == "tap":
        return ("tap", float(args.get("x", 0)), float(args.get("y", 0)))
    if action == "swipe":
        return ("swipe", float(args.get("x1", 0)), float(args.get("y1", 0)),
                float(args.get("x2", 0)), float(args.get("y2", 0)), 300)
    if action == "type":
        return ("text", str(args.get("text", "")))
    if action == "key":
        key = str(args.get("key", "Back"))
        code = "KEYCODE_" + key.upper().replace("Á", "A").replace(" ", "_")
        return ("key", code)
    if action == "done":
        return ("done",)
    raise MetaError(f"acción desconocida del modelo: {args}")
