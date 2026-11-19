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

Ante un fallo abre **Ajustes → Registro de errores** para copiar el último diagnóstico. Para depuración USB: `adb logcat -d -s FileTransfer:E '*:S'`. El diagnóstico omite mensajes crudos, rutas, IP, descriptor y credenciales. Las pruebas de proveedores SAF se detallan en `docs/TRY-PRUEBA.md` desde la raíz.

Desde Android 0.1.2 puedes conectar con **Escanear QR del PC** después de mostrar el código en Windows 0.1.1. El permiso de cámara se pide al pulsar. La alternativa está en **Ajustes → Conexión manual**, con el código copiado desde Windows o con el descriptor JSON v1. El escáner funciona sin Google Play Services, modelos descargados ni otra app de escaneo. Validar físicamente cámara/rotación/denegación y lectura en J4+.

La interfaz usa la paleta petróleo y menta y un icono de lanzador propio. Tras emparejar, **Enviar archivos** abre el selector de Android para elegir uno o varios archivos y la bandeja muestra las ofertas del PC; la descarga necesita **Aceptar**. El indicador **Vinculado** describe el emparejamiento guardado, no una comprobación de disponibilidad. El nombre del PC procede de la respuesta autenticada; pares anteriores sin nombre muestran **PC emparejado**.

**Transferencia** muestra las fases y los bytes. El archivo se da por recibido tras guardarlo y verificarlo. **Esta sesión → Ver actividad** conserva hasta 32 resultados en memoria. Si el PC aún debe confirmar un archivo guardado, puedes usar **Reintentar confirmación**. Para cambiar la dirección o cerrar el vínculo, abre **Ajustes**.

Desde Android **0.1.4-dev** (`versionCode 5`), la selección múltiple se envía en el orden entregado por el selector, un archivo cada vez. **Transferencia** muestra el archivo actual (`i de n`) y los pendientes sin enviar. Cada archivo requiere aceptación independiente en el PC y se verifica con SHA-256; el siguiente solo comienza después de confirmar el anterior. **Cancelar lote** cancela la operación actual y detiene el resto; en una selección de un solo archivo se mantiene **Cancelar**. Rechazo, fallo o confirmación pendiente detienen también el lote. Los archivos completados se conservan.

**Reintentar confirmación** solo consulta/confirma el archivo actual: nunca vuelve a transmitirlo ni retoma la cola detenida. **Reintentar envío** conserva el destino autenticado y exige decidir sobre archivos sin confirmación antes de reenviarlos.

La **Cola de envío** admite hasta 64 archivos, incluso sin PC conectado. **Elegir archivos** permite **Enviar ahora / Enviar al conectar** o **Dejar en espera**. También aparece File Transfer en **Compartir** de Android para uno o varios archivos. Antes de mostrar una selección en la cola se copia entera a almacenamiento privado con un buffer de 64 KiB: necesita espacio libre adicional, no conserva una dependencia de permisos temporales y conoce el tamaño real tras cerrar cada copia. Un nombre incompatible con Windows, permiso perdido, cancelación o fallo de almacenamiento descarta esa selección incompleta y conserva la cola anterior. Las copias confirmadas en el PC o eliminadas de la cola se borran; los originales nunca se eliminan.

Compartir deja preparadas solo las nuevas entradas. Tras autenticar el QR se envían por la autorización habitual de Windows mientras la pantalla principal está visible. Las entradas **En espera** o detenidas por un intento previo requieren **Enviar cola / Reintentar envío**; compartir otra selección no las rearma. Si había un PC vinculado al compartir, el envío automático queda limitado a ese mismo PC; cambiar de destino exige confirmación explícita. La cola privada sobrevive al cierre de la app, pero ningún intento iniciado se reproduce automáticamente después de morir el proceso. Una confirmación dudosa queda marcada para revisar el destino antes de reenviar.

Compartir mientras otra operación está ocupada deja los URI esperando copia, con aviso visible; aún no son persistentes. Se trasladan los permisos temporales al servicio durante la preparación, pero no se prometen después de forzar detención o morir el proceso. Al reiniciar se limpian copias huérfanas y se avisa de que hay que volver a compartir los archivos faltantes. La rotación conserva la preparación en memoria; la selección pendiente de decisión en el selector debe repetirse si se pierde esa pantalla.

En la bandeja puedes revisar el manifiesto y aceptar el lote de Windows. Durante la transferencia, Android mantiene vigente la sesión con consultas autenticadas.

Las operaciones de envío y recepción (también lotes completos y reintento de confirmación) arrancan el servicio mientras la pantalla de la app está visible. La notificación muestra **Archivo X de Y** y bytes acumulados del lote cuando se conocen; permite **Cancelar** o volver a la app y deja el resultado final si Android permite notificaciones; el permiso de notificaciones se solicita en Android 13+ y denegarlo no impide el servicio, aunque Android puede mostrarlo solo en su administrador de tareas activas. La recepción sigue requiriendo **Aceptar** o **Aceptar lote**. Al rotar o recrear la pantalla se conserva la operación en memoria y no se borran sus parciales.

El servicio mantiene CPU y Wi-Fi durante la operación, libera ambos recursos al terminar/cancelar/fallar y no se reinicia solo. Tiene un límite de seis horas; Android 15+ puede terminarlo antes si ya se consumió el presupuesto `dataSync`. **Atrás** al salir de la sesión y **Olvidar dispositivo** mantienen el cierre explícito. Forzar detención, matar el proceso, reiniciar el móvil o restricciones adicionales de batería del fabricante pueden interrumpirlo: los archivos verificados se conservan; los pendientes se reintentan desde cero. La comprobación de pantalla bloqueada, permisos y restricciones reales sigue pendiente en dispositivos; véase `docs/reviews/android-bg.md`.
