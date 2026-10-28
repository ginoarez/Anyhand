# AGENTS.md — actualizaciones (26 de octubre de 2026)

Lista de cambios al `AGENTS.md` y dónde aplicarlos. Fuentes: la guía del participante y las reglas del Meta Global AI Developer Hackathon, y las decisiones del día 1. Cada bloque indica qué hacer (agregar, reemplazar o borrar) y trae texto listo para pegar. Los nombres de API, modelos, comandos y campos se dejan en inglés.

Cambio de enfoque respecto a la versión anterior: **ya no hay una prueba de viabilidad sobre si Muse Spark entiende la pantalla del teléfono.** Se da por hecho que la entiende. Lo que sí se mide es la precisión en las apps sin etiquetas, pero como resultado de la evaluación, no como puerta de entrada al diseño.

## 1. Sección 1: reemplazar los dos últimos párrafos

Reemplaza el párrafo del evento y el de los idiomas por:

```
Construido para el Meta Global AI Developer Hackathon.
Pista: Indie Developer. Tema: Build for Your World (principal). La evaluación medida también apoya Push the Frontier, pero se entra con un solo tema.
Fechas: periodo de construcción 27 oct – 4 nov de 2026; kickoff 26 oct. Fecha límite de entrega: miércoles 4 nov, 9:00 PM ET (8:00 PM en San Luis Potosí). Meta: entregar la noche del 3 de noviembre.

Anyhand es un proyecto NUEVO construido durante el evento. La app publicada del autor, AirVox, es un proyecto previo y NO forma parte de esta entrega: no copiar su código y no mostrarla en el demo como parte del proyecto.

Idiomas del usuario: español, inglés y portugués.
```

## 2. Nueva sección después de la sección 2: "Reglas del hackatón y jurado"

```
## 2b. Reglas del hackatón y jurado (guía del participante)

Reglas que pueden descalificar el proyecto:
- Solo trabajo nuevo. Todo lo que se presenta debe construirse durante el evento. En la pista Indie no se reutiliza ningún código existente.
- El demo solo puede mostrar funciones, código y funcionalidad construidos durante el evento. Los jueces deben poder distinguir qué es nuevo; si no, el proyecto se descalifica.
- El repositorio debe ser público.
- Obligatorio: video demo de 3 minutos. Es uno de los artefactos principales del jurado, junto con el código y el producto en vivo.
- No usar código, datos ni recursos sobre los que no tengamos derechos.
- Evitar los tipos de proyecto prohibidos o genéricos: consejos médicos con IA, apps RAG básicas, analizadores de imágenes simples, chatbots simples de "IA para educación", filtros de candidatos para empleo, coaches de nutrición, analizadores de personalidad, analizadores o coaches deportivos, y cualquier cosa política o relacionada con guerra.
- Una sola pista. Un representante entrega; todas las personas que trabajaron en el proyecto deben figurar.

Jurado:
- Etapa 1 (aprobado/no aprobado): el proyecto encaja razonablemente en el tema y usa razonablemente Meta Model API y/o los modelos de pesos abiertos de Meta.
- Etapa 2 (0 a 10 por categoría): Impacto 30 %, Uso de las herramientas de Meta 25 %, Demo 25 %, Profundidad técnica y ejecución 20 %.
- Etapa 3: los 5 mejores por pista se juzgan en vivo a partir de sus videos.

Posicionamiento: Anyhand es un agente que ACTÚA, VERIFICA y se MIDE. No es un "descriptor de pantallas" (evitar la etiqueta de "simple image analyzer") ni un chatbot.

Premios a tener presentes (sin cambiar la arquitectura por perseguirlos): Best Agentic Use-Case; Best Build on Meta Open Weight Models (solo si una capa rápida con Muse Glimmer o Muse Spark Open funciona y corre en hardware propio o con un socio); Most Viral (publicar el demo en X y enviar el enlace); Community Vote.
```

## 3. Sección 3: agregar estas reglas duras

