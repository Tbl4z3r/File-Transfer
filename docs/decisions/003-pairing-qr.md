# ADR003 — QR para probar J4+ sin transcribir JSON

El usuario anticipa este paso de H2 antes de comprobar el J4+, sin WhatsApp/Play Store. Sol aprueba conservar el descriptor v1, TLS, pin y token temporal. El QR contiene exactamente JSON.stringify(descriptor); no cambia versión, no añade prefijo/URL, ni permite emparejar confiando solo en IP/puerto.

Windows genera una imagen GIF local negro/blanco ECC M, escala entera de seis píxeles por módulo y quiet zone de cuatro módulos. No hay API externa, persistencia o logs del QR. Ocultar, vencer o cerrar la sesión elimina la imagen y el descriptor de la vista. La ventana nueva invalida la anterior en el servidor.

Android lee solo QR con librerías Java incluidas en el APK. Solicita CAMERA al pulsar Escanear QR del PC; la cámara se cierra al volver y el resultado se valida mediante el mismo Descriptor.parse antes de la petición HTTPS. No guarda imágenes, no abre URLs escaneadas y no transmite códigos a terceros. Denegación/cancelación/cámara ausente conservan la entrada manual. pairing_denied y already_paired producen acciones útiles mediante error.code, sin comparar mensajes del servidor.

Verificación: encoder de Windows y ZXing3.5.4 decodifican el mismo descriptor sintético, incluidos píxeles/margen de la imagen real. Esto no prueba la óptica, foco, permiso o WebView del dispositivo. Se necesita probar la pantalla Windows y la cámara del Samsung J4+ API28. No se da H2 completo: mDNS sigue pendiente.

Dependencias exactas y fuentes en docs/architecture/compatibility.md. No se cambia firma/distribución estable ni se anticipa el rediseño visual general.
