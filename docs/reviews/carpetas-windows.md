# Carpetas en Windows

Rama `task/carpetas-windows`, base `04ab6b1` (cola local). Implementa el contrato aprobado `protocol/FOLDER-BATCHES.md` del worktree de integración; no modifica Android ni el contrato compartido.

## Comportamiento

- El vínculo registra `X-FT-Folders: v1` junto con la bandeja y expone `supports_folders`. `GET /v1/folder-support` exige autenticación. No cambia la respuesta de emparejamiento ni el formato de los lotes planos.
- Los manifiestos admiten directorios y rutas relativas. Se validan componentes v1, 512 bytes, 16 niveles, padres con grafía idéntica, colisiones al convertir a minúsculas, 64 archivos/directorios y 16 KiB del Inbox completo antes de reservar una oferta. Los campos opcionales presentes con `null`, directorios vacíos y rutas inválidas se rechazan.
- Recorrido local en core compartido por shell y harness: incluye carpetas vacías y nombres sin extensión, rechaza symlinks/reparse points y archivos especiales, acota cada `read_dir` al presupuesto antes de ordenar. La selección guarda rutas y sellos; al enviar valida esa selección exacta sin añadir archivos aparecidos posteriormente.
- La recepción crea raíces nuevas numeradas mediante `create_dir`, asigna cada índice a una subcarpeta recién preparada y publica únicamente tras SHA-256. La búsqueda de sufijos está acotada; una preparación fallida intenta eliminar solo sus directorios nuevos vacíos en orden inverso. Los enlaces se revalidan al iniciar y publicar. Las carpetas sin archivos permanecen pendientes hasta la aceptación y se completan al crearse.
- `Elegir carpeta`, drop nativo mixto y cola persistente sin sesión. La cola muestra rutas, carpetas vacías y recuentos. El destino sigue siendo explícito; un móvil antiguo queda deshabilitado para esa selección con explicación visible, sin degradar el árbol a archivos sueltos. Un drop sobre peer conserva su intención para ese vínculo/sesión.
- El detalle de lote muestra directorios y rutas completas, también con cero archivos. El retry conserva estructura y sellos, omite verificados, solicita destino/consentimiento nuevos y avisa de que creará una raíz nueva. Las carpetas vacías con resultado incierto exigen comprobar el destino antes de ofrecerlas otra vez.
- El comando stdin local del harness es `offer_folder_batch` con `peer_id`, `sources` (rutas raíz) y `session_id`; llama al mismo recorrido/oferta del shell. No hay administración HTTP nueva.

## Validación ejecutada

- `npm ci --prefix apps/windows --cache /workspace/toolchains/npm-cache --ignore-scripts`: correcto.
- `npm --prefix apps/windows run test:ui`: build TypeScript + **78 pruebas correctas**; seis escenarios nuevos de carpetas.
- `CARGO_TARGET_DIR=/tmp/ft-windows-lotes-target CARGO_INCREMENTAL=0 cargo test --manifest-path apps/windows/core/Cargo.toml --locked --offline`: **6 unitarias, 2 fixtures y 33 TLS correctas**. Se añadió después una prueba TLS enfocada de sustitución del árbol fuente por symlink, ejecutada por separado y correcta (42 pruebas Rust totales sobre esta revisión).
- `cargo clippy --manifest-path apps/windows/core/Cargo.toml --locked --offline --all-targets -- -D warnings`: correcto, usando el mismo target compartido.
- `apps/windows/scripts/test-taskbar.sh`: dos pruebas Rust headless correctas sobre el cálculo existente de taskbar con el nuevo core.
- `cargo fmt` para core/shell y `git diff --check`: correctos.

La suite incluye TLS real y autorización en ambos sentidos, selección/IPC roundtrip con sellos y rutas, árboles vacíos, colisión de raíz, validación maliciosa, límites de tamaño/niveles/elementos, no reserva en oferta incompatible/grande, rechazo de symlinks antes de leer/publicar, limpieza parcial y regresiones de lotes planos.

## Límites y validación pendiente

No se ejecutó Windows/WebView2/Explorer ni hardware Android. La compilación y UI nativas dependen del job Windows; la integración incorpora por separado la corrección de acceso estable a la ventana de Tauri del worker taskbar. No se afirma compilación completa del shell en este Linux sin GTK.

Los controles de enlaces/reparse comprueban rutas antes de operar; no aíslan frente a otro proceso local con escritura que sustituya directorios entre syscalls o por otros directorios normales. No se añadió FFI ni dependencia de identidad de directorios, según la decisión del orquestador. El remitente remoto no obtiene rutas del PC ni puede seleccionar rutas absolutas.

Pendiente en Windows: seleccionar/arrastrar árbol con archivos y carpetas vacías, DPI 100/150/200%, probar junctions, origen/destino inaccesibles, raíces ya existentes, recepción y retry hacia dos móviles, minimizar y verificar taskbar. El retry parcial crea una raíz nueva y puede repetir carpetas vacías; conserva los archivos verificados en la raíz anterior y no los vuelve a ofrecer.