```
- SOLO TRABAJO NUEVO. No copiar código de AirVox ni de ningún proyecto previo. Reimplementar desde cero. Hacer commits pequeños y frecuentes para que el historial de Git demuestre lo construido durante el evento.
- Mantener en el README una sección "What was built during the event" y otra "Third-party tools and models" (modelos de Meta por API, librerías, Muse Code).
- No subir al repositorio público capturas de apps de terceros. Guardar solo registros de ejecución y métricas. Preferir apps de prueba propias o de código abierto.
- Nada político ni de guerra en las tareas de prueba, las apps de prueba ni el demo.
- Nunca subir claves de API, datos personales ni capturas con información personal. Usar cuentas de prueba.
- Leer la política de uso aceptable de Meta Model API antes de construir la capa de acciones del agente (está en las páginas legales de https://dev.meta.ai). Reportar cualquier restricción sobre automatizar apps de terceros.
```

## 4. Sección 4.1: reemplazar la nota de acceso y créditos

```
- Créditos del hackatón: cada participante aceptado recibe $150 en créditos de Meta Model API. Se reclaman con el formulario de créditos antes del miércoles 4 nov, 9:00 PM ET; cada persona reclama los suyos. Para usarlos hay que agregar una tarjeta de pago a la cuenta de Meta (la preautorización temporal se libera sola). Activar la notificación de límite de gasto en la página de Billing. Confirmar en Billing que los créditos se aplicaron.
- Créditos de socios (no garantizados): Modal $100 (primeros 1,000 participantes), Baseten $100 (se revisa el caso de uso; para el hackatón usar `meta-models/muse-spark-open-1026`, no `meta-models/muse-spark-open`), Fireworks $25 (equipos preseleccionados).
- La guía lista Muse Wonder, Muse Image, Muse Voice Transcribe y SAM 3.1 como disponibles por Meta Model API; Muse Glimmer y Muse Spark Open son los modelos de pesos abiertos.
```

Borrar de la sección 12 el punto que dice que los créditos del hackatón no están verificados.

## 5. Sección 5: solo Kotlin, sin Flutter

El proyecto Android se creó como "Empty Activity" con Jetpack Compose (Kotlin, sin Flutter).
- En la sección 5, borrar el punto "UI layer / bridge (optional): Flutter (Dart) ... EventChannel".
- En la sección 9, borrar las dos líneas de Dart (evento final del EventChannel y `.toString()` en mapas de pasos).
- Conservar esta lección, reformulada: "Cualquier callback o Flow entre el servicio de accesibilidad y la interfaz debe completarse solo con un resultado final de éxito o error, nunca con el primer evento de progreso."
- Agregar: `App Android: Kotlin + Jetpack Compose. Package: com.anyhand.app (confirmar el valor final en el proyecto).`

## 6. Nueva sección después de la sección 5: orden de trabajo y estructura del repositorio

```
## 5b. Orden de trabajo y estructura del repositorio

Orden: primero el ciclo del agente desde la PC, después la app.
1. Harness en Python + adb desde la PC (sin escribir código de app Android): teléfono conectado con depuración USB; capturas con `adb exec-out screencap -p`; tamaño de pantalla con `adb shell wm size`; acciones con `adb shell input tap X Y` y `adb shell input swipe X1 Y1 X2 Y2 300`.
2. Primer ciclo completo con Muse Spark (herramienta `computer`) sobre el teléfono. Es calibración del driver, no una prueba de viabilidad: ajustar nombres de teclas, signo del scroll y el tamaño de captura, y registrar latencia y tokens por paso.
3. Prueba de SAM 3.1 sobre capturas de interfaces (único experimento que queda del día 1, porque SAM sí es una incógnita en interfaces).
4. Después: una app de prueba sin etiquetas (propia, un lienzo con botones dibujados) y el servicio de accesibilidad (AccessibilityDriver).

Un solo repositorio:
- `app/` app Android (Kotlin + Compose) y el AccessibilityDriver
- `harness/` Python: AdbDriver, ciclo del agente, verificador, ejecutor de la evaluación
- `results/` registros de ejecución (runs.jsonl) y tablas/gráficas generadas
- `docs/` recursos del README y guion del demo
- `AGENTS.md`, `README.md`, `.gitignore` (claves, configuración local, capturas)

No construir el AccessibilityService hasta que el ciclo del harness funcione de punta a punta.
```

## 7. Sección 8: reemplazar el párrafo del conjunto de tareas

