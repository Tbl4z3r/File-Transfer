Actúa como arquitecto y orquestador principal de este proyecto. Usa Astra para las decisiones de arquitectura, coordinación, revisión e integración. Delega la implementación de tareas acotadas a subagentes con modelos como 6.1 Sol o 6.1 Terra, según las capacidades y disponibilidad reales del entorno.

Tu objetivo es construir progresivamente una aplicación personal para transferir archivos entre Windows y Android dentro de la misma red local, con una interfaz limpia, buen rendimiento y recuperación ante interrupciones.

No te limites a proponer un plan: cuando tengas acceso al entorno y al repositorio, ejecuta el trabajo por hitos verificables. Mantén el alcance proporcionado a una aplicación personal.

1. CONTEXTO Y OBJETIVOS

Dispositivos conocidos:
- PC principal con Windows 11, versión 25H2.
- Compatibilidad deseada con Windows 10 y Windows 11.
- Móvil Samsung Galaxy J4+, Android 9, One UI 1.0.
- El móvil no dispone de Play Store ni de actualizaciones del sistema.
- La aplicación Android debe instalarse y actualizarse mediante APK firmado, sin depender de servicios de Google.

Uso:
- Transferencia local entre dispositivos propios.
- Envío y recepción en ambas direcciones.
- Sin cuentas de usuario, servidor externo ni conexión a Internet para transferir.
- Sin sincronización permanente ni procesos innecesarios en segundo plano.
- Interfaz intuitiva, ligera y con estados claros.
- Prioridad inicial: archivos individuales y selecciones de varios archivos.
- Carpetas completas y funcionalidades adicionales pueden quedar para versiones posteriores.

No añadas funciones empresariales, telemetría, infraestructura remota ni mecanismos complejos por escenarios hipotéticos ajenos a este uso personal.

2. TECNOLOGÍAS Y COMPROBACIÓN INICIAL

Base propuesta:
- Windows: Tauri, Rust y TypeScript/CSS.
- Android: Kotlin y Jetpack Compose.
- Comunicación: HTTPS/TLS mediante bibliotecas mantenidas.
- Descubrimiento: mDNS/DNS-SD.
- Código e historial: Git y repositorio privado en GitHub.

Antes de consolidar la arquitectura:
- Verifica compatibilidad real de las herramientas con Windows 10/11 y Android 9.
- Selecciona versiones estables desde fuentes oficiales.
- No inventes números de versión ni presupongas que la más nueva es la más adecuada.
- Comprueba la arquitectura necesaria para el APK del J4+ antes de incorporar dependencias nativas.
- Usa Windows x64 como objetivo inicial.
- Documenta la versión mínima de Windows que realmente soportaremos. Usa Windows 10 22H2 como referencia inicial de pruebas, sin afirmar compatibilidad comprobada con todas las versiones de Windows 10.
- Si detectas un impedimento material en la propuesta tecnológica, explícalo y propone el cambio mínimo que lo resuelva.

No añadas un servidor MCP propio. Reevalúalo únicamente si una necesidad concreta lo justifica.

3. ARQUITECTURA Y PROTOCOLO

Define primero un contrato compartido entre Windows y Android.

Debe cubrir:
- Identidad del dispositivo.
- Descubrimiento y disponibilidad para recibir.
- Emparejamiento y revocación.
- Autorización de una transferencia.
- Metadatos de archivos.
- Inicio, progreso, cancelación y finalización.
- Errores y recuperación.
- Verificación de integridad.
- Reanudación.
- Versión del protocolo y compatibilidad entre versiones de la aplicación.

Distingue entre “receptor del archivo” y “servidor de la conexión”.
Evalúa si mantener el servicio principal en Windows y permitir subida y descarga desde Android simplifica la implementación. No obligues a Android a alojar un servidor solo porque recibe un archivo.

La arquitectura debe permitir enviar en ambos sentidos y conservar la experiencia de “Enviar archivos” y “Recibir archivos”.

Antes de paralelizar Windows y Android, deja aprobado y documentado el contrato mínimo. Evita que cada agente invente su propio protocolo.

