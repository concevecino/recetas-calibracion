# SDD: aplicación Java y Spring para datasets, entrenamiento y evaluación

Estado: implementado como PoC local en `com.example.rag.ml`. Ver evidencia y límites en [ml-implementation.md](ml-implementation.md). SDD: requisito → contrato → prueba → implementación → evidencia.

## 1. Objetivo

Crear un backend Java con Spring Boot para importar datasets etiquetados, analizar su calidad, dividirlos sin fuga de información, entrenar un clasificador, ajustar su configuración y obtener métricas sobre un conjunto de test reservado. El resultado debe incluir matriz de confusión, métricas por clase, predicciones exportables y trazabilidad del experimento.

La primera versión resuelve clasificación supervisada de etiqueta única, binaria o multiclase. Ejemplo de recetas: clasificar una descripción en `entrante`, `principal` o `postre`. Regresión, clasificación multietiqueta, entrenamiento distribuido y fine-tuning de grandes modelos quedan fuera del primer alcance.

## 2. Stack y relación con Spring AI

Mantener Java 21, Maven y las versiones compatibles del proyecto existente. Spring Boot gestiona API, validación, configuración, orquestación y persistencia de experimentos. Aplicar las skills Java, estilo de clases y Spring AI existentes bajo `../.agents/skills` desde la raíz del módulo.

Spring AI permite integrar Claude para inferencia y análisis opcional de resultados. Invocar Claude con un prompt no entrena sus pesos. El backend debe distinguir explícitamente estos modos:

- `LOCAL_TRAINABLE`: clasificador entrenable en JVM, detrás de un puerto `TrainableClassifier`; primera implementación propuesta: Naive Bayes multinomial para texto, con suavizado configurable. Elegir y fijar biblioteca compatible antes de implementar el adaptador, o justificar una implementación propia y verificarla contra resultados conocidos.
- `CLAUDE_INFERENCE`: clasificación mediante Spring AI Anthropic. No ejecuta entrenamiento de pesos; utiliza entrenamiento únicamente como pool permitido de ejemplos few-shot. Prompts, ejemplos y parámetros se seleccionan con entrenamiento y validación. Test permanece reservado.

Los experimentos guardan el modo para evitar comparaciones engañosas. No añadir servicios Python ni presentar fine-tuning de Claude como una capacidad existente. La suite offline funciona sin claves. Las llamadas reales a Claude requieren configuración y autorización para su coste.

## 3. Roles de las particiones

| Partición | Uso permitido | Uso no permitido |
|---|---|---|
| Entrenamiento (`train`) | Ajustar parámetros, vocabulario, transformaciones aprendidas y ejemplos few-shot | Incorporar datos de validación o test al ajuste |
| Validación (`validation`) | Elegir hiperparámetros, umbrales, prompt y candidato | Informarla como resultado final independiente |
| Test (`test`) | Evaluación final del candidato congelado | Ajustar el modelo, prompt o umbral a partir de sus resultados |

Configuración predeterminada: **70 % entrenamiento, 15 % validación y 15 % test**. Ejemplo alternativo: 80/10/10. Son tres particiones separadas; validación no es otro nombre para test.

Cada porcentaje es configurable en puntos porcentuales enteros: entrenamiento entre 70 y 80, validación entre 10 y 20 y test entre 10 y 20, extremos incluidos. Su suma debe ser exactamente 100. Por ejemplo, 80/15/15 se rechaza aunque cada componente esté en su intervalo.

## 4. Contrato del dataset

Importación inicial mediante JSON UTF-8; CSV se puede añadir como adaptador posterior. Una versión publicada es inmutable. Las correcciones crean una versión nueva.

```json
{
  "name": "recipe-course-classification",
  "schemaVersion": "1.0",
  "task": "SINGLE_LABEL_CLASSIFICATION",
  "labels": ["entrante", "principal", "postre"],
  "records": [
    {
      "id": "sample-0001",
      "text": "Tarta de manzana horneada con canela",
      "label": "postre",
      "groupId": "recipe-family-001"
    }
  ]
}
```

El ejemplo muestra el formato, no un dataset suficiente para entrenar. Cada registro requiere ID único, texto no vacío y etiqueta perteneciente al catálogo declarado. `groupId` es opcional en la importación, pero obligatorio para registros relacionados cuando la fuente conoce esa relación. No incluir la etiqueta objetivo como un campo de entrada del modelo; tampoco campos derivados de ella.

