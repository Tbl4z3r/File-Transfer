# Aplicación Windows

## Desarrollo en Linux

Se necesita Rust según `rust-toolchain.toml` y Node/npm según `package.json`.
En el entorno del proyecto, cargar primero el toolchain:

```sh
source /workspace/toolchains/env.sh
```

Verificar y probar el core Rust, sin dependencias Tauri:

```sh
cargo fmt --manifest-path apps/windows/core/Cargo.toml --check
cargo test --manifest-path apps/windows/core/Cargo.toml --locked
cargo clippy --manifest-path apps/windows/core/Cargo.toml --locked --all-targets -- -D warnings
```

Instalar dependencias del frontend y compilar TypeScript/recursos web:

```sh
cd apps/windows
npm ci
npm run build
```

Si el caché predeterminado de npm no existe en el entorno, se puede indicar uno
temporal: `npm ci --cache=/tmp/h1-windows-npm-cache`.

`npm run build` no compila el shell Tauri. El build del shell y la validación de
la aplicación en Windows requieren su entorno Windows; los resultados Linux no
demuestran compatibilidad Windows.

## Interfaz Enfoque (Windows 0.1.2)

Inicio muestra la transferencia actual, dispositivos emparejados y las acciones **Enviar archivo** y **Recibir**. Elegir un dispositivo selecciona el destino; al enviar se abre el selector nativo y se ofrece el archivo para confirmarlo en Android. **Recibir** activa o desactiva las solicitudes, que siempre se aceptan o rechazan explícitamente.

La barra lateral abre **Actividad**, **Dispositivos** (incluye revocación) y **Ajustes** (dirección y cierre de sesión). La carpeta se elige con el selector nativo al aceptar, o antes con **Elegir**. Se reutiliza para las siguientes aceptaciones de esa sesión y se descarta al cerrarla; cambiarla no mueve una transferencia ya aceptada. No se guarda una preferencia persistente de carpeta.

El tema sigue la preferencia clara/oscura del sistema. Los botones de la barra lateral tienen nombre accesible y ayuda al pasar el cursor o recibir foco.

## Conexión por QR

Tras iniciar la sesión, pulsa **Mostrar QR**. En Android elige **Escanear QR del PC** y apunta al código de esta pantalla. El lector comprueba la identidad del PC usando los datos del QR. Ambos equipos deben estar en la misma red. No hace falta WhatsApp ni un servicio externo: el QR se genera localmente y contiene exactamente el JSON v1 existente. Sigue disponible **Conexión manual → Copiar datos de conexión**.

El código es negro sobre blanco, con corrección M, margen blanco de cuatro módulos y seis píxeles por módulo (414 × 414 px para el fixture actual). Se elimina de la pantalla, junto al JSON, al cerrarlo (incluido Escape), abandonar la página, cerrar sesión o vencer los 120 segundos. Una nueva ventana invalida el token anterior en el servidor. Ocultar solo retira los datos de la pantalla; el token mantiene su vencimiento original. No compartir capturas mientras sea válido.

Para comprobar el frontend y su ciclo de vida: `npm run test:qr`. La suite ampliada `npm run test:ui` incluye estas pruebas de QR y añade comandos de sesión, selector y oferta de archivo, recepción, aceptación/cancelación, revocación, progreso mayor de 4 GiB y nombres tratados como texto. El adaptador DOM/IPC vive únicamente en `scripts/`; no se distribuye como backend ni demuestra ejecución nativa en Windows. Para interoperabilidad con un lector independiente, después de compilar ejecuta `node scripts/export-pairing-qr.mjs /tmp/qr-fixture.gif`: exporta únicamente el fixture público de `protocol/fixtures/h1-vectors.json` y el JSON esperado en `/tmp/qr-fixture.gif.json`.

Dependencia gráfica: `qrcode-generator` **2.0.4**, MIT, fijada en el lock. `build.mjs` copia el ESM de navegador y su licencia a `dist/vendor/`; no se necesita bundler ni conexión de red en tiempo de ejecución. La licencia distribuida se conserva en `src/vendor/qrcode-LICENSE.txt`.
