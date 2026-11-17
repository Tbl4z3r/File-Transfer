# H1 Android — entrega para revisión

Cliente Android Compose API 28+ con conexión manual a Windows, descriptor verificado fuera de banda, TLS con pin DER y PKIX/hostname, credencial cifrada con AES-GCM en Android Keystore sin backup, selección SAF y flujo de un archivo por vez. La subida espera autorización en el PC; la descarga requiere confirmación explícita, escribe en temporal privado y publica con enlace exclusivo después de verificar SHA-256. La interfaz permite cancelar, olvidar el par, cambiar IP/puerto conservando identidad y reintentar la confirmación remota ambigua de una subida o de una descarga ya publicada. La recepción es activa solo al consultar y la app debe permanecer abierta. No hay mDNS, reanudación ni servidor Android.

El cliente usa buffers de 64 KiB, tamaños `Long`, DNS dedicado y el hostname fijo del contrato. El parser JSON estricto se comparte entre JVM y Android; rechaza duplicados, tipos inválidos, null y UTF-8 inválido. La publicación conserva nombres existentes y elimina temporales al fallar o al iniciar. La credencial y el descriptor no se registran.

## Validación

Desde `apps/android`, con JDK 21 y SDK 36 locales y `source /workspace/toolchains/env.sh`:

```sh
./gradlew --no-daemon --max-workers=4 :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

La primera repetición falló en `checkDebugAarMetadata`: Dependency Insight confirmó que `kotlin-stdlib-common:2.3.21` aparecía únicamente por la restricción del lock y no era parte del grafo resuelto. Se retiró esa sola entrada obsoleta. Luego pasó la matriz completa sin `--write-locks`; tras `:app:clean`, la misma matriz terminó de nuevo con éxito: `checkDebugAarMetadata`, `testDebugUnitTest` (9 pruebas, incluidas 1 interoperabilidad con `ft-core`), `lintDebug` y `assembleDebug`. El APK recién generado contiene `libandroidx.graphics.path.so` en `armeabi-v7a`, `arm64-v8a`, `x86` y `x86_64`. Lint advierte sobre el TrustManager personalizado: comprueba huella y delega PKIX a la plataforma; no se suprime el aviso. El JAR de Gradle Wrapper coincide byte por byte con `gradle/wrapper/gradle-wrapper.jar` del repositorio oficial Gradle, tag `v8.13.0` (SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`).

## Límites

La prueba opcional `CoreInteropTest.realCorePairUploadDownload`, ejecutada con `FT_CORE_BINARY=/workspace/worktrees/h1-windows/apps/windows/core/target/debug/ft-core`, pasó sin omitirse en Linux: emparejamiento, subida y descarga TLS de 1 MiB + 31 bytes, SHA-256 y complete. Es loopback JVM, no prueba Android ni LAN.

Pendientes emulador, instalación y ejecución J4+, bloqueo de pantalla y medición LAN. La compilación del core en Windows se valida por CI aparte; la prueba de interoperabilidad aquí es loopback Linux. No se afirma compatibilidad física ni rendimiento. El APK debug no es una distribución firmada para uso final. El estado de confirmación remota pendiente vive en la sesión de la interfaz; no sobrevive a reiniciar la app. SAF destino/exportación, persistencia de reanudación y servicio en segundo plano quedan fuera de H1. [README Android](../../apps/android/README.md) documenta build e instalación debug.