Analizar número de registros, distribución de clases, longitudes, vacíos, duplicados exactos, etiquetas inválidas y grupos con etiquetas contradictorias. Rechazar el mismo texto normalizado con etiquetas diferentes hasta resolver el conflicto. Agrupar duplicados exactos y registros relacionados para que no crucen particiones. La detección de similitud semántica entre paráfrasis es una mejora posterior; no afirmar que el hash elimina toda fuga.

Normalización para detectar duplicados: Unicode NFC, minúsculas con `Locale.ROOT`, espacios consecutivos colapsados y extremos recortados. Guardar versión del algoritmo; conservar el texto original. Los componentes conexos por `groupId` compartido o hash de texto compartido forman grupos indivisibles.

## 5. División reproducible y sin fuga

Contrato de creación:

```json
{
  "trainPercent": 70,
  "validationPercent": 15,
  "testPercent": 15,
  "seed": 42,
  "strategy": "STRATIFIED_GROUP",
  "maxDeviationPercentagePoints": 5
}
```

Reglas obligatorias:

1. Validar porcentajes, semilla, clases y grupos antes de asignar registros. El conjunto unido de las particiones debe coincidir exactamente con los IDs del dataset y sus intersecciones deben estar vacías.
2. Para registros independientes, calcular objetivos enteros por clase mediante mayores restos: tomar partes enteras y repartir el sobrante por resto descendente. Desempate fijo: train, validation, test. Conservar al menos un registro de cada clase en cada partición; si la proporción o el tamaño lo impide, rechazar con diagnóstico en vez de duplicar registros.
3. Para grupos indivisibles, usar asignación determinista que minimice desviaciones de tamaño y distribución por clase. Ordenar previamente por ID, fijar generador y versión del algoritmo, y documentar desempates. No depender del orden del JSON ni de iteración de un HashMap.
4. Calcular desviación absoluta en puntos porcentuales para tamaños globales y por clase. Usar los objetivos obtenidos por redondeo como referencia. Si se supera la tolerancia configurada, o falta una clase en una partición, devolver `SPLIT_NOT_FEASIBLE` con conteos y grupos responsables. No declarar estratificación exacta con grupos si solo es aproximada.
5. Guardar manifiesto con IDs, partición, hash de contenido, tamaños reales, distribución por clase, porcentajes solicitados, semilla y versión del algoritmo.
6. Misma versión, configuración y semilla produce idéntico manifiesto, incluso si cambia el orden de entrada. Otra semilla puede cambiar el reparto, pero no se garantiza que siempre lo haga en datasets pequeños.

Los porcentajes solicitados son objetivos: los tamaños enteros y grupos pueden impedir igualdad exacta. No eliminar ejemplos ni duplicarlos para aparentar el porcentaje. Si una clase tiene menos de tres grupos independientes, no es posible representarla en las tres particiones con esta política.

Para datos temporales, pacientes, usuarios o sesiones, el protocolo debe priorizar su independencia real. La primera versión no permite declarar un experimento temporal válido mediante mezcla aleatoria: rechazar ese tipo de fuente hasta implementar estrategia temporal con grupos y sus pruebas.

## 6. Entrenamiento y selección del candidato

Pipeline local propuesto: tokenización versionada → vocabulario aprendido solo con train → vector de frecuencias → Naive Bayes multinomial. Las transformaciones aprendidas se ajustan exclusivamente sobre train; validation y test reutilizan el artefacto congelado. Los términos desconocidos se gestionan con una política explícita y verificable.

Baseline obligatorio: clase mayoritaria calculada en train. Conservar predicciones y métricas de baseline para comparar en la misma partición. No usar accuracy como único criterio en datos desbalanceados.

Cada candidato registra datasetVersionId, splitId, modo, algoritmo, parámetros, semilla, versión del código y del preprocesado, hashes de artefactos y estado. Grid inicial propuesto de suavizado local: 0.1, 1.0 y 10.0. Evaluar candidatos con macro-F1 de validación; desempatar por configuración canónica ordenada. No es un resultado medido ni una garantía de calidad.

