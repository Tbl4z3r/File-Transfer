# Android

Requisitos: JDK 21 y Android SDK con API 36. Desde esta carpeta:

```sh
# Solo en el entorno Cloud preparado: source /workspace/toolchains/env.sh
./gradlew --no-daemon --max-workers=2 :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

El APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk`. Para instalarlo en un dispositivo conectado con depuración USB habilitada:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK universal incluye `armeabi-v7a`, `arm64-v8a`, `x86` y `x86_64`. Es un artefacto debug, no está firmado para distribución ni actualización personal. Antes de declarar compatibilidad con Galaxy J4+ hay que instalarlo y probarlo en ese dispositivo; emulador, hardware, red local y rendimiento requieren validaciones separadas.

En Android 0.1.3-dev, toca la fila **Carpeta de recepción** y después **Elegir carpeta** para dar permiso al directorio. La app lo recuerda; **Usar bandeja privada** vuelve al destino anterior. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas. Se comprueba el permiso antes de aceptar la descarga.

Ante un fallo usa **Copiar diagnóstico**. Para depuración USB: `adb logcat -d -s FileTransfer:E '*:S'`. El diagnóstico omite mensajes crudos, rutas, IP, descriptor y credenciales. Las pruebas de proveedores SAF se detallan en `docs/TRY-PRUEBA.md` desde la raíz.

Desde Android 0.1.2 puedes conectar con **Escanear QR del PC** después de mostrar el código en Windows 0.1.1. El permiso de cámara se pide al pulsar. La alternativa está en **Ajustes → Conexión manual con JSON**. El escáner funciona sin Google Play Services, modelos descargados ni otra app de escaneo. Validar físicamente cámara/rotación/denegación y lectura en J4+.

La interfaz Esencial sigue el tema claro/oscuro del sistema y usa iconos vectoriales locales. Tras emparejar, **Enviar archivo** abre el selector de Android y **Recibir archivo** consulta la oferta del PC; la descarga necesita **Aceptar**. El indicador **Vinculado** describe el emparejamiento guardado, no una comprobación de disponibilidad. El nombre del PC procede de la respuesta autenticada; pares anteriores sin nombre muestran **PC emparejado**.

**Transferencia** muestra fases y bytes reales; el 100 % del cuerpo no equivale a archivo guardado. **Esta sesión → Ver actividad** conserva hasta 32 resultados en memoria durante esta apertura. **Cancelar** interrumpe la operación y solicita cancelación al PC. Ante una confirmación pendiente puedes **Reintentar confirmación**, cambiar IP/puerto en **Ajustes → Actualizar dirección**, o **Cerrar confirmación pendiente** conservando el archivo recibido verificado y el vínculo. **Olvidar dispositivo** también está en Ajustes. No se garantiza transferencia en segundo plano ni recuperación después de cerrar la aplicación.
