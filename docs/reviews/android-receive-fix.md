# ANDROID-RX — publicación, destino y diagnóstico

Estado: aprobado por GPT-6.1 Sol para integrar en `prueba`; validación física pendiente. Base c5d33f3, utilidad JVM revisada de Luna 4a30fc8. Revisión independiente SAF: permisos de resultado/persistencia, rollback del permiso nuevo, directorio con creación, uso exclusivo del nuevo documento devuelto y limpieza acotada; no se identificaron bloqueos en flujo normal. Se conservan límites del proveedor en ADR002.

## Problema y cambio

La captura del Redmi Note 14 5G muestra 1351724/1351724 bytes recibidos y fallo al publicar con Files.createLink. Se retiró esa operación de la recepción y se prueba la publicación real además del transporte. No se deduce un errno/causa del SO de la captura. La conexión sí fue probada por el usuario; J4+ no fue probado.

Android 0.1.1 (versionCode 2): creación exclusiva/copia verificada en bandeja privada; selector SAF de carpeta y permisos persistentes; colisiones numeradas sin abrir archivos existentes; mensajes de destino/permisos/limpieza; Copiar diagnóstico y tag local FileTransfer. La copia usa 64KiB, vuelve a comprobar tamaño/hash y solo marca éxito después de cerrar. Cierre de Activity señala cancelación también durante publicación. Se conserva el archivo íntegro si el PC termina la operación después de publicarlo. No se cambian endpoints, TLS, credenciales, UI general ni código Windows.

## Evidencia local

Con JDK21/SDK36 fijados y FT_CORE_BINARY apuntando al núcleo real:

`./gradlew --no-daemon --max-workers=2 :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Resultado: BUILD SUCCESSFUL. 16 pruebas sin fallos/errores/omisiones: 3 validación, 5 TLS, 1 interoperabilidad Rust/Kotlin, 5 publicación, 2 diagnósticos. Interoperabilidad ahora publica el download a disco y conserva un original con mismo nombre. Pruebas de publicación cubren vacío, bytes, colisión/symlink, cambio de tamaño/hash, cancelación y buffers; las de diagnóstico comprueban que no se incluyan mensajes sensibles y que las causas sean acotadas. Metadata/lint y APK correctos. La señal de cancelación en onDestroy se revisó después de esa ejecución y se compilará en CI sobre el commit integrado.

## Límites

No se ejecutó en emulador, Redmi, J4+ ni proveedor SAF real. Repetir el mismo PDF, carpeta elegida, reinicio y permiso retirado en el móvil. SAF no garantiza publicación atómica/no-overwrite frente a escritores externos ni limpieza si el proveedor falla. La publicación añade una copia y requiere espacio temporal; con archivos grandes/proveedor lento puede superar los 120s de verificación. No se anuncia distribución estable; la firma debug de CI sigue sin garantizar actualizaciones entre runners.
