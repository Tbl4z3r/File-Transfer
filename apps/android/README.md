# Android

Requisitos: JDK 21 y Android SDK con API 36. Desde esta carpeta:

```sh
source /workspace/toolchains/env.sh
./gradlew --no-daemon --max-workers=2 :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

El APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk`. Para instalarlo en un dispositivo conectado con depuración USB habilitada:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK universal incluye `armeabi-v7a`, `arm64-v8a`, `x86` y `x86_64`. Es un artefacto debug, no está firmado para distribución ni actualización personal. Antes de declarar compatibilidad con Galaxy J4+ hay que instalarlo y probarlo en ese dispositivo; emulador, hardware, red local y rendimiento requieren validaciones separadas.

En Android 0.1.1 puedes pulsar **Elegir carpeta de recepción** y dar permiso al directorio. La app lo recuerda; **Usar bandeja privada** vuelve al destino anterior. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas. Se comprueba el permiso antes de aceptar la descarga.

Ante un fallo usa **Copiar diagnóstico**. Para depuración USB: `adb logcat -d -s FileTransfer:E '*:S'`. El diagnóstico omite mensajes crudos, rutas, IP, descriptor y credenciales. Las pruebas de proveedores SAF se detallan en `docs/TRY-PRUEBA.md` desde la raíz.
