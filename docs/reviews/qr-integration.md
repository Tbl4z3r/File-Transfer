# QR — revisión de integración por GPT-6.1 Sol

Aceptadas para `prueba` las tareas QR-WINDOWS (a64795a) y QR-ANDROID (5096273), solicitadas por el usuario para probar el J4+ sin transcribir JSON. Sol leyó los diffs, dependencias y reportes y mantiene el contrato descriptor v1/TLS existente. No se permite confianza basada solo en IP/puerto ni se da H2 completo.

## Comprobaciones

- Windows: build TypeScript/recursos y ocho pruebas de render/lifecycle correctos. Dependencia exacta qrcode-generator2.0.4, licencia MIT conservada, ESM local y CSP sin ampliaciones. Borra imagen/JSON en hide/expiry/stop y descarta respuestas IPC obsoletas. Fixture normativo genera GIF414x414, ECC M, escala6 y quietzone4.
- Android: metadata, 18 pruebas JVM, lint y APK correctos sin --write-locks tras corregir exclusivamente la restricción huérfana kotlin-stdlib-common. Incluye parser de resultado escaneado antes de red, URLs/prefijos/malformados rechazados y códigos de error reales sobre TLS simulado. Cámara opcional, permiso solo al pulsar, captura privada y sin guardar imágenes. ScanContract limita a QR; el emparejamiento reutiliza pin/PKIX/hostname/credenciales.
- Sol ejecutó `verify_pairing_qr.py --windows-dir /workspace/worktrees/qr-windows/apps/windows`: ZXing core3.5.4 leyó la imagen GIF real producida por el encoder de Windows y recuperó exactamente el JSON normativo, sin prefijo; también se comprobó el margen blanco de24px. No es una prueba óptica con cámara.
- Solo se añadieron dos módulos de runtime Android al grafo previo: JourneyApps4.3.0 y ZXingcore3.5.4. El AAR nuevo no incluye JNI y el decoder tiene bytecodeJava8; API28 está dentro de mínimos publicados. Fuentes/versiones en la matriz.
- Vectores de protocolo y git diff --check correctos. CI nuevo ejecuta test:qr y la decodificación cruzada además de los checks previos.

## Pendientes reales

La compilación nativa Windows y el APK de este commit se comprobarán en CI antes de recomendar sus artefactos. No hubo emulador ni cámara J4+/Redmi/WebView2 físico. Seguir docs/TRY-PRUEBA.md para permiso denegado/cancelación/QR vencido o ajeno/rotación y transferencias, incluyendo la corrección de recepción previa. Firma debug/distribución estable siguen pendientes. No se declaran resueltas en hardware la compatibilidad ni la recepción hasta que el usuario vuelva a probar.
