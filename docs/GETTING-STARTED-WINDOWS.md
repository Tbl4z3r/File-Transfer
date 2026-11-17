# Retomar el proyecto en tu PC

El entorno Cloud trabaja en `/workspace/File-Transfer` y no puede escribir directamente en `C:\Users\aferi\Desktop\File-Transfer`. Una vez publicado el estado en GitHub, abre PowerShell y ejecuta:

```powershell
git clone https://github.com/Tbl4z3r/File-Transfer.git 'C:\Users\aferi\Desktop\File-Transfer'
Set-Location 'C:\Users\aferi\Desktop\File-Transfer'
git status
```

`git clone` admite una carpeta existente vacía. Si ya contiene trabajo, no lo borres ni lo reemplaces: comprueba primero si es un checkout del mismo repositorio. Si ya lo es y está limpio, actualízalo con `git pull --ff-only`.

Lee `README.md`, `docs/CONTINUITY.md`, `docs/tasks/BOARD.md` y las instrucciones de cada aplicación antes de compilar. Las versiones fijadas y las fuentes oficiales están en `docs/architecture/compatibility.md`.

Para transferir finalmente no se requerirán herramientas de desarrollo. Los instaladores y la firma estable de distribución pertenecen a H5; no confundir un APK debug o una compilación de desarrollo con una versión final.

## Flujo Git

- `main`: estado integrado y comprobable.
- `task/<id>`: trabajo acotado, con reporte de revisión y pruebas.
- PR hacia `main` cuando el acceso remoto lo permita. Integrar secuencialmente tras revisión Astra; no usar una rama `develop` permanente.
- Etiquetas únicamente para versiones con alcance y verificaciones documentados. No reescribir historial publicado.

No introducir tokens, certificados privados ni keystores en los archivos del proyecto. Las credenciales configuradas en Windows no se comparten automáticamente con una máquina Cloud.
