# H1 — evidencia de integración parcial

Estado: en curso; no es aceptación del hito en hardware.

## Núcleo TLS real en Linux

Ejecutado `python3 scripts/integration/smoke.py <ft-core>` el 2026-11-17 sobre el núcleo del worktree H1-WINDOWS, antes de su commit final. Binario probado SHA-256 `53832660cb5bbc6a5e65288e5181bdd298407b230abc8d7aba4e7093863a82f0`.

Resultado correcto: conexión TLS con trust anchor exacto, hostname estándar y pin; rechazo de pin incorrecto antes de HTTP; petición sin autenticación rechazada; recepción desactivada rechazada; dos subidas de 16 MiB con confirmación y hash; colisión publicada con nombre distinto sin sobrescribir; complete idempotente; descarga rechazada antes de aceptar y completada después; revocación invalida credencial.

Los tres streams tuvieron SHA-256 `341aacac661ccb210720bedaa9ead5d668fe5ea41a73532fc147c71e34040df1`. Lectura/escritura del test es progresiva, con buffers acotados.

| Flujo | Bytes | Segundos con confirmación | MiB/s |
|---|---:|---:|---:|
| Upload 1 | 16777216 | 1.0093 | 15.85 |
| Upload 2, mismo nombre | 16777216 | 1.0078 | 15.88 |
| Download | 16777216 | 0.4399 | 36.37 |

Estas cifras corresponden a **loopback Linux con compilación debug**. No miden Wi-Fi, Android, Windows ni rendimiento final; no se midió memoria máxima. El cliente de esta prueba es Python stdlib y valida TLS, no un sustituto de la prueba Kotlin/Rust.

## Pendientes

- Interoperabilidad Kotlin JVM contra el núcleo real.
- Resultados finales de builds/tests/lint de ambos agentes y revisión Astra.
- Windows build y ejecución real; J4+ instalación/ABI/ejecución.
- Transferencia Windows ↔ Android y medición LAN inicial. Archivos >4 GiB y muchos pequeños en hardware.
- H2–H5: descubrimiento, uso cotidiano, recuperación, distribución firmada.
