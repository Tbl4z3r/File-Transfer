# Tablero

Estados: pendiente → lista → en curso → en revisión → cambios solicitados / aprobada → integrada.

| ID | Objetivo | Responsable | Estado | Dependencia |
|---|---|---|---|---|
| H0-ARCH | Aprobar arquitectura y contrato mínimo | Astra | integrada | Alcance |
| H0-COMPAT | Comprobar versiones oficiales y herramientas | Sol | integrada | Alcance |
| H1-WINDOWS | Servicio TLS y cliente Windows | Sol + cierre Luna | integrada en prueba; hardware pendiente | H0-ARCH, H0-COMPAT |
| H1-ANDROID | Cliente Android y flujo manual | Terra + cierre Luna | integrada en prueba; APK y pruebas JVM verificadas | H0-ARCH, H0-COMPAT |
| H1-INTEGRATION | Revisar, validar y documentar transferencia | GPT-6.2 | prueba básica en Redmi y J4+ reportada por el usuario | Ambas implementaciones |
| ANDROID-RX | Corregir publicación Android, carpeta SAF y diagnósticos | Sol + Luna | integrada en prueba; repetir en móvil | Prueba Redmi Note 14 5G |
| H2-QR | Conectar con QR sin copiar JSON para probar J4+ | Sol / Terra | integrada en prueba; lectura confirmada por el usuario | Descriptor v1 existente |
| UI-PALETA | Esencial en Android y Enfoque en Windows con paleta azul | GPT-6.2 y dos implementadores Sol | en prueba; confirmar en dispositivos | Flujos de QR y transferencia |
| PUERTO-LOTES | Puerto automático en Windows y selección múltiple en ambos clientes | GPT-6.2 y dos implementadores Sol | en prueba; confirmar en dispositivos | Sesión TLS y transferencias v1 |
| DETALLE-LOTE-PC | Aceptar un lote en Windows, revisar estados y usar ventana compacta | GPT-6.2 | en prueba; confirmar en dispositivos | Manifiesto autenticado de subida y colas |
| H2–H5 | Descubrimiento, uso cotidiano, recuperación y distribución | por asignar | pendiente | H1 validado |

La revisión del autor no equivale a aprobación. Las verificaciones en dispositivos físicos y mediciones de la LAN requieren acceso real al Windows y al J4+.
