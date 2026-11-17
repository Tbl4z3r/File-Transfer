# Revisión de integración — GPT-6.1 Sol

## Decisión

Aceptadas las implementaciones Windows `5e551f6` y Android `b24a8d3` para la rama **prueba**. Esta integración no declara H1 completo ni autoriza presentarlo como distribución estable. El usuario sustituyó la coordinación Astra por GPT-6.1 Sol y solicitó publicar el estado actual en esta rama.

Se leyeron los cambios reales del núcleo, transporte Android, almacenamiento, UI, bindings Tauri, tests y reportes. Las revisiones históricas de Astra aportaron hallazgos corregidos: envío de errores acotado, revocación en streaming, espera por estado en tests y recuperación de confirmaciones ambiguas en ambos sentidos. Dos tareas finales de Luna cerraron builds y documentación; su autoevaluación no sustituyó esta revisión.

## Evidencia aceptada

- Windows: formato Rust, 12 pruebas de comportamiento, Clippy con warnings como errores y build TypeScript correctos. El orquestador repitió las 12 pruebas sobre el checkout integrado de `prueba`; todas pasaron. CI también ejecutó esas pruebas correctamente en Windows2022 sobre el mismo código del núcleo.
- Android: matriz completa desde limpieza y sin regenerar locks; 9 pruebas JVM, incluida la conexión al núcleo Rust real, metadata, lint y APK correctos. Wrapper verificado contra Gradle oficial; cuatro ABI en APK.
- Prueba independiente de TLS real con dos uploads de 16 MiB y download: identidad/hostname/pin, autorización, hashes, colisión sin sobrescritura, complete idempotente y revocación correctos.
- Vectores compartidos y diff sin errores de espacios correctos. No se incorporan claves privadas, keystores de distribución ni datos transferidos al árbol versionado.

## Correcciones de reproducibilidad

Se retiró una única entrada Kotlin stdlib-common del lock que Dependency Insight identificó exclusivamente como restricción huérfana del propio lock. La build completa volvió a pasar sin `--write-locks`, también después de clean. No se deshabilitó el bloqueo de dependencias.

## Pendientes para una versión estable

Terminar CI del último commit y corregir sus fallos, ejecución real de las aplicaciones Windows y Android/J4+, confirmación ABI del móvil y medición LAN. No hay prueba de emulador ni instalador/clave release estable. H2–H5 siguen pendientes según el tablero. El cambio de rama predeterminada/protección requiere permisos de administración que la conexión no concede; la publicación de código sí funciona.
