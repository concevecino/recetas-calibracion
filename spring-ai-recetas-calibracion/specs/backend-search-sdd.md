# Backend de búsqueda de recetas: especificación SDD

SDD significa aquí Spec-Driven Development: requisito → contrato → prueba → implementación → evidencia. Este documento amplía `recipes-sdd.md`; no cambia las rutas vigentes.

## Objetivo y alcance

Encontrar recetas de un catálogo controlado y, opcionalmente, elaborar una respuesta con Claude basada solo en las recetas recuperadas. Calibrar recuperación por separado de generación para identificar qué componente mejora o empeora los resultados.

Base existente: Java 21, Maven, Spring Boot 3.5.15, Spring AI 1.1.8 y starter Anthropic. No se propone una migración automática. La documentación actual de Spring AI 2.x corresponde a Boot 4.x; no copiar sus APIs sin comprobar compatibilidad con 1.1.x.

## Contratos y criterios de aceptación

| ID | Requisito | Evidencia |
|---|---|---|
| REC-01 | Buscar sin credenciales ni red en modo léxico; normalizar acentos y mayúsculas | `SearchGoldTest`: consultas con variantes y ranking |
| REC-02 | Aplicar dieta y tiempo máximo antes de limitar resultados; desempatar por ID | Casos filtrados del gold y tests de índice existentes |
| REC-03 | Consulta vacía o top K inválido devuelve error de validación | Tests HTTP existentes, `IndexedRecipeTest` |
| REC-04 | Si no hay coincidencias, devolver lista vacía; no pedir recetas inventadas | Caso negativo gold y tests existentes de cero llamadas |
| REC-05 | Claude recibe únicamente recetas completas dentro del presupuesto | `IndexedRecipeTest`: contexto y número de llamadas |
| REC-06 | Modelo y clave configurables por entorno; errores del proveedor producen 503 | Perfil `claude`, tests del servicio existentes |
| REC-07 | Evaluar ranking con juicios versionados, sin usar el LLM como juez único | Gold JSON, `SearchGoldTest` y calibrador existente |
| REC-08 | Fixtures tienen IDs únicos, cantidades positivas, pasos y referencias válidas | `SearchGoldTest.fixtureHasUsableRecipes` |

API existente:

* `GET /api/recipes?query=tortilla&topK=5&diet=vegetariana&maxMinutes=40`: índice local.
* `POST /api/recipes/answer`: `{ "query": "tortilla española", "topK": 1, "contextBudget": 4000 }`; generación Claude con perfil activo.
* `POST /api/search` y `/api/calibrate`: API heredada con modo `lexical`, `semantic` o `hybrid`, `topK`, `threshold`, `diet`, `category`, `maxMinutes` y `referenceFilters`.

No asumir que ambas rutas comparten algoritmo o puntuaciones. El índice usa pesos IDF; el buscador heredado usa su propia línea base. Un umbral solo tiene significado dentro del motor que lo calcula.

## Arquitectura objetivo

Controlador → caso de uso → puerto de recuperación → catálogo/índice. El servicio de respuesta compone contexto y llama a un puerto de generación respaldado por Spring AI Anthropic. Evitar introducir base de datos, vector store o microservicios hasta necesitarlos. El catálogo JSON en memoria es suficiente para esta PoC.

Los filtros no pueden relajarse para rellenar top K. El contenido de recetas se trata como datos, nunca como instrucciones del sistema. Las respuestas deben citar IDs recuperados; el prompt actual no garantiza por sí solo fidelidad. La validación estructurada de citas es una mejora pendiente.

## Configuración vigente

Arranque offline: `mvn.cmd spring-boot:run`. Claude:

```powershell
$env:ANTHROPIC_API_KEY='clave-local'
$env:CLAUDE_MODEL='identificador-compatible-con-tu-cuenta'
$env:SPRING_PROFILES_ACTIVE='claude'
mvn.cmd spring-boot:run
```

El perfil configura temperatura 0, salida máxima de 300 tokens y un intento. Temperatura 0 no garantiza determinismo del proveedor. No guardar claves en JSON, Git ni informes. El modelo es configurable; no asumir acceso a uno concreto.

## Protocolo de calibración

1. Versionar catálogo, consultas, juicios y configuración juntos. El gold añadido es una regresión pequeña, no una demostración general de relevancia.
2. Para experimentos amplios, separar consultas por intención en desarrollo y holdout. Mantener variantes de la misma intención en el mismo grupo para evitar fuga.
3. En `/api/calibrate`, comparar `topK` 5 y 10 y umbrales 0, 0.1 y 0.25 en modo léxico. Usar topK >= 10 para comparar Recall@10 completo. Ejecutar con y sin filtros de referencia; no mezclar promedios de poblaciones diferentes.
4. Precision@5 = relevantes en primeras cinco / 5; Recall@10 = relevantes recuperados en diez / total relevante del gold; MRR = inverso de la primera posición relevante; NDCG@5 usa ganancia `2^grado - 1`. En consultas sin relevantes no calcular recall/NDCG como señal positiva: medir que no haya resultados.
5. Elegir parámetros en desarrollo; evaluar holdout una vez. Los umbrales de aceptación se fijan antes de ver el holdout. Como objetivo inicial propuesto: cero violaciones de filtros y ninguna regresión de NDCG@5 respecto a baseline; no son resultados medidos.
6. Registrar por ejecución: commit, hashes de datasets, motor, parámetros, resultados por consulta, promedios y duración. Comparar Claude aparte: citas válidas, fidelidad al contexto, respuesta ante ausencia de datos, Usage real y latencia.

## Entregas posteriores propuestas

La base ya permite variar top K, umbral y filtros en la API heredada; no expone pesos del índice ni reranking Claude. Para añadirlos: definir primero un contrato tipado con límites, tests de pesos y comparación sobre holdout. Si se necesita semántica sin OpenAI, elegir explícitamente un proveedor de embeddings independiente de Claude. Para producción: persistencia, límites de peticiones, autenticación de administración y evaluación de carga según requisitos reales.

## Recursos de prueba y ejecución

`src/test/resources/recetas_pruebas.json`: fixture sintético independiente con cantidades, raciones e instrucciones; no se carga automáticamente en el índice. `src/test/resources/search-gold.json`: consultas con IDs del catálogo actual de 61 recetas. `SearchGoldTest` ejecuta esas consultas contra el índice real y verifica el fixture independiente.

```powershell
mvn.cmd '-Dtest=SearchGoldTest' test
mvn.cmd test
```

La suite offline no demuestra calidad real de Claude. `ClaudeLiveTest` hace llamadas reales optativas y se mantiene desactivado por defecto.

Fuentes oficiales: [Spring AI: inicio y compatibilidad](https://docs.spring.io/spring-ai/reference/getting-started.html), [Anthropic en Spring AI 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/chat/anthropic-chat.html).
