# Continuidad

## Dirección y ramas

Por instrucción posterior del usuario, **GPT-6.1 Sol** coordina arquitectura, revisión e integración. Las aprobaciones históricas de Astra se conservan como evidencia. Delegación actual: Luna para tareas acotadas de validación/documentación; el orquestador revisa cambios sensibles. Máximo dos implementadores simultáneos.

`prueba` reúne el estado actual solicitado por el usuario. `main` conserva la base H0 hasta aceptar una versión de aplicación. No declarar H1 completo sin las pruebas esenciales de hardware y rendimiento LAN descritas en `TESTING.md`. H2–H5 siguen pendientes.

## Entorno

Checkout principal H0: `/workspace/File-Transfer`. Checkout de `prueba`: `/workspace/worktrees/integration-h1` (la ruta conserva su nombre anterior). Windows y Android se implementaron en worktrees `h1-windows` y `h1-android`, con ramas `task/h1-windows` y `task/h1-android`.

La carpeta `C:\Users\aferi\Desktop\File-Transfer` no está montada en Cloud. Para traer el proyecto al PC, usar `GETTING-STARTED-WINDOWS.md` y seleccionar la rama `prueba`.

En esta máquina `source /workspace/toolchains/env.sh` configura Rust 1.100.0, JDK21 completo y SDK36/BuildTools35. Es configuración local; no debe copiarse como requisito a otras máquinas. Usar la matriz y los comandos de cada aplicación.

## GitHub

Repositorio autorizado `Tbl4z3r/File-Transfer`, público por decisión del usuario. Tras instalar la app oficial en Tbl4z3r, la lectura y el push funcionan; el bloqueo403 anterior quedó resuelto para contenido. La conexión no permite administrar ajustes del repositorio: cambiar rama predeterminada o protección devolvió403. Por la creación inicial del remoto, su rama predeterminada actualmente es `prueba`; `main` también existe. Mantener esta diferencia visible y no afirmar que se configuró protección.

No usar tokens incrustados, keystores de distribución o certificados privados en Git. No hay claves de firma release ni instalación en hardware acreditada.

## Siguiente paso concreto

1. Consultar resultados de CI del último commit de `prueba`; corregir cualquier fallo nativo Windows/Android y mantener los locks reproducibles.
2. Seguir el reporte de revisión e interoperabilidad. Una compilación y un test JVM no sustituyen ejecución Android real.
3. Instalar en Windows y J4+, comprobar ABI con adb, transferir en ambos sentidos y registrar integridad/tiempo/MiB/s. Probar confirmación, revocación, corte, colisión y permisos.
4. Solo después de cumplir aceptación, promover a `main` por PR. Empezar H2 de descubrimiento tras estabilizar H1, sin mezclar reanudación o distribución aún.
