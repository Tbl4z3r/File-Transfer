# QR-WINDOWS — en revisión

Responsable: GPT-6.1 Sol, tarea delegada por el orquestador Sol. Rama `task/qr-windows`, base `3f6be55`. Alcance exclusivo `apps/windows/**` y este reporte.

## Resultado

Windows 0.1.1 ofrece **Mostrar QR de conexión**, QR local negro/blanco ECC M, quiet zone de cuatro módulos y escala natural entera de seis píxeles/módulo. El fixture normativo produce un GIF de 414 × 414 píxeles. El panel se desplaza a la vista y conserva huella y copia manual del JSON. No requiere WhatsApp, Internet ni API externa.

`src/pairing-qr.ts` recibe exactamente `JSON.stringify(descriptor)`; no agrega prefijo, URL ni versión. Exporta `encodePairingQr(payload) -> {matrix,dataUrl,pixels}` para validación independiente. No cambian comandos, endpoints, TLS/pin ni token de un solo uso de 120 segundos del core. Core permanece en 0.1.0 porque no cambió; paquete frontend, manifiesto Tauri y paquete/lock de la shell Rust pasan a 0.1.1.

Se borran imagen, JSON y huella al ocultar, vencer, cerrar sesión y antes de generar otra ventana. Un contador de generación descarta respuestas IPC si el usuario ocultó el panel mientras esperaba. El tiempo de UI usa reloj monotónico desde antes de la llamada para no alargar la ventana del servidor. Fallos de IPC no reutilizan el descriptor previo; fallos de encoder borran la imagen previa y conservan únicamente el descriptor actual como alternativa manual. Ocultar no revoca el token en el servidor: conserva su vencimiento original, sin añadir un endpoint.

## Dependencia y distribución

`qrcode-generator` 2.0.4 exacta, MIT, publicada 2025-08-07 según comprobación oficial entregada por el orquestador. Lock con integridad `sha512-mZSiP6RnbHl4xL2Ap5HfkjLnmxfKcPWpWe/c+5XxCuetEenqmNFf1FH/ftXPCtFG5/TDobjsjz6sSNL0Sr8Z9g==`. `build.mjs` copia el ESM de navegador desde el paquete instalado a `dist/vendor/qrcode.mjs`, más licencia MIT preservada en `src/vendor/qrcode-LICENSE.txt` (texto de https://raw.githubusercontent.com/kazuhikoarase/qrcode-generator/master/LICENSE). Import relativo servido localmente, sin bundler nuevo ni bare imports de runtime. CSP actual `script-src 'self'` y `img-src 'self' data:` se mantiene.

No se registran ni persisten tokens. La herramienta de exportación acepta solo la ruta de salida y carga el fixture sintético público normativo; no toma credenciales reales como argumentos. No se versionan imágenes ni artefactos generados.

## Verificación ejecutada en Linux

Con `source /workspace/toolchains/env.sh`, desde `apps/windows`:

- `npm ci --cache=/tmp/ft-qr-npm-cache`: correcto.
- `npm run test:qr`: build TypeScript + copia de recursos correctos y **8/8 pruebas** aprobadas. Incluye dimensiones GIF, escala/margen, limpieza al ocultar/vencer/cerrar, error del encoder, error IPC y respuestas IPC obsoletas por ocultación o expiración. Lifecycle con DOM/invoke simulados; no es una prueba de WebView real.
- `node scripts/export-pairing-qr.mjs /tmp/qr-windows-fixture.gif`: correcto; exportó GIF 414 × 414 y JSON normativo exacto en `.gif.json`, listos para decodificar con ZXing Java independiente por el orquestador.
- `git diff --check`: correcto.

## Límites y comprobación pendiente

No se ha compilado Tauri para Windows ni ejecutado la pantalla en WebView2 Windows. No se han probado cámara/escaneo ni legibilidad real en Samsung J4+. La decodificación independiente con ZXing queda a la validación de integración del orquestador. No se afirma aprobación ni integración de esta rama.

Prueba manual Windows ↔ J4+: iniciar sesión en red privada, mostrar QR, escanear desde Android, contrastar huella y emparejar; verificar ambas direcciones de transferencia. Después verificar ocultar, regenerar (QR anterior rechazado por el servidor), vencimiento y cierre de sesión. Confirmar manual fallback y funcionamiento sin WhatsApp/servicios remotos. No se incorpora mDNS en esta tarea.
