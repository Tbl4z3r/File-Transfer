# ADR 001 — TLS Windows y credenciales de dispositivo en H1

Estado: aceptada por Astra para H1.

Windows es el servidor HTTPS mientras la sesión está activa; Android es cliente de subida y descarga. Se distinguen receptor de archivos y servidor de conexión. Evita servidor/puertos entrantes en Android y no exige servicios remotos.

La identidad criptográfica de Windows es una clave ECDSA P-256 y certificado persistentes. Android verifica el SHA-256 del certificado completo obtenido fuera de banda y delega la validación TLS a bibliotecas estándar con ese certificado como anchor y nombre DNS fijo. El pin no desactiva hostname, firma ni vigencia. El descriptor contiene además un token de emparejamiento aleatorio de 256 bits y 120 segundos. Se descarta un PIN corto como único secreto.

Android se autentica posteriormente mediante una credencial aleatoria de 256 bits por emparejamiento, asociada a su ID persistente, transmitida dentro del TLS autenticado. Esto proporciona una identidad criptográfica basada en posesión de secreto, no una identidad asimétrica de cliente. Elegimos esta solución mínima y revocable por encima de mTLS para evitar emisión/verificación dinámica de certificados cliente en Android 9 y Rust. No hay firma casera de mensajes. La extracción del secreto del almacenamiento privado permite suplantar ese peer hasta revocarlo; proteger almacenamiento y no registrar bearer. mTLS solo se reconsiderará si una necesidad concreta justifica migración.

Autorización de transferencias separada de autenticación: confirmar receptor por cada archivo, sin recepción automática H1. Revocación invalida credential y streams activos. El ID, nombre, IP y futuro mDNS no conceden acceso.

Integridad SHA-256 calculada durante cada stream y reconciliada después mediante `complete`; no cabeceras con hash que obliguen a leer archivos dos veces, ni trailers de soporte desigual. Publicación solo tras verificación y sin sobreescritura. Sin reanudación H1; H4 definirá offsets y snapshots verificables.
