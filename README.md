# Anyhand

Hands-free voice control for any Android app. Built for the Meta Global AI
Developer Hackathon (Indie Developer track, Build for Your World).

Anyhand is an agent that **acts, verifies, and measures**: it executes voice
commands on the phone screen, checks every step against before/after
screenshots, asks for confirmation before irreversible actions, and logs
latency and results for every run.

Status: working hands-free agent. Say the wake word (configurable, no
touch needed) and dictate any order; the agent plans with Meta, acts over
whatever app is in front, verifies each step, and keeps listening in the
background behind a small floating avatar. The end-to-end loop also runs
from a PC (adb harness), driving our own unlabeled test app in Spanish,
English, and Portuguese.

## Meta models used

- **Muse Spark 1.3** (Meta Model API, Responses API, `computer` tool) — the
  planning and acting core of the agent. It receives the user's command,
  a phone screenshot, the labeled on-screen elements and the step history,
  and returns the next UI action (tap, swipe, type...). Used in-app
  (`app/.../agent/MetaPlanner.kt`) and from the PC harness
  (`harness/meta_client.py`). Real Meta runs are logged with per-step
  token counts (`tok=`) in `results/runs.jsonl`.

To run with the real Meta planner:

1. Claim your Meta Model API hackathon credits and create a key at
   https://dev.meta.ai
2. Copy `.env.example` to `.env` and paste your key into `.env`
3. Set the planner to `meta` (in-app Settings, or `META_PLANNER=meta`)
4. Run a task — every step now calls Muse Spark 1.3

Without a key, the app falls back to a free local stub planner so the UI
and the accessibility driver can be reviewed without API access.

## Try the app

1. Install and open the app.
2. Enable Anyhand in Settings > Accessibility (the status flips to active
   on its own, no restart needed).
3. Open the unlabeled test app from the main screen.
4. Run a voice or typed command such as "ve a la pantalla siguiente".

No extra tooling is required to try it.

First-time setup without touching the phone (for hands-free users, a
helper runs this once over USB; afterwards everything is voice):

```powershell
adb shell settings put secure enabled_accessibility_services com.anyhand.app/.agent.AccessibilityDriverService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.anyhand.app android.permission.RECORD_AUDIO
```

Android does not allow apps to enable their own accessibility service,
so the very first activation always needs a helper or adb. The app's
first-launch card links straight to the system settings for the manual
path.

## Reproduce the evaluation

With the phone on USB debugging:

```powershell
$env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $env:ADB devices
python harness/agent_loop.py "ve a la pantalla siguiente" --lang es
python harness/eval.py
```

Runs append to `results/runs.jsonl` (one JSON object per line: task,
language, success, planner, model, per-step latency, auto/manual
verdicts). Results are reported per planner, never mixed.

**Tests performed and completed:** the formal evaluation set was run
end-to-end in Spanish, English and Portuguese and **all of its tasks
passed (8/8)**. The per-task table is below and the full record is in
`results/summary.md`.

- **Stub planner (local calibration):** formal set (Oct 28, 1080x2400
  device) 8/8. This calibrates the driver and verifier only; it says
  nothing about model intelligence.
- **Meta planner (Muse Spark 1.3, real runs):** real runs were also
  executed against the test app, with per-step token counts (`tok=`)
  recorded. Early exploratory real-app runs are kept only as log
  records in `results/` and are not presented as the main evidence;
  debug captures stay local in `results/debug/` (not committed).
  Regenerate the two tables any time with
  `python harness/eval.py --resumen`.
- **SAM 2 (on-device tap refinement):** also tested on device. Its
  ONNX encoder/decoder load on the phone and it refines taps only on
  unlabeled/canvas screens; it is skipped when the screen already
  exposes labeled clickable elements, where the accessibility node
  center is the more reliable target.

The demo video is edited and sped up: live Meta steps take ~12 s per
step on simple tasks (the physical action itself is 120–170 ms; the
rest is the model call). The demo footage shows the test app.

Formal calibration set (stub planner):

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
- Main UI: voice or text commands, wake word (Settings or by voice),
  continuous background listening with a floating avatar that shows the
  agent state and what it hears, ES/EN/PT selector, confirmation dialog,
  run log.
- PC harness (`harness/`, stdlib only): adb driver, PNG verifier,
  agent loop, task evaluator.
- Unit tests (`AgentLogicTest`, run from Android Studio).

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
- Lucide icons (ISC License) - https://lucide.dev — vector drawables in
  `app/src/main/res/drawable/ic_lucide_*.xml`, license in `docs/licenses/`
- Atkinson Hyperlegible font (SIL Open Font License 1.1), Braille Institute
  of America — fonts in `app/src/main/res/font/`, license in `docs/licenses/`
- SAM 2 (Segment Anything 2, Meta, Apache 2.0) — optional on-device
  tap refinement, tested on device; ONNX models are downloaded
  separately and never committed. Integration adapted from
  Segment-Anything-Android (Apache 2.0).
- ONNX Runtime for Android (Microsoft, MIT).
- AndroidX libraries (Compose, Activity, Core, Lifecycle).
- Muse Code (development assistant).
- Python standard library only for the harness; no third-party packages.

## Repository layout

- `app/` — Android app (Kotlin + Compose) and AccessibilityDriver.
- `harness/` — Python adb driver, agent loop, verifier, evaluator.
- `results/` — run records (`runs.jsonl`) and generated tables.
- `docs/` — third-party licenses (`licenses/`) and build notes
  (`agents-updates-2026-10-26.md`).
