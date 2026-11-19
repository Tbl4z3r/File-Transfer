# Cola local, drop nativo y progreso de Windows

Rama: `task/cola-windows`, base `1d809b6`. Sin cambios de core ni contrato remoto.

- `Elegir archivos` funciona sin sesión/dispositivo y guarda la selección en espera; `Dejar en espera` también está disponible durante otra transferencia. Lista visible, eliminación por archivo/selección y destino explícito propio para cada selección. Un nuevo vínculo nunca hereda automáticamente un destino anterior.
- Cola en `localStorage` con rutas, nombre y sello tamaño/mtime; se valida acceso/sello antes de ofrecer y también mediante las APIs checked existentes. No se filtran extensiones. Las selecciones inválidas y las ofertas rechazadas antes de crearse conservan sus rutas y elección.
- Drop del Explorador mediante `getCurrentWebviewWindow().onDragDropEvent`, con rutas nativas y conversión de coordenadas físicas por DPI. Drop general siempre encola. Sobre un peer autenticado se ofrece exclusivamente a ese peer/sesión; si está ocupado, queda armado y se ofrece una vez al liberarse. Cambiar/cerrar sesión o perder ese peer desarma la intención automática. Se conserva aceptación del móvil y el flujo existente de retry.
- Antes de autorizar se guarda un journal `offering`. Restaurarlo tras cierre, o recibir un fallo IPC ambiguo, exige comprobar la carpeta remota antes de ofrecer otra vez; nunca reintenta solo. Un fallo al guardar el journal impide autorizar desde la cola. Una selección entregada al flujo de transferencia/retry sale de la cola para evitar duplicados.
- Taskbar Tauri v2 nativo: hilo Rust de 500 ms independiente del sondeo WebView, aplica solo cambios; progreso agregado de manifiestos sin duplicar la transferencia activa, espera indeterminada, máximo 99% hasta verificación y limpieza en terminales/cierre de sesión/ventana. El worker se detiene al destruir la ventana.
- Picker y drop comparten inspección de rutas en Rust, punto de extensión real para futura selección de carpetas.

## Validación ejecutada

- `npm ci --cache /workspace/toolchains/npm-cache --ignore-scripts`: correcto.
- `npm --prefix apps/windows run test:ui`: build TypeScript + **72 pruebas correctas** (61 existentes y 11 de cola/drop/persistencia/consentimiento).
- `source /workspace/toolchains/env.sh; apps/windows/scripts/test-taskbar.sh`: **2 pruebas Rust correctas** sobre el cálculo real de snapshots, >4 GiB, archivo vacío, espera, verificación, terminales y cierre.
- `cargo fmt --manifest-path apps/windows/src-tauri/Cargo.toml` y `git diff --check`: correctos.
- `cargo test --manifest-path apps/windows/src-tauri/Cargo.toml --locked --offline`: bloqueado por ausencia de `glib-2.0.pc` en Linux antes de compilar el shell. No se afirma compilación nativa Windows ni ejecución real Explorer/WebView2/taskbar.

## Validación nativa pendiente

En Windows 10/11: dejar dos archivos (incluido uno sin extensión) sin sesión, reiniciar y conectar dos móviles; la cola debe permanecer sin destino seleccionado. Elegir el segundo y enviar: solo él recibe la solicitud. Eliminar un archivo pendiente y cambiar otro en disco: el primero desaparece y el segundo conserva la selección con aviso. Arrastrar desde Explorer a zona general, a cada peer y a un peer ocupado; comprobar DPI 100/150/200%, destino correcto, un único envío y aceptación en móvil. Cerrar sesión antes de que el peer ocupado quede libre impide el envío. Minimizar durante una transferencia: la taskbar continúa y se limpia al terminar/cancelar/cerrar sesión.

La cola guarda referencias, no copias de archivos; no implementa reanudación de cuerpos ni recuperación del flujo autorizado después de cerrar. El retry existente mantiene sus límites y consentimiento.