```
Conjunto de tareas: 10 a 15 apps en dos grupos (con etiquetas / sin etiquetas), 2 o 3 tareas por app con un criterio de éxito sin ambigüedad.
- Grupo sin etiquetas: primero, nuestra propia app de prueba (un lienzo con botones dibujados y algunas pantallas), construida durante el evento. Sumar apps de código abierto de F-Droid y apps del sistema. Evitar apps comerciales cuyas capturas puedan terminar en el repositorio público.
- Verificar la calidad de las etiquetas con `adb shell uiautomator dump`.
- Excluir apps con capturas bloqueadas o con datos sensibles, y cualquier cosa política o de guerra.
- La precisión de localización de Muse Spark en las apps sin etiquetas se MIDE como resultado (éxito por tipo de app y por sistema). No es una prueba previa de viabilidad.
```

## 8. Sección 10: reemplazar los hitos por este calendario

Todas las horas de la guía están en hora del Este (ET). San Luis Potosí (SLP) va 2 horas por detrás hasta el 31 de octubre y 1 hora por detrás desde el 1 de noviembre (EE. UU. cambia el reloj el 1 de noviembre; SLP no).

```
- Lun 26 oct: kickoff 12:00 PM ET (10:00 AM SLP). Reclamar créditos (Meta, Modal, Baseten), entrar al Discord, crear el repositorio público, primer commit. Primera llamada a la API y primer ciclo con adb + Python. Prueba de SAM 3.1 sobre capturas de interfaces. Medir latencia y tokens por paso.
- Mar 27 oct: sesión "Agents 101: Build a Multimodal AI Agent", 12:00 PM ET (10:00 AM SLP). Ciclo mínimo (voz -> acción computer -> verificación) en una app con AdbDriver. Empezar a grabar clips cortos de avance.
- Mié 28 oct: sesión "Agents 102: Best Practices for Verifiable Agents", 12:00 PM ET (10:00 AM SLP). Harness + línea base A. Revisar el diseño de la verificación después de la sesión.
- Jue 29 oct: ConfirmationGate, comprobaciones de seguridad de la API, reintentos, tres idiomas.
- Vie 30 oct: sesión sobre modelos abiertos de Muse con Fireworks, 12:00 PM ET (10:00 AM SLP). Primera evaluación completa.
- Sáb 31 oct: ablación (B vs C), trabajo de latencia, comparación opcional Spark vs Wonder.
- Dom 1 nov: corregir los fallos principales; prototipo de iPhone con capturas. El guion del video ya debe existir.
- Lun 2 nov: congelar funciones; evaluación final.
- Mar 3 nov: edición del video, README, resultados; último día completo para dudas de entrega. Entregar esta noche si está listo.
- Mié 4 nov: office hours finales; fecha límite 9:00 PM ET (8:00 PM SLP). Entregar antes de que termine la tarde si no se hizo ya.
- Jue 5 – Dom 8 nov: evaluación del jurado. Lun 9 nov: ceremonia de cierre, 12:00 PM ET (11:00 AM SLP); ganador del voto de la comunidad el 16 de noviembre.

Office hours (texto, Discord #office-hours): entre semana 10–11 AM ET = 8:00–9:00 AM SLP hasta el 30 de octubre, y 9:00–10:00 AM SLP del 2 al 4 de noviembre.
```

## 9. Nueva sección: video demo (3 minutos, obligatorio)

```
## 10b. Video demo (3 minutos)

Guion:
1. Problema (unos 30 s): una persona que no puede usar las manos y una app sin etiquetas de accesibilidad que las herramientas de control por voz no pueden operar.
2. El contraste: la línea base con el árbol de accesibilidad falla en la app sin etiquetas; Anyhand funciona por voz.
3. El ciclo de verificación en acción (pantalla antes/después, reintento o pregunta cuando no coincide).
4. La confirmación en una acción irreversible.
5. Resultados: éxito por sistema y tipo de app, latencia, errores evitados (desde runs.jsonl).
6. Limitaciones honestas y siguiente paso (iPhone, fase 2).
En todo el video debe quedar claro qué se construyó durante el evento y qué herramientas de Meta se usaron (herramienta `computer` de Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Code). Mostrar solo trabajo del evento.

Proceso: grabar clips cortos desde el 27 de octubre, guion listo el 1 de noviembre, edición el 2 y 3 de noviembre. Subirlo a un lugar donde el enlace abra sin iniciar sesión y probarlo desde otro navegador. Antes de entregar, comprobar: repositorio público, enlace del video funcionando y todas las personas del equipo listadas.
```

