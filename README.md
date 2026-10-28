# Anyhand

Hands-free voice control for any Android app. Built for the Meta Global AI
Developer Hackathon (Indie Developer track, Build for Your World).

Anyhand is an agent that **acts, verifies, and measures**: it executes voice
commands on the phone screen, checks every step against before/after
screenshots, asks for confirmation before irreversible actions, and logs
latency and results for every run.

Status: working calibration milestone. The end-to-end loop runs both in-app
(AccessibilityDriver) and from a PC (adb harness), driving our own unlabeled
test app in Spanish, English, and Portuguese.

## Try the app

1. Install and open the app.
2. Enable Anyhand in Settings > Accessibility (the status flips to active
   on its own, no restart needed).
3. Open the unlabeled test app from the main screen.
4. Run a voice or typed command such as "ve a la pantalla siguiente".

No extra tooling is required to try it.

## Reproduce the evaluation

With the phone on USB debugging:

```powershell
$env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $env:ADB devices
python harness/agent_loop.py "ve a la pantalla siguiente" --lang es
python harness/eval.py
```

Runs append to `results/runs.jsonl` (one JSON object per line: task,
language, success, per-step latency, auto/manual verdicts).
Formal evaluation (Oct 28, 1080x2400 device): 8/8.

| task | lang | ok |
|---|---|---|
| ve a la pantalla siguiente | es | x |
| go to the next screen | en | x |
| avançar para a próxima tela | pt | x |
| escribe "hola mundo" | es | x |
| type "hello" | en | x |
| pulsa ok para terminar | es | x |
| vuelve atrás (Settings) | es | x |
| go back (Settings) | en | x |

Calibrations from real runs: pixel threshold 0.005 → 0.002 (a short
typed word moves few pixels), wake + settle + focus wait (locked screen
and cold starts ate taps), force-stop per task (stale Done screen).
Full table in `results/summary.md`.

## What was built during the event

- Agent core (`app/.../agent/`): plan-act-verify loop, pixel-diff verifier,
  key mapper, confirmation gate, JSONL run records.
- AccessibilityDriver (`AccessibilityDriverService`): gestures, text input,
  screenshots, node dumps, Back/Home global actions.
- Unlabeled test app (canvas-drawn, zero accessibility nodes, painted
  pixel-only labels).
- Main UI: voice or text commands, ES/EN/PT selector, confirmation dialog,
  run log.
- PC harness (`harness/`, stdlib only): adb driver, PNG verifier,
  agent loop, task evaluator.
- Unit tests (`AgentLogicTest`, 6/6 passing).

Planning has two modes. Default is a keyword stub for free driver
calibration (`tok: 0` in every record). With `META_PLANNER=meta` and a
`MODEL_API_KEY` from dev.meta.ai, each step calls Muse Spark 1.3 via
`POST https://api.meta.ai/v1/responses` with the `computer.computer`
tool (official look-act-look recipe), screenshot in, one GUI action out,
tokens recorded per step. Captures are shrunk to half resolution before
sending (≈55% fewer input tokens, ~2200/step measured) and coordinates are
scaled back; the loop aborts after two straight no-change steps. The key
lives only in your terminal environment (see `.gitignore`: `.env` files
are never committed).

## Third-party tools and models

- Meta Model API: Muse Spark 1.3 (`computer.computer` tool, Responses API) for planning; stub planner for free calibration.
- AndroidX libraries (Compose, Activity, Core, Lifecycle).
- Muse Code (development assistant).
- Python standard library only for the harness; no third-party packages.

## Repository layout

- `app/` — Android app (Kotlin + Compose) and AccessibilityDriver.
- `harness/` — Python adb driver, agent loop, verifier, evaluator.
- `results/` — run records (`runs.jsonl`) and generated tables.
- `docs/` — README assets and demo script (in progress).
