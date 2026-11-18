# Probar la rama prueba en Windows y Android

Esta entrega añade vínculos por sesión, avisos de desconexión y recepción con detalle de archivos en Android. Actualiza ambos clientes. El APK usa firma de desarrollo.

## Obtener aplicaciones

1. Abre **Actions → CI** en el repositorio y elige la ejecución del último commit de `prueba`. Comprueba que los cuatro jobs terminen correctamente.
2. Descarga `windows-development-x64` y `android-development-apk` en Artifacts y extrae los ZIP. El `.exe` de desarrollo requiere WebView2; si Windows indica su ausencia, instala el runtime oficial de Microsoft. No hacen falta Rust, Node o Java para usar estos artefactos.
3. Ejecuta el `.exe` en Windows. El sistema puede mostrar una advertencia porque la aplicación todavía no tiene firma/reputación de distribución.
4. En Android permite instalar desde la aplicación con la que abras el APK y ábrelo. El APK debug de CI usa una clave de desarrollo del runner: no garantiza actualizaciones sobre builds de otros runners. No desinstales una instalación con datos importantes para eludir un conflicto de firma. La clave estable y su respaldo se prepararán en H5.

## Conectar y transferir

1. Conecta PC y móvil a la misma LAN. En Windows ejecuta `ipconfig` para identificar la IPv4 de la interfaz activa; evita la dirección de una VPN o una interfaz desconectada.
2. En File Transfer para Windows introduce la IPv4 e inicia la sesión. Windows usa el puerto 51337 si está libre y pide uno disponible al sistema si está ocupado o reservado. Consulta la dirección y el puerto en **Ajustes → Conexión del PC**. Si el firewall pregunta, autoriza la aplicación para esa red privada.
3. Pulsa **Mostrar QR** en Windows. En Android pulsa **Escanear QR del PC** debajo de **Conecta tu PC**, concede el permiso de cámara y apunta al código. El QR dura 120 segundos y solo puede usarse una vez. Si caduca o quieres conectar otro móvil, genera uno nuevo. La conexión manual está en el cuadro del QR de Windows y en los ajustes de Android.
4. **Android → Windows.** Activa **Recibir** en Windows y elige varios elementos desde **Enviar archivos** en Android. En el PC puedes revisar el nombre y tamaño de cada uno antes de pulsar **Aceptar lote**. Usa el control de carpeta en la esquina inferior izquierda para fijar el destino. Si aún no hay carpeta, Windows la pide una vez al aceptar. El lote admite hasta 64 elementos con un manifiesto de hasta 16 KiB.
5. **Windows → Android.** Selecciona el dispositivo y pulsa **Enviar archivos** en Windows. Elige uno o varios archivos. Con Android abierto aparece la oferta en la sección de recepción. Si es un lote, abre **Detalle** para ver los nombres y tamaños y aceptarlo de una vez. Puedes cambiar el destino desde **Carpeta de recepción** antes de aceptar. Android recuerda esa carpeta y su permiso. Si no eliges una, usa la bandeja privada. En Android 11+ puede ser necesario crear una subcarpeta dentro de Descargas.
6. Mantén Android abierto. La cola avanza después de cada archivo verificado y se detiene si cancelas, rechazas una oferta o falla un archivo. Los ya verificados se conservan. Si una confirmación queda pendiente, reinténtala antes de volver a elegir los archivos que faltan.
7. Al terminar, cierra la sesión desde **Ajustes** o cierra la aplicación en uno de los equipos. El otro muestra la desconexión. Si Android se cierra de golpe, Windows retira el dispositivo al vencer el plazo de hasta 45 s sin actividad. Abre las dos aplicaciones y genera un QR nuevo para reconectar.

Para comprobar los lotes, selecciona tres archivos de tamaños distintos. Al recibir en Windows acepta el lote una sola vez y comprueba el resultado de cada fila. Repite cancelando el segundo. El primero debe conservarse y el tercero debe figurar como no enviado. Reduce la ventana para comprobar que los dispositivos y los botones quedan a la derecha y que el selector de carpeta permanece al pie. Con más archivos, desplázate en la lista debajo de la transferencia.

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
