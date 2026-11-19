# Revisión de Esencial y Enfoque

GPT-6.2 coordinó Android y Windows en ramas separadas y encargó una revisión de los flujos de emparejamiento, recepción, cancelación y revocación. Ambos clientes usan los mismos acentos. En claro, el marino `#091540` da contraste al texto y el persa `#1B2CC1` destaca las acciones. En oscuro, el marino es el fondo, el `#7692FF` destaca las acciones y el `#ABD2FA` ayuda a leer información secundaria. La imagen QR mantiene sus módulos negros y el margen blanco.

## Comprobaciones de integración

- **Prototipos.** Chromium abrió ambos diseños en tres anchos, en claro y oscuro, para cinco estados. Se comprobó el cambio de plataforma y la simulación de emparejamiento.
- **Frontend de Windows.** Chromium ejecutó el HTML, CSS y TypeScript compilado de la aplicación con respuestas IPC simuladas. Se verificaron el botón Recibir, la generación y limpieza de la imagen y el JSON de emparejamiento al pulsar Escape, la carpeta de la sesión, la aceptación, el progreso, la cancelación, el envío al dispositivo seleccionado, la revocación y el cierre de sesión. La compilación del ejecutable y el funcionamiento en WebView2 son comprobaciones distintas.
- **Protocolo.** Se validaron los 41 vectores escalares o de hash, 13 tipos de mensaje y sus invariantes. Las 12 pruebas de Rust pasaron en serie y el ensayo TLS local verificó los dos sentidos con archivos de 16 MiB, colisiones, autorización e integridad. En la primera ejecución paralela, la prueba existente de tiempos de espera no observó el primer byte dentro de su ventana de 70 ms. El núcleo coincide con `prueba` antes del cambio de interfaz.

Las nuevas pantallas quedan pendientes de lectura QR y transferencias entre los dispositivos físicos del usuario. El [registro anterior](manual-redmi-j4-2026-11-17.md) corresponde al diseño previo. Consultar los informes de Esencial y Enfoque para las comprobaciones de cada cliente.