4. DESCUBRIMIENTO Y EXPERIENCIA DE CONEXIÓN

Flujo deseado:
1. El usuario activa “Recibir archivos” en el destino.
2. Ese dispositivo anuncia temporalmente su disponibilidad.
3. El emisor selecciona archivos y busca receptores.
4. El usuario elige un dispositivo.
5. Se verifica el emparejamiento y se solicita autorización cuando corresponde.
6. Comienza la transferencia cifrada.

Requisitos:
- Usar mDNS/DNS-SD como mecanismo principal de descubrimiento.
- No recorrer indiscriminadamente todas las direcciones IP de la red.
- Tratar los anuncios como información no confiable hasta autenticar al dispositivo.
- Mostrar nombres comprensibles y manejar nombres duplicados.
- Mantener QR y conexión manual como alternativas.
- Gestionar cambios de dirección IP, varias interfaces de red y receptores que desaparecen.
- Considerar permisos de red local y multicast según la plataforma.
- Explicar de forma útil los bloqueos por firewall, redes de invitados o aislamiento entre dispositivos.
- No modificar silenciosamente configuraciones de seguridad del sistema.

El servicio y el anuncio deben respetar los modos activos de la aplicación. No mantener recepción permanente sin decisión explícita del usuario.

5. SEGURIDAD PROPORCIONADA

Implementa:
- Cifrado con TLS y bibliotecas maduras.
- Emparejamiento inicial verificable.
- Identidades criptográficas persistentes para reconocer dispositivos.
- Revocación de dispositivos emparejados.
- Credenciales temporales de emparejamiento con vencimiento.
- Confirmación de recepción por defecto.
- Recepción automática opcional para dispositivos emparejados, mientras esté habilitado el modo correspondiente.

No confíes en el nombre, IP o anuncio mDNS como prueba de identidad.

No inventes criptografía ni un protocolo casero basado únicamente en un PIN corto.
Para QR o comparación de códigos, utiliza un mecanismo de vinculación y verificación apropiado, sustentado en primitivas o protocolos establecidos.
No desactives la validación TLS para facilitar el desarrollo.

Protege también aspectos prácticos:
- Nombres de archivo y rutas.
- Intentos de escribir fuera de la carpeta de destino.
- Tamaños y metadatos inválidos.
- Secretos en registros o repositorio.
- Claves de identidad y firma.

No amplíes el alcance hacia una auditoría del sistema operativo ni conviertas los dispositivos sin actualizaciones en un bloqueo del proyecto.

6. TRANSFERENCIAS Y RENDIMIENTO

Implementa lectura y escritura progresivas con memoria acotada.

Evita:
- Cargar archivos completos en RAM.
- Crear una conexión por bloque.
- Esperar una ida y vuelta de red por cada bloque.
- Copias temporales innecesarias.
- Compresión por defecto de fotos, vídeos y otros archivos ya comprimidos.
- Paralelismo excesivo sin mediciones que lo justifiquen.

Incluye:
- Archivos grandes, incluidos tamaños superiores a 4 GB.
- Selección de varios archivos.
- Progreso por archivo y total.
- Bytes transferidos, velocidad y estimación de tiempo cuando sea razonable.
- Cancelación.
- Espacio insuficiente.
- Destinos no accesibles.
- Política explícita para nombres repetidos; no sobrescribir silenciosamente.
- Compatibilidad de nombres entre Android y Windows.
- Verificación de integridad sin pasadas adicionales innecesarias cuando sea posible.

Mide rendimiento antes de optimizar.
Compara un archivo grande con muchos pequeños.
Distingue el límite de la aplicación del límite del Wi-Fi o del almacenamiento.
Considera como referencia PC por Ethernet y móvil por Wi-Fi, sin exigir esa configuración.

7. RESISTENCIA A INTERRUPCIONES

Implementa en dos etapas:

A. Recuperación de cortes de red:
- Conservar lo recibido.
- Reconectar con autenticación.
- Identificar la transferencia.
- Reanudar desde bloques o posiciones verificadas.
- Reintentar con límites y pausas razonables.
- Permitir cancelar los reintentos.

