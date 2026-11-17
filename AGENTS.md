# Instrucciones del proyecto

Lee `docs/project-brief.es.md`, `protocol/PROTOCOL.md`, `docs/tasks/BOARD.md` y `docs/CONTINUITY.md` cuando existan.

- Aplicación personal Windows x64 / Android API 28+, LAN, sin servicios remotos ni Google Play Services.
- GPT-6.1 Sol coordina y aprueba arquitectura, contrato, revisión e integración. Delegar tareas acotadas a modelos de menor coste cuando resulte adecuado; Luna para validación/documentación y Sol/Terra para cambios que lo necesiten. Máximo dos implementadores simultáneos; tercer agente solo para revisión independiente.
- No modificar el contrato compartido sin revisión del orquestador. Nunca omitir validación TLS ni incorporar secretos.
- Una rama `task/<id>` y un worktree por tarea concurrente. No editar el checkout de otro agente ni integrar en main desde un subagente.
- Cada tarea define objetivo, modelo, archivos, dependencias, aceptación y verificaciones. Entregar commits y `docs/reviews/<id>.md` con pruebas reales y limitaciones.
- Mantener main comprobable. Integrar secuencialmente después de revisar diff y evidencia.
- No afirmar pruebas en Windows o J4+ sin ejecutarlas. Documentar por separado compilación, pruebas automáticas, emulador, hardware y rendimiento.
- Fijar dependencias y toolchains; confirmar archivos de bloqueo. No versionar claves, certificados privados, keystores, tokens, archivos transferidos o artefactos de compilación.
- Comunicación y documentación principal en español. No ampliar alcance sin coordinación.

## Git

GitHub Flow: `main` estable, `prueba` para el estado integrado aún en validación, ramas cortas `task/<id>`, PR si hay acceso al remoto, etiquetas al alcanzar hitos verificables. No se necesita rama develop permanente. Usar revert para deshacer cambios publicados; no reescribir historial compartido.
