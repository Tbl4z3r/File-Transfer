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

## Interfaz Enfoque (Windows 0.1.3)

Inicio muestra la transferencia actual, dispositivos emparejados y las acciones **Enviar archivos** y **Recibir**. Elegir un dispositivo selecciona el destino; al enviar se abre el selector nativo con selección múltiple. Se conserva el dispositivo y la sesión de esa selección durante todo el lote, aunque se seleccione otro dispositivo en pantalla. Se ofrece un archivo cada vez y cada uno requiere confirmación explícita en Android. El siguiente se ofrece solo después de recibir el estado verificado `completed` del anterior. **Recibir** activa o desactiva las solicitudes, que siempre se aceptan o rechazan explícitamente.

El lote muestra **Archivo i de n** y el número de pendientes. **Cancelar lote** descarta los pendientes y cancela el archivo activo; también funciona entre archivos o mientras llega la respuesta de una oferta. Rechazo, fallo, cancelación, revocación y cierre o reemplazo de sesión detienen el resto del lote. Los archivos ya completados se conservan. La cola vive únicamente en memoria; no hay streams paralelos, reanudación ni recuperación tras cerrar la app.

La barra lateral abre **Actividad**, **Dispositivos** (incluye revocación) y **Ajustes** (dirección y cierre de sesión). La carpeta se elige con el selector nativo al aceptar, o antes con **Elegir**. Se reutiliza para las siguientes aceptaciones de esa sesión y se descarta al cerrarla; cambiarla no mueve una transferencia ya aceptada. No se guarda una preferencia persistente de carpeta.

El tema oscuro en verde petróleo y menta se mantiene con cualquier preferencia del sistema. La interfaz muestra tamaños y velocidades con unidades decimales (1 MB = 1 000 000 bytes). Los botones de la barra lateral tienen nombre accesible y ayuda al pasar el cursor o recibir foco.

## Reintentar transferencias (Windows 0.1.7)

**Reintentar** vuelve a ofrecer los archivos pendientes del último envío mientras la ventana esté abierta. Si el móvil se desconectó, abre **Mostrar QR**, empareja el móvil y vuelve a **Reintentar** para elegir el destino. El nuevo envío empieza desde el principio y necesita aceptación en el móvil. Se omiten los archivos verificados y los orígenes que ya no están disponibles o cambiaron de tamaño o fecha de modificación. Si la entrega de un archivo quedó sin confirmación, comprueba la carpeta del móvil antes de autorizar que se ofrezca otra vez.

Al recibir, **Reintentar recepción** habilita las solicitudes y abre el QR si hace falta emparejar el móvil. El móvil vuelve a enviar sus archivos pendientes y el usuario del PC acepta la solicitud nueva.

## Puerto automático

**Iniciar sesión** elige automáticamente una IPv4 activa mediante la API nativa de Windows, sin procesos externos ni sondeos. Prioriza adaptadores físicos, luego los que tienen puerta de enlace y menor métrica; descarta loopback, túneles y direcciones link-local o no utilizables. Si el enlace falla por un cambio de red intenta los demás candidatos. Con varias LAN no se puede saber cuál comparte el móvil: **Elegir otra conexión** permite indicar manualmente la IPv4 del PC. Sin red activa muestra un mensaje recuperable. El escritorio intenta enlazar directamente el puerto **51337** para conservar las conexiones existentes; si está ocupado o reservado, pide al sistema otro puerto en esa misma IPv4. No realiza sondeos ni abre el firewall. **Ajustes** muestra la dirección y el puerto reales, y el QR y los datos manuales anuncian ese mismo puerto. Si cambia, actualiza los datos de conexión en Android. La identidad TLS persistente se conserva. Un error al enlazar también el puerto alternativo deja la sesión cerrada y muestra un error recuperable. El core y la CLI conservan la opción de puerto explícito.

## Conexión por QR

Al iniciar la sesión aparece el QR automáticamente. **Mostrar QR** permite renovarlo. Un fallo al generar el QR conserva la sesión abierta y permite reintentarlo. En Android elige **Escanear QR del PC** y apunta al código de esta pantalla. El lector comprueba la identidad del PC usando los datos del QR. Ambos equipos deben estar en la misma red. No hace falta WhatsApp ni un servicio externo: el QR se genera localmente y contiene exactamente el JSON v1 existente. Sigue disponible **Conexión manual → Copiar datos de conexión**.

El código es negro sobre blanco, con corrección M, margen blanco de cuatro módulos y seis píxeles por módulo (414 × 414 px para el fixture actual). Se elimina de la pantalla, junto al JSON, al cerrarlo (incluido Escape), abandonar la página, cerrar sesión o vencer los 120 segundos. Una nueva ventana invalida el token anterior en el servidor. Ocultar solo retira los datos de la pantalla; el token mantiene su vencimiento original. No compartir capturas mientras sea válido.

Para comprobar el frontend y su ciclo de vida: `npm run test:qr`. La suite ampliada `npm run test:ui` incluye estas pruebas de QR y añade comandos de sesión, selector múltiple, secuencia del lote, cancelación incluso entre archivos y con IPC diferido, vinculación a sesión/dispositivo, recepción, aceptación/cancelación, revocación, progreso mayor de 4 GB y nombres tratados como texto. El adaptador DOM/IPC vive únicamente en `scripts/`; no se distribuye como backend ni demuestra ejecución nativa en Windows. Para interoperabilidad con un lector independiente, después de compilar ejecuta `node scripts/export-pairing-qr.mjs /tmp/qr-fixture.gif`: exporta únicamente el fixture público de `protocol/fixtures/h1-vectors.json` y el JSON esperado en `/tmp/qr-fixture.gif.json`.

Dependencia gráfica: `qrcode-generator` **2.0.4**, MIT, fijada en el lock. `build.mjs` copia el ESM de navegador y su licencia a `dist/vendor/`; no se necesita bundler ni conexión de red en tiempo de ejecución. La licencia distribuida se conserva en `src/vendor/qrcode-LICENSE.txt`.