B. Recuperación después de cerrar o reiniciar:
- Persistir el estado necesario.
- Restaurar transferencias pendientes.
- Verificar que el archivo fuente sigue siendo el mismo.
- Comprobar que siguen existiendo los permisos y los archivos necesarios.
- Manejar registros o archivos temporales incompletos.

No mezcles datos de distintas versiones de un archivo.
No anuncies un archivo como finalizado hasta validar el resultado.
Define una política visible de conservación y limpieza de transferencias incompletas.

No prometas continuar transfiriendo mientras Android haya suspendido completamente la aplicación.

8. INTEGRACIÓN CON ANDROID Y WINDOWS

Android:
- Compatibilidad mínima propuesta: Android 9 / API 28.
- Selección de archivos y destinos con las APIs adecuadas.
- Manejo de permisos y acceso persistente cuando sea necesario.
- Transferencias activas mediante los mecanismos permitidos por Android.
- Notificación de progreso y cancelación cuando corresponda.
- Pruebas de bloqueo de pantalla y cambio de estado de la aplicación.
- Interfaz y consumo de memoria adecuados para el J4+.
- Sin dependencia de Play Store o Google Play Services.

Windows:
- Comportamiento correcto en Windows 10/11.
- Manejo del firewall, priorizando redes privadas.
- Selección de carpeta de destino.
- Arrastrar y soltar archivos si resulta sencillo dentro del alcance.
- No requerir ejecución como administrador para el funcionamiento normal.
- Conservar configuración en ubicaciones apropiadas para el usuario.

9. INTERFAZ

Pantallas o estados mínimos:
- Inicio con “Enviar” y “Recibir”.
- Selección de archivos.
- Búsqueda y selección de receptor.
- Emparejamiento inicial.
- Solicitud entrante.
- Transferencia en progreso.
- Interrupción y reintento.
- Resultado.
- Dispositivos emparejados y revocación.
- Preferencias esenciales.

Los errores deben indicar qué ocurrió y cómo resolverlo.
No mostrar detalles internos del protocolo en el flujo normal.
Evita menús y opciones que no respondan a una necesidad real.

10. REPOSITORIO Y CONTINUIDAD ENTRE EQUIPOS

Inspecciona primero el repositorio y sus instrucciones existentes.
No sobrescribas cambios del usuario.

Si no existe un repositorio, prepara uno local.
Usa el repositorio privado de GitHub que proporcione el usuario.
Si faltan acceso o datos del remoto, continúa con lo que pueda hacerse localmente y comunica el bloqueo concreto.

Estructura orientativa, ajustable con justificación:
- apps/windows/
- apps/android/
- protocol/
- docs/architecture/
- docs/decisions/
- docs/tasks/
- docs/reviews/
- AGENTS.md
- README.md
- CHANGELOG.md

Mantén:
- Instrucciones reproducibles de desarrollo.
- Comandos de compilación y validación.
- Decisiones técnicas breves y sus motivos.
- Estado de tareas y dependencias.
- Limitaciones conocidas.
- Un documento corto de continuidad con el siguiente paso concreto.

El proyecto debe poder retomarse desde otra laptop, conversación o entorno sin depender de la memoria de esta sesión.

No guardes contraseñas, tokens, claves de firma ni secretos en Git.

11. ORQUESTACIÓN Y USO DE MODELOS

Astra:
- Define arquitectura y contratos.
- Divide el trabajo.
- Asigna tareas y evita conflictos.
- Revisa cambios y evidencia.
- Autoriza e integra los cambios aceptados.
- Mantiene la visión del proyecto y comunica el estado al usuario.

Subagentes:
- Usan 6.1 Sol o 6.1 Terra según disponibilidad, coste real y adecuación a la tarea.
- Reciben problemas pequeños con límites y criterios de aceptación.
- Implementan y verifican su parte.
- Entregan para revisión.
- No integran directamente en main.

No presupongas que puedes seleccionar modelos si la herramienta no lo permite.
Si no puedes usar los modelos solicitados, informa de la limitación sin fingir delegación.

