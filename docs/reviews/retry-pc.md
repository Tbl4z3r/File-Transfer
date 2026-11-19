# Reintentos, unidades y paleta en Windows 0.1.7

Implementación acotada a la aplicación Windows y su core Rust, con coordinación de GPT-6.2. La oferta nueva usa los flujos existentes de archivo individual y de lote, con sesión y dispositivo autenticados. El estado local adicional se entrega a Tauri. El protocolo de red conserva sus rutas y mensajes.

- **Reintentar** conserva el último envío mientras está abierta la ventana. Requiere seleccionar el dispositivo de destino, incluido el nuevo identificador después de volver a emparejar por QR. El móvil debe aceptar la oferta nueva.
- Los archivos verificados quedan fuera de la selección. Se comprueban disponibilidad, tamaño y fecha de modificación y se informa de los descartes. Si el PC pudo entregar el contenido y perdió la confirmación, pide comprobar la carpeta del móvil antes de incluir ese archivo.
- **Reintentar recepción** habilita las solicitudes, ofrece un QR si el móvil se desconectó y pide que reenvíe los pendientes. El PC exige aceptar la solicitud nueva.
- La paleta verde petróleo y menta queda fija, también con la preferencia clara del sistema. El QR conserva el contraste negro sobre blanco. Los tamaños y las velocidades usan prefijos decimales.

## Verificación

- `npm run test:ui` superó 58 pruebas. Las nuevas cubren reemparejamiento con otro móvil aún conectado, exclusión de verificados y orígenes indisponibles, confirmación de entrega incierta, preparación de recepción individual y por lote y representación SI de tamaños y velocidad.
- `cargo test --locked --manifest-path apps/windows/core/Cargo.toml` superó 32 pruebas en Linux, incluidas 26 con TLS. La prueba nueva comprueba bloqueo de fuentes modificadas y clasificación local de una descarga entregada sin confirmación.
- `cargo check` del shell Tauri para Linux encontró un bloqueo en `glib-2.0`, ausente del entorno. La ejecución en Windows y la transferencia PC ↔ móvil con corte real siguen pendientes.

Una oferta nueva empieza desde el primer byte. La lista de reintentos vive en memoria y depende de conservar abierta la ventana. Tras cerrar una sesión en curso, un archivo cuyo estado terminal no se alcanzó a observar se trata como entrega incierta.
