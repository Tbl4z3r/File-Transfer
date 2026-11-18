# Lote autorizado: motor Windows

Entrega para revisión, sin integración ni push. Implementación en `apps/windows/core/src/upload_batch.rs`; cambios de conexión en el motor/servidor y comandos locales en Tauri y `ft-core`. No modifica Android, frontend, descriptor ni rutas individuales.

## Contrato implementado

- `POST /v1/upload-batches` recibe `{files:[{file_name,size_bytes}]}` y devuelve `201` `{batch_id,state}`. Manifiesto inmutable de 2–64 entradas; límites/validaciones JSON, nombre y tamaño de v1 (16 KiB, tamaños decimales string hasta 1 TiB por archivo).
- `GET /v1/upload-batches/{id}` y `POST /v1/upload-batches/{id}/cancel` con `{}` devuelven `200` `{batch_id,state}`. Estados: `awaiting_acceptance`, `accepted`, `completed`, `cancelled`, `failed`.
- `POST /v1/upload-batches/{id}/start` recibe exclusivamente `{index:<entero cero-based>}` y devuelve `200` con el `Transfer` v1 en `ready`. Solo admite el siguiente índice aún no iniciado después de verificar y completar el anterior. Replay o índice fuera de orden → `409 invalid_state`. Cada archivo sigue PUT content/complete/cancel de v1; no hay aprobación remota.
- Tauri `accept_upload_batch(batch_id,destination)` y `cancel_upload_batch(batch_id)` devuelven `Result<BatchStatus,String>`: éxito `{batch_id,state}`, error código estable string. Aceptar exige `awaiting_acceptance`, destino accesible y espacio agregado; start repite preflight individual, crea temporal exclusivo y actualiza journal existente. El harness local admite `{command:"accept_upload_batch",batch_id,destination}` y `{command:"cancel_upload_batch",batch_id}` dentro de su respuesta habitual `{ok:true,result:...}`.
- Snapshot añade `upload_batch:null|{batch_id,peer_id,state,files:[{file_name,size_bytes,transfer_id:null|string,state}]}`. Estados de fila: `pending`, `not_sent` y estados `TransferState` existentes. Copia independiente conserva las 64 filas completadas aunque se evicten transfers antiguos; se reemplaza al crear otro lote y se elimina al cerrar sesión.

El lote reserva la posición de trabajo global, incluso entre archivos. Todas las operaciones remotas revalidan propietario y par dentro del lock; un ID de otro dispositivo devuelve 404. Desactivar recepción bloquea solicitudes nuevas pero mantiene el lote ya autorizado. Cancelación del lote o archivo, rechazo local, revocación, cierre, fallo y mismatch durante verificación impiden el resto y conservan completados. Replay complete con distinto hash de archivo ya completado conserva semántica v1 (409 sin cambiarlo ni detener el resto autorizado). Espera inicial/interarchivo usa 120 s; archivo ready, verificación e inactividad usan los timeouts v1 existentes. Consultar estado no renueva plazos.

## Evidencia ejecutada (Linux, 2026-11-18)

Con `source /workspace/toolchains/env.sh`:

- `cargo test --locked --manifest-path apps/windows/core/Cargo.toml --target-dir /tmp/ft-windows-lotes-target`: **21 pruebas OK** (2 fixtures + 19 TLS; seis pruebas TLS nuevas del lote).
- Cobertura nueva: una aprobación local para dos archivos con hashes distintos y colisión; sin aprobación; bearer ausente; otro propietario; replay/índice inválido; manifiesto inválido/duplicado/null/desconocido/16 KiB; exclusión frente a solicitudes/ofertas individuales; toggle recepción; rechazo; cancelación lista y durante stream TLS; cancelación individual; conservación de completado; mismatch y limpieza; destino eliminado después de aprobación; 64 archivos y evicción de los primeros 32 registros; timeout inicial/interarchivo/inactividad; revocación.
- `cargo build --locked --manifest-path apps/windows/core/Cargo.toml --target-dir /tmp/ft-windows-lotes-target`: OK. Harness para interoperabilidad: `/tmp/ft-windows-lotes-target/debug/ft-core`.
- `cargo fmt` de core/Tauri y `git diff --check`: OK.
- `cargo check --locked --manifest-path apps/windows/src-tauri/Cargo.toml --target-dir /tmp/ft-windows-lotes-tauri-target`: bloqueado antes de compilar aplicación por biblioteca de sistema Linux ausente (`glib-2.0.pc`, requiere >=2.70). No se instalaron dependencias del sistema.

## Límites

No ejecutado Windows nativo, emulador, hardware Android ni rendimiento LAN. Tauri necesita validación CI/Windows. Sin persistencia de lote, reanudación ni modificación del flujo Windows → Android. La comprobación de espacio no reserva disco: se vuelve a comprobar por archivo y los fallos de escritura/publicación mantienen las garantías y limpieza v1.
