# File Transfer

Aplicación personal para transferir archivos entre Windows y Android por la red local, con TLS, emparejamiento verificable y sin cuentas ni servicios externos para transferir.

**Versión de desarrollo en `prueba`.** Esencial en Android concentra las acciones, la carpeta y el estado en una pantalla. Enfoque en Windows muestra la transferencia y los dispositivos junto a una barra de accesos. Ambas interfaces comparten una paleta azul con tema claro y oscuro. La aplicación transfiere un archivo por operación en ambos sentidos con autorización explícita y verificación SHA-256. El usuario comprobó el QR y las transferencias en Redmi Note 14 5G y Galaxy J4+ con las versiones previas. [Registro de prueba física](docs/reviews/manual-redmi-j4-2026-11-17.md).

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

Mantén Android abierto durante la transferencia y reintenta desde cero tras un corte. Pendientes las pruebas físicas de esta interfaz, el descubrimiento mDNS, los lotes, la reanudación y la distribución estable.

[Continuidad](docs/CONTINUITY.md) · [Tablero](docs/tasks/BOARD.md) · [Alcance original](docs/project-brief.es.md)
