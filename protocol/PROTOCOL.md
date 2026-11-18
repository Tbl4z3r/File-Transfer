# File Transfer — contrato H1 aprobado, versión 1

Estado: **aprobado por Astra para implementar H1**. Cambios incompatibles requieren revisión y otra versión. Esta aprobación no declara H1 ejecutado en hardware. Transporte HTTP/1.1 sobre TLS; JSON UTF-8. Windows aloja el servidor; Android inicia todas las conexiones, tanto al enviar como al recibir archivos.

## 1. Alcance y modos

H1: conexión manual IPv4, emparejamiento verificable, un archivo por transferencia, envío en ambos sentidos, escritura/lectura progresivas, hash SHA-256, autorización explícita y revocación. Sin mDNS, reanudación, recepción automática ni transferencias en segundo plano garantizadas. El QR gráfico de este descriptor v1 se anticipa de H2 por petición del usuario para probar el J4+; no cambia el contrato remoto. Varias transferencias pueden crearse secuencialmente; solo un cuerpo de archivo activo por servidor en H1.

Windows mantiene el listener durante una sesión que el usuario activa explícitamente para enviar o recibir. `receive_enabled` autoriza solicitar subidas; se inicia en `false`. Para descargar, Windows selecciona un archivo y un dispositivo emparejado y crea una oferta local dirigida exclusivamente a ese dispositivo. Cerrar la sesión cancela transferencias pendientes/activas, destruye la ventana de emparejamiento y detiene el listener. No se abre el firewall silenciosamente.

## 2. TLS y confianza inicial

- TLS 1.2 mínimo (1.3 cuando ambos extremos lo soportan), bibliotecas mantenidas; sin HTTP plano ni fallback. Servidor ECDSA P-256, certificado autofirmado X.509 persistente con SAN DNS `file-transfer.local`, EKU serverAuth y vigencia válida. Clave generada mediante CSPRNG, guardada en almacenamiento privado de usuario. El certificado no cambia por reinicio/IP; renovación exige nuevo emparejamiento H1.
- La URL HTTPS usa **siempre** `file-transfer.local` como host TLS/HTTP. Android configura el resolvedor de su cliente dedicado para dirigir exclusivamente ese nombre al IPv4 y puerto introducidos. No cambiar el verificador de hostname, no redirecciones ni proxies HTTP, no cookies, no reutilizar este cliente para Internet.
- Antes de conectar por primera vez, Windows muestra localmente IP, puerto, fingerprint SHA-256 de los **bytes DER completos del certificado leaf** y token temporal. Android recibe estos datos por el QR leído de esa pantalla o mediante transcripción/copia por un canal físicamente verificado. No aceptar un fingerprint recuperado de la propia red sin verificación fuera de banda. El fingerprint tiene 64 hex minúsculas; la UI puede agruparlo visualmente, pero el parser normaliza espacios y mayúsculas antes de validar. No sustituirlo por un PIN corto.
- Android comprueba en el handshake que SHA-256(leaf DER) coincide, y utiliza ese certificado exacto como trust anchor de un TrustManager/validador PKIX estándar; valida explícitamente fechas del leaf y hostname estándar. La comparación de pin **no** reemplaza la verificación criptográfica TLS ni permite certificados diferentes. Un TrustManager personalizado solo puede delegar a esa validación después de comprobar el pin; nunca debe aceptar cualquier certificado. Si la biblioteca necesita observar primero el chain, la comparación y validación ocurren dentro de `checkServerTrusted`, antes de enviar HTTP, credenciales o datos.
- El `server_device_id` esperado se contrasta en la respuesta TLS autenticada de emparejamiento y se guarda junto al pin. Cambios de IP mantienen el pin. Pin distinto, certificado expirado o identidad distinta bloquean y requieren un nuevo emparejamiento iniciado por el usuario; nunca TOFU silencioso.
- El servidor tiene identidad criptográfica asimétrica persistente. Android tiene un `device_id` aleatorio persistente y un secreto criptográfico de autenticación independiente por servidor. El ID no autentica. Este secreto de 256 bits, transmitido exclusivamente dentro de TLS autenticado y almacenado privadamente, es su credencial persistente revocable. H1 no usa certificados cliente/mTLS. Véase ADR 001.

