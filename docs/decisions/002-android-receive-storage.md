# ADR 002 — recepción Android y carpeta elegida

Petición del usuario: priorizar el fallo PC→móvil observado en Redmi Note 14 5G y permitir elegir el destino. Se anticipa únicamente esta parte de SAF prevista para H3; no se amplía a rediseño de UI, lotes, QR ni distribución.

La captura muestra 1351724/1351724 bytes y un error con rutas inbox→partial en el punto de `Files.createLink`. El archivo llegó; la publicación local falló y la aplicación canceló la transferencia. No se atribuye una causa exacta del sistema/SELinux sin excepción/errno. El test JVM anterior verificaba transporte, pero no ejecutaba la publicación de Android.

Decisión: retirar la dependencia de hard links. El destino privado reserva con CREATE_NEW y copia a 64 KiB desde el temporal previamente verificado, con comprobación de bytes/hash durante esa copia y limpieza del archivo propio si falla. La carpeta elegida usa ACTION_OPEN_DOCUMENT_TREE y permisos persistentes READ/WRITE: no se añade acceso amplio a almacenamiento. Se valida la carpeta antes de aceptar; cambios/revocaciones muestran una explicación útil.

SAF usa createDocument, nunca abre un archivo encontrado por nombre, numera colisiones y rechaza IDs previamente existentes. No hay una garantía universal de publicación atómica de proveedores externos: el documento nuevo puede verse mientras se escribe; si la eliminación del parcial falla se informa para revisión manual. La copia extra y el espacio temporal son un coste de este guardado portable; medir antes de optimizar. El timeout de verificación de 120 s sigue vigente: copias lentas/grandes requieren validación, y si el PC termina mientras el archivo local quedó íntegro, se conserva y se informa sin exigir otra descarga.

El botón Copiar diagnóstico y logcat/FileTransfer registran solo versión, dispositivo/API, etapa, código y clases de causas acotadas/errno. Excluyen mensajes crudos, nombres, rutas, IP, descriptor y credenciales. No hay telemetría ni servicio de logs externo.

Fuentes oficiales: https://developer.android.com/training/data-storage/shared/documents-files y https://developer.android.com/reference/android/provider/DocumentsContract#createDocument(android.content.ContentResolver,android.net.Uri,java.lang.String,java.lang.String).

Pendientes: confirmar recepción/colisiones/permisos persistentes en Redmi y J4+, y proveedores locales reales. La compilación y los tests JVM no sustituyen esas pruebas.
