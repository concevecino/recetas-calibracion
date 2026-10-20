# Implementación del flujo de datasets

Se implementa la especificación `dataset-training-analysis-sdd.md` en `com.example.rag.ml`, manteniendo la API de recetas. No se ha realizado entrenamiento ni evaluación de Claude real durante el desarrollo.

## Ejecución y contratos

La demo `scripts/demo-ml.ps1` recorre la API con un clasificador local. Requiere la aplicación arrancada y permite cambiar `-BaseUrl`, `-DatasetPath` y `-OutputPath`. No usa credenciales ni hace llamadas al proveedor de IA.

Ejemplo de importación manual:

```powershell
$base = 'http://localhost:8080/api/ml'
$bytes = [IO.File]::ReadAllBytes((Resolve-Path 'src/test/resources/ml/recipes-balanced.json'))
$dataset = Invoke-RestMethod "$base/datasets" -Method Post -ContentType 'application/json' -Body $bytes
$splitBody = '{"trainPercent":70,"validationPercent":15,"testPercent":15,"seed":42,"strategy":"STRATIFIED_GROUP","maxDeviationPercentagePoints":5}'
$split = Invoke-RestMethod "$base/datasets/$($dataset.datasetVersionId)/splits" -Method Post -ContentType 'application/json' -Body $splitBody
```

`POST /api/ml/experiments` recibe:

```json
{
  "splitId": "ID-DEL-MANIFIESTO",
  "mode": "LOCAL_TRAINABLE",
  "algorithm": "MULTINOMIAL_NAIVE_BAYES",
  "alpha": 1.0,
  "preprocessingVersion": "nfc-word-count-v1",
  "positiveLabel": null
}
```

Devuelve 202, experimentId y Location. Consultar GET /experiments/{experimentId} hasta SUCCEEDED o FAILED. Después solicitar POST /experiments/{experimentId}/validation y consultar el evaluationId. Congelar mediante POST /experiments/{experimentId}/freeze con `{"validationId":"..."}`. Solo entonces se permite POST /experiments/{experimentId}/test. Las rutas completas llevan prefijo `/api/ml`.

La evaluación devuelve conteos absolutos en `metrics.matrix`, filas reales y columnas predichas según `metrics.labels`. Incluye métricas por clase, macro-F1, weighted-F1, balanced accuracy, baseline de clase mayoritaria y predicciones. GET /evaluations/{evaluationId}/predictions exporta los pares real/predicho y errores. Las métricas indefinidas son null; los fallos de inferencia producen INCOMPLETE y coverage inferior a 1, nunca se ocultan como resultados completos.

Las solicitudes de entrenamiento y evaluación admiten `Idempotency-Key`. Reutilizar la clave con otro cuerpo devuelve 409. Cada candidato y partición tienen una evaluación canónica, por lo que cambiar la clave no repite un test completado. Una evaluación incompleta puede reintentarse por la misma ruta: se preservan los casos válidos.

## Clasificación opcional con Claude

Con el perfil Claude configurado según el README, usar mode `CLAUDE_INFERENCE`, algorithm `CLAUDE_FEW_SHOT`, prompt opcional y fewShotExamples entre 0 y 10. Modelo, opciones y versión del prompt se registran en provenance. Los ejemplos proceden exclusivamente de train. El adaptador devuelve una etiqueta conocida y captura Usage cuando está disponible.

Preparar este experimento no invoca Claude. Solicitar validación o test en ese modo sí realiza llamadas facturables. No existe entrenamiento de pesos de Claude. El límite de 30 segundos es una espera local, y no garantiza detener una petición ya recibida por el proveedor. No se presenta el uso simulado de los tests como tokens reales medidos.

## Organización y persistencia

- `domain`: contratos, reglas del dataset, split, clasificador y matriz sin dependencia de Spring.
- `application/MlService`: ciclo de vida, selección por validación, trabajos e idempotencia.
- `infrastructure`: adaptador Spring AI y repositorio JSON con publicación atómica y bloqueo exclusivo.
- `api/MlController`: contratos HTTP y errores del módulo.