Tras seleccionar, congelar candidato, etiqueta positiva si aplica, umbrales y configuración. No reentrenar con validation en la primera versión. Si se solicita entrenar luego con train+validation, tratarlo como un artefacto distinto y definir previamente su protocolo de evaluación.

En Claude, congelar también modelo declarado, prompt, ejemplos, opciones y fecha. No asumir reproducibilidad exacta de un proveedor externo por usar temperatura cero. Una evaluación por petición debe limitar tokens, llamadas, tiempos de espera y reintentos.

## 7. Matriz de confusión y métricas

Orientación fija: **filas = etiqueta real, columnas = etiqueta predicha**. Orden de clases igual al catálogo `labels` versionado, incluyendo clases sin predicciones. Guardar conteos enteros, no solo porcentajes.

Ejemplo binario independiente del dataset anterior, con clase positiva `positive`:

```json
{
  "labels": ["negative", "positive"],
  "orientation": "ROWS_ACTUAL_COLUMNS_PREDICTED",
  "matrix": [[40, 5], [10, 45]],
  "evaluatedCount": 100,
  "positiveLabel": "positive",
  "accuracy": 0.85,
  "positivePrecision": 0.90,
  "positiveRecall": 0.8181818182,
  "positiveF1": 0.8571428571
}
```

En el ejemplo: TN=40, FP=5, FN=10, TP=45. La etiqueta positiva se declara; no inferirla de su posición alfabética.

Para cada clase c en multiclase, calcular one-vs-rest: TP=M[c,c], FN=suma de fila menos TP, FP=suma de columna menos TP y TN=N−TP−FN−FP. Precision=TP/(TP+FP), recall=TP/(TP+FN), F1=2TP/(2TP+FP+FN). Support es el número de casos reales de la clase. Accuracy es suma de diagonal/N.

Informar precision, recall, F1 y support por clase, macro-F1, weighted-F1, balanced accuracy y accuracy. Macro-F1 es media de F1 por clase; weighted-F1 pondera por support; balanced accuracy es media de recalls. No redondear antes de agregar. Cuando un denominador es cero, devolver `null` y un indicador de métrica indefinida. Si alguna clase del catálogo tiene una métrica indefinida, el agregado que dependa de ella también es `null`; no omitirla silenciosamente. La partición de evaluación vacía se rechaza.

Para probabilidades, validar dimensión, valores finitos, rango [0,1] y suma con tolerancia documentada. No presentar la confianza textual de Claude como probabilidad calibrada. Curvas ROC/PR y calibración probabilística quedan para una entrega posterior.

Una etiqueta desconocida, salida inválida o fallo del proveedor no se convierte en acierto ni se elimina del informe silenciosamente. Estado `INCOMPLETE`, conteo de fallos y coverage=predicciones válidas/total solicitado. Puede mostrarse una matriz provisional de válidos, identificada como tal, pero no publicarse como evaluación final hasta completar todos los casos.

## 8. API REST propuesta

Prefijo separado `/api/ml` para no alterar la API de recetas existente.

| Método y ruta | Acción | Resultado esperado |
|---|---|---|
| POST /api/ml/datasets | Importar y publicar versión JSON | 201 con datasetVersionId y hash |
| GET /api/ml/datasets/{datasetVersionId}/analysis | Distribución y problemas de calidad | 200 con resumen |
| POST /api/ml/datasets/{datasetVersionId}/splits | Crear división reproducible | 201 con splitId y conteos |
| GET /api/ml/splits/{splitId}/manifest | Exportar IDs y particiones | 200 JSON |
| POST /api/ml/experiments | Registrar candidato y trabajo de entrenamiento local o preparación Claude | 202 con experimentId y Location |
| GET /api/ml/experiments/{experimentId} | Consultar estado y artefactos | 200 |
| POST /api/ml/experiments/{experimentId}/validation | Evaluar candidato en validación | 202 con evaluationId |
| POST /api/ml/experiments/{experimentId}/freeze | Congelar candidato con referencia a validación | 200 |
| POST /api/ml/experiments/{experimentId}/test | Evaluar candidato congelado en test reservado | 202 con evaluationId |
| GET /api/ml/evaluations/{evaluationId} | Matriz, métricas y coverage | 200 |
| GET /api/ml/evaluations/{evaluationId}/predictions | Exportar ID, real y predicción | 200 JSON |

