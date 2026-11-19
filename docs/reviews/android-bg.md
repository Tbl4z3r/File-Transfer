# Android: transferencias activas en segundo plano

## Objetivo y alcance

Base `09269a1` (`origin/prueba`), rama `task/android-bg`. Mantener envío y recepción individuales y por lotes al cambiar de aplicación, bloquear la pantalla o recrear la Activity. Sin cambios de protocolo, dependencias ni aceptación. Implementación y revisión local; integración corresponde a Astra.

## Cambios

- `TransferForegroundService`: servicio no exportado `dataSync`, no sticky, iniciado desde la acción visible del usuario antes de ejecutar la transferencia. Notificación de progreso con volver/cancelar e identificador de operación; callbacks atrasados no cancelan el siguiente trabajo. Conserva el servicio durante todo el lote y la confirmación.
- Permisos API 28–36: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`. Se pide notificaciones en API 33+, sin exigir concesión para transferir. Android decide dónde mostrar el aviso si se deniega.
- CPU parcial y Wi-Fi high performance durante la operación, con liberación en fin/error/cancelación/destrucción/timeout. Límite local de seis horas y `onTimeout` de Android 15+. Sin arranque por boot ni reinicio del proceso; no se solicita excepción de batería.
- La sesión compartida en memoria sobrevive a destrucción de la pantalla durante el servicio. Al volver se recuperan progreso, resultado, lote y opciones de reintento. No se borran parciales en esa recreación. Se conserva el heartbeat autenticado para renovar el lease de 45 s del PC incluso durante streaming o publicación SAF local; se reemplaza al recrear pantalla y se detiene al terminar sin Activity. Back/Olvidar siguen desconectando explícitamente.
- Los fallos transitorios de sondeo durante una operación no cancelan el stream ni gastan el umbral idle; 401/sesión cerrada siguen siendo autoritativos. Una solicitud iniciada antes de otra operación no acumula fallos contra ésta.
- Retiene el último diagnóstico al iniciar otra operación (coordinado con tarea de ajustes).

## Verificación automatizada

Primera ejecución: `source /workspace/toolchains/env.sh` y `FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core ./gradlew --no-daemon --max-workers=2 :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL**. 45 pruebas JVM, cero fallos/errores/omitidas; incluyen siete pruebas nuevas de ownership/heartbeat y el interop TLS real de envío/recepción/lotes contra core Rust en Linux. APK debug generado y lint pasó.

`python3 protocol/validate_fixtures.py`: 41 vectores y 13 mensajes correctos. `git diff --check`: correcto.

Al entregar el commit de implementación se vuelve a ejecutar Gradle sobre los últimos ajustes de cancelación/notificación y la nueva demora real de 47 s con heartbeat independiente antes de confirmar una descarga. El resultado final de esa repetición se registrará en un segundo commit de evidencia; la primera ejecución no cubría esa demora ni los últimos ajustes. No confundir estas pruebas JVM/loopback con ejecución del servicio Android.

## Prueba manual pendiente (sin emulador ni móvil conectado)

Ejecutar por separado en API 28/J4+, API 33 y API 35–36, frente a Windows con recepción/emparejamiento vigentes:

1. Enviar un archivo grande y luego un lote de al menos tres archivos. Tras aceptar en PC, pulsar Home, usar otra app, bloquear dos minutos y volver desde la notificación. Deben progresar todos secuencialmente; SHA-256/resultado completado solo tras verificación. La notificación muestra fase y archivo del lote y desaparece al terminar.
2. Recibir un archivo y un lote tras revisar nombres/tamaños y aceptar. Repetir Home/bloqueo durante contenido y durante publicación en SAF lenta (>45 s). El heartbeat mantiene la sesión; los archivos íntegros quedan publicados una sola vez. Sin aceptar, no debe descargarse contenido.
3. Rotar varias veces; en una ejecución activar temporalmente la opción de desarrollador «No conservar actividades», salir y volver durante la operación y también después de completarse. Deben recuperarse estado/resultados sin borrar parciales. Restaurar la opción al acabar.
4. Cancelar desde la notificación durante envío, recepción y entre archivos; luego iniciar otra transferencia y comprobar que una notificación antigua no la cancela. No deben enviarse siguientes elementos ni publicarse parciales. Verificar desaparición del servicio/notificación y liberación de locks con `adb shell dumpsys power`, `adb shell dumpsys wifi` y `adb shell dumpsys activity services local.filetransfer`.
5. Conceder y denegar notificaciones en API 33+. La concesión muestra progreso/cancelación en el cajón; denegar mantiene la transferencia y el registro del servicio en tareas activas de Android. La UI conserva Cancelar. Comprobar el canal silenciado también.
6. Cortar Wi-Fi/revocar la sesión del PC; comprobar fallo/cancelación claros, limpieza de recursos y conservación de archivos ya verificados. Volver a conectar/reintentar desde cero con las opciones existentes. Repetir Back/Olvidar para verificar cierre deliberado, sin continuar el lote.
7. En emulador de pruebas API 35+, acortar temporalmente `data_sync_fgs_timeout_duration` con `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 10000`; iniciar transferencia larga, salir y confirmar mensaje de tiempo agotado, stop del servicio, liberación de locks y detención del lote. Restaurar con `adb shell device_config delete activity_manager data_sync_fgs_timeout_duration`. No ejecutar este ajuste en el móvil personal sin intención de probarlo.

## Límites

No se han ejecutado pruebas en emulador, Windows real, J4+ ni mediciones de rendimiento. La compilación y pruebas JVM no prueban scheduling de Android, suspensión de radio, permisos interactivos ni restricciones Samsung. Forzar detención/matar proceso/reiniciar no tienen recuperación: el servicio no se restaura y la transferencia parcial no se anuncia completa. El sistema puede consumir antes el presupuesto compartido `dataSync`; seis horas es el máximo de esta operación, no una garantía de duración disponible. No se garantiza recepción de nuevas ofertas con la app cerrada.
