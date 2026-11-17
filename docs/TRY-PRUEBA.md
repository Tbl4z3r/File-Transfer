# Probar la rama prueba en Windows y Android

Esta es una entrega de desarrollo. Las pruebas automáticas y la compilación no acreditan todavía tu Windows ni tu J4+. No hay instalador ni firma de distribución estable.

## Obtener aplicaciones

1. Abre **Actions → CI** en el repositorio y elige la ejecución del último commit de `prueba`. Comprueba que los cuatro jobs terminen correctamente.
2. Descarga `windows-development-x64` y `android-development-apk` en Artifacts y extrae los ZIP. El `.exe` de desarrollo requiere WebView2; si Windows indica su ausencia, instala el runtime oficial de Microsoft. No hacen falta Rust, Node o Java para usar estos artefactos.
3. Ejecuta el `.exe` en Windows. El sistema puede mostrar una advertencia porque la aplicación todavía no tiene firma/reputación de distribución.
4. En Android permite instalar desde la aplicación con la que abras el APK y ábrelo. El APK debug de CI usa una clave de desarrollo del runner: no garantiza actualizaciones sobre builds de otros runners. No desinstales una instalación con datos importantes para eludir un conflicto de firma. La clave estable y su respaldo se prepararán en H5.

## Conectar y transferir

1. Conecta PC y móvil a la misma LAN. En Windows ejecuta `ipconfig` para identificar la IPv4 de la interfaz activa; evita la dirección de una VPN o una interfaz desconectada.
2. En File Transfer para Windows introduce esa IPv4 y el puerto mostrado, e inicia la sesión. Si el firewall pregunta, autoriza la aplicación para la red privada que estás usando; no desactives el firewall completo.
3. Abre la ventana de emparejamiento. Pasa el descriptor completo al móvil por un medio físicamente verificado y dentro de sus 120 segundos de vigencia. La huella identifica el PC; no sustituyas el descriptor por datos procedentes de un anuncio de red. En Android pégalo y pulsa Emparejar. La representación QR llegará en H2; esta primera versión requiere transcripción/copia manual.
4. **Android → Windows:** activa Recibir en Windows, elige un archivo en Android y acepta la solicitud en el PC seleccionando carpeta de destino. Espera el resultado verificado.
5. **Windows → Android:** selecciona dispositivo y archivo en Windows, envía la oferta y pulsa Buscar archivo para recibir en Android. Acepta la recepción y espera el resultado verificado. Antes de aceptar puedes pulsar **Elegir carpeta de recepción**; Android recordará la carpeta y su permiso. Si no eliges una, se usa la bandeja privada. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas. Puedes abrir el último archivo recibido.
6. Mantén Android abierto. Un corte requiere volver a enviar desde cero. En una confirmación remota pendiente, usa Reintentar confirmación antes de repetir el envío.
7. Al terminar, cierra la sesión Windows. Si cambia la IP del PC, actualízala en Android conservando el emparejamiento. Si cambia el certificado, inicia un nuevo emparejamiento consciente.

## Resultado que falta registrar

Sigue `TESTING.md` para comprobar los hashes en ambos sentidos, la ABI real del J4+, bloqueo de pantalla, colisiones, cancelación y revocación. Registra el commit, sistemas, tamaño del archivo y tiempo. Estos resultados son necesarios antes de declarar H1 completo.

Para traer el código a `C:\Users\aferi\Desktop\File-Transfer`, usa `GETTING-STARTED-WINDOWS.md`; el trabajo de esta sesión se realiza en Cloud y no escribe directamente en esa carpeta.

## Si falla la recepción Android (0.1.1 o posterior)

Pulsa **Copiar diagnóstico** y comparte ese texto: contiene versión, modelo/API, etapa y tipo de excepción; no incluye descriptor, credenciales ni rutas. La captura previa que llegó al 100% fallaba en el paso de publicación con `Files.createLink`; la recepción nueva usa creación de archivo exclusivo/copia verificada y la API de documentos de Android para carpetas elegidas. La captura no permitía conocer la causa exacta del error del sistema.

Con depuración USB también puedes obtener el registro con `adb logcat -d -s FileTransfer:E '*:S'`. No hace falta una consola para el diagnóstico habitual de esta versión.

Prueba en el Redmi Note 14 5G: recibir el PDF que fallaba en bandeja privada; elegir una subcarpeta local y recibirlo otra vez; repetir el nombre sin sobrescribir; cerrar/reabrir y recibir conservando carpeta; retirar el permiso o mover la carpeta y comprobar que la app pide elegirla de nuevo. El J4+ y los proveedores SAF reales siguen pendientes de prueba.
