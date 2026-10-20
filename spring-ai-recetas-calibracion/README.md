# PoC SDD: API REST de recetas indexadas con Claude

Java 21, Spring Boot 3.5.15 (Spring Framework), Spring AI 1.1.8 y starter oficial `spring-ai-starter-model-anthropic`. No existe una dependencia oficial llamada `spring-starter-ia`: se utiliza el starter de Anthropic documentado por Spring.

Especificación y trazabilidad: [specs/recipes-sdd.md](specs/recipes-sdd.md).
Skills del proyecto: `../.agents/skills/recipes-sdd/SKILL.md` y `../.agents/skills/recipes-token-evidence/SKILL.md`.

## Arranque y demostración de la API nueva

Desde este módulo, con JAVA_HOME apuntando a Java 21:

```powershell
mvn.cmd test
mvn.cmd spring-boot:run
```

La búsqueda indexada funciona sin claves ni tokens de IA:

```powershell
Invoke-RestMethod 'http://localhost:8080/api/recipes?query=tortilla&topK=1&diet=vegetariana&maxMinutes=40'
```

Para generar la respuesta con Claude, detener y arrancar con su perfil:

```powershell
$env:ANTHROPIC_API_KEY='tu-clave'
$env:SPRING_PROFILES_ACTIVE='claude'
# Opcional: $env:CLAUDE_MODEL='modelo-compatible-de-tu-cuenta'
mvn.cmd spring-boot:run
```

En otra terminal:

```powershell
$body = @{query='tortilla española';topK=1;contextBudget=4000} | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/api/recipes/answer' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) | ConvertTo-Json -Depth 10
```

Modelo predeterminado configurable: `claude-haiku-4-5-20251001`. Salida limitada a 300 tokens; sin historial. La respuesta incluye IDs de fuentes seleccionadas y métricas `usage`. Un error de proveedor o perfil no configurado devuelve 503. Sin coincidencias o sin espacio para una receta completa no se llama a Claude.

El índice invertido se construye una vez desde `src/main/resources/recetas_rag_calibracion.json`. Normaliza acentos/mayúsculas, recupera candidatos por términos y ordena por pesos IDF. Solo se envían topK recetas completas que caben en el presupuesto. No se envían metadatos ni el campo redundante texto_busqueda. Los filtros diet y maxMinutes son explícitos: la consulta libre no interpreta restricciones dietéticas. Esta búsqueda es léxica, sin embeddings ni comprensión semántica.

## Evidencia JUnit y consumo

`IndexedRecipeTest` verifica recuperación, filtros, HTTP, presupuesto, una sola llamada al modelo simulado y cero llamadas cuando no procede. `ApplicationTest` comprueba el arranque sin credenciales; se mantienen los tests de calibración previos.

Verificación de esta entrega: `mvn.cmd test` con Java 21 terminó correctamente: **11 tests aprobados y 1 omitido** (ClaudeLiveTest). Ambas skills pasaron `quick_validate.py`.

La consulta de prueba «tortilla española» con topK=1 selecciona `receta_0001`: contexto compacto de **547** frente a **37.824** unidades para el catálogo completo con la misma proyección (**98,55 % menos contexto estimado**).

Los campos `*EstimatedTokens` cuentan bytes UTF-8 como aproximación conservadora. No son tokens reales de Claude. El presupuesto limita el contexto de recetas; sistema, consulta y protocolo añaden entrada. Los campos `actualInputTokens` y `actualOutputTokens` proceden de Usage del proveedor; los tests offline simulan esos valores para comprobar su propagación. No se afirma mínimo absoluto de tokens ni ahorro monetario medido.

Para demostrar ahorro de **tokens reales de entrada**, ejecutar el test optativo con clave y conectividad:

```powershell
$env:RUN_CLAUDE_LIVE='true'
$env:ANTHROPIC_API_KEY='tu-clave'
mvn.cmd '-Dtest=ClaudeLiveTest' test
Remove-Item Env:RUN_CLAUDE_LIVE
```

