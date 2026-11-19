# Revisión de implementación H1 Windows

## Alcance implementado

Core Rust desacoplado de Tauri con identidad y pares persistentes, servicio TLS,
emparejamiento temporal, autorización de recepción, envío/recepción en stream,
hash SHA-256, cancelación, revocación, estados y eliminación de parciales en
fallo. UI local TypeScript/Tauri para iniciar sesión, emparejar y gestionar
transferencias. Los vectores normativos compartidos tienen pruebas.

## Verificaciones ejecutadas en Linux

- `cargo fmt --manifest-path apps/windows/core/Cargo.toml --check`: correcto.
- `cargo test --manifest-path apps/windows/core/Cargo.toml --locked`: correcto,
  12 pruebas (2 fixtures y 10 TLS/transferencia).
- `cargo clippy --manifest-path apps/windows/core/Cargo.toml --locked --all-targets -- -D warnings`:
  correcto.
- `npm ci --cache=/tmp/h1-windows-npm-cache` y `npm run build` en
  `apps/windows`: correctos. `npm ci` sin caché explícito no pudo inicializar
  `/home/agent/.npm`; el reintento con caché temporal terminó correctamente.
- La prueba de expiración ahora espera observar el estado fallido mediante
  consultas acotadas en vez de depender de una pausa fija.

## Límites y pendientes

- No se compiló el shell Tauri ni se probó en Windows; este Linux no acredita
  compatibilidad con Windows 10/11.
- Falta la prueba de transferencia real con Android y la validación en hardware.
- Reanudación tras interrupción o reinicio y selección múltiple de archivos no
  forman parte de esta entrega H1 acotada; el estado actual no debe presentarse
  como reanudable.
