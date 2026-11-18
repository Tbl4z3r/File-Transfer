# Bandeja en vivo Android

Android 0.1.6-dev (versionCode 7), API mínima 28; sin dependencias nuevas ni cambios en la validación TLS.

## Comportamiento

- El vínculo, la credencial, la cola y la actividad se mantienen exclusivamente en memoria. Un objeto nonconfiguration conserva la sesión durante una recreación por configuración. Al arrancar se eliminan las preferencias y la clave de cifrado de vínculos de versiones anteriores. Cada intento de QR usa un UUIDv4 nuevo, también al reiniciar el proceso, para no quedar bloqueado por el vínculo anterior. `POST /v1/pair` anuncia `X-FT-Inbox: v1` para activar la caducidad del núcleo incluso si el proceso muere antes del primer sondeo. Una respuesta QR tardía tras cerrar también solicita disconnect.
- La bandeja consulta `/v1/inbox` mediante un cliente TLS fijado independiente del cliente de archivos. Pausa de 2 s entre consultas; conexión/lectura/escritura de 3 s y límite total de 4 s por consulta. Solo el 404 autenticado `not_found` activa `/v1/offers` por compatibilidad. Nunca acepta ni descarga por sondeo.
- Una oferta individual o de lote aparece en la tarjeta de transferencia. «Detalle» abre la lista desplazable completa de nombres y tamaños, el total y el destino. Si cambia el ID o el manifiesto, se cierra el detalle; la aceptación captura y verifica el lote mostrado. «Aceptar lote» está dentro del detalle; valida de nuevo acceso al destino antes de enviar la única aceptación del manifiesto.
- Cada `/start` se contrasta con nombre/tamaño aprobados, dirección download, estado ready y cero bytes. El flujo secuencial reutiliza temporal privado → bytes/hash → estado autenticado → publicación SAF/bandeja sin sobrescribir → complete. Un fallo, cancelación o confirmación pendiente detiene el resto y solicita cancelar el lote. Lo ya publicado y verificado permanece guardado; no se vuelve a descargar para confirmar.
- Atrás desde la pantalla principal, «Olvidar» y destrucción real envían `/v1/disconnect` de mejor esfuerzo y limpian el vínculo. Rotación, diálogos y apertura normal de selectores Android no cierran la sesión. Un 401 o cuatro fallos consecutivos de sondeo eliminan el vínculo y muestran instrucciones de QR nuevo; se conservan la actividad y el acceso al último archivo guardado.
- Generaciones y disposición del sondeo impiden que respuestas anteriores restauren una sesión olvidada o alteren una sesión nueva. La oferta individual y el detalle capturan la solicitud mostrada y comprueban que sigue siendo la misma antes de aceptar. Al desconectar se detiene también la presentación de la cola y se omiten consultas de limpieza de la generación anterior. Las actualizaciones de operaciones de la generación anterior también se descartan.

## Validación

Comando reproducible (ejecutado en Linux):

```sh
source /workspace/toolchains/env.sh
FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core \
  ./apps/android/gradlew -p apps/android --offline --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Resultado: 35 pruebas JVM correctas, ninguna omitida, APK debug generado y lint sin errores (8 avisos de preferencias, orientación, TrustManager, icono y KTX). `git diff --check` correcto. El ensayo cruzado acredita `supports_inbox` antes del primer GET de bandeja, usando la cabecera opcional del emparejamiento.

Pruebas nuevas: inbox vacío/individual/lote sin aceptación implícita; autenticación y fallback exclusivo; tamaños/manifiestos inválidos; rechazo de metadatos cambiados antes de content; cuerpos y rutas de acciones; cliente de sondeo operativo mientras otra conexión entrega bytes. `CoreInteropTest` amplía la prueba TLS con el núcleo Rust real: lote binario+vacío, inicio denegado antes de aceptación, hashes y publicación, completed, desconexión/401 y QR nuevo.

## Alcance de la evidencia

Compilación Android y pruebas Linux/JVM. No se ejecutaron emulador, Windows nativo, J4+ ni Redmi; tampoco se midió rendimiento LAN. Nonconfiguration está disponible en el rango soportado aunque AndroidX lo marca obsoleto; se usa para conservar memoria sin incorporar dependencias ni persistir secretos. No hay servicio, push del sistema ni promesa de ejecución si Android suspende/mata el proceso; el PC caduca el heartbeat ausente.

Prueba manual pendiente: abrir el APK actualizado sobre una instalación vinculada y comprobar que exige QR; rotar estando vinculado y durante transferencia; mantener abiertos selector SAF/archivos y detalle, volver sin desconexión; recibir lote con nombres largos y colisiones; rechazar/cancelar a mitad del lote; retirar permiso SAF; cortar Wi-Fi o cerrar sesión PC y comprobar mensaje de QR nuevo y archivos íntegros conservados; Atrás/Olvidar y volver a emparejar.
