# File Transfer

Aplicación personal para transferir archivos entre Windows y Android por la red local, con TLS, emparejamiento verificable y sin cuentas ni servicios externos para transferir.

**Estado: versión de desarrollo en `prueba`.** Incluye conexión manual, un archivo por transferencia en ambos sentidos, autorización explícita, progreso, cancelación, revocación y verificación SHA-256. Android permite elegir una carpeta de recepción y copiar diagnósticos de errores. El núcleo y el cliente Kotlin se han probado juntos mediante TLS real en Linux/JVM. Esto todavía no acredita funcionamiento en Windows y en el J4+ del usuario ni constituye una distribución estable.

## Ramas

- `prueba`: implementación integrada y comprobaciones en curso.
- `main`: base H0 aprobada; promover una versión de aplicación cuando cumpla sus criterios de aceptación.
- `task/<id>`: tareas acotadas con revisión y worktree propio. No se necesita una rama `develop` permanente.

Consulta la [guía para probar los artefactos](docs/TRY-PRUEBA.md).

## Desarrollo

Consulta la [matriz de versiones](docs/architecture/compatibility.md), las instrucciones de [Windows](apps/windows/README.md) y [Android](apps/android/README.md), el [contrato](protocol/PROTOCOL.md) y las [pruebas de aceptación](docs/TESTING.md).

```sh
python3 protocol/validate_fixtures.py
cargo test --manifest-path apps/windows/core/Cargo.toml --locked
cargo build --manifest-path apps/windows/core/Cargo.toml --locked --bin ft-core
python3 scripts/integration/smoke.py apps/windows/core/target/debug/ft-core
```

Para la prueba Kotlin contra el núcleo, establece `FT_CORE_BINARY` a la ruta absoluta del binario y ejecuta las pruebas Gradle indicadas en Android. En Windows el ejecutable lleva extensión `.exe`.

CI comprueba el protocolo, núcleo Linux, interfaz y shell Windows, APK/lint/pruebas Android e interoperabilidad Kotlin/Rust. Los artefactos de CI son de desarrollo; los instaladores y la clave estable de firma Android se trabajarán en H5. Ver [evidencia](docs/reviews/h1-integration.md).

## Límites actuales

Un archivo por operación; reintentar desde cero tras un corte. Mantener Android abierto durante la transferencia. Pendientes mDNS/QR, lotes, recuperación, distribución final y pruebas físicas de Windows 10 22H2/Windows 11 y Android 9/J4+. No hay telemetría ni Google Play Services.

[Continuidad](docs/CONTINUITY.md) · [Tablero](docs/tasks/BOARD.md) · [Alcance original](docs/project-brief.es.md)
