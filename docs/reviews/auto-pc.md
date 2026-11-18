# AUTO-PC — inicio automático Windows

## Entrega

Rama `task/auto-pc`, base `56627b5`, responsable implementador Windows. Versión escritorio 0.1.6. Sin cambios al contrato compartido ni al motor de transferencias.

**Iniciar sesión** funciona sin escribir IP ni puerto. Enumera interfaces mediante `GetAdaptersAddresses`/`GetIfEntry2` de Windows, sin PowerShell, procesos externos ni tráfico de sondeo. Descarta interfaces desconectadas, loopback y túneles, direcciones no preferidas, link-local y no unicast. Ordena físicas antes de virtuales/VPN, luego puerta de enlace y métrica IPv4. Intenta el siguiente candidato si no se puede enlazar. Conserva la selección de puerto existente: 51337 o puerto del SO. El QR sale del descriptor del socket realmente enlazado.

El QR se abre automáticamente una vez aplicado el snapshot de la nueva sesión. Se espera cualquier snapshot anterior para evitar que retire el QR nuevo. **Mostrar QR** permite regenerarlo. Un error de emparejamiento/QR no cierra el listener. Alternativa **Elegir otra conexión** plegada, error recuperable sin red activa. Fondo con gradiente suave y paleta más sobria en claro/oscuro. README actualizado.

## Verificaciones ejecutadas

- `npm ci --prefix apps/windows --ignore-scripts --cache /workspace/toolchains/npm-cache`: correcto, lock preservado.
- `npm run test:ui --prefix apps/windows`: compilación TypeScript/frontend y **53/53 pruebas** correctas. Incluye inicio sin IP, QR automático, fallo de ventana que conserva sesión y snapshot anterior al inicio.
- `rustc --test apps/windows/src-tauri/src/network.rs -o /tmp/auto-pc-network-test && /tmp/auto-pc-network-test`: **1/1** correcta, ranking y descarte de direcciones inválidas.
- Copia exacta de `network.rs` en crate temporal con `windows-sys =0.61.2` y mismas features: `cargo check --manifest-path /tmp/auto-pc-native-check/Cargo.toml --target x86_64-pc-windows-msvc --tests --offline`: correcto. Compila API nativa y pruebas para Windows; no ejecuta Windows.
- `cargo fmt --manifest-path apps/windows/src-tauri/Cargo.toml` y `git diff --check`: correctos.
- `cargo check --manifest-path apps/windows/src-tauri/Cargo.toml --locked` en Linux: bloqueado por `glib-2.0.pc` ausente, dependencia Tauri del entorno. No es evidencia de compilación nativa Windows.
- CI Windows ampliado con `cargo test ... src-tauri ... network::tests`; **pendiente ejecutar remotamente**.

## Límites y prueba manual pendiente

No se ejecutó Windows real, WebView2, instalador ni móvil físico. No se midió latencia de arranque. La compilación cruzada del módulo no sustituye compilar la aplicación Windows completa.

Con varias LAN activas no se puede deducir cuál comparte el móvil. Se prioriza red física, y puede elegirse otra IPv4 manualmente. Las redes de invitados, aislamiento y firewall pueden impedir conexión aunque el socket esté abierto. No se modifica firewall.

En Windows: iniciar con Wi-Fi/Ethernet → esperar QR inmediato sin campos obligatorios → escanear y transferir. Repetir con puerto 51337 ocupado, dos interfaces físicas, VPN activa, interfaz desconectada y sin red. Comprobar que Ajustes/QR anuncian la dirección real, que offline muestra ayuda y que el fallback manual permite elegir otra LAN.
