# Manifiesto de envíos Android → Windows

Entrega en `task/lote-manifiesto-android`, sobre `c0e5e70`. Android pasa a 0.1.5-dev (código 6), manteniendo API 28 y las dependencias fijadas.

## Comportamiento

- Se consultan y validan los nombres y tamaños enteros de toda la selección antes de hacer peticiones. Los lotes permiten 2–64 elementos y se comprueba el límite de 16 KiB sobre el JSON UTF-8 ya escapado. Metadatos ausentes o inválidos detienen todo el envío.
- `ProtocolClient` añade tipos y operaciones de creación, consulta, inicio por índice y cancelación de manifiestos, conservando TLS fijado y autenticación. Solo `404 not_found` recibido al crear un manifiesto autenticado activa el flujo antiguo. Un archivo usa directamente el flujo antiguo; este mantiene aceptación individual por archivo.
- Una aceptación del PC habilita el manifiesto completo. Se inicia un archivo cada vez y se verifica `ready`, dirección, nombre y tamaño antes de abrir/enviar su contenido. El siguiente empieza únicamente tras completar SHA-256 y confirmación.
- Cancelar antes del inicio, durante la espera o entre archivos detiene el resto y cancela el manifiesto cuando corresponde. Los archivos ya completados se conservan. Un lote totalmente completado no genera una cancelación adicional.
- Una confirmación recuperable pendiente conserva el archivo actual y detiene la cola sin cancelar transferencia ni manifiesto. Reintentar confirma únicamente ese archivo y cierra el resto; no reenvía contenido. Si el servidor informa fallo/cancelación o que el registro ya no existe, se informa del cierre sin afirmar éxito. Cerrar expresamente el aviso abandona la confirmación y solicita cancelar el lote, advirtiendo que debe comprobarse el destino antes de reenviar. Si no hay red, siguen aplicándose los plazos del servidor.
- Se reutilizan la cola y sus controles actuales; solo se añade el texto de espera del manifiesto y se aclara el abandono. No se modifica el transporte/publicación de recepción Android ni se rediseñan pantallas.

## Pruebas

Comando ejecutado desde `apps/android`, con el entorno de `/workspace/toolchains/env.sh`:

```sh
FT_CORE_BINARY=/tmp/ft-windows-lotes-target/debug/ft-core ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Resultado: 29 pruebas JVM correctas, ninguna omitida; APK debug generado. Lint: 0 errores y 9 avisos en orientación/icono, trust manager existente y uso de KTX. `git diff --check` correcto.

La prueba cruzada JVM usa el núcleo Rust real por TLS y emparejamiento: rechaza iniciar antes de aceptar, realiza **un solo** `accept_upload_batch`, transmite un archivo de 1 MiB + 31 bytes y otro vacío, comprueba ambos SHA-256/archivos publicados y estado final `completed`. Conserva la comprobación de descarga/publicación de recepción existente. No utiliza aceptaciones individuales en estos dos envíos.

Las pruebas TLS de cliente comprueban límite codificado, metadatos inválidos, petición autenticada e índice sin metadatos mutables, compatibilidad estricta con servidor antiguo, exclusión de otros errores del fallback, cancelación antes/entre envíos y ausencia de cancelación ante confirmación pendiente. Se conservan las pruebas de cola, integridad/publicación y certificados.

Núcleo usado: SHA-256 `969d0bdc314a5226355b828e291ec2f25ee417d43cbd284619833b07ef45bdc7`.

## Límites

Validación en Linux/JVM y compilación Android. No se ejecutó emulador, Windows real ni Samsung J4+; no se midió rendimiento LAN. La cola y los identificadores pendientes son de sesión y no implementan persistencia ni reanudación. Una cancelación que no puede llegar al PC depende del timeout del servidor. Falta probar en hardware: selección múltiple → una aceptación en Windows, cancelación entre archivos y recuperación/abandono del aviso de confirmación.
