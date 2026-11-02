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
import time
import urllib.request

import verify

BASE_URL = os.environ.get("META_BASE_URL", "https://api.meta.ai/v1")
MODEL = os.environ.get("META_MODEL", "muse-spark-1.3")
# Factor de reducción de la captura enviada al modelo (0.5 = 1/4 de
# píxeles ≈ 1/4 de tokens de entrada). Ajustable sin tocar código.
try:
    SHRINK = float(os.environ.get("META_SHRINK", "0.5"))
except ValueError:
    SHRINK = 0.5
if not 0.1 <= SHRINK <= 1.0:
    SHRINK = 0.5


class MetaError(RuntimeError):
    pass


def _save_debug(small_png, task, step, raw_action, scaled_action,
                small_w, small_h, upscale):
    """Evidencia local por paso (results/debug/, nunca se sube): la
    captura exacta que vio el modelo + la acción cruda que devolvió +
    la acción ya escalada que tocó el driver. Comparar las tres cosas
    dice si falla el mapeo de coordenadas o el modelo eligió mal."""
    try:
        folder = os.path.join("results", "debug")
        os.makedirs(folder, exist_ok=True)
        stamp = time.strftime("%H%M%S")
        base = os.path.join(folder, f"{stamp}-paso{step}")
        with open(base + ".png", "wb") as fh:
            fh.write(small_png)
        data = {
            "task": task,
            "step": step,
            "captura_modelo": [small_w, small_h],
            "upscale": upscale,
            "accion_modelo": list(raw_action),
            "accion_driver": list(scaled_action),
        }
        with open(base + ".json", "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=1)
    except OSError:
        pass  # la depuración nunca tumba una tarea


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


def plan_step(task, lang, png_bytes, width, height, step=0, cap=10,
              timeout_s=120, history=(), ui_elements=()):
    """Un paso look-act-look: captura + tarea -> (acción, in_tok, out_tok).

    La captura se reduce (ver SHRINK) antes de enviarla y las coordenadas
    del modelo se re-escalan a píxeles reales antes de devolverlas.
    `history` trae los últimos pasos ("tap 534 2062 cambio=0.3027")
    para que el modelo corrija el rumbo en vez de repetir a ciegas.
    `ui_elements` trae ("etiqueta", x, y) en píxeles reales desde
    uiautomator: se re-escalan a la captura para que el modelo elija por
    nombre en vez de adivinar píxeles.
    Devuelve acción en el formato del stub: ("tap", x, y), ("swipe", ...),
    ("text", s), ("key", code) o ("done",). Lanza MetaError si la API
    falla; quien llama decide el fallback.
    """
    small_png, small_w, small_h, upscale = verify.shrink_png(
        png_bytes, SHRINK)
    image_b64 = base64.b64encode(small_png).decode("ascii")
    prompt = (
        f"Tarea ({lang}): {task}\n"
        f"Paso {step + 1} de máximo {cap}. La captura mide "
        f"{small_w}x{small_h} píxeles: da las coordenadas en ESA escala. "
        f"Devuelve UNA llamada a computer.computer con la siguiente "
        f"acción física que cumple la tarea. Si la pantalla ya muestra "
        f"éxito o finalización (pantalla verde de fin, confirmación "
        f"visible), usa action=done en vez de tocar nada. Responde SOLO "
        f"con la llamada a la herramienta. Si la tarea pide ir a mensajes "
        f"o al chat, toca el ícono de mensajes de arriba a la derecha; no "
        f"toques fotos, videos, historias ni la barra inferior. Si "
        f"escribiste en un buscador y el teclado sigue abierto, ciérralo "
        f"con Back antes de tocar resultados."
    )
    elements = list(ui_elements)[:30]
    if elements:
        items = []
        for label, ex, ey in elements:
            items.append(f"- «{label}» en "
                         f"({ex * small_w / width:.0f}, "
                         f"{ey * small_h / height:.0f})")
        prompt += (
            "\nElementos tocables con etiqueta (misma escala de la "
            "captura):\n" + "\n".join(items) +
            "\nSi alguno sirve para la tarea, toca SUS coordenadas "
            "exactas en vez de adivinar píxeles."
        )
    recent = list(history)[-4:]
    if recent:
        prompt += (
            "\nYa intentaste (de más viejo a más nuevo): "
            + "; ".join(recent)
            + ". No repitas toques que no abrieron nada: corrige el rumbo "
              "(por ejemplo, el buzón de mensajes suele estar arriba a la "
              "derecha, no en la barra inferior)."
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
    action, in_tok, out_tok = parse_response(payload)
    scaled = scale_action(action, upscale)
    _save_debug(small_png, task, step, action, scaled,
                small_w, small_h, upscale)
    return scaled, in_tok, out_tok


def scale_action(action, upscale):
    """Coordenadas del modelo (captura chica) -> píxeles reales."""
    kind = action[0]
    if kind == "tap":
        _, x, y = action
        return ("tap", x * upscale, y * upscale)
    if kind == "swipe":
        _, x1, y1, x2, y2, ms = action
        return ("swipe", x1 * upscale, y1 * upscale,
                x2 * upscale, y2 * upscale, ms)
    return action


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
