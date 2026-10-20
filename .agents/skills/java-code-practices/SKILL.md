---
name: java-code-practices
description: Escribir, refactorizar y revisar código Java y Spring Boot del proyecto aplicando reglas de mantenibilidad, contratos, errores, concurrencia y pruebas. Usar al modificar clases Java, servicios, controladores o adaptadores del backend.
---

# Buenas prácticas de código Java

## Aplicación y contexto

Antes de cambiar código, leer las instrucciones del repositorio, el POM y las clases y pruebas relacionadas. Respetar Java 21, dependencias y estilo existentes; no incorporar versiones, librerías, Lombok ni funciones preview sin necesidad del requisito. Esta skill guía al agente: no configura por sí sola reglas del compilador ni del CI.

En el backend de recetas, complementar con `../recipe-search-backend/SKILL.md` para los contratos SDD y de recuperación. La raíz del módulo Maven es `../../../spring-ai-recetas-calibracion` respecto a esta carpeta.

Para formato, imports, llaves y orden uniforme de miembros, aplicar [java-class-style](../java-class-style/SKILL.md). Sus reglas concretan el estilo del proyecto para el código nuevo o reformateado.

Para integración Spring AI, inyección de modelos, prompts, contexto y pruebas de Claude, aplicar [spring-backend-practices](../spring-backend-practices/SKILL.md).

## Diseño y legibilidad

- Mantener una responsabilidad reconocible por clase y métodos con nombres de intención. Extraer lógica cuando mejora claridad o permite probar una regla; evitar interfaces con una sola implementación si no existe un límite útil de dominio o integración.
- Preferir composición e inyección por constructor. Evitar dependencias estáticas globales e inyección por campos.
- Mantener controladores centrados en HTTP, servicios en casos de uso y adaptadores en I/O. No introducir dependencias de Spring AI en el dominio.
- Preferir DTO tipados y `record` para valores inmutables. No propagar `Map<String, Object>` o árboles JSON por nuevas APIs internas cuando el esquema sea conocido. Conservar contratos heredados salvo cambio explícito.
- Usar `final` y copias defensivas cuando protejan invariantes. Un `record` que contiene una lista no hace inmutable su contenido: copiar listas al construir y no exponer estado mutable del catálogo.
- Evitar argumentos booleanos ambiguos y cadenas mágicas; usar tipos o configuración validada cuando representen opciones de negocio. No convertir cada literal en una constante sin aportar significado.
- Usar bucles cuando expresen mejor el algoritmo; reservar streams para transformaciones legibles sin efectos laterales. No usar `parallelStream()` en peticiones HTTP sin medir y controlar sus recursos.

## Contratos y corrección

- Validar entradas en el límite HTTP y preservar invariantes al construir objetos del dominio. Comprobar límites de top K, longitud, presupuesto y números finitos; `NaN` supera validaciones basadas solo en comparaciones de rango.
- Representar ausencia explícitamente. Preferir colecciones vacías; no devolver `null` como colección ni usar `Optional.get()` sin garantizar presencia. Usar `Optional` principalmente como retorno cuando mejora el contrato.
- Especificar unidades y límites inclusivos en nombres o tipos. Usar `Duration` para tiempos de espera y `Clock` inyectable si el comportamiento depende de la hora.
- Para texto de búsqueda, fijar `Locale.ROOT` y una política uniforme de acentos. No eliminar negaciones y asumir después que se han respetado restricciones dietéticas.
- Definir desempates estables del ranking y aplicar restricciones antes de top K. No depender del orden de un `HashMap` o `HashSet`.
- Usar UTF-8 explícito al leer o escribir recursos; cerrar streams con try-with-resources. No depender de la codificación de Windows.

## Spring e integraciones

- Modelar nueva configuración agrupada con `@ConfigurationProperties` y validación de límites. Conservar las propiedades públicas existentes cuando sea necesario para compatibilidad.
- No exponer entidades persistentes directamente por HTTP. Si se añade persistencia, delimitar transacciones en casos de uso; evitar llamadas al proveedor de IA dentro de una transacción de base de datos abierta.
- Configurar tiempos de espera, tamaño máximo de respuesta y concurrencia de llamadas externas. Reintentar solo fallos transitorios, con límite y considerando coste e idempotencia.
- Los beans singleton deben ser seguros para peticiones concurrentes. No guardar datos de una petición en campos del servicio. Acotar cachés y definir invalidación; una colección concurrente no resuelve por sí sola operaciones compuestas.
- Si se introduce SQL, parametrizar consultas. No construir SQL, comandos o URLs de destino externo concatenando entradas sin control.

## Errores y observabilidad

- Capturar excepciones donde se pueda recuperar o traducir el error; conservar la causa. No silenciar excepciones ni devolver éxito con resultados inventados.
- Traducir errores HTTP consistentemente y respetar el contrato existente. Al añadir un formato nuevo, considerar `ProblemDetail` sin romper clientes de forma implícita.
- No devolver stack traces, claves ni detalles internos. Usar logs parametrizados; no registrar claves, prompts o catálogos completos por defecto. Registrar IDs de correlación y datos mínimos necesarios para diagnosticar.
- Mantener separados errores de entrada, ausencia de resultados y caída del proveedor. Una búsqueda vacía válida no equivale a un fallo de Claude.

## Pruebas y revisión

- Vincular cada comportamiento nuevo o corregido con un test observable. Probar límites, errores y resultados, no nombres privados o llamadas internas sin relevancia contractual.
- Usar JUnit y las herramientas ya presentes. Probar reglas puras sin arrancar Spring; usar tests HTTP o de integración cuando importe el comportamiento de esa frontera. No cargar todo el contexto para probar una función pura.
- Simular el proveedor de IA en la suite offline; verificar presupuesto, fuentes y ausencia de llamadas cuando no hay resultados. Evitar aserciones sobre el texto exacto de una respuesta generativa.
- En ranking, mantener juicios versionados y comparar métricas; no cambiar expectativas únicamente para que pase una regresión. Usar semillas fijas si una prueba requiere aleatoriedad y evitar esperas temporales arbitrarias.
- Ejecutar desde el módulo `mvn.cmd test` con JAVA_HOME apuntando a Java 21; usar `mvn.cmd verify` si el cambio afecta verificaciones adicionales configuradas. Para cambios solo documentales, validar la skill y sus referencias, sin repetir toda la suite Java.
- Al revisar, informar hallazgos con archivo, impacto y corrección propuesta. Distinguir defectos funcionales de preferencias de estilo. Al entregar, indicar cambios, comprobaciones ejecutadas y límites pendientes.