### Descriptor fuera de banda

Objeto JSON exacto (ejemplo sintético, no credenciales utilizables):

```json
{"v":1,"host":"192.168.1.20","port":51337,"server_device_id":"11111111-1111-4111-8111-111111111111","cert_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pairing_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
```

`host`: IPv4 literal unicast introducido explícitamente, no URL ni nombre DNS; puerto entero 1024–65535. `v` entero exactamente 1. Fingerprint y token no se obtienen de mDNS. Token CSPRNG de 32 bytes, base64url sin padding (43 caracteres), válido 120 segundos desde generación según reloj monotónico del servidor, de un solo uso exitoso; otra ventana invalida la anterior. Máximo cinco intentos incorrectos por ventana; después se cierra. El usuario conserva control físico del descriptor, que permite emparejar durante su vigencia. No registrarlo. No usar el token temporal como credencial persistente.

## 3. Convenciones exactas

- UUID: formato canónico minúsculo UUIDv4, generado aleatoriamente. IDs de dispositivo y transferencia son distintos. El cliente crea y persiste su ID; Windows crea los IDs de transferencia y su propio ID.
- `size_bytes`, `bytes_transferred`: cadenas decimales ASCII canónicas (`"0"` o `[1-9][0-9]*`); máximo H1 **1099511627776** (1 TiB), incluyendo archivos >4 GiB. Se procesan como enteros de 64 bits, nunca float ni int32. `Content-Length` usa el entero decimal HTTP de la misma cantidad. Contadores locales usan 64 bits.
- Hash: SHA-256 completo de los bytes del archivo, hex minúsculas, 64 caracteres. Hash de archivo vacío: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- `device_name`: 1–64 caracteres Unicode, sin controles C0/DEL. Solo etiqueta visible, nunca autoridad.
- `file_name`: 1–180 **bytes UTF-8**, nombre único sin ruta; rechazar `/`, `\\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, C0/DEL, `.` y `..`, espacio/punto final, y nombres Windows reservados (base antes del primer punto: CON, PRN, AUX, NUL, COM1–COM9, LPT1–LPT9; sin distinguir mayúsculas). Rechazar también COM¹/²/³ y LPT¹/²/³. No interpretar escapes, unidades, rutas ni nombres de metadatos como destino. La UI puede proponer un nombre válido antes de enviar. No MIME obligatorio.
- JSON de solicitud máximo 16 KiB; rechazar claves duplicadas, campos adicionales, `null`, tipos incorrectos y UTF-8 inválido. Respuestas JSON también ≤16 KiB. Content-Type `application/json` para JSON; archivo `application/octet-stream`. API sin compresión, multipart, rangos ni trailers. No aceptar Content-Encoding distinto de identity ni cuerpos de archivo sin longitud exacta. Error 411 si falta Content-Length, 413 si supera límite y 400 si contradice metadata.
- Solo rutas `/v1/...`; versión desconocida → 404 `unsupported_version`. Métodos incorrectos → 405 `method_not_allowed`. No inferir compatibilidad de la versión de la aplicación.
- Requests autenticadas llevan `Authorization: Bearer <credential>` y `X-Device-Id: <device_id>`. Credencial: 32 bytes aleatorios base64url sin padding. Comparar en tiempo constante o comparar hashes; Windows almacena hash SHA-256 de credencial, no necesita el secreto original. Android guarda el secreto en almacenamiento privado (preferible cifrado por Android Keystore); no logs/backups/exportación. Windows guarda registros de emparejamiento y clave con acceso del usuario actual. Sin credencial válida → 401 `unauthorized`.
- Todos los errores JSON tienen exactamente `{"error":{"code":"...","message":"..."}}`; `message` texto informativo sin secretos ni rutas locales, ≤256 caracteres. Los clientes deciden por `code`, no por el texto. Cabeceras `Cache-Control: no-store` en todas las respuestas. No filtrar si existe un ID ajeno: 404 `not_found`.

## 4. Emparejamiento y revocación

`POST /v1/pair` es la única ruta sin bearer (TLS/pin sigue obligatorio). Solicitud exacta:

```json
{"pairing_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","device_id":"22222222-2222-4222-8222-222222222222","device_name":"Android personal"}
```

Con ventana válida consume el token atómicamente, genera credencial **distinta** y persiste registro. `201`:

```json
{"protocol_version":1,"server_device_id":"11111111-1111-4111-8111-111111111111","server_name":"PC personal","credential":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"}
```

Token incorrecto/expirado/consumido/ventana cerrada → 401 `pairing_denied`, sin revelar la causa remota. Un `device_id` ya emparejado → 409 `already_paired`; el usuario debe revocarlo localmente antes de volver a emparejar. El token no se consume en ese 409. Tras perder la respuesta de creación, revocar localmente y crear otro descriptor. No repetir automáticamente el POST.

Windows lista y revoca dispositivos mediante acción **local**, no endpoint administrativo remoto. Revocar elimina la credencial, cancela sus transferencias y cierra sus streams en curso antes de procesar más datos; no basta comprobar bearer al abrir. Android «olvidar» elimina pin/credencial local e informa que Windows puede revocar también. No hay recepción automática H1; pairing autoriza autenticarse, no recibir archivos sin consentimiento.

## 5. API de transferencias

Todas las rutas siguientes requieren autenticación. `direction` es `upload` (Android → Windows) o `download` (Windows → Android), desde la perspectiva de Android. Cada transferencia pertenece a un único dispositivo Android; su ID jamás permite acceso de otro dispositivo emparejado.

### Estado común `Transfer`

Todas estas claves son obligatorias excepto `sha256` y `error_code`, que **se omiten** si no aplican:

```json
{"transfer_id":"33333333-3333-4333-8333-333333333333","direction":"upload","file_name":"prueba.txt","size_bytes":"3","state":"awaiting_acceptance","bytes_transferred":"0"}
```

`bytes_transferred`: cantidad leída/escrita por Windows, monotónica, 0..size; para download es bytes entregados al stream HTTP, no confirmación de escritura remota. `sha256` aparece únicamente después de leer/escribir **exactamente** size bytes, en `awaiting_verification` y `completed`; no es una promesa sobre fuentes que aún no se han leído. `error_code` obligatorio en `failed`, opcionalmente `cancelled` (`cancelled`/`revoked`/`session_closed`). El contador no implica integridad ni finalización.

1. `POST /v1/transfers` — solo upload. Solicitud `{"file_name":"prueba.txt","size_bytes":"3"}`. Exige modo recibir de Windows activo, límite de registros y sin otra transferencia no terminal. Crea ID y solicitud para autorización visible en Windows. Devuelve `201` Transfer `awaiting_acceptance`. Modo inactivo → 409 `receive_disabled`; ocupado → 409 `busy`. Windows decide localmente aceptar (`ready`) o rechazar (`cancelled`, error_code `cancelled`), después de elegir destino y comprobar acceso/espacio.
2. Oferta download — Windows crea localmente Transfer `awaiting_acceptance` con archivo seleccionado y propietario emparejado, una sola oferta pendiente/activa global. No existe endpoint para seleccionar rutas en Windows. Android consulta `GET /v1/offers`; `200` `{"transfers":[...]}` con 0 o 1 Transfer **download en awaiting_acceptance** del propietario. Requiere recepción activa en Android para consultar/mostrar; no aceptar automáticamente.
3. `POST /v1/transfers/{id}/accept` — solo download, solicitud `{}`. Android ha confirmado recepción y reservado destino local. Transición awaiting_acceptance → ready; `200` Transfer. Repetición en ready devuelve igual. Upload → 409 `invalid_state`.
4. `GET /v1/transfers/{id}` — `200` Transfer; consulta de estado. Durante espera/progreso puede sondearse como máximo 2 veces/s. El progreso local del cuerpo no necesita sondeos por bloque.
5. `PUT /v1/transfers/{id}/content` — solo upload ready. Cuerpo binario, longitud exacta. Windows reclama estado atómicamente, crea temporal exclusivo, escribe progresivamente y calcula SHA-256; tamaño corto, extra, error IO o desconexión → failed. Tras flush y verificación de tamaño, `200` Transfer awaiting_verification con hash. Aún no publicar archivo final. Una segunda operación de content, incluso concurrente, → 409 `invalid_state`.
6. `GET /v1/transfers/{id}/content` — solo download ready. Windows abre la fuente seleccionada, comprueba tamaño disponible y pasa a streaming; `200`, Content-Type octet-stream y Content-Length exacto. Lee secuencialmente calculando SHA-256. Al terminar pasa a awaiting_verification con hash. Android escribe a temporal y calcula su propio hash. Ante error después de iniciar respuesta, abortar cuerpo y marcar failed; no introducir JSON en el archivo. Android consulta estado hasta awaiting_verification (si hay carrera al finalizar), con timeout de 30 s. No admite Range. En H1 una caída requiere nueva transferencia desde byte cero.
7. `POST /v1/transfers/{id}/complete` — solicitud `{"sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}`. Solo awaiting_verification; servidor compara digest propio con enviado. Upload: el hash viene del stream emisor Android; si coincide Windows publica archivo final sin sobrescribir, después state completed. Download: Android compara primero hash local con el estado autenticado del servidor, publica su archivo local y **después** llama complete; Windows compara digest y marca completed. `200` Transfer completed. Repetir complete en completed con mismo hash devuelve completed; distinto →409 `integrity_mismatch` sin alterar el archivo ya completado. Mismatch en awaiting_verification → failed, elimina temporal Windows si upload, responde 409 `integrity_mismatch`. Android elimina el suyo en caso de mismatch. No anunciar éxito con solo HTTP 200/content o contador igual a tamaño.
8. `POST /v1/transfers/{id}/cancel` — solicitud `{}`. Cancela pending/ready/streaming/awaiting_verification y señala token de cancelación al stream, limpia temporal del receptor; `200` Transfer cancelled. Repetida en cancelled es idempotente; completed/failed →409 `invalid_state`. Cancelación local Windows usa la misma transición. Android al fallar escritura local cancela y registra causa local; nunca publica parcial.

El servidor conserva hasta 32 registros terminales en memoria por sesión (evictar el más antiguo al necesitar espacio), y un único no terminal; GET de registro evictado →404. El cliente no repite automáticamente POST create/PUT/GET content. Puede repetir consultas y complete idempotente. Desconexión durante complete: consultar estado; si sigue awaiting_verification repetir mismo hash. Reiniciar sesión elimina estado no persistido; se reinicia desde cero. Pares y TLS sí persisten.

### Estados y límites temporales

`awaiting_acceptance → ready → streaming → awaiting_verification → completed`.
Desde todo estado no terminal: `cancelled` por usuario/revocación/cierre/timeout de espera; `failed` por tamaño, red, IO o integridad. Completed/failed/cancelled son terminales. Timeout de awaiting_acceptance/ready: 120 s; awaiting_verification: 120 s; inactividad durante stream: 60 s sin bytes (no límite total del archivo); handshake HTTP headers/JSON: 15 s. Timeout de transferencia es failed `timeout`; timeout esperando aceptación es cancelled `cancelled`. Mantener sesión/transferencia activa solo por decisión visible del usuario.

### Errores estables

400 `invalid_request`, `invalid_name`, `size_mismatch`; 401 `unauthorized`, `pairing_denied`; 404 `not_found`, `unsupported_version`; 405 `method_not_allowed`; 409 `already_paired`, `receive_disabled`, `busy`, `invalid_state`, `integrity_mismatch`, `source_changed`; 411 `length_required`; 413 `limit_exceeded`; 415 `unsupported_media_type`; 500 `io_error`; 507 `insufficient_space`. Estados failed pueden añadir `network_error`, `timeout`, `source_changed`. Una modificación de fuente no siempre es detectable sin snapshot: el hash garantiza igualdad del stream realmente enviado/recibido; H1 no promete snapshot de un archivo que otro proceso escribe. El emisor debe mantenerlo estable y abortar si detecta cambio de tamaño/mtime durante el envío.

## 6. Almacenamiento e integridad

Buffers acotados (64 KiB recomendado, máximo 1 MiB por stream); actualizar digest incremental. No precalcular hash mediante segunda lectura ni cargar archivo completo. Usar conexión persistente; ninguna request por bloque. Un archivo de cero bytes recorre las mismas fases y usa SHA-256 vacío.

El receptor genera nombre temporal interno impredecible y exclusivo en su destino; nunca lo deriva de una ruta enviada. Mantener oculto como incompleto hasta completar verificación. Antes de publicar revalidar destino, no seguir symlinks controlados por el remitente, y crear/publicar sin sobrescribir atómicamente donde esté disponible. Colisión: `nombre (1).ext`, incrementando, con creación exclusiva; no confiar en check-then-write. Conservar extensión final, cortar stem por límites de filesystem si hace falta. Windows publica en carpeta elegida localmente; Android puede usar una bandeja privada o una carpeta elegida con SAF (anticipado por petición del usuario durante la prueba de H1). La recepción se verifica primero en un temporal privado y luego se copia progresivamente a un archivo nuevo del destino, comprobando tamaño/hash durante la copia. No se anuncia completado hasta cerrar el destino. No se promete publicación atómica de proveedores SAF desconocidos: el documento puede ser visible durante la escritura y se intenta eliminar solo el recién creado si hay fallo. No se reabre un documento existente para escribir; se numeran colisiones y se rechaza un ID ya presente en la consulta previa. La carrera con escritores externos depende de las garantías del proveedor. La copia de publicación consume E/S adicional y debe terminar dentro de la ventana de verificación de 120 segundos; los proveedores lentos y archivos grandes requieren prueba específica. Si vence esa ventana después de guardar el archivo íntegro, conservarlo e informar que la operación remota terminó.

En H1 fallos/cancelación borran parciales; limpiar temporales propios huérfanos al iniciar, jamás archivos arbitrarios. Informa «transferencia interrumpida; vuelve a enviarla». Fallo al publicar → failed io_error/insufficient_space, nunca completed. Download completado localmente pero sin respuesta complete: conservar archivo íntegro, mostrar confirmación remota pendiente y reintentar complete/consultar mientras exista sesión, sin volver a escribirlo.

## 7. Evolución, fixtures y aprobación

La extensión opcional [Lotes de Android a Windows](UPLOAD-BATCHES.md) define el manifiesto y su aprobación local. Los cuerpos y la comprobación de integridad de cada archivo siguen este contrato.

El QR implementado codifica exactamente este descriptor JSON v1, sin URI/prefijo y sin modificar autenticación. El lector solo procesa QR, usa Descriptor.parse antes de iniciar HTTP y no abre enlaces. H2 conserva pendiente mDNS no confiable. H3 añade lotes y ciclo de vida/UX. H4 necesita un contrato nuevo revisado: identidad de fuente estable, manifiesto/bloques hasheados, offsets verificados por ambos, retención visible, autenticación al reconectar, journal y permisos persistentes antes de recuperación tras reinicio. No implementar Range, append ni retomar offsets no verificados en H1. Los IDs actuales y estados permiten evolucionar sin mezclar versiones de archivo, pero no constituyen implementación de reanudación.

Vectores normativos en `fixtures/h1-vectors.json`; ejecutar `python3 protocol/validate_fixtures.py`. Ambas implementaciones deben consumirlos para IDs/tamaños/nombres/hashes. Añadir pruebas propias de TLS con pin incorrecto, token expirado/repetido, receptor sin autorización, acceso de otro peer, revocación durante stream, upload/download íntegro, hash incorrecto, truncado, cancelación, colisión y memoria acotada. Los vectores y pruebas de Linux no sustituyen compilación Windows, APK/emulador ni prueba real Windows ↔ J4+.
