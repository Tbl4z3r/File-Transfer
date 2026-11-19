# Ajustes de Android y código manual de Windows

Android 0.1.9-dev (10) muestra el nombre editable del teléfono en Inicio, incluso antes de vincular. El nombre se conserva localmente y se envía al PC en el siguiente emparejamiento. Ajustes ocupa toda la pantalla y permite desplazarse entre conexión, carpeta de recepción, registro de errores y versión. Conserva y permite copiar el último diagnóstico saneado. La entrada de Conexión manual se borra al salir o iniciar el emparejamiento.

Windows 0.1.8 ofrece **Copiar código** en la ventana del QR. Si el portapapeles rechaza la operación, el código queda seleccionado para pulsar Ctrl+C. El código representa los mismos datos en 119 caracteres. Android admite el código compacto y el JSON de clientes anteriores. El QR sigue transportando el JSON v1, y los dos formatos manuales comparten las comprobaciones de identidad y TLS. La ventana de Windows limpia los valores al cerrar, caducar o terminar la sesión.

## Comprobaciones

- Android, JVM y APK de depuración. `./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug` compiló el APK y pasó 41 pruebas, incluidas validación canónica, límites del nombre, saneamiento y TLS. La prueba opcional con el core se omitió en esta ejecución de la rama porque requiere `FT_CORE_BINARY`.
- Windows, frontend en Linux con adaptador DOM/IPC. `npm run test:ui` pasó 61 pruebas, incluidos el vector binario compartido, la copia de ambos formatos, la selección alternativa y la limpieza de la ventana.
- Interoperabilidad de imagen. `python3 scripts/integration/verify_pairing_qr.py` recuperó con ZXing el JSON exacto del GIF generado por Windows y confirmó el margen.
- Pendiente en hardware. Comprobar nombre después de reiniciar la aplicación, teclado y Atrás en Ajustes, copia nativa del código, conexión por cada formato y copia del diagnóstico tras un error. Contrastar en Windows y Android (incluido Galaxy J4+).
