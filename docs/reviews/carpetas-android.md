# Carpetas Android

Estado: candidata para revisión de GPT-6.2. Rama `task/carpetas-android`, base `f54432e` (cola Android). Aplica el contrato aprobado `protocol/FOLDER-BATCHES.md`; no modifica archivos del protocolo, Windows ni dependencias.

## Implementación

- Selector SAF de carpeta para enviar o dejar en espera. Recorre metadatos con límites antes de abrir contenido; copia por streaming a snapshots privados y persiste rutas, grupos y todos los directorios, incluidos vacíos. La cola v2 lee el esquema v1 previo y limpia los mismos temporales propios. No persiste permisos de origen innecesarios. El Intent del FGS traslada lectura y añade PREFIX solo para URI de árbol.
- Cada selección de carpeta usa su propio manifiesto y aprobación, serializada con las demás; raíces idénticas no se mezclan ni colisionan dentro de un manifiesto. Archivos sueltos se particionan por presupuesto JSON. Fallo/cancelación detiene el plan y deja pendientes en espera. El plan de despacho es de sesión; no se reproduce al morir proceso.
- `UploadFile.relativePath`, `DownloadOfferBatch.directories`, anuncio `X-FT-Folders: v1` y consulta autenticada `folder-support`. Lotes de carpetas admiten 0–64 archivos/1–64 directorios; no hay fallback al protocolo antiguo para carpetas. Padres exactos y misma grafía; minúsculas solo para colisiones. Límites de nombres/ruta/profundidad, unicidad y JSON antes de red. El parse de Inbox valida estructura; el límite de bytes es el de la respuesta wire, sin reserializarla.
- Grupos se marcan inciertos antes de ofrecerse, para no repetir silenciosamente una aceptación de carpetas vacías cuyo resultado se perdió. Se avisa al reintentar: nueva raíz con pendientes; archivos verificados y directorios previos se conservan. Marcar incertidumbre antes de request puede producir un aviso conservador aunque el PC no llegara a crear nada.
- El detalle entrante muestra directorios/rutas antes de aceptar. Android prepara carpetas antes del POST accept; recepción privada crea raíces exclusivas y rechaza enlaces; SAF usa solo IDs recién creados, rechaza IDs conocidos y comprueba autoridad/árbol antes de reutilizar URI. La publicación verificada existente recibe la ubicación asignada. `ReceiveLocation` conserva directorio real SAF o ruta privada relativa para abrir carpeta por fila.
- Proveedores SAF que devuelven IDs engañosos o escriben fuera de las garantías de su propio árbol no pueden ofrecer garantías de filesystem de la app. Un ID nuevo se comprueba contra el padre y los IDs ya conocidos; no se busca/reabre un documento por nombre. No se promete publicación atómica en SAF. Fallo al preparar intenta retirar solo directorios creados por esta operación; interrupción tras aprobación conserva los verificados y puede dejar carpetas vacías.

## Evidencia

`source /workspace/toolchains/env.sh`; cache Gradle compartida existente, sin dependencias nuevas:

- Primera corrida `:app:testDebugUnitTest`: 72 pruebas, 71 correctas, 1 omitida (`CoreInteropTest` sin binario), 0 fallos/errores. La compilación Kotlin también pasó. Incluye manifiestos con vacías/un archivo, padres con grafía incorrecta, traversal/colisiones/límites, capability/no fallback, vacío autorizado, snapshots/grupos/armado, partición por selección/JSON y publicación privada con colisión/enlace/cancelación.
- Corrida con `FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core`: **72/72 correctas, 0 omitidas**, incluido `CoreInteropTest` previo contra el core Linux nuevo. Las aserciones cruzadas de carpetas del coordinador se ejecutan en la integración.
- Primera corrida completa JVM/lint/APK: `BUILD SUCCESSFUL in 5m 26s`. La corrida final con binario sigue en lint/assemble al registrar esta evidencia; el código de seguimiento siguiente volvió a compilar correctamente con su prueba focalizada.
- `git diff --check`: correcto.
- Cola base: corrida completa JVM/lint/APK correcta; evidencia final añadida a `cola-android.md`.

No se probó emulador, Windows nativo, J4+, Redmi ni rendimiento LAN/RAM. Las pruebas JVM de archivos usan filesystem Linux y no sustituyen proveedores SAF reales.

## Prueba manual pendiente

1. Seleccionar árbol con archivo grande, vacío, subdirectorios y vacíos; dejar en espera, cerrar/abrir y revisar rutas. Enviar mediante QR: PC aprueba lote y conserva estructura/hash. Probar dos selecciones con la misma raíz: aprobaciones separadas y raíces nuevas numeradas.
2. Fuente SAF con nombre inválido, permisos retirados, exceso de 64/64/16 niveles, ruta larga y fallo de espacio durante snapshot: no publicar grupo parcial; conservar cola previa.
3. Carpeta vacía en ambos sentidos: revisión/aprobación explícita, creación de vacíos y finalización sin content. Cortar conexión alrededor de la aceptación: no reofrecer automáticamente; aviso de posible raíz ya creada antes del reintento explícito.
4. Recibir hacia bandeja privada y SAF con raíz existente/archivo del mismo nombre. Comprobar sufijo, archivos anteriores intactos, apertura de carpeta real por fila y que un proveedor que devuelve ID existente no se abre para escribir.
5. Cortar durante segundo archivo, cancelar desde notificación, rotar/bloquear y forzar detención. Completados se conservan; cola detiene resto. Reintentar informa otra raíz y solo usa pendientes; no reanuda offsets ni reutiliza carpeta anterior.
6. PC sin folder-support o respuesta no autorizada: ninguna carpeta se transforma en archivos sueltos. Archivos sueltos mantienen compatibilidad previa.

## Seguimiento: evitar bloqueo durante ingestión

El staging ya no mantiene el monitor del store mientras lee o sincroniza contenido. Solo el commit del manifiesto se serializa; vuelve a comprobar cancelación, límite de 64 archivos/64 directorios y mezcla con el estado vigente para preservar pausas/eliminaciones concurrentes. El cierre de un plan señala cancelación antes de desarmar. Así una fuente lenta no bloquea la pantalla al cerrar o pausar la cola.

Validación adicional: `:app:testDebugUnitTest --tests local.filetransfer.OutgoingQueueStoreTest`, **15/15 correctas**, `BUILD SUCCESSFUL in 3m 38s`. Incluye una fuente bloqueada mientras se pausa/cancela y dos importaciones concurrentes compitiendo por la última plaza: la perdedora revierte solo sus snapshots, sin exceder capacidad ni perder el ganador. La compilación Kotlin de este seguimiento pasó.
