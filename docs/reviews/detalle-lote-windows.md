# Detalle de lotes en Windows

Rama `task/detalle-lote-windows`, base `bce5912`. Requiere los comandos y snapshot del motor de `lote-autorizado-core` para la recepción por lote.

- Windows muestra nombres, tamaños y estados individuales antes de aceptar un lote entrante. «Aceptar lote» reutiliza la carpeta elegida en la sesión o abre el selector una vez; «Rechazar lote» y «Cancelar lote» usan el identificador del lote. La aceptación individual v1 sigue disponible para solicitudes individuales.
- La lista permanece al completar, cancelar o fallar, distingue archivos verificados de los que quedaron sin enviar y se limpia al cambiar de sesión. El envío sigue siendo secuencial; un lote entrante reserva el envío incluso antes del primer archivo. La selección y aceptación comprueban sesión/lote al regresar del selector. Si entra un lote entre archivos salientes, la cola espera y recupera su panel cuando reanuda.
- La lista de 180 px conserva sus nodos y desplazamiento al consultar estados. Los controles preceden a la lista; nombres remotos y locales se insertan con `textContent`. No se añaden exportaciones de rutas ni cambios de autorización, TLS, QR o interfaz Android.
- `choose_sources` devuelve el DTO local `{source,file_name,size_bytes}` a partir de metadatos nativos. La ruta se conserva para el IPC de oferta; el panel muestra solo nombre/tamaño. La respuesta real de cada oferta actualiza los metadatos visibles. Se añade `serde =1.0.229`, igual a la versión ya fijada por el motor y el lock. Versiones npm, shell Tauri y configuración sincronizadas a `0.1.4`.

## Validación

- `npm ci --cache /tmp/detalle-lote-npm-cache`: correcto.
- `npm run test:ui`: compilación TypeScript y **40/40 pruebas** correctas. Incluye selección múltiple nativa simulada, una aceptación por lote, selector cancelado, rechazo sin selector, estados/resultados persistentes, filas y scroll estables, carreras al reemplazar el lote o llegar una reserva durante selección, reanudación saliente tras lote entrante, cancelación tardía, revocación, sesión y regresiones QR.
- Chromium headless con el `dist` real y solo IPC simulado: 32 filas; viewports 760×640, 740×610 y 1100×760; sin desbordamiento horizontal; pie fijo; lista interna de 180 px con scroll conservado entre consultas; botones antes de lista; una aceptación y una elección de carpeta; resultados verificados/sin enviar retenidos; sin errores JavaScript. Script local `/tmp/detalle-lote-headless.py` y captura `/tmp/detalle-lote-windows-1100.png`.
- `cargo fmt --manifest-path apps/windows/src-tauri/Cargo.toml`: correcto.
- `cargo check --locked --manifest-path apps/windows/src-tauri/Cargo.toml`: dependencias/lock resueltos; compilación del shell bloqueada en Linux por falta de `glib-2.0 >=2.70` en `glib-sys`/pkg-config. No acredita compilación Windows ni ejecución del selector nativo.
- `git diff --check`: correcto.

No se ejecutaron transferencias en Windows real ni en el J4+. La verificación de interfaz usa comandos simulados; queda pendiente compilación Windows y prueba física integrada con el motor y Android.
