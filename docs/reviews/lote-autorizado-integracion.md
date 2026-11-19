# Detalle de lote y ventana compacta

GPT-6.2 definió el contrato y coordinó cuatro tareas de implementación con ramas separadas: autorización del manifiesto, anuncio desde Android, geometría de la ventana y filas del lote en Windows. Una revisión independiente cubrió la autorización, la cancelación y la disposición de los controles.

Windows muestra nombre, tamaño y estado bajo la transferencia actual. La lista se desplaza cuando hay más filas de las que caben. En lotes del móvil a Windows, la pantalla presenta el manifiesto antes de habilitar la transferencia y usa la carpeta elegida para todos los archivos aprobados. Android sigue consultando y aceptando cada archivo que recibe del PC.

## Comprobaciones

- El core pasó 21 pruebas, incluidas seis de lote sobre TLS. Se comprobó la aprobación única, el orden, el aislamiento entre dispositivos, la integridad, la cancelación, la caducidad, la revocación y los resultados de un lote de 64 archivos. Clippy y el ensayo TLS de transferencias individuales también pasaron.
- La prueba cruzada Kotlin/Rust anunció dos archivos, aceptó el manifiesto una vez, transfirió ambos y verificó los hashes. El equipo Android generó el APK y ejecutó lint y las pruebas JVM.
- Chromium ejecutó el frontend Windows compilado con respuestas IPC simuladas. Se comprobó el selector de carpeta al pie en 740 × 610, 760 × 640 y 1100 × 780, la columna derecha de dispositivos y acciones, la ausencia del divisor y de Inicio, y un lote de 32 filas con scroll conservado durante una actualización. Se verificó una sola invocación de aceptación y una elección de carpeta, además de la retención del archivo completado cuando se cancela el resto.

Las comprobaciones locales usarán la compilación nativa de Windows en CI para contrastar la integración de Tauri. Queda comprobar en los equipos físicos la red local, el selector de Android y las dimensiones de la ventana con el marco y escalado elegidos por el usuario.
