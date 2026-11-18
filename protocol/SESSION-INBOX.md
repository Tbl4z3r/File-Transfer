# Vínculo de sesión y bandeja de Android

Las aplicaciones de escritorio y móvil mantienen las credenciales del vínculo en memoria. Arrancar Windows limpia los vínculos guardados por versiones anteriores. Cerrar la sesión o la aplicación invalida todos los vínculos de ese PC. El certificado del PC conserva su identidad. Cerrar Android o usar Olvidar intenta desconectar su vínculo. Los archivos ya verificados se conservan.

Las rutas de esta extensión usan el TLS y la autenticación de v1. La bandeja comprueba el vínculo y presenta la oferta del PC mientras Android está abierto.

| Operación | Solicitud | Respuesta |
|---|---|---|
| `GET /v1/inbox` | Consulta autenticada | `200` con `transfer` y `batch` |
| `POST /v1/disconnect` | `{}` | `200` con `disconnected: true` |
| `GET /v1/download-batches/{id}` | Consulta autenticada | `200` con `batch_id` y `state` |
| `POST /v1/download-batches/{id}/accept` | `{}` | `200` con `batch_id` y `state` |
| `POST /v1/download-batches/{id}/start` | `{"index":0}` | `200` con transferencia v1 en `ready` |
| `POST /v1/download-batches/{id}/cancel` | `{}` | `200` con `batch_id` y `state` |

En `/v1/inbox`, `transfer` contiene la oferta individual v1 o es `null`. `batch` contiene `{batch_id,files:[{file_name,size_bytes}]}` o es `null`. Los dos campos pueden estar vacíos. Si hay una oferta, solo uno contiene datos. Un lote admite de 2 a 64 archivos y rige el límite JSON de 16 KiB. El receptor puede leer todos los nombres y tamaños antes de aceptar. El estado local de Windows identifica a los clientes que consultaron `/v1/inbox`. Se conservan las rutas de archivos individuales para clientes anteriores.

Windows registra la actividad de los vínculos que consultan la bandeja. El resto de sus solicitudes autenticadas renueva el plazo de actividad. Si el móvil desaparece y no puede notificar el cierre, el vínculo caduca tras 45 s sin actividad. Android avisa al perder el acceso autenticado a su PC, descarta el vínculo y vuelve al QR. Interrumpir una conexión cancela el trabajo pendiente y conserva los archivos publicados tras su verificación. Las notificaciones se muestran en la aplicación abierta.

Un lote solo acepta el dispositivo al que Windows lo ofreció. `start` prepara el siguiente índice del manifiesto aceptado después de confirmar el anterior. El flujo de contenido, SHA-256 y publicación sigue v1. La autorización y la lista de archivos se atan a la sesión. Una revocación, cancelación, fallo o vencimiento detiene lo pendiente. Solo se admite una transferencia o un lote en progreso a la vez. La instantánea local de Windows presenta `download_batch` con la misma forma que `upload_batch` y los resultados por archivo.
