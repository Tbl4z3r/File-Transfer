# ANDROID-RECEIVE-PUBLISH

## Cambios

- `VerifiedCopy.copy` copia en bloques de 64 KiB, cuenta con `Long`, calcula SHA-256 incremental, compara tamaño y digest al terminar, comprueba cancelación entre bloques y hace `flush`. Cierra el stream de entrada; el stream de salida sigue siendo propiedad del llamador.
- `PrivateInboxPublisher.publish` valida nombre/tamaño/hash, reserva el destino con `CREATE_NEW`, resuelve colisiones como `nombre (1).ext`, y nunca reemplaza destinos existentes ni sigue enlaces simbólicos existentes. Ante cualquier error elimina solo el archivo que reservó; mantiene intacta la fuente.
- La publicación directa por archivo exclusivo evita depender de hardlinks y renombres, que no están disponibles de forma uniforme en Android 9 y proveedores/filesystems. Como consecuencia, una copia privada puede verse en la carpeta mientras se escribe; el llamador debe mantener la transferencia en estado no completado hasta que el método retorne correctamente. Esta implementación favorece compatibilidad API 28 y corrección frente a una pasada temporal adicional.

## Verificación

Comando: `source /workspace/toolchains/env.sh && gradle :app:testDebugUnitTest --tests local.filetransfer.VerifiedPublicationTest`

Resultado: `BUILD SUCCESSFUL` (Gradle 9? wrapper output, 24 tareas; incluyó compilación Kotlin y pruebas JVM dirigidas). El intento con el comando `gradle` del sistema falló porque no está instalado en `PATH`; el wrapper incluido sí ejecutó la verificación solicitada.

Pruebas añadidas: bytes y archivo vacío, colisión sin sobrescritura, colisión con symlink cuando el sistema la soporta, limpieza por tamaño/hash/cancelación, conservación de fuente/archivos previos, límite de escritura por buffer y tamaño esperado `Long` superior a 4 GiB sin crear un archivo de ese tamaño.

No se ejecutaron build completo, emulador ni hardware Android. No se crearon archivos multi-GB: el test pasa una metadata `Long` de 5,000,000,000 bytes y comprueba rechazo frente a la fuente pequeña.