Un trabajador ML, cola de 16 trabajos y un cliente Claude con capacidad acotada. El repositorio almacena una instantánea de estado; no es una solución para varios servidores ni grandes volúmenes. Cambiar `ml.storage-directory` para aislar entornos. No editar manualmente `state.json` mientras está abierto. Si no existe movimiento atómico en el sistema de archivos, la publicación falla explícitamente.

El arranque recupera instantáneas y marca trabajos interrumpidos como FAILED. Los artefactos de Naive Bayes incluyen vocabulario, log-priors, log-likelihoods, etiqueta mayoritaria y versión del preprocesado. Los hashes vinculan dataset, split y artefacto. Las métricas se recalculan desde las predicciones, sin usar datos de test para elegir parámetros.

## Trazabilidad de aceptación

| Requisitos SDD | Evidencia automatizada |
|---|---|
| ML-01 | MlDomainTest: importaciones inválidas y fixtures conflictivos |
| ML-02 a ML-06 | MlDomainTest: intervalos, 1000 registros, orden de entrada, grupos y falta de cobertura |
| ML-07 | MlDomainTest: término exclusivo de test fuera del vocabulario |
| ML-08 | MlServiceTest: lifecycle y selección del candidato por validación |
| ML-09 a ML-11 | MlDomainTest: matriz conocida, multiclase y métricas indefinidas; MlServiceTest: informe incompleto |
| ML-12 | ClaudeClassificationGatewayTest: modelo simulado, contexto, Usage y errores; MlServiceTest: ejemplos solo de train |
| ML-13 | MlServiceTest: claves, conflictos y evaluación canónica sin repetir llamadas |
| ML-14 | MlDomainTest: round-trip del modelo; MlServiceTest: persistencia y reinicio |

Ejecutar con Java 21: `mvn.cmd verify`. La suite predeterminada omite ClaudeLiveTest, que sigue siendo optativo. Los informes se generan en `target/surefire-reports`.

## Límites de la evidencia

Los fixtures tienen señales sencillas y variantes sintéticas relacionadas. Un resultado alto en ellos solo valida el funcionamiento técnico. Para medir generalización hacen falta ejemplos representativos e independientes, juicios revisados y un holdout no utilizado durante el ajuste. La partición greedy con grupos puede rechazar casos donde una búsqueda más costosa encontraría solución; el error nunca autoriza a dividir un grupo.

El bloqueo de holdout se aplica por split. Un usuario puede reimportar los mismos datos, por lo que el protocolo científico también exige controlar su reutilización fuera de esta API. Se mantiene todo el estado para auditoría y no se promete exactamente una llamada al proveedor frente a fallos entre inferencia y persistencia.

## Evidencia de esta entrega

- `mvn.cmd -o -Dlogging.level.root=WARN -Ddebug=false verify`, con Java 21: BUILD SUCCESS; 33 tests, cero fallos, cero errores y uno omitido (ClaudeLiveTest). Son 32 tests aprobados. Incluye regresiones de la API de recetas.
- El test de almacenamiento simula bloqueos transitorios y permanentes: el reintento está acotado y conserva la instantánea anterior si no consigue publicar la nueva. Los trabajos también pasan a FAILED si falla su inicio o publicación; si el almacenamiento permanece inaccesible, el fallo se expone en memoria y el reinicio detecta el trabajo interrumpido.
- Demo ejecutada contra el JAR en HTTP local, con `scripts/demo-ml.ps1`: 300 ejemplos, tres candidatos, validación, freeze y test. Conteos efectivos: train 210 (70 %), validation 48 (16 %) y test 42 (14 %); variación causada por grupos de dos ejemplos.
- Matriz de test en orden `entrante`, `principal`, `postre`: `[[14,0,0],[0,14,0],[0,0,14]]`. Coverage 1, accuracy 1 y macro-F1 1 en este fixture sintético sencillo. La suma de la matriz coincide con las 42 predicciones exportadas a `target/ml-demo/report.json`.
- No se han ejecutado llamadas reales a Claude. Estos resultados no miden generalización sobre recetas reales ni calidad del proveedor.
