# Reintento y presentación en Android

Android 0.1.8-dev, código 9. La pantalla compacta usa el fondo petróleo, paneles oscuros, acentos menta y título claro en ambos modos del sistema. Los tamaños y la velocidad media del archivo activo usan unidades decimales. El límite de protocolo y las comprobaciones de integridad conservan sus valores en bytes.

- **Enviar.** El teléfono conserva las URI pendientes durante la sesión de la app. Los archivos confirmados quedan fuera del siguiente intento. Si pudo publicarse un archivo sin confirmación, este requiere una decisión de incluirlo u omitirlo antes de iniciar otra transferencia. Se obtiene una nueva autorización del PC para el envío, y se pide un QR vigente del mismo PC si se perdió la sesión. La identidad exige el mismo identificador y pin TLS, aunque cambien dirección o puerto. El reintento comienza desde cero para cada archivo seleccionado.
- **Recibir.** El botón actualiza la bandeja una vez con la sesión vinculada o después de escanear un nuevo QR del mismo PC. Cuando aparece una oferta, se conserva la aceptación explícita por archivo o lote. Si la oferta terminó, el usuario debe iniciar otra en el PC. La confirmación pendiente conserva su acción independiente para archivos ya guardados.
- **Ciclo de vida.** Las URI y el estado de reintento sobreviven a la recreación de la actividad y a la pérdida del enlace dentro del proceso. Cerrar la app u olvidar el PC limpia los datos en memoria. El protocolo no cambia.

## Verificación

- `FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core ./gradlew --offline :app:testDebugUnitTest :app:assembleDebug`. Compilación y 38 pruebas correctas, incluidas identidad del PC, selección ante publicación incierta, unidades decimales e interoperabilidad con el core local.
- `aapt dump badging`. APK de depuración 0.1.8-dev, código 9, API mínima 28.
- Pendiente validación visual y cortes reales en Android y Windows. El SDK disponible no incluye emulador y ADB no pudo iniciar en este entorno debido al directorio de configuración de solo lectura. Sin medición de rendimiento en hardware.