`POST /experiments` recibe splitId, mode, algoritmo/configuración y versión del preprocesado. El backend obtiene IDs de train del manifiesto; el cliente no puede pasar un conjunto arbitrario como entrenamiento. El mismo principio rige validación y test.

Errores: 400 para esquema o porcentajes inválidos, 404 para recursos inexistentes, 409 para transición incompatible o mutación de un candidato congelado y 422 para partición inviable. Integraciones indisponibles producen error explícito y estado de trabajo fallido; nunca éxito vacío. Usar un formato de error consistente con código, mensaje y detalles sin secretos.

Trabajo asíncrono con estados QUEUED, RUNNING, SUCCEEDED y FAILED; evaluación puede terminar INCOMPLETE. Un POST con la misma Idempotency-Key y cuerpo devuelve el mismo trabajo; con cuerpo distinto devuelve 409. Acotar colas y concurrencia.

Una evaluación final se vincula a split, candidato congelado y protocolo. Repetir la misma solicitud recupera el resultado existente, sin consumir llamadas otra vez. Un fallo técnico permite reintentar los casos fallidos conservando los resultados válidos y la trazabilidad. Cambiar configuración tras ver test obliga a marcar el conjunto como expuesto para futuras comparaciones y preparar un nuevo holdout antes de afirmar una evaluación independiente.

## 9. Arquitectura y almacenamiento

Paquetes propuestos bajo un módulo o feature `ml`: `api`, `application`, `domain` e `infrastructure`. Casos de uso: ImportDataset, AnalyzeDataset, CreateSplit, TrainCandidate, ValidateCandidate, FreezeCandidate y EvaluateTest. Dominio: DatasetVersion, Sample, SplitManifest, Experiment, Prediction y EvaluationReport.

Puertos separados para catálogo, artefactos, entrenamiento, inferencia y repositorio de experimentos. `ConfusionMatrixCalculator` es una función de dominio sin dependencia de Spring ni de Claude. El particionador tampoco necesita arrancar el contexto para probarse.

PoC local: metadatos y manifiestos JSON, artefactos en un directorio configurado y escritura atómica. Al confirmar publicación, evitar sobrescrituras; en reinicio recuperar metadatos y marcar trabajos interrumpidos. Cada archivo se identifica por ID generado; nunca usar un nombre importado como ruta. Guardar hash SHA-256, algoritmo y versión. PostgreSQL y almacenamiento de objetos son opciones posteriores, no requisitos de este fichero.

## 10. Requisitos y aceptación

Las responsabilidades de prueba de la tabla están implementadas en `MlDomainTest`, `MlServiceTest` y `ClaudeClassificationGatewayTest`; ver trazabilidad en `ml-implementation.md`.

| ID | Criterio verificable | Prueba propuesta |
|---|---|---|
| ML-01 | Rechazar IDs repetidos, etiquetas desconocidas y textos vacíos | DatasetImportTest |
| ML-02 | Aceptar 70/15/15 y 80/10/10; rechazar 80/15/15 y valores fuera de rango | SplitConfigurationTest |
| ML-03 | En 1000 registros, dos clases equilibradas y sin grupos, obtener 700/150/150 | StratifiedSplitTest |
| ML-04 | Sin IDs, duplicados o grupos compartidos entre particiones; unión completa | GroupLeakageTest |
| ML-05 | Semilla y versión iguales generan mismo manifiesto aun reordenando JSON | SplitReproducibilityTest |
| ML-06 | Clase con menos de tres grupos independientes da 422 con diagnóstico | SmallDatasetTest |
| ML-07 | Palabra exclusiva de test no aparece en vocabulario entrenado | PreprocessingLeakageTest |
| ML-08 | Elegir candidato con validación sin leer test; test exige freeze | ExperimentLifecycleTest |
| ML-09 | Pares conocidos generan [[40,5],[10,45]] y métricas del ejemplo | ConfusionMatrixCalculatorTest |
| ML-10 | Matriz multiclase conserva orden, suma N y diagonales correctas | MulticlassMetricsTest |
| ML-11 | Cero denominadores da null e indicadores; errores de predicción hacen informe incompleto | UndefinedMetricsTest |
| ML-12 | Proveedor simulado permite evaluar sin credenciales ni red | OfflineInferenceTest |
| ML-13 | Repetir solicitud idempotente no duplica entrenamiento ni llamadas | ExperimentIdempotencyTest |
| ML-14 | Exportar y recargar artefactos conserva predicciones locales | ModelArtifactRoundTripTest |