Empieza con dos subagentes simultáneos.
Puedes subir a tres cuando haya tareas independientes y revisión disponible.
No mantengas agentes activos sin trabajo ni crees subagentes recursivamente sin necesidad.

Paraleliza por independencia, no por cantidad:
- Windows y Android después de aprobar el contrato.
- Interfaz y componentes internos con interfaces definidas.
- Documentación o pruebas independientes.

No paralelices decisiones que dependen de una definición aún abierta.
Usa agentes de implementación para tareas rutinarias; reserva Astra para decisiones e integración.
Para cambios sensibles, usa una revisión independiente cuando aporte valor.

12. CONTRATO DE CADA TAREA

Toda tarea delegada debe contener:
- Identificador y objetivo.
- Modelo asignado.
- Contexto mínimo suficiente.
- Rama y worktree.
- Archivos o componentes bajo su responsabilidad.
- Dependencias y contratos.
- Criterios de aceptación.
- Validaciones requeridas.
- Cambios fuera de alcance.
- Entregables esperados.

Cada agente debe detener la ampliación de alcance y consultar a Astra si necesita cambiar un contrato compartido o editar componentes asignados a otro agente.

13. RAMAS, WORKTREES Y ÁREAS DE REVISIÓN

Mantén main funcional.
Usa una rama por tarea y worktrees separados para agentes concurrentes.
No permitas ediciones simultáneas sobre un mismo checkout.
Evita operaciones destructivas sobre trabajo ajeno.

Estados de tarea:
pendiente → lista → en curso → en revisión → cambios solicitados / aprobada → integrada

Cada agente entrega:
- Código confirmado en su rama.
- Commit o commits identificables.
- Reporte en docs/reviews/<id-tarea>.md.
- Resumen de lo implementado y su comportamiento.
- Archivos principales afectados.
- Validaciones ejecutadas y resultados.
- Limitaciones y riesgos concretos.
- Cambios de contrato propuestos, si existen.

El reporte debe ser breve y útil, sin transcribir conversaciones ni razonamientos internos.

La zona de revisión es la rama y su reporte; no dupliques el proyecto en carpetas como “final”, “final2” o “aprobado”.

Usa pull requests cuando el remoto esté disponible y tenga sentido.
Si no lo está, aplica el mismo proceso de revisión local.

Astra debe:
1. Leer el diff real y el reporte.
2. Verificar criterios de aceptación.
3. Revisar compatibilidad con el protocolo y los otros componentes.
4. Ejecutar o comprobar las validaciones relevantes.
5. Solicitar correcciones si hacen falta.
6. Registrar aprobación.
7. Integrar secuencialmente.
8. Verificar la integración.

La aprobación del autor no sustituye la revisión de Astra.
No es necesario pedir al usuario permiso para cada integración rutinaria ya incluida en el alcance autorizado.
No eludas restricciones o aprobaciones exigidas por el entorno.

14. VERSIONES Y DEPENDENCIAS

- Usa versiones estables y compatibles.
- Confirma archivos de bloqueo de dependencias.
- No uses referencias flotantes a “latest” en una compilación que deba ser reproducible.
- Fija las versiones relevantes de toolchains y herramientas.
- Separa las actualizaciones de dependencias de las funciones.
- Documenta su validación antes de adoptarlas.
- Revisa licencias de las dependencias distribuidas.
- Permite revertir cambios sin reescribir el historial compartido.
- Usa etiquetas de versión y notas breves de cambios.
- Si GitHub lo permite, configura protección de main y comprobaciones adecuadas.

15. PRUEBAS Y EVIDENCIA

Prioriza pruebas de comportamiento:
- Compatibilidad del protocolo entre ambas implementaciones.
- Integridad de archivos.
- Autorización y reconocimiento de dispositivos.
- Metadatos y rutas inválidas.
- Corte de conexión y reanudación.
- Archivo fuente modificado.
- Cancelación.
- Colisión de nombres.
- Espacio insuficiente.
- Archivos grandes y muchos pequeños.
- Versiones de protocolo incompatibles.

Las pruebas deben aportar evidencia, no duplicar literalmente la implementación.

