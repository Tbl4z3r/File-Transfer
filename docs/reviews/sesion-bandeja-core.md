# Sesión efímera y bandeja autenticada: motor Windows

Rama `task/sesion-bandeja-core`, base `9c1d560`. Entrega para revisión del orquestador; no integrada ni publicada.

## Comportamiento

- Tauri abre `Engine::open_ephemeral`: elimina peers anteriores al abrir y mantiene los vínculos nuevos solo en memoria. Conserva identidad/certificado y el journal de parciales; la limpieza inicial de temporales mantiene su política existente y no elimina archivos verificados. La escritura del journal excluye credenciales efímeras. Emparejar/desconectar no requiere escribir el registro en este modo. Cerrar/detener la sesión cancela actividad y limpia vínculos/manifiestos.
- `GET /v1/inbox` devuelve `{transfer:null|Transfer,batch:null|{batch_id,files:[{file_name,size_bytes}]}}`, limitado al peer autenticado. Consultarlo marca `Peer.supports_inbox=true` en snapshot. Solo estos peers, cuando el motor es efímero, vencen tras 45 s sin actividad autenticada; requests y progreso autenticado del stream renuevan el plazo. El ticker existente y snapshot aplican vencimiento. Peers antiguos sin inbox conservan compatibilidad hasta cerrar/revocar.
- `POST /v1/disconnect {}` responde `200 {disconnected:true}`, revoca únicamente al solicitante y cancela sus pendientes. HTTP y stream conservan el hash exacto de la credencial autenticada y lo revalidan bajo lock; revocar y volver a emparejar el mismo ID no autoriza requests anteriores.
- `Engine::offer_download_batch(peer_id, sources: Vec<PathBuf>, session_id)` y el IPC Tauri equivalente devuelven `BatchStatus`. `cancel_download_batch(batch_id)` está disponible en ambos. Snapshot añade `download_batch` con el mismo DTO local que `upload_batch`.
- El manifiesto guarda nombre/tamaño/mtime/ruta al crear, admite 2–64 archivos y comprueba el tamaño JSON completo de inbox (máximo 16 KiB) antes de reservar. La aceptación remota aprueba todo el manifiesto; `/start {index}` entrega un Transfer `ready` solamente para el siguiente índice, después de verificar el anterior. No se permite saltar, repetir ni transferir archivos sin aceptación.
- Endpoints autenticados: `GET /v1/download-batches/{id}`, `POST /v1/download-batches/{id}/accept {}`, `/start {index}`, `/cancel {}`. Estados, errores y código 200 reutilizan v1/BatchStatus. Se conserva `/v1/offers` y el flujo individual anterior.
- Ambos tipos de lote comparten reserva global, sincronización y vencimiento; también bloquean ofertas/solicitudes individuales. Cancelación, revocación, vencimiento, cambio de fuente o fallo detienen el resto y conservan resultados completados incluso después de desalojar records antiguos (máximo 32 records, hasta 64 resultados por lote). TLS/pin/autenticación, lectura acotada y verificación SHA-256 permanecen obligatorios.

## Harness local

`ft-core serve --data <directorio> --bind <IPv4> --port <puerto> [--ephemeral-peers]` conserva el modo legado si no se pide el flag. Comandos JSON locales nuevos:

```json
{"command":"offer_download_batch","peer_id":"…","sources":["ruta1","ruta2"],"session_id":"…"}
{"command":"cancel_download_batch","batch_id":"…"}
```

El session_id se obtiene del evento `started` o de snapshot. Binario de interoperabilidad: `/tmp/ft-windows-lotes-target/debug/ft-core`.

## Verificación

Entorno Linux, Rust 1.100.0 mediante `source /workspace/toolchains/env.sh`:

- `cargo fmt --manifest-path apps/windows/core/Cargo.toml` y el equivalente src-tauri: OK.
- `cargo build --locked --manifest-path apps/windows/core/Cargo.toml --bin ft-core --target-dir /tmp/ft-windows-lotes-target`: OK.
- `cargo test --locked --manifest-path apps/windows/core/Cargo.toml --target-dir /tmp/ft-windows-lotes-target`: 4 unitarias, 2 vectores y 25 pruebas TLS reales, OK. Incluye 46 s de espera real para TTL45, rechazo de credenciales antiguas, aislamiento por peer, desconexión durante stream saliente de 64 MiB, secuencia/consentimiento de 64 archivos, preservación de completados, cambio de fuente y reserva entre sentidos. El manifiesto máximo de nombres válidos de 180 bytes y tamaños MAX_SIZE cabe bajo 16 KiB; el límite se valida de todos modos al crear.
- `git diff --check`: OK.
- `cargo check --locked --manifest-path apps/windows/src-tauri/Cargo.toml --target-dir /tmp/ft-windows-lotes-tauri-target`: bloqueado antes de compilar la aplicación por falta de `glib-2.0 >=2.70` (`glib-sys`/pkg-config). No se instalaron bibliotecas del sistema ni se modificaron dependencias/locks.

## Límites

No ejecutado Windows nativo, WebView2, selector nativo, emulador ni hardware Android; sin mediciones LAN. La interoperabilidad Kotlin/Android se valida por la tarea independiente. El cierre de ventana Tauri llama a stop_session existente; requiere comprobación Windows/CI. Credenciales y manifiestos son temporales, sin recuperación/reanudación ni persistencia de lotes. La comprobación de fuente conserva el contrato H1 de tamaño/mtime; no crea una copia inmutable del archivo.
