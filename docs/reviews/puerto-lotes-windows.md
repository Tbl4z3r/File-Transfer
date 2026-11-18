# Puerto automático y lotes Windows 0.1.3

Rama `task/puerto-lotes-windows`, base `5a89f3d`. Implementación para revisión del orquestador; no publicada ni integrada por el autor.

## Comportamiento

- El escritorio solo solicita IPv4. Enlaza directamente 51337; ante `AddrInUse` o `PermissionDenied` pide puerto 0 al sistema sobre la misma IPv4. Conserva el listener y anuncia su `local_addr().port()` real en Ajustes, SessionInfo y descriptor QR/manual. Se mantienen validación IPv4/puerto real, TLS e identidad persistente. El método core/CLI de puerto explícito conserva su comportamiento y no activa fallback.
- **Enviar archivos** usa `blocking_pick_files`. La cola efímera retiene sesión y destinatario originales, ofrece un archivo v1 cada vez y solo avanza con `completed`. No envía aceptaciones automáticas. El core comprueba el `session_id` efímero bajo el mismo lock de creación de oferta; ese campo es IPC local y no entra en el descriptor ni contrato remoto.
- Inicio muestra «Archivo i de n», pendientes y destinatario. **Cancelar lote** cancela el actual y descarta pendientes, incluso entre archivos o con una oferta IPC todavía pendiente. La respuesta tardía se cancela antes de permitir otro lote. Rechazo, fallo, cancelación individual, revocación o cierre/reemplazo de sesión detienen el resto.
- Una revisión de ofertas evita aplicar snapshots pedidos antes de crearse la oferta. El avance se decide exclusivamente desde snapshots frescos, también cuando una acción de recepción coincide con un refresh en vuelo.
- Versión del escritorio 0.1.3 en package.json, package-lock, Cargo.toml/lock de Tauri y tauri.conf.json. La biblioteca core mantiene su versión independiente. README actualizado en español.

## Verificación ejecutada en Linux

- `npm ci --cache /tmp/ft-windows-npm-cache`: correcto (el primer intento con caché predeterminado falló porque `/home/agent/.npm` no es escribible).
- `npm run test:ui`: **33/33**, compilación TypeScript incluida. Regresiones QR existentes, picker múltiple/cancelado, cola serial y destinatario fijo, avance solo tras verificación, rechazo/fallo, cancelación activa/en hueco/IPC tardío, stop/revocación/reemplazo de sesión y carrera snapshot/Recibir.
- `source /workspace/toolchains/env.sh`; `CARGO_TARGET_DIR=/tmp/ft-windows-lotes-target cargo test --locked --manifest-path apps/windows/core/Cargo.toml`: **15/15** (2 fixtures y 13 TLS). Los tres tests nuevos prueban listener preferido ocupado retenido durante fallback, TLS real sobre el puerto anunciado, retorno a 51337 al liberarlo, validaciones y rechazo de una sesión antigua tras reabrir la misma dirección.
- `cargo clippy --locked --manifest-path apps/windows/core/Cargo.toml --all-targets -- -D warnings` con el mismo target temporal: correcto.
- `cargo fmt --manifest-path apps/windows/core/Cargo.toml --check` y equivalente Tauri: correctos.
- `python3 protocol/validate_fixtures.py`: correcto (41 vectores escalares/hash y 13 mensajes).
- `git diff --check`: correcto.

## Límites y prueba pendiente

`cargo check --locked --manifest-path apps/windows/src-tauri/Cargo.toml` en Linux quedó bloqueado por dependencia nativa ausente `glib-2.0 >= 2.70` (`glib-sys`/pkg-config). No acredita compilación del shell. La API `blocking_pick_files` pertenece a la dependencia ya fijada; la compilación Windows debe comprobarla en CI.

No se ejecutaron Windows, J4+, Redmi, selector nativo, reservas WSAEACCES ni mediciones LAN. No hay persistencia de lotes, streams paralelos, reanudación, cambios de protocolo ni modificaciones del firewall.

Prueba manual propuesta: mantener 51337 ocupado y abrir sesión; comprobar puerto alternativo en Ajustes y QR y conectar Android. Repetir liberando el puerto: debe usar 51337. Elegir tres archivos, confirmar cada uno en Android, observar i/n; rechazar el segundo o pulsar **Cancelar lote**, revocar o cerrar sesión y comprobar que no se ofrece el tercero. Repetir con fuente eliminada y red interrumpida; los pendientes deben detenerse y los archivos ya verificados conservarse.
