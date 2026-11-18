# Probar la rama prueba en Windows y Android

Esta entrega de desarrollo incluye Esencial en Android y Enfoque en Windows, con colores adaptados a la apariencia del sistema. El usuario confirmó el QR y las transferencias en ambos sentidos con las versiones anteriores en Redmi Note 14 5G y Galaxy J4+. Vuelve a comprobar ese recorrido con las aplicaciones de esta entrega. Los artefactos aún usan firma de desarrollo en Android.

## Obtener aplicaciones

1. Abre **Actions → CI** en el repositorio y elige la ejecución del último commit de `prueba`. Comprueba que los cuatro jobs terminen correctamente.
2. Descarga `windows-development-x64` y `android-development-apk` en Artifacts y extrae los ZIP. El `.exe` de desarrollo requiere WebView2; si Windows indica su ausencia, instala el runtime oficial de Microsoft. No hacen falta Rust, Node o Java para usar estos artefactos.
3. Ejecuta el `.exe` en Windows. El sistema puede mostrar una advertencia porque la aplicación todavía no tiene firma/reputación de distribución.
4. En Android permite instalar desde la aplicación con la que abras el APK y ábrelo. El APK debug de CI usa una clave de desarrollo del runner: no garantiza actualizaciones sobre builds de otros runners. No desinstales una instalación con datos importantes para eludir un conflicto de firma. La clave estable y su respaldo se prepararán en H5.

## Conectar y transferir

1. Conecta PC y móvil a la misma LAN. En Windows ejecuta `ipconfig` para identificar la IPv4 de la interfaz activa; evita la dirección de una VPN o una interfaz desconectada.
2. En File Transfer para Windows introduce esa IPv4 y el puerto mostrado, e inicia la sesión. Si el firewall pregunta, autoriza la aplicación para la red privada que estás usando; no desactives el firewall completo.
3. Pulsa **Mostrar QR** en Windows. En Android pulsa **Escanear QR del PC** debajo de **Conecta tu PC**, concede el permiso de cámara y apunta al código. El QR dura 120 segundos y solo puede usarse una vez. Si caduca o quieres conectar otro móvil, genera uno nuevo. La conexión manual está en el cuadro del QR de Windows y en los ajustes de Android.
4. **Android → Windows.** Activa **Recibir** en Windows y usa **Enviar archivo** en Android. Acepta la solicitud en el PC y elige la carpeta si aún no has fijado un destino para esa sesión. Espera el resultado verificado.
5. **Windows → Android.** Selecciona el dispositivo y pulsa **Enviar archivo** en Windows. En Android pulsa **Recibir archivo** para consultar la oferta. Puedes cambiar el destino desde **Carpeta de recepción** antes de aceptar. Android recuerda la carpeta y su permiso. Si no eliges una, usa la bandeja privada. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas.
6. Mantén Android abierto. Un corte requiere volver a enviar desde cero. En una confirmación remota pendiente, usa Reintentar confirmación antes de repetir el envío.
7. Al terminar, cierra la sesión desde **Ajustes** en la barra izquierda de Windows. Si cambia la IP del PC, actualiza la dirección desde el dispositivo vinculado en Android. Si cambia el certificado, inicia un nuevo emparejamiento.

## Resultado que falta registrar

Sigue `TESTING.md` para comprobar los hashes en ambos sentidos, la ABI real del J4+, bloqueo de pantalla, colisiones, cancelación y revocación. Registra el commit, sistemas, tamaño del archivo y tiempo. Estos resultados son necesarios antes de declarar H1 completo.

Para traer el código a `C:\Users\aferi\Desktop\File-Transfer`, usa `GETTING-STARTED-WINDOWS.md`; el trabajo de esta sesión se realiza en Cloud y no escribe directamente en esa carpeta.

## Si falla la recepción Android (0.1.1 o posterior)

Pulsa **Copiar diagnóstico** y comparte ese texto: contiene versión, modelo/API, etapa y tipo de excepción; no incluye descriptor, credenciales ni rutas. La captura previa que llegó al 100% fallaba en el paso de publicación con `Files.createLink`; la recepción nueva usa creación de archivo exclusivo/copia verificada y la API de documentos de Android para carpetas elegidas. La captura no permitía conocer la causa exacta del error del sistema.

Con depuración USB también puedes obtener el registro con `adb logcat -d -s FileTransfer:E '*:S'`. No hace falta una consola para el diagnóstico habitual de esta versión.

En ambos teléfonos, recibe el PDF en la bandeja privada y después en una carpeta elegida. Repite el nombre para comprobar que conserva el archivo anterior, vuelve a abrir la aplicación para comprobar la carpeta y retira el permiso para comprobar el aviso al recibir.

## Prueba QR en J4+

Al probar un emparejamiento nuevo, pon un nombre reconocible al móvil para distinguirlo del Redmi. Android lee la IP y el puerto del QR que muestra Windows.

Comprueba la lectura en ambas apariencias, el permiso denegado, el botón Atrás al escanear y la posibilidad de generar otro QR después de que caduque. Al volver a abrir una app emparejada se conserva su identidad.
