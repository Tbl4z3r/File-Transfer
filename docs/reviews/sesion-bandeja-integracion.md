# Integración de vínculos y bandeja

## Comportamiento

- Windows mantiene los dispositivos vinculados durante la sesión. Al cerrarla, conserva la identidad TLS y los archivos verificados, e invalida las credenciales de todos los móviles de esa sesión.
- Android intenta avisar al salir. Si el proceso desaparece, Windows retira solo ese móvil tras 45 segundos sin actividad. Android vuelve al QR si su PC cierra la sesión o se pierde el vínculo. Girar la pantalla o usar el selector de archivos conserva el vínculo.
- Android consulta la bandeja mientras la aplicación está abierta. Una oferta de varios archivos expone el manifiesto completo antes de autorizar la descarga. La transferencia es secuencial y cada archivo se publica después de verificar su hash.

## Verificación local

- Con Rust 1.100.0 pasaron 4 pruebas unitarias, 2 de vectores y 25 de TLS con sockets reales. Cubren el plazo de actividad desde el QR, el aislamiento entre dispositivos, el orden de los archivos y el cierre durante una descarga.
- El smoke test TLS transfirió 16 MiB en ambos sentidos y comprobó la verificación, la colisión de nombres y la revocación. Los vectores de protocolo, rustfmt y Clippy pasaron.
- En Windows pasaron 51 pruebas de interfaz y QR. Chromium con IPC simulado a 760 px comprobó una oferta nativa de lote, nombre y tamaño por fila, cancelación con registro del archivo verificado, aviso al retirar un móvil y limpieza de la sesión.
- Android pasó 35 pruebas JVM, lint y compilación del APK. La prueba cruzada contra el binario Rust real leyó un manifiesto de dos archivos, confirmó que el servidor exige aprobación y verificó los hashes, la revocación y el nuevo QR.

## Validación en dispositivos

En Windows, J4+ y Redmi Note 14 5G, instala ambos clientes de la misma ejecución de CI. Abre Android y envía un archivo desde Windows sin pulsar la bandeja. Repite con un lote de varios tamaños, compara el detalle con la selección y acepta una sola vez. Cierra Android desde Atrás y comprueba que Windows solo retire ese móvil. Cierra Windows con Android abierto y comprueba el aviso. Genera un QR nuevo en cada reconexión. Queda pendiente ejecutar estos pasos en hardware para esta candidata.
