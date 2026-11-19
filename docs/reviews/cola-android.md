# Cola Android y hoja Compartir

Estado: implementación para revisión de GPT-6.2; rama `task/cola-android`, base `1d809b6`. Sin cambios de protocolo, Windows ni dependencias.

## Comportamiento

- Android registra `ACTION_SEND` y `ACTION_SEND_MULTIPLE`, tanto en apertura nueva como con Activity existente. Solo acepta URI `content`, hasta 64 por intent; extras malformados producen un aviso.
- La cola aparece sin PC, con nombre/tamaño/estado y eliminación individual. El selector SAF ofrece enviar o dejar en espera. Cada selección se copia por streaming antes de publicarse en la cola privada (`noBackupFilesDir/outgoing`). El envío abre esa copia, sin depender del proveedor original ni del tamaño declarado por este.
- Armado por entrada: una nueva selección compartida no rearma selecciones en espera ni remanentes. Si existía vínculo al recibir el intent, se conserva `RetryPeer`; otro PC requiere confirmación explícita. Sin vínculo previo, el QR autenticado siguiente elige destino. Solo se despacha automáticamente desde la pantalla principal visible, sin solicitudes entrantes/confirmaciones/operaciones activas.
- Despacho por `startUpload`, `UploadAuthorization`, cola secuencial y FGS existentes. Se desarman las entradas antes de cualquier request. No se restablece actividad de red al reiniciar proceso. El marcador de confirmación dudosa se persiste antes de `complete`; se exige revisar el destino antes de reenviar.
- Eliminación después de confirmación remota o acción explícita; originales intactos. El manifiesto se reemplaza atómicamente tras cerrar/sincronizar los snapshots. Fallo o cancelación al ingerir revierte la selección completa y mantiene lo anterior. Inicio descarta snapshots ausentes/de tamaño incorrecto y limpia únicamente huérfanos del directorio privado dedicado, con aviso.
- Los URI temporales se trasladan mediante `ClipData` y permiso de lectura al Intent del FGS durante copia. No se intenta persistir permisos que el emisor no ofreció. La rotación conserva sesión/copia; muerte de proceso/force-stop no conserva permisos ni copias aún sin commit. Un marcador local permite explicar que hay que recompartir faltantes. Compartir durante otra operación avisa que espera copia y pide mantener pantalla abierta: todavía no es una selección persistida.
- La notificación muestra archivo X/Y, bytes acumulados de lote y progreso total cuando se conocen. El cambio de archivo/fase fuerza actualización; bytes se limitan a una actualización por segundo. Conserva cancelación identificada por operación y deja resultado final sin botón cancelar cuando Android permite notificaciones. La ingestión no genera aviso terminal adicional.

## Verificación automática

Comandos con `source /workspace/toolchains/env.sh`, cache Gradle existente y `--offline --no-daemon --max-workers=1`:

- `:app:testDebugUnitTest`: 60 pruebas, 59 correctas, 1 omitida (`CoreInteropTest`, sin `FT_CORE_BINARY`), 0 fallos/errores.
- `:app:lintDebug`: ejecución previa correcta (14 advertencias, 0 errores); una corrida final con la fuente congelada está en curso junto con `:app:assembleDebug` al entregar el commit. La compilación Kotlin final y las pruebas JVM de esa misma corrida ya terminaron correctamente. No se acredita APK hasta ver el resultado de assemble.
- Avisos de entorno: métricas Android no disponibles bajo `/home/agent/.android` y uso heredado de retención no configurable, sin fallo de compilación.
- `git diff --check`: correcto.
- Nuevas pruebas JVM: snapshot accesible al perder permiso del origen y tras restaurar; armado por entrada; selección automática limitada al peer; peer persistido; incertidumbre persistida; cancelación/fallo revierte selección; huérfanos y snapshot ausente; escritura fallida de journal sin perder cola; buffer <=64 KiB/archivo vacío; nombres inválidos y máximo 64 sin abrir fuentes. Progreso de lote evita contar dos veces el último archivo confirmado.

## Comprobación manual pendiente

No se ejecutó en emulador, Windows, Redmi ni J4+; no hay medición real de rendimiento o RAM.

1. Desde Archivos y una app con grants temporales, compartir uno y varios archivos sin PC. Esperar fin de copia; cambiar de app, rotar y volver. La cola conserva nombres/tamaños y permite eliminar. Forzar detención al copiar debe dejar únicamente selecciones previas/confirmadas, limpiar `.part` al abrir y pedir recompartir faltantes.
2. Abrir QR del PC con recepción normal; volver a pantalla principal. Solo las nuevas entradas armadas solicitan autorización habitual. Rechazar/cancelar detiene lote; volver a QR no reproduce el intento. Añadir otra compartida no envía los remanentes o entradas en espera.
3. Compartir con PC A vinculado y cambiar a B antes de enviar: no hay autoenvío a B; el botón exige elegir destino. Sin PC al compartir, el siguiente vínculo autenticado sí permite envío automático.
4. Cortar red antes/durante/después de `complete`, matar proceso y abrir: los confirmados desaparecen, las entradas dudosas conservan aviso; nunca reenviar automáticamente. Decidir explícitamente incluir/retirar una dudosa.
5. Probar falta de espacio, permiso retirado durante copia, nombre inválido, selección de 65 y payload SEND malformado. No mostrar selección parcial ni cerrar app. Compartir durante lote muestra espera de copia; si se interrumpe, recompartir los faltantes.
6. Transferir lote con tamaños distintos y un vacío; notificación archivo X/Y, bytes totales y cancelación coherentes. Cancelar desde aviso y reabrir. Android 13+ con permiso denegado no debe impedir transferencia; con permiso, comprobar aviso final al terminar y bloqueando pantalla.

## Límites

El staging añade una copia local y ocupa espacio hasta confirmar/eliminar. El journal conserva selección, no transferencia ni credenciales; no hay reanudación parcial ni descubrimiento nuevo. La durabilidad contempla muerte de proceso, no garantiza sobrevivir un corte físico de energía durante un rename del filesystem. Un archivo privado alterado con el mismo tamaño queda fuera de este alcance (el proveedor original ya no puede alterarlo). Carpeta completa se implementará aparte; el punto de extensión es el manifiesto de `OutgoingFile` y la preparación antes de `UploadAuthorization`.
