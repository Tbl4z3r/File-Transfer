# Puerto automático y lotes

GPT-6.2 coordinó la implementación en Windows y Android y encargó una revisión independiente. Windows intenta abrir `51337` en la IPv4 elegida. Si el sistema lo rechaza por estar ocupado o reservado, la aplicación solicita un puerto disponible en la misma dirección. El QR y Ajustes muestran el puerto del socket que abrió el sistema.

Las dos aplicaciones encolan los archivos seleccionados en memoria. Cada archivo usa el flujo v1 de autorización y verificación SHA-256. Solo se inicia el siguiente cuando el anterior se confirma como completado. Cancelar, rechazar o fallar detiene el lote. Si el PC cambia de puerto, se puede actualizar ese dato en Android conservando la identidad emparejada.

## Verificación

- Se validaron los 41 vectores escalares o de hash y 13 tipos de mensaje del protocolo existente.
- El núcleo Rust abrió un puerto alternativo con `51337` ocupado y respondió por TLS en la dirección anunciada en el QR. También se comprobó que reiniciar una sesión con la misma dirección invalida ofertas de una cola anterior.
- En Chromium, con el frontend Windows compilado y respuestas IPC simuladas, se inició sesión sin campo de puerto, se observó el puerto devuelto en Ajustes, se completó el primer archivo, se ofreció el segundo y se detuvo el lote sin ofrecer el tercero.
- Las pruebas JVM de Android cubrieron la cola y dos envíos consecutivos contra el núcleo Rust local. La verificación en Android físico debe probar el selector del sistema, incluyendo proveedores distintos, y la aceptación por archivo en ambos sentidos.

Los informes de [Windows](puerto-lotes-windows.md) y [Android](lotes-android.md) registran las pruebas de cada cliente. Consulta la [guía](../TRY-PRUEBA.md) para probar el puerto alternativo y cancelar un lote en dispositivos físicos.
