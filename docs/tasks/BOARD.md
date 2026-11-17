# Tablero

Estados: pendiente → lista → en curso → en revisión → cambios solicitados / aprobada → integrada.

| ID | Objetivo | Responsable | Estado | Dependencia |
|---|---|---|---|---|
| H0-ARCH | Aprobar arquitectura y contrato mínimo | Astra | lista | Alcance |
| H0-COMPAT | Comprobar versiones oficiales y herramientas | Sol | lista | Alcance |
| H1-WINDOWS | Servicio TLS y cliente Windows | Sol | pendiente | H0-ARCH, H0-COMPAT |
| H1-ANDROID | Cliente Android y flujo manual | Terra | pendiente | H0-ARCH, H0-COMPAT |
| H1-INTEGRATION | Revisar, validar y documentar transferencia | Astra / orquestador | pendiente | Ambas implementaciones |
| H2–H5 | Descubrimiento, uso cotidiano, recuperación y distribución | por asignar | pendiente | H1 validado |

La revisión del autor no equivale a aprobación. Las verificaciones en dispositivos físicos y mediciones de la LAN requieren acceso real al Windows y al J4+.
