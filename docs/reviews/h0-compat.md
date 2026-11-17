# Revisión H0-COMPAT

Estado: **en revisión**. Autor: Sol. Rama `task/h0-compat`; worktree `/workspace/worktrees/h0-compat`. Fecha de comprobación: 2026-11-17 UTC. Astra aprobó previamente las selecciones de matriz y la excepción JNI; integración final corresponde a Astra/orquestador.

## Entrega

- `docs/architecture/compatibility.md`: matriz exacta, enlaces oficiales, límites Windows/Android y herramientas, comandos de build/CI y validación física pendiente.
- `scripts/environment/check-host.sh`: inventario sin instalación ni aceptación de licencias; evita ejecutar ADB/sdkmanager y evita descargas Rust automáticas.
- `scripts/environment/inspect-android-artifacts.py`: inspección de muestras AAR oficiales con URL, SHA-256, minSdk, aar-metadata y bibliotecas nativas. No resuelve el grafo Gradle completo.

Selección: Rust/Cargo1.100.0; Node24.22.1/npm11.9.0; TypeScript6.0.3; Tauri2.12.6/tauri-build2.7.5/npm API-CLI2.12.5. Android: Kotlin/Compose compiler2.3.21, AGP8.13.2, Gradle8.13, JDK21.0.13+9/JVM17, SDK36rev2/BT35.0.0/min28; Compose BOM2025.10.01, Activity1.11.0, Lifecycle2.9.4; OkHttp5.4.0/Okio3.17.0. Rust: rustls0.23.46 con ring y tls12, rcgen0.14.10, axum0.8.9, Tokio1.54.1.

## Evidencia ejecutada

- HTTP a documentación oficial, tags de repositorios, npm/crates y Google Maven/Maven Central; versiones reales y requisitos documentados en matriz. No se seleccionaron versiones de memoria.
- Lectura de 11 AAR con `python3 scripts/environment/inspect-android-artifacts.py --include-compose`: todos declaran minSdk ≤28; OkHttp5.4.0 requiere compile36, sin `.so`. Graphics Path1.0.1 incluye cuatro ABIs (armeabi-v7a, arm64-v8a, x86, x86_64). Huellas y comandos reproducibles en matriz.
- Aserciones independientes sobre salida JSON: 11 muestras; minSdk≤28 en todas; SHA OkHttp esperado; cuatro entradas JNI en Graphics Path. Resultado correcto.
- `bash -n scripts/environment/check-host.sh` y ejecución tras `source /workspace/toolchains/env.sh`: correctos. Confirma rustc/Cargo1.100.0, JDK completo21.0.13+9, SDK36/BT35; falta Gradle global y GUI Linux. Wrapper se crea en H1.
- `git diff --check`: correcto.

## Hallazgos y decisiones revisadas

- OkHttp5.5.1 se descartó porque exige compileSdk37, fuera de AGP8.13. No se suprimen comprobaciones metadata. Contrato usa truststore privado del certificado Windows; no CA públicas.
- Astra aprobó mantener Compose y APK universal sin abiFilters. Esto modifica la restricción inicial de cero nativas Android únicamente para JNI transitivo oficial de Compose inspeccionado. No se añade TLS por JNI ni se infiere ABI del teléfono desde su CPU.
- No se modificó protocolo ni archivos de otros responsables; no se instaló software ni se publicaron cambios.

## Límites

No hay compilación de aplicación en H0: aún no existen sus módulos/locks en esta rama. No se ejecutaron tests de producto, emulador, Windows físico, J4+ ni rendimiento LAN. La revisión del grafo final, compilación H1 y pruebas físicas siguen pendientes. No afirmar que el minSdk declarado prueba el funcionamiento completo.

Incidencia de inventario: `adb --version` intentó crear `/home/agent/.android` y abortó por solo lectura. El script final evita ese arranque; la siguiente ejecución completó sin ese fallo. Adoptium API403 y dos rutas documentales404 se registran en matriz; se usaron fuentes oficiales alternativas accesibles.

Siguiente paso: revisión final de diff por Astra e integración secuencial por orquestador; implementar núcleo Windows y Android con matriz fijada, generar locks y ejecutar compilaciones reales.
