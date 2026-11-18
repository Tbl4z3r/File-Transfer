# Interfaz y actividad de recepción en Android

La versión 0.1.7-dev reorganiza la pantalla en una tarjeta compacta de conexión, una sección de transferencia actual y la actividad reciente. La carpeta de recepción queda fija junto al borde inferior del área segura mientras el contenido central se desplaza. La paleta usa un degradado suave con variantes clara y oscura.

La actividad conserva hasta 32 entradas de la instancia de la aplicación. Cada archivo recibido registra el URI devuelto al publicarlo, su nombre definitivo y el destino efectivo antes de confirmar al PC. «Abrir archivo» comparte el documento exacto con un visor mediante un permiso de lectura. «Ir a la carpeta» usa la ubicación original aunque cambie el destino configurado. Para SAF se intenta abrir la carpeta en Android y se ofrece un navegador integrado cuando el sistema no puede hacerlo. La bandeja privada se abre con ese navegador y los archivos se abren mediante FileProvider. Las carpetas históricas conservan solo un permiso temporal de lectura, acotado por el historial y retirado al iniciar una nueva instancia.

## Verificación

- `FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core ./gradlew --offline testDebugUnitTest assembleDebug` en `apps/android` con JDK 21, SDK 36 y Build Tools 35. Las 35 pruebas JVM pasaron, incluida la interoperabilidad con el núcleo.
- `aapt dump badging` confirmó el APK de depuración con versión 0.1.7-dev, código 8 y API mínima 28.

Pendiente de confirmar en Redmi y Galaxy J4+ con navegación de botones y gestos, ambas paletas y tamaños de texto ampliados. Recibir dos archivos del mismo nombre, cambiar de destino y comprobar que cada fila abre el archivo y la carpeta correctos. Repetir si el PC pierde la conexión después del guardado, con la bandeja privada y con un proveedor SAF sin gestor de carpetas compatible. Comprobar también la selección explícita de una oferta individual y el detalle antes de aceptar un lote.

No se ejecutaron pruebas en emulador ni en dispositivos físicos. No se midió rendimiento.
