# Compatibilidad y versiones — base H0

Verificado el **2026-11-17 UTC** mediante documentación oficial, metadatos de publicación y lectura de AAR. Esta matriz selecciona una combinación estable concreta para H1; no equivale a una compilación de la aplicación ni a una prueba física. El contrato pertenece a Astra y a `protocol/PROTOCOL.md`.

## Plataformas objetivo

- **Windows x64:** referencia mínima del proyecto Windows 10 **22H2**, además del PC Windows 11 **25H2** indicado por el usuario. No se promete soporte de todas las versiones de Windows 10. Tauri usa WebView2; comprobar el runtime durante instalación. Microsoft mantiene actualizaciones de Edge/WebView2 en Windows 10 22H2 hasta al menos octubre de 2028, lo que no constituye validación de esta aplicación. Compilar en runner Windows con MSVC, SDK Windows y WebView2; probar ejecución en ambos equipos/sistemas por separado.
- **Android:** `minSdk=28` (Android 9), `compileSdk=36`, `targetSdk=36`. Compilar con un SDK moderno no cambia el mínimo de instalación. Cada acceso a API posterior a 28 necesita protección y alternativa; lint y el manifiesto combinado deben verificarlo. APK firmado fuera de Play Store, sin Google Play Services.
- **Galaxy J4+:** Samsung documenta Android 9 para variantes SM-J415F/G/FN; no se conoce aún la variante ni la ABI del equipo del usuario. Una CPU con capacidad de 64 bits no demuestra que su firmware Android ejecute procesos `arm64-v8a`.

