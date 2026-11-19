# Ventana compacta de Windows

Rama `task/ventana-compacta`, base `c0e5e70`.

- Eliminados Inicio y el separador vertical de navegación. Actividad, Dispositivos, Ajustes y QR conservan sus acciones.
- Transferencia a la izquierda; dispositivos, envío/recepción y actividad reciente en una columna derecha. A 850 px o menos los controles se apilan.
- Pie con fila reservada: icono y ruta constituyen un único botón `#choose-folder` que reutiliza el selector real. Una ruta larga se elide visualmente y queda completa en título y nombre accesible. El contenido central se desplaza sin llevarse el pie.
- `#batch-panel`, `#batch-status` y `#cancel-batch` se conservan debajo de `#current-transfer`. Se añade `ul#batch-files.batch-files`, vacío hasta la siguiente tarea funcional, con alto máximo de 180 px, desplazamiento interno y foco de teclado.

## Evidencia

- `npm --cache /workspace/toolchains/npm-cache ci` y `npm --cache /workspace/toolchains/npm-cache run test:ui`: compilación TypeScript y 33 pruebas correctas. La prueba de carpeta comprueba también el título accesible actualizado del botón.
- Chromium real headless con frontend `dist` compilado, servidor HTTP local e IPC simulado: 760×640, 740×610, 720×600 y 1280×800. Capturas inspeccionadas: `/tmp/ventana-760x640.png` y `/tmp/ventana-1280x800.png`; también se generaron las otras dos medidas.
- En las cuatro medidas: ancho del documento igual al viewport, transferencia a la izquierda, controles debajo de dispositivos y borde inferior del pie igual a la altura de ventana. A 760×640 los botones se apilan; a 1280×800 aparecen en paralelo. Sin errores JavaScript.
- Lista simulada con 60 elementos: desplazamiento interno comprobado y pie visible. Se probó elegir una ruta larga con el botón del pie.
- `git diff --check`: correcto.

No se ejecutó la aplicación nativa en Windows ni una transferencia física. Chromium verifica geometría y frontend con datos simulados; el renderizador funcional del lote queda para la siguiente tarea. Sin cambios de protocolo ni dependencias.
