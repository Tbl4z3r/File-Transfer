# Validación y pruebas en dispositivos

Las pruebas automáticas de Linux, una compilación Windows, una compilación APK y una transferencia real Windows ↔ J4+ son evidencias diferentes. Registrar versión/commit, sistema, dispositivo y resultado de cada prueba. No marcar un hito completo por la existencia de pantallas o un artefacto compilado.

## Puerta de aceptación H1

1. Compilar el núcleo Rust y ejecutar sus pruebas. Compilar la aplicación Windows x64 en Windows y el APK con el wrapper Gradle del repositorio.
2. Instalar en Windows 10 22H2 o Windows 11 y Android 9 real. Windows 10 22H2 es una referencia de prueba, no una afirmación sobre todas las versiones de Windows 10.
3. En el J4+, registrar `adb shell getprop ro.product.cpu.abilist` y `adb shell getprop ro.build.version.sdk`. No inferir el ABI del sistema a partir del modelo de CPU. Inspeccionar las carpetas `lib/` del APK y comprobar que cubren el ABI observado.
4. Usar la misma LAN. Abrir una sesión en Windows, mostrar el descriptor y transcribirlo por un canal físicamente verificado al móvil. Verificar que una huella alterada impide el emparejamiento y que un descriptor expirado se rechaza.
5. Activar recepción Windows. Enviar desde Android un archivo de prueba conocido, aceptar explícitamente en Windows y comprobar tamaño y SHA-256 final. Un dispositivo emparejado no debe saltarse la confirmación.
6. Seleccionar archivo y dispositivo en Windows; activar recepción Android, aceptar la oferta y comprobar su contenido y hash final. No declarar éxito antes de verificar integridad.
7. Repetir con archivo vacío, nombre repetido, archivo de varios GiB y selección de fuente cancelada. El archivo existente nunca debe sobrescribirse.
8. Cortar Wi-Fi durante cada sentido. H1 debe fallar claramente y eliminar parciales; H4 añadirá reanudación. Repetir cancelación y revocación durante transferencia.
9. Cerrar/reabrir aplicaciones: conservar identidad/emparejamiento, sin activar recepción automáticamente. No restaurar falsamente como completada una transferencia interrumpida.

## Rendimiento: medición pendiente en LAN real

Probar un archivo no comprimible de 256 MiB o más y después 100 archivos pequeños (secuenciales en H1; lote después de H3). Mantener el mismo conjunto de datos y repetir tres veces. Registrar:

| Campo | Registro |
|---|---|
| Commit y build | |
| Windows, Android, modelo y ABI | |
| PC Ethernet o Wi-Fi / banda Wi-Fi móvil | |
| Dirección y bytes | |
| Tiempo desde primer hasta último byte | |
| Tiempo total con confirmación/hash/publicación | |
| MiB/s = bytes / segundos / 1048576 | |
| Memoria máxima de cada proceso | |
| Espacio libre / tipo de almacenamiento | |
| SHA-256 emisor/receptor | |

Separar transferencia y tiempo de interacción. Una medición loopback solo mide ese entorno. Comparar con lectura/escritura local y una herramienta de rendimiento LAN si hace falta aislar el límite; no deducir la velocidad Wi-Fi de la compilación ni optimizar paralelismo sin medidas.

## Ciclo de vida y red

- Bloquear pantalla, cambiar de aplicación y volver. H1 no garantiza ejecución con Android suspendido; debe informar el corte. Servicio foreground y notificación de cancelación se validan en H3.
- Probar firewall Windows con perfil privado, sin ejecutar como administrador. Autorizar la aplicación explícitamente si Windows lo solicita; no desactivar firewall completo.
- En redes de invitados puede existir aislamiento entre clientes. Si no conecta, verificar misma subred, IP/puerto mostrados, sesión activa y reglas del perfil correcto. No recorrer toda la red para buscar el dispositivo.
- Repetir tras cambio de IP: conservar identidad y volver a introducir dirección. No aceptar un certificado diferente automáticamente.
- Comprobar errores de destino no escribible y disco lleno usando un volumen/carpeta de prueba; no llenar el disco principal del usuario.

## Hitos posteriores

H2: mDNS, nombres duplicados, varias interfaces, desaparición del receptor y QR.
H3: selección múltiple, progreso total/velocidad/ETA, SAF, notificación y cancelación.
H4-A: corte/reconexión autenticada y offsets verificados. H4-B: reinicio, cambio de fuente, permisos perdidos, journal incompleto y limpieza visible.
H5: NSIS por usuario/WebView2, instalación sin herramientas de desarrollo, APK release firmado con clave respaldada fuera de Git, actualización conservando datos y desinstalación.
