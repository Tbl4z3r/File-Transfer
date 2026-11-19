# Prueba funcional reportada por el usuario — 2026-11-17

Fuente: confirmación directa del usuario en esta conversación, posterior a la entrega de QR Windows 0.1.1 / Android 0.1.2. No es una prueba ejecutada por el agente. El commit/build exacto instalado no se registró de forma independiente.

| Operación | Redmi Note 14 5G | Samsung Galaxy J4+ |
|---|---|---|
| Emparejamiento por QR | Funciona según usuario | Funciona según usuario |
| PC → móvil, recepción de un archivo | Correcta según usuario | Correcta según usuario |
| Móvil → PC, envío de un archivo | Correcto según usuario | Correcto según usuario |
| Ambos móviles figuran en PC | Confirmado por usuario | Confirmado por usuario |

El usuario no observó interferencias en la red. No se infiere transferencia de archivos simultánea: el protocolo mantiene un stream activo global. No se aportaron en esta comprobación tamaños, hash externo, tiempos, cantidad de repeticiones o versión de SO/ABI. No se considera medida de rendimiento, ensayo de cortes, archivos >4 GiB ni validación de actualización/desinstalación.

Esta evidencia confirma el flujo básico y la lectura QR física en ambos modelos reportados, y aporta confirmación del arreglo de recepción. Los tests automatizados y compilaciones previos siguen documentados por separado. No implica que H2–H5 completos o la distribución estable estén terminados.

Siguiente trabajo solicitado: explorar bocetos de distribución visual antes de implementar cambios de interfaz. Mantener el transporte funcional mientras se decide la jerarquía de conexión, acciones, destino y progreso. Los bocetos interactivos de la conversación son contenido de revisión, no código de la app ni un cambio ya adoptado.
