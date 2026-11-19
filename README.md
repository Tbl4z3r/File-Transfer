# File Transfer

Aplicación personal para transferir archivos entre Windows y Android por la red local, con TLS, emparejamiento verificable y sin cuentas ni servicios externos para transferir.

**Versión de desarrollo en `prueba`.** El emparejamiento por QR dura una sesión. Windows elimina los vínculos al cerrarla y Android muestra las ofertas del PC mientras está abierto. Ambos clientes permiten revisar el nombre y tamaño de los archivos y aceptar un lote de una vez. La cola transfiere y verifica cada archivo en orden con SHA-256. [Registro de prueba física de las versiones anteriores](docs/reviews/manual-redmi-j4-2026-11-17.md).

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

Después de iniciar el envío o aceptar la recepción, puedes cambiar de aplicación en Android. La notificación de transferencia permite volver a File Transfer o cancelar. Un corte de red detiene el lote. Los archivos verificados se conservan y puedes elegir los que quedaron pendientes. Quedan por comprobar el puerto automático y los lotes en dispositivos físicos. El descubrimiento mDNS, la reanudación y la distribución estable siguen pendientes.

[Continuidad](docs/CONTINUITY.md) · [Tablero](docs/tasks/BOARD.md) · [Alcance original](docs/project-brief.es.md)
