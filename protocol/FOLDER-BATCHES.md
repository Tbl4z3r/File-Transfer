# Carpetas en lotes v1

Esta extensión conserva los endpoints de [subida](UPLOAD-BATCHES.md) y [bandeja](SESSION-INBOX.md), la autorización explícita del receptor y las transferencias verificadas de cada archivo. Los lotes de archivos sin carpetas mantienen el formato anterior.

## Compatibilidad

Android anuncia `X-FT-Folders: v1` en `POST /v1/pair` junto con la cabecera de bandeja existente. Windows registra la capacidad para ese vínculo, la expone como `supports_folders` en la instantánea local y solo ofrece carpetas a vínculos que la anuncian. La respuesta de emparejamiento no añade campos. Antes de ofrecer una carpeta desde Android, `GET /v1/folder-support` autenticado responde `200 {"folders":true}`. Un `404 not_found` indica que el PC requiere actualizarse. Una carpeta nunca se degrada a archivos sueltos.

## Manifiesto

La solicitud de `POST /v1/upload-batches` y el objeto `batch` de `GET /v1/inbox` admiten `directories`, una lista no vacía de rutas de carpetas. Se enumeran todas las carpetas, incluidas las vacías. Dentro de `files`, un archivo en una subcarpeta incluye `relative_path` con su ruta completa. `file_name` sigue siendo el último componente. Los archivos sueltos se incluyen sin `relative_path`.

```json
{
  "directories": ["Estudios", "Estudios/Apuntes", "Estudios/Vacía"],
  "files": [
    {"file_name":"clase.pdf","size_bytes":"42000","relative_path":"Estudios/Apuntes/clase.pdf"},
    {"file_name":"foto.jpg","size_bytes":"19"}
  ]
}
```

- Una ruta tiene hasta 512 bytes UTF-8 y 16 componentes separados por `/`. Cada componente cumple la validación v1 de `file_name`. Se rechazan rutas absolutas, separadores inversos y segmentos vacíos, `.` o `..`. Cada carpeta no raíz tiene un padre enumerado. Todo archivo con ruta tiene un padre enumerado.
- Los nombres relativos completos son únicos al convertirlos a minúsculas. Una carpeta y un archivo no pueden ocupar la misma ruta. También se rechaza la colisión de un archivo suelto con una carpeta raíz. Entre archivos sueltos se mantiene la política anterior de nombres repetidos.
- Un lote con carpetas admite de 0 a 64 archivos, hasta 64 directorios y hasta 16 KiB de JSON codificado. Sin `directories` se conservan los límites de 2 a 64 archivos. Una oferta que excede los límites falla antes de reservar la transferencia.
- El orden de `files` define los índices de transferencia. Los cuerpos y las respuestas individuales mantienen `file_name` sin la ruta. Los receptores usan la ruta del manifiesto aprobado para colocar cada archivo. La instantánea local de Windows incluye las rutas y carpetas para mostrar el detalle completo antes de aceptar.

## Recepción

Al aceptar el lote, el receptor prepara las carpetas bajo la ubicación de recepción autorizada. Cada raíz se crea como directorio nuevo y recibe un sufijo si el nombre ya existe. Se reutilizan solo las subcarpetas recién creadas para el mismo manifiesto. Una ruta procedente del remitente no autoriza seguir un enlace, un punto de reanálisis ni un documento encontrado por nombre. En Android, cada carpeta SAF se crea mediante el identificador devuelto por el proveedor y se comprueba que sea nueva. Se publica el archivo en su carpeta asignada una vez verificado, con la política habitual de evitar sobrescrituras.

Un lote de carpetas con archivos entra en `accepted` y continúa por índice. Si no tiene archivos, se completa con la aceptación y la creación correcta de las carpetas. Android prepara las carpetas tras la aprobación del usuario y antes de confirmar por HTTPS la aceptación de una oferta de Windows. Si una transferencia se interrumpe después de aprobarla, se conservan los archivos verificados y pueden quedar las carpetas ya creadas.