Fuentes: [requisitos Tauri](https://v2.tauri.app/start/prerequisites/), [instalador Windows](https://v2.tauri.app/distribute/windows-installer/), [soporte Microsoft Edge/WebView2](https://learn.microsoft.com/en-us/deployedge/microsoft-edge-supported-operating-systems), [Samsung SM-J415G](https://doc.samsungmobile.com/SM-J415G/001869180921/eng.html), [ABIs Android](https://developer.android.com/ndk/guides/abis), [Build.SUPPORTED_ABIS](https://developer.android.com/reference/android/os/Build#SUPPORTED_ABIS).

## Matriz fijada para implementar

| Componente | Versión exacta / configuración | Evidencia y motivo |
|---|---|---|
| Rust / Cargo | **1.100.0**, toolchain mínimo con rustfmt y clippy | Publicación estable 2026-11-12 y manifiesto de distribución oficial. Rust solo mantiene la estable más reciente: fijar una antigua por costumbre no mejora su mantenimiento. |
| Target Windows | `x86_64-pc-windows-msvc` | Toolchain MSVC nativo Windows; Linux no valida su compilación ni ejecución. |
| Node.js | **24.22.1 LTS** | Publicación oficial y versión disponible aquí. Node solo se necesita al construir. |
| npm | **11.9.0** | Disponible aquí; registrar `packageManager` y conservar `package-lock.json`. |
| TypeScript | **6.0.3** | Paquete oficial estable, motor Node `>=14.17`; combinación conservadora del compilador JS frente al nuevo major 7. |
| Tauri Rust | **2.12.6** | Rama estable 2, MSRV declarado **1.90**. No adoptar Tauri 3 alpha. |
| `tauri-build` | **2.7.5** | Versión usada por el workspace oficial del tag Tauri 2.12.6. |
| `@tauri-apps/api` / `@tauri-apps/cli` | **2.12.5** / **2.12.5** | Paquetes oficiales disponibles; los números de patch de npm y crate no tienen que coincidir. Compilación conjunta pendiente. |
| Kotlin / KGP | **2.3.21** | Publicación estable y tabla de compatibilidad: Gradle 7.6.3–9.3.0 y AGP 8.2.2–9.0.0. |
| Compose compiler plugin | **2.3.21** | `org.jetbrains.kotlin.plugin.compose`, misma versión que Kotlin. No usar el antiguo `kotlinCompilerExtensionVersion` 1.x. |
| Android Gradle Plugin | **8.13.2** | Soporta Kotlin 2.3, SDK hasta 36.1, Gradle mínimo 8.13 y JDK mínimo 17. Evita la migración mayor AGP 9 para H1. |
| Gradle Wrapper | **8.13** | Intersección oficial AGP/KGP; daemon Java 21 soportado desde Gradle 8.5. |
| JDK completo | **Eclipse Temurin 21.0.13+9** | Estable LTS, publicación oficial. Un JRE sin `javac` no basta. Daemon Java 21; bytecode Kotlin/JVM y Java **17** alineados. Android usa D8/desugaring: JDK 21 de compilación no exige Android con Java 21. |
| SDK | plataforma **android-36**, Build Tools **35.0.0** | AGP 8.13 declara BT 35.0.0 como mínimo/predeterminado. Plataforma local preparada por orquestador: revisión **2**. Sin NDK/CMake propios. |
| Compose BOM | **2025.10.01** | POM oficial: UI/runtime/foundation **1.9.4**, Material3 **1.4.0**. Combinación inspeccionada, sin atribuir soporte LTS a estas bibliotecas. |
| Activity Compose | **1.11.0** | AAR requiere compileSdk 36 y AGP 8.9.1; cubiertos por esta matriz. |
| Lifecycle runtime | **2.9.4** | AAR Android inspeccionado; mínimo Android 21. |
| OkHttp Android | **5.4.0** | AAR mínimo Android 21, compileSdk 36 y sin `.so`; su POM usa Okio **3.17.0** y stdlib Kotlin **2.1.21**, consumible con Kotlin 2.3.21. |
| Rust TLS | `rustls` **0.23.46** | MSRV 1.71; `default-features=false`, activar `std`, `tls12`, `ring`. Mantener TLS 1.2 para Android 9; no exigir TLS 1.3. |
| Certificados Rust | `rcgen` **0.14.10** | MSRV 1.88; proveedor ring. ECDSA-P256 según contrato Astra. |
| HTTP / async Rust | `axum` **0.8.9**, `tokio` **1.54.1** | MSRV 1.80 / 1.85. Versiones estables no retiradas; Tokio 1.54.0 está retirado y se descarta. Activar solo funciones utilizadas. |

Estas son dependencias seleccionadas, no archivos de bloqueo ya generados. En H1 fijar dependencias directas, `rust-toolchain.toml`, wrapper con checksum, catálogo de versiones, `Cargo.lock`, lock npm y locks/verificación Gradle. Validar el grafo realmente resuelto antes de afirmar que toda dependencia transitiva es compatible. Las actualizaciones posteriores son cambios separados con evidencia.

Fuentes de versiones consultadas:

- Rust: [anuncios oficiales](https://blog.rust-lang.org/releases/), [manifiesto 1.100.0](https://static.rust-lang.org/dist/channel-rust-1.100.0.toml), [política de soporte](https://rust-lang.org/policies/security/). El manifiesto `stable` observado tenía fecha 2026-11-12 y Rust 1.100.0; no usar ese alias flotante en builds reproducibles.
- [Node 24.22.1 LTS](https://nodejs.org/en/blog/release/v24.22.1/), [paquete TS 6.0.3](https://registry.npmjs.org/typescript/6.0.3), [Tauri tag y versiones internas](https://github.com/tauri-apps/tauri/blob/tauri-v2.12.6/Cargo.toml), [release Tauri 2.12.6](https://github.com/tauri-apps/tauri/releases/tag/tauri-v2.12.6), [API npm 2.12.5](https://registry.npmjs.org/@tauri-apps/api/2.12.5), [CLI npm 2.12.5](https://registry.npmjs.org/@tauri-apps/cli/2.12.5).
- [KGP/Gradle/AGP](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP 8.13.2 y tabla de requisitos](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Gradle/Java](https://docs.gradle.org/current/userguide/compatibility.html), [plugin Compose](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), [Temurin 21.0.13+9](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9).
- [POM Compose BOM 2025.10.01](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom), [POM OkHttp Android 5.4.0](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-android/5.4.0/okhttp-android-5.4.0.pom), [README OkHttp](https://raw.githubusercontent.com/square/okhttp/parent-5.5.1/README.md), [changelog OkHttp](https://raw.githubusercontent.com/square/okhttp/parent-5.5.1/CHANGELOG.md).
- Metadatos de crates publicados: [rustls 0.23.46](https://crates.io/api/v1/crates/rustls/0.23.46), [rcgen 0.14.10](https://crates.io/api/v1/crates/rcgen/0.14.10), [axum 0.8.9](https://crates.io/api/v1/crates/axum/0.8.9), [tokio 1.54.1](https://crates.io/api/v1/crates/tokio/1.54.1). También se leyeron los Cargo.toml de los tags de Axum y Tokio.

### Por qué no elegir automáticamente la última versión

OkHttp **5.5.1** sigue permitiendo instalación en API 21, pero su `aar-metadata.properties` exige **compileSdk 37**. AGP 8.13 soporta hasta 36.1: por ello se selecciona **5.4.0**, no se suprime `checkAarMetadata` ni se fuerza `overrideLibrary`. 5.5.1 añade bloqueo de intermediarias eMudhra comprometidas en almacenes públicos; el contrato H1 usa un almacén cerrado al certificado exacto de Windows, sin CA públicas. Esta justificación no habilita usar 5.4.0 para navegación pública ni omitir validaciones TLS. Antes de ampliar el uso de HTTPS público, revisar dependencias y matriz.

El proveedor `ring` de Rust evita añadir la cadena AWS-LC/CMake al build inicial; sigue siendo una dependencia nativa de **Windows/Linux**, no una biblioteca Android. No incorporar Conscrypt, `okhttp-zstd`, TLS propio por JNI ni un núcleo Rust Android en H1.

## AAR, API 28 y excepción JNI de Compose

Se descargaron AAR oficiales a memoria y se inspeccionaron manifiesto, metadatos y entradas ZIP. Todos los AAR enumerados a continuación declaran un mínimo inferior a API 28. Es evidencia de requisitos publicados; no demuestra ausencia de fallos en ejecución.

| Muestra AAR | minSdk | minCompileSdk / minAGP | JNI observado |
|---|---:|---|---|
| OkHttp Android 5.4.0 | 21 | 36 / 1.0.0 | Ninguno |
| Startup runtime 1.2.0 | 21 | 34 / 1.0.0 | Ninguno |
| Tracing 1.0.0 | 14 | No declarado | Ninguno |
| Compose UI / UI graphics / foundation 1.9.4 | 21 | 35 / 8.6.0 | Ninguno en esos AAR directos |
| Compose runtime 1.9.4 | 21 | 34 / 8.1.1 | Ninguno |
| Material3 Android 1.4.0 | 21 | 35 / 8.6.0 | Ninguno |
| Activity Compose 1.11.0 | 21 | 36 / 8.9.1 | Ninguno |
| Lifecycle runtime Android 2.9.4 | 21 | 34 / 8.1.1 | Ninguno |
| Graphics Path 1.0.1, transitivo de UI graphics | 21 | 34 / 1.0.0 | `libandroidx.graphics.path.so` para **armeabi-v7a, arm64-v8a, x86, x86_64** |

**Decisión revisada por Astra:** conservar Compose y producir APK universal **sin `abiFilters` ni splits por ABI**. Esto sustituye la restricción inicial de cero bibliotecas nativas Android: se permite la dependencia transitiva oficial de Compose inspeccionada, no TLS nativo añadido. No excluir `graphics-path` para aparentar ausencia de JNI: podría romper Compose. La cobertura de cuatro ABIs es provisional; instalación/carga real en el J4+ y el contenido del APK final son criterios pendientes.

Repetir muestras con `python3 scripts/environment/inspect-android-artifacts.py --include-compose`. El script imprime URL, SHA-256, mínimo, metadatos y `.so`; no instala herramientas ni resuelve Gradle. Huellas observadas:

```text
okhttp-android-5.4.0.aar   0e9cbf6c7c373a5f0b0cde7f773185eb69b485faa19f76bcfab3310badbda901
graphics-path-1.0.1.aar   8ca4032b6d79b351f0b59ad4b580eddbb9423e1652f7c958830687f1eee2ec03
```

No se ha auditado todavía cada variante transitiva del grafo Gradle final. Conservar el manifiesto fusionado y ejecutar `dependencies`/`dependencyInsight`, lint e inspección del APK antes de aceptación.

### Verificación del dispositivo real

Con autorización de depuración USB y herramientas Android instaladas, ejecutar en el host al que esté conectado el teléfono:

```sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist32
adb shell getprop ro.product.cpu.abilist64
adb shell getprop ro.product.cpu.abi
adb shell uname -m
```

Esperado: modelo/variante real y API **28**; `abilist` determina ABIs aceptadas, y `abilist64` puede estar vacío aunque el SoC tenga capacidad de 64 bits. `uname -m` solo complementa: no sustituye la lista de ABIs de Android. No fijar `arm64-v8a` a partir de una ficha de CPU.

```sh
apkanalyzer manifest min-sdk apps/android/app/build/outputs/apk/debug/app-debug.apk
apkanalyzer files list apps/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Inspeccionar `lib/<abi>/` en el APK, comprobar que las librerías necesarias cubren una ABI del teléfono, abrir la app y confirmar ausencia de `UnsatisfiedLinkError`. No ejecutar todavía `adb install`: no hubo dispositivo disponible en H0. Debug sirve para validación; distribución exige APK firmado y conservar la misma clave para actualizar.

## Entorno observado y factibilidad

Inventario inicial de este agente: Linux x86_64, Node 24.22.1, npm 11.9.0, **JRE** OpenJDK 21.0.13; faltaban `javac`, `cargo`, `rustc`, `rustup`, Gradle, `adb`, `sdkmanager` en PATH y no había SDK en ubicaciones usuales. Había GCC/cc, make y pkg-config; faltaban CMake y paquetes GTK/WebKitGTK consultados. El agente no instaló nada ni ejecutó operaciones de sistema.

El orquestador preparó posteriormente toolchains locales bajo `/workspace/toolchains`; usar `source /workspace/toolchains/env.sh` y repetir `bash scripts/environment/check-host.sh`. Inventario repetido tras cargar ese archivo: rustup 1.29.3, rustc/Cargo 1.100.0, Temurin java/javac 21.0.13+9, SDK android-36 revisión 2, Build Tools 35.0.0, Command-line Tools 19.0 y Platform Tools 37.0.1 disponibles. No hay Gradle global; se usará wrapper 8.13. Esta preparación permite continuar hacia compilación Rust de lógica y Android en Linux; no convierte Linux en un entorno de validación Windows. Una consulta inicial a `adb --version` abortó al intentar crear `/home/agent/.android` en filesystem de solo lectura; no hubo dispositivo ni instalación. El script final lee metadatos del SDK y no ejecuta ADB/sdkmanager. La compilación real del producto queda para H1 con sus manifest/locks. El inventario es temporal y no una condición permanente del repositorio.

## Comandos y CI recomendados para H1

Los comandos siguientes son objetivos para cuando existan los módulos indicados; **no fueron compilaciones ejecutadas en H0**. Desde raíz:

```sh
# Linux: núcleo Rust sin GUI Windows, según arquitectura H0.
cargo fmt --manifest-path apps/windows/core/Cargo.toml --all -- --check
cargo test --locked --manifest-path apps/windows/core/Cargo.toml
cargo clippy --locked --manifest-path apps/windows/core/Cargo.toml --all-targets -- -D warnings

# Frontend: usar npm ci con package-lock confirmado.
npm --prefix apps/windows ci
npm --prefix apps/windows run build

# Android: wrapper confirmado; JAVA_HOME debe apuntar a JDK completo 21.
cd apps/android
./gradlew --version
./gradlew --no-daemon :app:dependencies --configuration debugRuntimeClasspath
./gradlew --no-daemon :app:checkDebugAarMetadata :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

En runner Windows x64 con Rust **1.100.0 MSVC** y herramientas C++/SDK presentes: ejecutar tests del núcleo, frontend y `npm --prefix apps/windows exec -- tauri build --target x86_64-pc-windows-msvc --no-bundle` desde un directorio/configuración que localice el proyecto Tauri. La ruta exacta dependerá del scaffold H1. Después validar build de instalador NSIS por separado; ningún runner alojado acredita Windows 10 22H2 físico.

Gradle wrapper: distribución `https://services.gradle.org/distributions/gradle-8.13-bin.zip`, `distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` ([checksum oficial consultado](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256)). Versionar los cuatro archivos del wrapper y verificar su JAR. En CI fijar imágenes/acciones, toolchains y locks; no usar `latest` para dependencias de build. Registrar revisiones SDK y contenido de APK; no guardar certificados privados, keystore ni secretos en logs/artefactos.

Prueba emulador API 28, prueba J4+ API 28, prueba Windows 10 22H2, prueba Windows 11 25H2 y medición LAN son evidencias distintas y están **pendientes**. Ejecutar TLS 1.2, archivo íntegro en ambos sentidos y rechazo de certificado/hostname incorrecto según contrato antes de cerrar H1.

## Licencias y límites de la comprobación

Metadatos publicados consultados: Tauri MIT/Apache-2.0; TypeScript y AndroidX Apache-2.0; OkHttp y Okio Apache-2.0; Rust bibliotecas estándar MIT/Apache-2.0; rustls Apache-2.0/ISC/MIT; rcgen MIT/Apache-2.0; axum y Tokio MIT; Node.js MIT más avisos de terceros; Temurin GPLv2 con Classpath Exception. Conservar avisos de todas las dependencias **realmente distribuidas**, incluido ring y librerías transitivas nativas, al preparar distribución. No se realizó auditoría jurídica ni inventario completo del lock final.

Se verificaron URLs y contenido mediante HTTP a sitios oficiales y repositorios/metadatos de publicación. La API Adoptium devolvió **403**; su release oficial sí fue accesible. Dos rutas inicialmente exploradas (`/reference/platform-versions/` de Tauri y `/webview2/concepts/supported-platforms` de Microsoft) devolvieron **404** y se sustituyeron por las fuentes válidas enlazadas arriba. No se usaron esos errores como evidencia de incompatibilidad.
