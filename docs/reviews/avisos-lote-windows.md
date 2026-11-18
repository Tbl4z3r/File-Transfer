# Avisos de sesión y lote saliente en Windows

## Alcance

Windows 0.1.5 conserva la ventana compacta Enfoque y reutiliza su panel de archivos. Para selecciones de varios archivos destinadas a un móvil con `supports_inbox`, ofrece un único manifiesto mediante `offer_download_batch`; la aceptación corresponde al móvil. Un archivo individual y los móviles anteriores conservan el flujo v1. No se modificaron HTML, CSS ni código Rust.

La instantánea `download_batch` gobierna los nombres, tamaños y estados por archivo. El lote mantiene ocupado el envío entre archivos aunque no haya transferencia individual; Cancelar lote invoca `cancel_download_batch`. Los resultados verificados y pendientes permanecen visibles tras cancelar o desconectar un móvil dentro de la misma sesión, y se descartan al cerrar o reemplazar la sesión. Los controles individuales no cancelan archivos que pertenecen al manifiesto.

Las bajas detectadas entre instantáneas consecutivas de la misma sesión activa muestran los nombres y la necesidad de un QR nuevo. Las altas iniciales, el reemplazo de sesión y su cierre voluntario no generan falsos avisos de desconexión. El cierre voluntario conserva su aviso propio y limpia QR, destino y lotes; una respuesta de sondeo anterior al cierre no puede restaurar el lote.

Se actualizaron npm, su lock, Cargo de la aplicación, su lock y la configuración Tauri a 0.1.5. Sin cambios del contrato aprobado en `protocol/SESSION-INBOX.md`.

## Validación ejecutada

- `npm --cache /workspace/toolchains/npm-cache ci`: correcto, dependencias fijadas.
- `npm --prefix apps/windows --cache /workspace/toolchains/npm-cache run test:ui`: 51 pruebas correctas (40 anteriores y 11 regresiones nuevas).
- `npm --prefix apps/windows --cache /workspace/toolchains/npm-cache run build`: correcto, TypeScript y copia de recursos locales.
- `git diff --check`: correcto.

Las regresiones cubren la oferta única con los argumentos aprobados, ausencia de aceptación local, compatibilidad individual, reserva durante el selector y entre archivos, progreso autoritativo, filas y scroll estables, cancelación, conservación de verificados, bajas de uno o varios móviles, convivencia con otro móvil, cierre y reemplazo de sesión y respuesta de sondeo obsoleta. Se conservaron las regresiones de lotes entrantes y cola v1.

## Límites y comprobación pendiente

Estas pruebas usan el adaptador DOM/IPC en Linux; no constituyen ejecución de Tauri en Windows ni transferencia real a Android. El coordinador comunicó una comprobación adicional en Chromium a 760 px y revisión independiente sin bloqueos; su evidencia de integración se registra por separado.

En Windows y móvil: vincular mediante QR, seleccionar varios archivos, revisar el manifiesto completo en Android, aceptar una sola vez y comprobar secuencia, integridad y cancelación tras el primero. Cerrar el móvil y esperar la baja del vínculo: Windows debe identificarlo, conservar lo verificado y permitir usar los demás móviles. Cerrar sesión en Windows y abrir otra: no debe reaparecer ningún lote ni QR anterior.
