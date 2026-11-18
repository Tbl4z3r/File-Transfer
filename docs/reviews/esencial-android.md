# Esencial Android

Rama: `task/esencial-android`. Android `0.1.3-dev` (`versionCode 4`), mínimo API 28. Implementación de la referencia aprobada `esencial-ajustado.html` con la paleta compartida azul, sin nuevas dependencias.

## Comportamiento

- Inicio: `Conecta tu PC`, glifo QR centrado y `Escanear QR del PC` debajo. La cámara solo se pide al pulsar. `Ajustes → Conexión manual con JSON` conserva la alternativa validada; el nombre Android se edita en Ajustes.
- Vinculado: fila del PC, `Enviar archivo` y `Recibir archivo`. Se muestra el nombre real recibido por TLS autenticado; pares antiguos sin nombre usan `PC emparejado`. `Vinculado` indica identidad guardada, sin simular disponibilidad de red.
- La fila completa `Carpeta de recepción` permanece visible antes y después del emparejamiento. Abre `Elegir carpeta` (selector SAF real) y, cuando corresponda, `Usar bandeja privada`. No se inventa una carpeta Descargas ni se cambia el destino sin permiso del usuario.
- `Transferencia` muestra nombre/tamaño y fases reales: espera de autorización, bytes enviados/recibidos, verificación, publicación y confirmación. La barra determinada solo representa el cuerpo; llegar al total no presenta éxito. Actualizaciones visuales limitadas a 10/s más el último bloque.
- La consulta explícita `Recibir archivo` muestra la oferta real con `Rechazar`/`Aceptar`; no consulta en segundo plano ni acepta sola. Rechazar retira la oferta local incluso si el PC ya no responde y comunica si el rechazo remoto no pudo confirmarse.
- `Cancelar` conserva la interrupción de E/S y solicitud remota. Los archivos recibidos solo se anuncian guardados después de publicar y verificar; se preservan aunque falle la confirmación al PC.
- `Reintentar confirmación` conserva el hash/ID reales. `Cerrar confirmación pendiente` permite continuar sin borrar el archivo recibido ni el vínculo, aclarando cuándo un envío aún no está confirmado. `Ajustes → Actualizar dirección` sigue disponible con una confirmación/oferta pendiente; `Olvidar dispositivo` pide confirmar el borrado local.
- `Esta sesión → Ver actividad` muestra hasta 32 resultados reales en memoria, sin archivos de ejemplo. `Abrir último archivo recibido` utiliza la URI real. `Copiar diagnóstico` reutiliza el saneamiento existente: no incluye rutas, nombres de archivo, direcciones, JSON, tokens ni credenciales.
- Tema claro/oscuro del sistema, barras del sistema con insets, texto con ajuste de línea, regiones de estado accesibles y glifos vectoriales locales. Acciones principales y controles de icono tienen objetivos táctiles de al menos 48 dp.

## Alcance técnico

Se separa la presentación en `EsencialUi.kt` y se conectan los callbacks de `MainActivity`. Se conserva `server_name` ya validado dentro del registro cifrado existente (lectura compatible con registros anteriores). No cambia el contrato remoto, TLS, almacenamiento SAF, firma ni dependencias. La prueba de interoperabilidad comprueba también el nombre autenticado real del core.

## Verificación

Comando de validación:

```sh
source /workspace/toolchains/env.sh
FT_CORE_BINARY=/workspace/worktrees/h1-windows/apps/windows/core/target/debug/ft-core \
  apps/android/gradlew -p apps/android --no-daemon --max-workers=2 \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Entorno: JDK21 y SDK36/BuildTools35. Se generó el APK debug y pasaron las 18 pruebas JVM, sin omitidas, incluida `CoreInteropTest` contra el core Rust real: TLS/emparejamiento, nombre del PC, subida, descarga, publicación y SHA-256. El comando final terminó `BUILD SUCCESSFUL`: APK generado, 18 pruebas sin fallos ni omitidas y lint con 0 errores/19 avisos de versiones disponibles, orientación QR, TrustManager existente, falta de icono de lanzador y sugerencias KTX. No se actualizan dependencias ni se modifica TLS por esta tarea.

Revisión independiente de flujos completada: corregidos los bloqueos por confirmación/oferta pendiente; sin hallazgos bloqueantes adicionales.

No hay emulador instalado ni prueba en Android físico en este entorno. Cámara, TalkBack, escalado de fuente, API28/J4+, proveedor SAF y desempeño LAN quedan pendientes de comprobación física. La actividad de sesión y confirmaciones siguen siendo memoria de esta apertura; no se añade reanudación ni garantía de ejecución en segundo plano.

## Comprobación manual pendiente

1. Abrir sin vínculo: comprobar QR centrado, carpeta visible y alternativa JSON en Ajustes; denegar cámara y usar JSON.
2. Emparejar: confirmar nombre/dirección reales y dos acciones; cambiar carpeta y volver a bandeja privada.
3. Enviar: aceptar/rechazar en PC, cancelar durante espera/E/S y comprobar fases/resultados sin éxito anticipado.
4. Ofrecer desde PC: pulsar Recibir, rechazar/aceptar; simular oferta vencida y confirmar que se puede volver a operar.
5. Cortar red después de guardar: abrir el archivo verificado, actualizar IP, reintentar confirmación y cerrar el pendiente sin borrar vínculo/archivo.
6. Revisar actividad real, tema oscuro, texto grande, iconos sin conexión y objetivos táctiles en J4+.
