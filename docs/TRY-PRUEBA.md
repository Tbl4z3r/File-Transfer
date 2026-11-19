# Probar la rama prueba en Windows y Android

Esta entrega añade transferencias activas en segundo plano en Android, un código de emparejamiento copiable y una pantalla de Ajustes con versión y registro de errores. Actualiza ambos clientes. El APK usa firma de desarrollo.

## Obtener aplicaciones

1. Abre **Actions → CI** en el repositorio y elige la ejecución del último commit de `prueba`. Comprueba que los cuatro jobs terminen correctamente.
2. Descarga `windows-development-x64` y `android-development-apk` en Artifacts y extrae los ZIP. El `.exe` de desarrollo requiere WebView2; si Windows indica su ausencia, instala el runtime oficial de Microsoft. No hacen falta Rust, Node o Java para usar estos artefactos.
3. Ejecuta el `.exe` en Windows. El sistema puede mostrar una advertencia porque la aplicación todavía no tiene firma/reputación de distribución.
4. En Android permite instalar desde la aplicación con la que abras el APK y ábrelo. El APK debug de CI usa una clave de desarrollo del runner: no garantiza actualizaciones sobre builds de otros runners. No desinstales una instalación con datos importantes para eludir un conflicto de firma. La clave estable y su respaldo se prepararán en H5.

## Conectar y transferir

1. Conecta PC y móvil a la misma red local.
2. En File Transfer para Windows pulsa **Iniciar sesión**. La aplicación selecciona una dirección IPv4 local. Windows usa el puerto 51337 si está libre y pide uno disponible al sistema si está ocupado o reservado. Consulta la dirección y el puerto en **Ajustes → Conexión del PC**. Si el firewall pregunta, autoriza la aplicación para esa red privada.
3. Al iniciar la sesión se abre el QR. Puedes volver a abrirlo con **Mostrar QR**. En Android pulsa **Escanear QR del PC** debajo de **Conecta tu PC**, concede el permiso de cámara y apunta al código. El QR dura 120 segundos y solo puede usarse una vez. Si caduca o quieres conectar otro móvil, genera uno nuevo. Para vincular manualmente, usa **Copiar código** en la ventana del QR de Windows y pégalo en **Ajustes → Conexión manual** de Android. Esa pantalla también acepta el JSON de las versiones anteriores.
4. **Android → Windows.** Activa **Recibir** en Windows y elige varios elementos desde **Enviar archivos** en Android. En el PC puedes revisar el nombre y tamaño de cada uno antes de pulsar **Aceptar lote**. Usa el control de carpeta en la esquina inferior izquierda para fijar el destino. Si aún no hay carpeta, Windows la pide una vez al aceptar. El lote admite hasta 64 elementos con un manifiesto de hasta 16 KiB.
5. **Windows → Android.** Selecciona el dispositivo y pulsa **Enviar archivos** en Windows. Elige uno o varios archivos. Con Android abierto aparece la oferta en la sección de recepción. Si es un lote, abre **Detalle** para ver los nombres y tamaños y aceptarlo de una vez. Puedes cambiar el destino desde **Carpeta de recepción**, al pie de la pantalla, antes de aceptar. Android recuerda esa carpeta y su permiso. Si no eliges una, usa la bandeja privada. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas.
6. Después de iniciar el envío o aceptar la recepción, puedes cambiar de aplicación o apagar la pantalla. La notificación permite volver a File Transfer o cancelar el trabajo. La cola avanza después de cada archivo verificado y se detiene si cancelas, rechazas una oferta o falla un archivo. Los ya verificados se conservan. Si una confirmación queda pendiente, reinténtala antes de volver a elegir los archivos que faltan.
7. Al terminar, cierra la sesión desde **Ajustes** o cierra la aplicación en uno de los equipos. El otro muestra la desconexión. Si Android se cierra de golpe, Windows retira el dispositivo al vencer el plazo de hasta 45 s sin actividad. Abre las dos aplicaciones y genera un QR nuevo para reconectar.

Para comprobar los lotes, selecciona tres archivos de tamaños distintos. Al recibir en Windows acepta el lote una sola vez y comprueba el resultado de cada fila. Repite cancelando el segundo. El primero debe conservarse y el tercero debe figurar como no enviado. Reduce la ventana para comprobar que los dispositivos y los botones quedan a la derecha y que el selector de carpeta permanece al pie. Con más archivos, desplázate en la lista debajo de la transferencia.

En Android abre el detalle de actividad después de recibir dos archivos en destinos distintos. Comprueba que cada fila permita abrir su archivo y consultar la carpeta en la que se guardó, incluso después de cambiar la carpeta de recepción.

Para comprobar las unidades, envía un archivo de exactamente 1 000 000 bytes y verifica que ambos equipos muestren 1 MB. Para probar el reintento, corta la conexión mientras se envía el segundo archivo de un lote de tres. Vuelve a vincular los equipos si la sesión caducó. En el dispositivo de origen, reintenta el envío y comprueba en el destino que el primer archivo verificado tiene una sola copia. Repite en el otro sentido.

Comprueba el segundo plano con dos archivos grandes, en ambos sentidos. Inicia el envío, acepta la solicitud y cambia de aplicación mientras avanzan los bytes. Bloquea la pantalla y comprueba al volver que el lote sigue avanzando o terminó y que los archivos se guardaron íntegros. Repite usando **Cancelar** en la notificación y comprueba que se conserve lo ya verificado.

## Resultado que falta registrar

Sigue `TESTING.md` para comprobar los hashes en ambos sentidos, la ABI real del J4+, bloqueo de pantalla, colisiones, cancelación y revocación. Registra el commit, sistemas, tamaño del archivo y tiempo. Estos resultados son necesarios antes de declarar H1 completo.

Para traer el código a `C:\Users\aferi\Desktop\File-Transfer`, usa `GETTING-STARTED-WINDOWS.md`; el trabajo de esta sesión se realiza en Cloud y no escribe directamente en esa carpeta.

## Si falla la recepción Android (0.1.1 o posterior)

Pulsa **Copiar diagnóstico** y comparte ese texto: contiene versión, modelo/API, etapa y tipo de excepción; no incluye descriptor, credenciales ni rutas. La captura previa que llegó al 100% fallaba en el paso de publicación con `Files.createLink`; la recepción nueva usa creación de archivo exclusivo/copia verificada y la API de documentos de Android para carpetas elegidas. La captura no permitía conocer la causa exacta del error del sistema.

Con depuración USB también puedes obtener el registro con `adb logcat -d -s FileTransfer:E '*:S'`. No hace falta una consola para el diagnóstico habitual de esta versión.

En ambos teléfonos, recibe el PDF en la bandeja privada y después en una carpeta elegida. Repite el nombre para comprobar que conserva el archivo anterior, vuelve a abrir la aplicación para comprobar la carpeta y retira el permiso para comprobar el aviso al recibir.

## Prueba QR en J4+

Al probar un emparejamiento nuevo, pon un nombre reconocible al móvil para distinguirlo del Redmi. Android lee la IP y el puerto del QR que muestra Windows.

Comprueba la lectura en ambas apariencias, el permiso denegado, el botón Atrás al escanear y que puedes generar otro QR después de que caduque. Cierra Windows y comprueba el aviso en Android. Vuelve a vincular, cierra Android y comprueba que Windows retira solo ese dispositivo. Repite con un segundo móvil conectado.
