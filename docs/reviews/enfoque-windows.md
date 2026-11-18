# Enfoque Windows — entrega para revisión

Rama: `task/enfoque-pc`. Base: `origin/prueba`. Versión del cliente Windows: **0.1.2**. Referencia aprobada: `enfoque-pc-ajustado.html`, con la paleta azul clara/oscura solicitada.

## Resultado

- Inicio compacto: transferencia actual a la izquierda, dispositivos seleccionables y acciones a la derecha, carpeta y último resultado de la sesión debajo. Sin eslóganes ni contadores redundantes.
- Barra lateral con botones transparentes de 44 × 44 px, contorno, radio de 9 px, nombres accesibles, foco visible y ayuda al pasar el cursor o recibir foco. Abre Actividad, Dispositivos con revocación y Ajustes con dirección/cierre de sesión. Los diálogos nativos mantienen foco y Escape.
- Enviar abre el selector nativo y crea la oferta dirigida al dispositivo seleccionado. Recibir conmuta el estado real. Se conservan aceptación/rechazo, cancelación, errores, progreso con enteros grandes y resultado verificado. Los controles de cancelación permanecen en el DOM durante el avance.
- Carpeta: el selector nativo permite elegirla antes de aceptar o al aceptar la primera solicitud. Se reutiliza en las siguientes aceptaciones **solo de esa sesión**; cerrar sesión la descarta. Cambiarla no altera una transferencia ya aceptada. Cada solicitud sigue exigiendo aceptación explícita. No se incorpora recepción automática ni persistencia de preferencias.
- Mostrar QR conserva botón compacto de 132 × 46 px y solo amplía el glifo a 24 px. El QR escaneable permanece negro sobre blanco con el mismo encoder, JSON v1, escala natural y zona blanca. Conexión manual queda en un desplegable y se abre si falla el encoder.
- El plazo monotónico de 120 segundos incluye el tiempo de IPC; cerrar/Escape/close/pagehide, cierre de sesión y caducidad limpian imagen, JSON y huella. Se descartan respuestas tardías; abrir otra ventana invalida el token anterior como antes. Ocultar sigue retirando los datos de pantalla sin cambiar la caducidad del servidor.
- Mensajes dentro de los diálogos permiten percibir fallos y resultados de acciones mientras el resto de la ventana está inerte. Datos de pares, nombres y rutas se insertan con `textContent`; no se interpola HTML remoto.

No cambian los comandos Tauri, el servicio Rust, TLS ni el contrato compartido. El adaptador de pruebas está limitado a `scripts/` y no forma parte de `dist`.

## Verificaciones ejecutadas

- `npm ci --cache /workspace/toolchains/npm-cache`: correcto con dependencias fijadas; no se añadieron dependencias de runtime.
- `npm run build`: TypeScript estricto y copia de recursos correctos.
- `npm run test:ui`: **23/23**. Incluye las ocho regresiones QR anteriores, cuatro salidas del diálogo QR (Escape, close, pagehide y sesión cerrada) y once pruebas de comandos/estados: sesión, selección/envío, cancelación del picker, recepción y error, aceptación con destino, rechazo/cancelación en cuatro estados, progreso >4 GiB sin falso éxito, nombres literales, actividad y revocación. Usa adaptador DOM/IPC; no equivale a ejecución nativa.
- `git diff --check`: correcto.
- Formateo legible del HTML, TypeScript, CSS y scripts, con Prettier 3.6.2 como herramienta temporal; sin nueva dependencia del proyecto.
- Verificación independiente comunicada por el orquestador: Chromium en claro/oscuro con IPC simulado sobre el frontend compilado; QR real, Escape, aceptar/progreso/cancelar, envío al segundo dispositivo, revocación y cierre. También comunicó core Rust **12 pruebas en serie** y smoke TLS de **16 MiB por sentido**. La compilación duplicada del core en este worktree se detuvo por indicación del orquestador; no se atribuye como ejecución propia completada.

## Límites pendientes

No se compiló ni ejecutó el shell nativo Windows en este entorno (solo está instalado el target Rust Linux). No se ejecutaron pruebas en Windows 10/11, cámara del J4+, LAN física ni mediciones de rendimiento. La comprobación Chromium usa IPC simulado; los selectores nativos y el flujo completo requieren prueba en Windows. Se conservan los límites H1: IPv4 manual, un archivo cada vez, sin mDNS ni reanudación.

Prueba manual recomendada: abrir sesión en Windows, escanear desde Android, activar Recibir, aceptar en la carpeta elegida, cancelar otro archivo, enviar en sentido inverso, comprobar resultado verificado, revocar el móvil y cerrar sesión. Repetir con tema oscuro y navegación de teclado por barra lateral/diálogos.