Fixtures de implementación: dataset balanceado de al menos 100 registros por clase, desbalanceado, duplicados relacionados, conflictos de etiquetas, dataset demasiado pequeño y predicciones binarias/multiclase conocidas. Son pruebas técnicas; datos sintéticos no demuestran generalización en datos reales. No reutilizar sin adaptación los seis ejemplos culinarios existentes como benchmark suficiente de entrenamiento.

## 11. Secuencia de implementación y definición de terminado

1. Dominio de datasets y validación de importación; fixtures y tests ML-01.
2. Split con manifiestos, análisis de distribución y tests ML-02 a ML-06.
3. Clasificador local, baseline y artefactos; tests ML-07 y ML-14.
4. Ciclo train/validation/freeze/test, matriz y métricas; tests ML-08 a ML-11.
5. API, persistencia, idempotencia y recuperación; ML-13 e integración HTTP.
6. Adaptador Spring AI Claude opcional; ML-12 y evaluación real autorizada aparte.

La entrega estará terminada cuando un dataset válido se importe, se divida, entrene, valide y evalúe mediante API; los artefactos puedan recargarse; la matriz coincida con predicciones exportadas; y `mvn.cmd verify` con Java 21 pase sin llamadas externas en la suite predeterminada. Documentar comandos, resultados, hashes y limitaciones. Ningún porcentaje de calidad mínimo se da por alcanzado: acordarlo con datos representativos antes de usar el holdout.


## 12. Decisiones de implementación

- El split usa mayores restos sin grupos y una heurística greedy determinista con grupos: minimiza el incremento de error cuadrático normalizado por objetivo, con desempate train/validation/test. Puede rechazar una configuración aunque una búsqueda combinatoria encontrara solución; nunca publica particiones que violen cobertura o tolerancia.
- Naive Bayes se implementa en Java sin otra dependencia: algoritmo reducido contrastado con probabilidades calculadas a mano y pruebas de serialización. Ignora tokens desconocidos, usa priors empíricos y desempata por orden de etiquetas.
- La demo ejecuta el grid alpha 0.1/1/10. Freeze compara macro-F1 con los candidatos del mismo split y modo ya evaluados y desempata por configuración canónica ordenada. Hay que esperar a que todos los candidatos planificados terminen antes de congelar.
- LOCAL_TRAINABLE usa MULTINOMIAL_NAIVE_BAYES. CLAUDE_INFERENCE usa CLAUDE_FEW_SHOT y prepara solo baseline y ejemplos de train, sin entrenar pesos. Ejemplos deterministas por clase e ID, máximo 10.
- Límites PoC: 20000 registros, 100 clases, 10000 caracteres por texto, 2000000 caracteres acumulados y vocabulario de 20000 términos. Tolerancia entre 0 y 20 puntos, por defecto 5. Claude: 300 casos por evaluación, 16000 bytes UTF-8 de prompt estimado y 30 segundos de espera por llamada del adaptador.
- El timeout cancela la tarea Java, pero no garantiza cancelar la petición o evitar su coste en Anthropic. Un trabajador y una cola de un elemento acotan las tareas del proveedor; el perfil Claude limita reintentos a un intento.
- Persistencia en una instantánea JSON atómica con bloqueo exclusivo del directorio. Contiene datasets, manifiestos, modelos y evaluaciones; está diseñada para volumen local. El reinicio marca QUEUED/RUNNING como FAILED. Las evaluaciones pueden reanudarse conservando predicciones publicadas.
- Tras usar test se rechazan nuevos candidatos y el test de otro candidato sobre ese split. Reimportar los mismos datos o reparticiones no demuestra independencia: un holdout nuevo debe contener datos no utilizados para decidir ajustes. No se impone independencia científica entre todos los históricos solo mediante hashes.
- Idempotency-Key cubre experimentos y evaluaciones. La evaluación canónica por candidato y partición evita repetir llamadas con claves nuevas. Una inferencia remota interrumpida antes de persistir su resultado puede repetirse al reintentar; no se promete exactly-once frente al proveedor.
