# Código de emparejamiento

Windows ofrece un código copiable junto al QR. Android acepta el código y el descriptor JSON v1 en la conexión manual. El QR sigue codificando el JSON v1 para los lectores existentes.

El código empieza por `FT1-`, seguido de Base64 URL canónico sin relleno. Los datos contienen exactamente 86 bytes en este orden.

| Datos | Longitud en bytes | Codificación |
| --- | ---: | --- |
| IPv4 local | 4 | Octetos en orden de red |
| Puerto | 2 | Entero sin signo, byte más significativo primero |
| Identificador del PC | 16 | Pares hexadecimales del UUID en orden textual |
| Huella SHA-256 del certificado | 32 | Bytes de la huella |
| Token | 32 | Bytes del token v1 |

El código ocupa 119 caracteres. Android admite espacios exteriores al pegarlo. Verifica la forma canónica y los campos de la misma manera que para el descriptor v1. La huella TLS, la comprobación de identidad y las reglas de uso y caducidad del token se aplican a ambas representaciones. Copia el código desde la pantalla del PC que quieres vincular.
