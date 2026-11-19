# Integración de colas, carpetas y progreso

GPT-6.2 Sol coordinó el contrato y revisó la integración. Dos agentes GPT-6.1 Sol implementaron los flujos de Windows y Android. GPT-6.1 Terra revisó de forma independiente el destinatario, el reenvío, la recepción de carpetas y los permisos.

- [Cola y progreso de Windows](cola-windows.md)
- [Cola y progreso de Android](cola-android.md)
- [Carpetas en Windows](carpetas-windows.md)
- [Carpetas en Android](carpetas-android.md)
- [Contrato compartido de carpetas](../../protocol/FOLDER-BATCHES.md)

Windows guarda las rutas de la selección y vuelve a revisar los archivos al enviar. Android guarda copias privadas para conservar los archivos compartidos cuando caduca el permiso temporal. La autorización en el destino se conserva en ambos sentidos. Al reintentar una carpeta se prepara otra raíz para los pendientes. Los archivos ya verificados permanecen en su ubicación.

## Verificación

- El núcleo Rust pasa 42 pruebas y clippy con advertencias tratadas como errores. La prueba de humo con TLS real en Linux verificó 16 MiB por operación, consentimiento, colisiones y revocación.
- La interfaz Windows pasa 78 pruebas y el cálculo del progreso nativo pasa dos pruebas separadas en Linux.
- La primera candidatura integrada pasó 72 pruebas JVM, incluida la prueba cruzada del cliente Kotlin contra el núcleo Rust real. Comprueba rutas anunciadas, tamaños, consentimiento, publicación verificada dentro de carpetas nuevas y ofertas compuestas solo por carpetas vacías.
- El ajuste final de importación pasó 15 pruebas focalizadas. Comprueban que una fuente lenta permite pausar y cancelar la cola, y que dos importaciones que compiten por la última plaza conservan la selección que obtuvo la plaza.
- Las pruebas en el Explorador, la barra de tareas de Windows, Android 9/J4+, Redmi, el menú de compartir y proveedores SAF reales requieren los respectivos sistemas. Los pasos están en [Probar la rama](../TRY-PRUEBA.md).