Este test hace **dos peticiones facturables**: recuperación indexada y línea base con todo el catálogo compacto; compara Usage real e imprime ambos valores. Se omite por defecto. No se ha ejecutado con Claude real en esta entrega por falta de credenciales. Los informes JUnit se guardan en `target/surefire-reports`.

Las recetas son sintéticas, con cantidades nulas y etiquetas dietéticas aproximadas. El prompt pide no inventar datos y citar IDs, pero no valida automáticamente la fidelidad de la respuesta ni garantiza ausencia de alérgenos.

Fuentes: [integración oficial Spring AI/Anthropic](https://docs.spring.io/spring-ai/reference/1.0/api/chat/anthropic-chat.html) y [modelo Claude Haiku 4.5](https://platform.claude.com/docs/en/models/haiku-4-5/overview).

## Aplicación anterior: consultas y calibración

Aplicación local Spring Boot con 61 recetas y 30 consultas de referencia.
Requiere Java 21 y Maven. No necesita PostgreSQL ni Docker: catálogo y caché de embeddings en memoria.

## Arrancar en Windows

Desde la carpeta superior ejecuta `./arrancar.ps1` y abre http://localhost:8080.
El lanzador selecciona el JDK 21 de `C:\Program Files\Java\jdk-21` si existe.
Alternativamente, desde esta carpeta, con JAVA_HOME apuntando a un JDK 21:

```powershell
mvn.cmd spring-boot:run
```

El servidor escucha únicamente en la interfaz local. La búsqueda por palabras funciona sin clave ni llamadas externas.

## API key de OpenAI

No se distribuye ninguna clave real. Copia `application-local.properties.example` como `application-local.properties` y rellena:

```properties
recipes.api-key=TU_CLAVE_REAL
```

Este archivo está excluido de Git. También puedes establecer `OPENAI_API_KEY` antes de arrancar.
Reinicia y selecciona el motor semántico o híbrido. El modelo predeterminado es `text-embedding-3-small`, configurable con `OPENAI_EMBEDDING_MODEL`.
La clave se utiliza solo en el servidor. Las búsquedas semánticas envían consultas y recetas a OpenAI y requieren saldo en esa cuenta.
Referencia: https://developers.openai.com/api/reference/resources/embeddings/methods/create

El primer uso de la API anterior genera embeddings de la consulta y las recetas candidatas. La calibración genera los de las 30 consultas. Se reutilizan en memoria hasta el siguiente arranque (caché limitada a 2000 textos). Esta API anterior no genera respuestas de chat; la nueva API `/api/recipes/answer` utiliza Claude.

## Consultar y calibrar

- Escribe una consulta o selecciona una de referencia.
- Ajusta motor, Top K, umbral, dieta, categoría y tiempo máximo.
- Pulsa **Calibrar las 30 consultas**. Cada ejecución aparece en la comparación de esta sesión.
- Despliega las filas para ver resultados, puntuaciones y relevancia esperada.
- **Exportar JSON** guarda la última evaluación y las ejecuciones comparadas. El historial se pierde al recargar.

La búsqueda por palabras normaliza acentos y calcula la proporción de términos encontrados. Es una línea base léxica.
El motor semántico usa similitud coseno. El híbrido combina 70 % semántico y 30 % léxico. El umbral se aplica a la puntuación del motor elegido.

Precision@5 divide siempre entre 5. Recall@10 considera todos los IDs positivos de referencia. MRR considera el primer relevante en Top K. NDCG@5 usa grados 3 (alta), 1 (media), 0 (resto). Top K menor de 10 puede limitar estas métricas.
Los filtros laterales se aplican también al evaluar. Los filtros de cada consulta se activan por separado. Ingredientes incluidos requieren todas las coincidencias textuales; categorías, cocinas y dietas de referencia permiten cualquiera de sus valores. Exclusiones usan coincidencias textuales; excluir_carnes requiere etiqueta vegetariana o vegana. No hay taxonomía: términos como legumbres o pescado blanco pueden no coincidir con ingredientes concretos. Las etiquetas dietéticas y los juicios requieren revisión.

## API y pruebas

- `GET /api/catalog`: catálogo, consultas y disponibilidad de configuración semántica (no valida saldo ni clave).
- `POST /api/search`: recetas ordenadas y puntuaciones.
- `POST /api/calibrate`: parámetros, promedios y evaluación por consulta.

Cuerpo de búsqueda o calibración:

```json
{"query":"garbanzos con verduras","mode":"lexical","topK":10,"threshold":0,"diet":"","category":"","maxMinutes":0,"referenceFilters":false}
```

`mvn.cmd test` comprueba métricas, filtros, validación y evaluación sin consumir API.

El runner original se conserva como ejemplo de integración con un VectorStore de Spring AI. Solo se activa con el perfil `legacy-cli`, que requiere aportar un bean VectorStore externo; no hace falta para la aplicación web.

## Inicio SDD y nuevos datos de prueba

La especificación [backend-search-sdd.md](specs/backend-search-sdd.md) define requisitos, criterios de aceptación y el protocolo de calibración. La skill está en `../.agents/skills/recipe-search-backend/SKILL.md`.

El archivo `src/test/resources/recetas_pruebas.json` contiene seis recetas sintéticas con cantidades y pasos, como fixture independiente; no sustituye al catálogo activo de 61 recetas. `src/test/resources/search-gold.json` aporta siete casos contra el catálogo activo: ranking, acentos, filtros y ausencia de resultados. `SearchGoldTest` verifica ambos recursos.

Ejecutar desde este módulo con Java 21: `mvn.cmd test` (o `mvn.cmd -o test` si las dependencias ya están disponibles). El protocolo SDD diferencia funciones existentes y mejoras propuestas, e identifica el proveedor de embeddings de la API heredada.


## SDD de datasets y evaluación de modelos

La propuesta [dataset-training-analysis-sdd.md](specs/dataset-training-analysis-sdd.md) define una aplicación Java y Spring para clasificación supervisada, división 70/15/15 configurable, entrenamiento local o inferencia Claude, validación y matriz de confusión. La implementación está descrita en [ml-implementation.md](specs/ml-implementation.md).


## Aplicación de datasets y matriz de confusión

Nuevo backend `/api/ml`: importación, análisis, split estratificado con grupos, entrenamiento local Naive Bayes, validación, congelación y evaluación final. Funciona sin credenciales en modo local y conserva la API de recetas.

Con Java 21, desde este módulo:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn.cmd verify
mvn.cmd spring-boot:run
```

En otra terminal, desde el mismo módulo:

```powershell
.\scripts\demo-ml.ps1
```

La demo importa 300 ejemplos sintéticos, solicita 70/15/15, compara alpha 0.1, 1 y 10 mediante macro-F1 de validación y exporta la matriz y predicciones a `target/ml-demo/report.json`. Los grupos indivisibles pueden alterar ligeramente los tamaños; consultar los conteos reales del manifiesto.

Fixtures en `src/test/resources/ml`: `recipes-balanced.json`, `recipes-imbalanced.json`, `too-small.json` y `label-conflict.json`. Son pruebas técnicas, no evidencia de calidad en datos reales. El modo `CLAUDE_INFERENCE` usa Spring AI sin entrenar pesos de Claude.

Estado y modelos se guardan en `data/ml/state.json`; cambiar con `--ml.storage-directory=...`. Una instancia por directorio. Ver [contratos, arquitectura y límites](specs/ml-implementation.md).

Verificación de esta entrega: Maven verify correcto, 32 tests aprobados y ClaudeLiveTest omitido. La demo HTTP exportó 42 predicciones y la matriz de confusión; el reparto efectivo fue 210/48/42 por los grupos indivisibles. Ver la evidencia y sus límites en el documento de implementación.