## 10. Sección 12: actualizar la lista de puntos sin verificar

Borrar:
- "Whether the hackathon grants credits ..." (confirmado arriba).
- "How well Muse Spark 1.3 localizes elements in phone screenshots of unlabeled apps ..." (se da por hecho que entiende la pantalla del teléfono; la precisión se mide en la evaluación, sección 7 de este archivo).

Mantener, como calibración del driver y no como duda de viabilidad:
- Qué nombres de teclas envía el modelo en `keypress` y cómo se traducen a Android.
- La convención de signo de `scroll_x` / `scroll_y`.
- Si el modelo usa los botones `right`/`wheel`/`back`/`forward` en una interfaz táctil.
- El esquema exacto de las herramientas de función combinadas con la herramienta `computer`.
- Latencia por paso y costo por tarea con nuestro tamaño de captura.
- Si `truncation: "auto"` está habilitado para nuestra cuenta.

Agregar:
- Si Meta acepta la tarjeta (o tarjeta virtual) usada para los créditos.
- Qué nivel de API de Android requiere el método de captura del servicio de accesibilidad y si el teléfono de pruebas lo soporta (de ahí sale el SDK mínimo; el proyecto se creó con un SDK mínimo por confirmar).
- Si `adb shell input text` maneja espacios y caracteres no ASCII (ñ, á) para escribir en español.
- Qué dice la política de uso aceptable de Meta Model API sobre agentes que automatizan apps de terceros.
- Si SAM 3.1 aporta algo sobre capturas de interfaces.

## 11. Git: commits pequeños y frecuentes, con mensajes en inglés

Agregar al `AGENTS.md`, en la sección 3 (reglas duras) o como sección nueva "Git workflow":

```
## Git workflow

Why: the public repository and its commit history are the evidence that Anyhand was built during the event (Oct 27 – Nov 4). The history must show clear, steady progress.

Commits:
- Make small, frequent commits: one logical change per commit, after each working step. Several commits per day is expected.
- Write every commit message in English, describing what was done or adjusted.
- Subject line in the imperative mood, present tense, no trailing period, about 72 characters or fewer (e.g. "Add ...", "Fix ...", "Update ...", "Refactor ...", "Remove ...").
- Add a short body only when the reason is not obvious (why the change was needed, what was measured).
- Commit working states. Avoid vague messages such as "wip", "fix", "changes" or "update stuff".
- Do not mix unrelated changes in one commit.
- Commit before and after risky changes so they can be reverted.

Good examples:
- Add adb screenshot capture and screen size helper
- Implement first computer-tool loop with Muse Spark
- Fix scroll direction mapping for the computer tool
- Add runs.jsonl logger with latency and token fields
- Add ConfirmationGate for irreversible actions
- Adjust screenshot size to reduce tokens per step
- Update README with "What was built during the event"

Rules:
- Never rewrite published history: no force push, no rebase or squash of pushed commits, no amending pushed commits.
- Before every commit, run `git status` and review `git diff --staged`. Make sure no API keys, personal data, third-party app screenshots, or demo videos are staged.
- Push at the end of each work session at least, and ideally after each commit, so the remote history shows steady progress.
- The first commit contains the Android Studio template (generated code). Its message must say so: "Add Android Studio Empty Activity template".
- Muse Code does not commit or push on its own. When a task is finished, say what changed and propose a commit message in English; run `git commit` or `git push` only when the user asks.
- If a secret is ever committed, tell the user immediately so they can revoke the key. Deleting the commit is not enough.
```

Rutina diaria sugerida (para el autor, no para el modelo): al terminar cada paso, `git status`, `git diff --staged`, `git commit -m "<mensaje en inglés>"` y `git push`.

## 12. Preguntas para #office-hours (Discord, equipo de Meta)

- ¿Qué nombres de teclas envía el modelo en `keypress` y cuáles recomiendan mapear en Android?
- ¿Cuál es la convención de signo de `scroll_x` / `scroll_y`?
- ¿Está habilitado `truncation: "auto"` en las cuentas del hackatón?
- ¿Qué tamaño de captura recomiendan para pantallas de teléfono en trayectorias largas?
- ¿Hay recomendaciones para combinar la herramienta `computer` con nuestras propias funciones (`ask_user`, `request_confirmation`)?
