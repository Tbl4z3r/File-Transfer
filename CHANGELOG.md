# Cambios

## Windows 0.1.1 / Android 0.1.2 en desarrollo — QR

- Windows muestra QR local del descriptor v1, con vencimiento de 120 s y copia manual opcional.
- Android solicita permiso de cámara al escanear y empareja sin copiar JSON, sin Google Play Services ni modelos descargados.
- Rechaza QR ajenos/malformados antes de conectar y muestra una explicación útil si el QR venció o el móvil ya figura emparejado.
- Mantiene TLS/pin y credenciales revocables, recepción con carpeta elegida y diagnósticos de 0.1.1.

## Android 0.1.1 en desarrollo — rama prueba

- Sustituye el guardado por hard link, que fallaba después de recibir todos los bytes en la prueba móvil, por creación exclusiva y copia verificada.
- Permite elegir y recordar carpeta de recepción con el selector Android, conservando la bandeja privada como alternativa.
- Añade Copiar diagnóstico y el tag local FileTransfer de logcat, con etapa/tipos de error y sin credenciales/rutas.
- Mantiene el protocolo v1 y el programa Windows actual.

## 0.1.0 en desarrollo — rama prueba

- Base Windows x64/Tauri y Android API28+/Compose para conexión manual en LAN.
- TLS con identidad persistente, emparejamiento verificable y credenciales revocables.
- Archivo individual en ambos sentidos, consentimiento, progreso, cancelación y SHA-256 incremental.
- Publicación sin sobrescritura y eliminación de parciales al fallar.
- Núcleo Rust independiente y cliente Kotlin probados juntos mediante TLS real.
- CI para protocolo, Rust/Linux, Windows y Android; dependencias y toolchains fijados.

No es una versión estable ni un instalador final. Pruebas físicas y medición LAN pendientes. Descubrimiento, lotes y reanudación corresponden a hitos posteriores.