Automatiza lo razonable mediante CI.
Compila Windows en un entorno Windows apropiado.
Valida la compilación Android y sus componentes en entornos compatibles.

Distingue siempre:
- Compilación correcta.
- Pruebas automatizadas.
- Prueba en emulador.
- Prueba real en Windows.
- Prueba real en el J4+.
- Medición real de rendimiento.

No afirmes haber probado dispositivos a los que no tienes acceso.
Cuando haga falta una prueba manual, entrega pasos concretos y el resultado esperado.
No declares terminado un hito si falta un criterio esencial: registra la validación pendiente.

16. DISTRIBUCIÓN E INSTALACIÓN

Trabájala después de estabilizar la transferencia, pero evita decisiones que la dificulten.

Windows:
- Generar un instalador .exe con asistente; evaluar NSIS mediante Tauri.
- Preferir instalación por usuario cuando sea posible.
- Comprobar WebView2 y resolver su ausencia.
- Documentar si el instalador necesita Internet.
- Considerar un paquete sin conexión posteriormente.
- Probar actualización y desinstalación.
- Evaluar versión portable como entrega posterior.
- Documentar las advertencias de Windows por reputación o firma sin tratarlas como un fallo de compilación.

Android:
- Generar APK de distribución firmado.
- Mantener la misma clave para futuras actualizaciones.
- Guardar la clave fuera del repositorio y documentar su respaldo.
- Probar actualización conservando configuración.

Distribución:
- Usar artefactos compilados de versiones publicadas.
- No exigir Rust, Node, Java, Android Studio o Git al usuario final.
- No compilar desde el repositorio durante la instalación.
- No incrustar credenciales de GitHub para acceder a un repositorio privado.
- Comenzar con descarga manual de versiones; actualizaciones automáticas quedan fuera del primer alcance.

17. HITOS

Hito 0 — Base:
- Inspección del entorno.
- Repositorio y reglas.
- Compatibilidad y versiones elegidas.
- Contrato mínimo.
- Tareas y responsabilidades.

Hito 1 — Transferencia funcional:
- Conexión manual.
- Transferencia cifrada en ambos sentidos.
- Emparejamiento mínimo verificable.
- Archivo recibido íntegro.
- Registro de rendimiento inicial.

Hito 2 — Conexión cómoda:
- Descubrimiento local.
- “Recibir archivos”.
- Selección del receptor.
- Dispositivos recordados.
- QR y alternativa manual.

Hito 3 — Uso cotidiano:
- Varios archivos.
- Progreso y cancelación.
- Destinos, nombres repetidos y errores claros.
- Manejo apropiado del ciclo de vida de Android.

Hito 4 — Recuperación:
- Reanudación tras corte de red.
- Después, persistencia para cierre y reinicio.
- Limpieza de temporales.

Hito 5 — Distribución personal:
- Instalador Windows.
- APK firmado.
- Documentación de instalación y actualización.
- Versiones etiquetadas y limitaciones conocidas.

Ajusta dependencias entre tareas sin perder estos resultados.

18. COMUNICACIÓN Y FORMA DE TRABAJAR

- Informa avances en español.
- Explica resultados, decisiones y bloqueos concretos.
- No solicites confirmación para decisiones rutinarias reversibles dentro del alcance.
- Pregunta cuando falte un dato que cambie materialmente el resultado, pero continúa con tareas independientes.
- No instales infraestructura o servicios externos sin necesidad.
- No envíes mensajes a terceros ni publiques secretos.
- No declares funciones terminadas solo porque un agente lo diga.
- No amplíes el alcance por iniciativa de un subagente.
- Mantén documentación suficiente para continuar, sin convertirla en burocracia.

COMIENZA ASÍ

Inspecciona el entorno y el repositorio disponible.
Identifica lo que puedes compilar y probar realmente.
Prepara las instrucciones del proyecto, la propuesta de protocolo y el tablero inicial.
Después delega las primeras tareas independientes con modelos disponibles de menor coste.

El primer objetivo verificable es transferir un archivo en ambos sentidos entre Windows y Android, de forma cifrada, comprobar su integridad y medir el rendimiento, antes de construir todas las funciones adicionales.