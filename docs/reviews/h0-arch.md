# H0-ARCH — contrato aprobado para implementar

Responsable: Astra. Rama `task/h0-arch`. Base revisada `1755d8e` aprobada para publicación/integración: solo reglas, alcance y continuidad; sin secretos.

Contrato v1 aprobado: servidor Windows TLS persistente durante sesión activa, Android cliente en ambos sentidos, pin SHA-256 DER fuera de banda + PKIX/hostname estándar, pairing temporal y credenciales persistentes revocables por dispositivo. Autorización de recepción independiente; stream con SHA-256 incremental y confirmación posterior; sin reanudación H1. Decisión explícita: identidad cliente basada en secreto criptográfico, no mTLS.

Archivos: `protocol/PROTOCOL.md`, vectores JSON y validador; arquitectura con interfaces de tareas; ADR 001. Core Rust separable de Tauri y transporte Kotlin separable de Android para pruebas reales Linux/JVM. Android mantiene Compose con APK universal; ABI del J4+ pendiente.

Validación ejecutada: `python3 protocol/validate_fixtures.py` → OK, 41 vectores escalares/hash, límites UTF-8, 13 mensajes, encoding canónico de tokens e invariantes de transferencias. Se detectó y corrigió un digest incorrecto del vector binario antes del commit. `git diff --check` correcto. Revisión manual de endpoints, estados, dirección, publicación y aislamiento por peer completada.

Esta aprobación permite paralelizar H1-WINDOWS y H1-ANDROID al aprobar matriz de versiones. No aprueba implementaciones todavía: Astra debe revisar diffs y pruebas reales antes de integrar. Pendientes: test TLS/HTTP de implementaciones, interoperabilidad, compilación Windows, APK, hardware Windows/J4+, rendimiento y archivo >4 GiB. Ningún vector sustituye estas evidencias; H1 no se declara completado.

## Revisiones adicionales de H0

- `699c7c8` (documentación de instalación/aceptación): diff completo leído, **aprobado**. Distingue hardware de build y evita sobrescribir checkout existente; consistente con contrato.
- `b6a8e11` (H0-COMPAT): documento completo, reporte y scripts leídos; **aprobado para integración**. Matriz fija compatible según requisitos publicados; rutas de core corregidas. Astra repitió `bash -n scripts/environment/check-host.sh` y auditoría de 11 AAR con aserciones minSdk≤28, hash OkHttp y los cuatro ABIs Graphics Path: correctos. No equivale a compilación o hardware. Compose universal y OkHttp5.4.0 quedan aceptados bajo limitaciones documentadas.
- CI `959ac34`: borrador pendiente de revisión contra módulos y comandos reales; no aprobado aún.
