# Lotes de Android a Windows

La extensión de v1 permite revisar los nombres y tamaños de un lote antes de aceptarlo en Windows. Una aceptación local autoriza únicamente ese lote, ese dispositivo, ese destino y los archivos del manifiesto. Cada archivo se transfiere y se verifica por separado, mediante el flujo de contenido, integridad y publicación de v1.

Las rutas usan el TLS, la autenticación y los límites JSON de v1. Los clientes y servidores anteriores siguen usando las operaciones individuales.

| Operación | Solicitud | Respuesta |
|---|---|---|
| `POST /v1/upload-batches` | `{"files":[{"file_name":"uno.pdf","size_bytes":"10"},{"file_name":"dos.pdf","size_bytes":"20"}]}` | `201` con `batch_id` y `state` |
| `GET /v1/upload-batches/{id}` | Consulta autenticada | `200` con `batch_id` y `state` |
| `POST /v1/upload-batches/{id}/start` | `{"index":0}` | `200` con la transferencia v1 en estado `ready` |
| `POST /v1/upload-batches/{id}/cancel` | `{}` | `200` con `batch_id` y `state` |

El manifiesto admite entre 2 y 64 archivos y un cuerpo de hasta 16 KiB. Nombre, tamaño y reglas de JSON corresponden a una subida v1. Los índices empiezan en cero. Los nombres y tamaños se fijan al crear el lote. La operación `start` toma solo el índice y prepara el siguiente archivo del manifiesto aceptado. Se aplican las comprobaciones de espacio, destino y colisiones de cada subida. La confirmación de integridad de un archivo permite iniciar el siguiente. Una respuesta perdida al crear o iniciar no causa un reenvío automático.

Los estados del lote son `awaiting_acceptance`, `accepted`, `completed`, `cancelled` y `failed`. Un lote en espera o aceptado reserva la única posición de trabajo. Se consulta el estado hasta que Windows acepte o el lote termine. La autorización se extingue al cancelar, fallar, revocar, cerrar la sesión o vencer el plazo. Se conservan los archivos ya verificados. La lista local muestra los archivos que quedaron sin enviar. Desactivar la recepción impide que lleguen solicitudes nuevas. La aceptación de un lote existente sigue vigente hasta terminar o cancelar ese lote.

Windows muestra el manifiesto y sus estados mediante el snapshot local. Los comandos de Tauri `accept_upload_batch(batch_id,destination)` y `cancel_upload_batch(batch_id)` permiten aceptar o cancelar. Las rutas remotas comprueban el propietario autenticado y no aceptan archivos por el PC. La carpeta, los identificadores internos de sesión y la lista local de resultados se quedan en el PC.

Android anuncia el manifiesto para selecciones múltiples. Un `404 not_found` al intentar crear el manifiesto permite usar el flujo anterior con una aceptación por archivo. Un fallo de red o de validación detiene el lote. Windows → Android conserva el flujo individual en esta iteración.
