# Lotes Android

Rama `task/lotes-android`, base `origin/prueba` `5a89f3d`. Android **0.1.4-dev**, código **5**, mínimo API 28. Sin cambios de protocolo, dependencias, TLS, paleta ni publicación SAF.

## Comportamiento

- **Enviar archivos** abre `OpenMultipleDocuments`: uno o varios documentos, en el orden devuelto por el selector, sin duplicar URIs idénticos. No se prometen orden por nombre ni permisos después de cerrar la app.
- Cola de sesión secuencial, un stream/transferencia v1 cada vez. Muestra `Archivo i de n` y `X pendientes sin enviar`; al detenerse conserva el índice y la cantidad restante. Cada archivo consulta su propio nombre/tamaño, valida reglas v1, solicita aceptación al PC y calcula/compara SHA-256 incremental. El siguiente solo arranca tras `completed` con el hash esperado, incluso cuando hay que resolver una respuesta de confirmación perdida mediante status.
- **Cancelar lote** en selección múltiple y **Cancelar** en selección individual. Interrumpe E/S, solicita cancelación remota y comprueba cancelación antes de abrir cada siguiente elemento y antes/después de esperar consentimiento. Los anteriores ya verificados se conservan.
- Rechazo/cancelación remota, fallo de fuente/red/integridad o confirmación pendiente detienen el resto. No se repite una creación ni un cuerpo enviado parcialmente. **Reintentar confirmación** actúa solo sobre el ID/hash actual y nunca reinicia la cola. Los pendientes requieren selección explícita nueva.
- Consulta columnas SAF por su nombre, sin depender del orden devuelto por el proveedor. Nombre/tamaño ausentes, nombre Windows inválido, tamaño fuera de límite o stream nulo fallan sin inventar metadatos ni nombres derivados de la URI. Las instantáneas inmutables por elemento ordenan fase/bytes/resultado en el hilo principal; se limpia el elemento anterior antes de consultar la siguiente fuente.
- La recepción de una cola Windows conserva **Recibir archivo → Aceptar** para cada siguiente oferta. Sin consulta ni aceptación automática en segundo plano; si Windows aún prepara la siguiente, se vuelve a pulsar Recibir archivo.

## Validaciones

```sh
source /workspace/toolchains/env.sh
FT_CORE_BINARY=/workspace/worktrees/h1-windows/apps/windows/core/target/debug/ft-core \
  apps/android/gradlew -p apps/android --no-daemon --max-workers=2 \
  :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Validación completa terminada con `BUILD SUCCESSFUL`: metadata y APK correctos, **23 pruebas JVM sin fallos ni omitidas**, lint **0 errores y 19 avisos existentes** (versiones, KTX, orientación QR, TrustManager e icono). Cinco pruebas JVM de `SendQueueTest`: segundo archivo posterior al primer éxito, stop ante resultado no confirmado/rechazado, excepción sin abrir el siguiente, cancelación entre archivos y cancelación antes del primero/después de presentar el índice. `CoreInteropTest` usa el core Rust real en la misma sesión TLS para dos subidas consecutivas (binario y segundo archivo vacío con nombre Unicode), ambos con aceptación independiente, hash, publicación y IDs distintos; conserva la descarga/publicación/colisión existente.

## Comprobación manual pendiente

1. En J4+/API28, abrir **Enviar archivos**, seleccionar tres documentos locales, confirmar que solo se pide aceptación para el primero; aceptar cada uno y comparar los tres hashes/nombres en Windows. Revisar tema oscuro, nombres largos y texto grande.
2. Rechazar el segundo: conservar el primero, no crear el tercero, mostrar lote detenido y un pendiente. Repetir con cancelación durante la espera y durante E/S; **Cancelar lote** debe impedir abrir el siguiente.
3. Cortar red durante el segundo y después de enviar los bytes antes de complete: sin replay del cuerpo. Si aparece confirmación pendiente, actualizar dirección/reintentar solo confirma ese archivo y no arranca el tercero.
4. Seleccionar desde Descargas y otro proveedor SAF: columnas reordenadas, tamaño desconocido, nombre incompatible y permiso perdido deben detener el elemento/resto con explicación; no anunciar éxito ni sustituir el nombre. Probar también vacío, Unicode y dos nombres iguales.
5. Ofrecer tres archivos desde Windows: tras cada recepción verificada, pulsar **Recibir archivo** y **Aceptar** para el siguiente. Rechazar/cancelar uno debe detener el lote del PC.

No se ejecutaron emulador ni hardware Android/Windows en este entorno. Esta compilación no prueba selección SAF real, ciclo de vida, rendimiento LAN, J4+ ni ejecución en segundo plano. La cola sigue siendo memoria de esta apertura, sin persistencia o reanudación.
