# Continuidad

## Estado guardado

Base H0 integrada en `main`: alcance y reglas, contrato H1 aprobado por Astra, 41 vectores/13 mensajes verificados, matriz de versiones y plan de pruebas. Todavía no hay aplicación instalable ni transferencia Windows ↔ Android demostrada.

Implementación en curso en ramas/worktrees separados:
- `task/h1-windows`: `/workspace/worktrees/h1-windows`, Sol, responsable de `apps/windows/**`.
- `task/h1-android`: `/workspace/worktrees/h1-android`, Terra, responsable de `apps/android/**`.
- `task/h0-infra`: `/workspace/worktrees/h0-infra`, borrador CI. No integrar workflow antes de verificar paths/builds de ambas apps.
- Astra revisa contratos y diffs antes de integrar. Ver reportes en `docs/reviews/`.

## Entorno y acceso

Linux Cloud, checkout principal `/workspace/File-Transfer`. La carpeta `C:\Users\aferi\Desktop\File-Transfer` no está montada aquí; ver `GETTING-STARTED-WINDOWS.md`.

Herramientas de esta máquina: `source /workspace/toolchains/env.sh` prepara Rust 1.100.0, JDK21 completo y Android SDK36/BuildTools35. El script de entorno es local a esta máquina y no contiene credenciales del proyecto. Otras máquinas deben seguir la matriz de compatibilidad.

GitHub `Tbl4z3r/File-Transfer` se hizo público por decisión del usuario. Lectura accesible, repositorio remoto vacío al inspeccionarlo. Escritura rechazada403 tanto Git como API. Perfil conectado Tbl4z3r; instalación de la app enumerada únicamente en Bastian13U. Se identificó la app oficial `https://github.com/apps/chatgpt-codex-connector`, propietaria OpenAI, con escritura de contenido/workflows. No instalar la acción de terceros del Marketplace llamada chatgpt-codex-plugin-autopilot. No se ha publicado código.

El navegador integrado local no ofrece controles de interacción a este chat; no confundirlo con el navegador Linux Cloud. El usuario autorizó instalar la app oficial y las integraciones rutinarias, sin más preguntas de confirmación.

## Siguiente paso concreto

1. Continuar implementadores existentes, sin sobrescribir sus worktrees. Revisar `git status` y mensajes antes de retomar.
2. Compilar core y frontend Windows; compilar APK/test/lint con wrapper. Revisar TLS, autorización, hashes y consumo de fixtures.
3. Astra revisa diffs y evidencia, corrige e integra secuencialmente. Ejecutar interoperabilidad real disponible en Linux, distinguiéndola del hardware objetivo.
4. Cuando GitHub permita escritura, publicar ramas e historial, abrir PRs si corresponde y ejecutar CI Windows/Android; proteger main solo con checks comprobados.
5. H1 no se cierra sin transferencia real Windows ↔ Android e integridad/rendimiento registrados. H2–H5 siguen pendientes.
