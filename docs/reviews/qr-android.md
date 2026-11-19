# QR Android — revisión

La pantalla sin emparejar permite escanear el JSON v1 ya definido o introducirlo manualmente. El permiso de cámara se solicita al pulsar el botón; la cámara se declara opcional y la actividad de captura no se exporta. Se limita el escaneo a QR, sin imagen de captura ni apertura de enlaces. El lector devuelve un resultado tras cerrar la cámara; la validación estricta ocurre antes de crear el cliente y mantiene el canal TLS, el pin y el almacenamiento de credenciales existentes. Los errores o cancelaciones permiten volver a intentar o usar JSON manual. La versión es 0.1.2 (código 3).

Dependencias fijadas: JourneyApps zxing-android-embedded 4.3.0 (Apache 2.0, AAR sin JNI; POM declara core 3.4.1) y ZXing core 3.5.4 explícito (bytecode Java 8, major 52). El bloqueo se regeneró por estas dependencias. Al bloquear el informe de todas las configuraciones apareció una entrada obsoleta de `kotlin-stdlib-common` en `debugRuntimeClasspath`; la regeneración con las tareas de compilación y prueba conservó esa entrada huérfana. Se eliminó únicamente esa línea, coincidente con el diagnóstico anterior de `dependencyInsight` (solo una restricción de bloqueo), antes de repetir sin `--write-locks`. El manifiesto fusionado conserva cámara opcional; el APK sigue sin Google Play Services ni permisos de almacenamiento amplios.

## Verificación

- JDK 21, Android SDK 36, Gradle 8.13: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, primero con `--write-locks` y después sin él; `:app:checkDebugAarMetadata` también se repitió sin escritura, `--no-daemon --max-workers=2`. 18 pruebas JVM con `FT_CORE_BINARY` apuntando al core suministrado, incluida interoperabilidad.
- Se comprueban los códigos de error de emparejamiento con un servidor TLS simulado y el descriptor de fixture y el rechazo de URLs, prefijos y JSON mal formado antes de invocar el conector. Las pruebas de TLS y publicación previas se mantienen.
- El orquestador verificó aparte que el QR real de Windows se decodifica con ZXing 3.5.4 y conserva exactamente el JSON y la zona tranquila.
- Pendiente de ejecución en emulador y Samsung J4+ Android 9: conceder/denegar permiso (también denegación permanente), cámara ausente/ocupada, cancelar, QR inválido y caducado, reintentar, rotar durante permiso/captura y emparejar con nombre elegido; comprobar copia manual y transferencias/SAF. No se ha medido rendimiento ni probado hardware real.
- APK de depuración solamente; sin afirmaciones sobre firma o actualización de una instalación estable.
