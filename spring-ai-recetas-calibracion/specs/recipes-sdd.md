# Especificación de la PoC: recetas indexadas con Claude

## Objetivo y alcance
API REST Spring Framework/Spring Boot, catálogo JSON existente de 61 recetas sintéticas e integración Spring AI con Claude.
La recuperación es léxica local, mediante índice invertido en memoria construido al arrancar. No requiere embeddings, base de datos ni llamadas de IA para buscar.
Se conserva la API anterior de calibración; sus modos semántico/híbrido siguen siendo una funcionalidad independiente con OpenAI.

## Contrato
- `GET /api/recipes?query=tortilla&topK=3&diet=vegetariana&maxMinutes=40`: recetas originales, candidatos del índice y tamaño del catálogo. Cero llamadas a modelos.
- `POST /api/recipes/answer`: `{ "query":"tortilla española", "topK":1, "diet":"", "maxMinutes":0, "contextBudget":4000 }`.
- query obligatoria de 1 a 500 caracteres; topK entre 1 y 10 (por defecto 3); maxMinutes >= 0 (0 sin límite).
- contextBudget entre 2 y 12000, por defecto 4000. Unidad: bytes UTF-8, proxy conservador del tamaño tokenizado, NO contador de tokens de Claude.
- Respuesta: answer, recipeIds (IDs de las fuentes enviadas, no validación de las citas generadas), usage.
- 400 para entradas inválidas; 503 si falta configurar Claude o falla el proveedor. Sin coincidencias/contexto suficiente: 200 sin invocar Claude.

## Recuperación y contexto
Normalizar mayúsculas y acentos; quitar palabras vacías. Postings por término, unión de candidatos, puntuación por suma de pesos IDF `ln(1+N/df)`, desempate por ID. Aplicar filtros explícitos antes de topK.
No interpretar automáticamente restricciones en lenguaje natural: usar diet y maxMinutes. No hay búsqueda semántica, stemming ni sinónimos en esta API.
Enviar JSON compacto con id, nombre, ingredientes, instrucciones, tiempo_minutos y dietas; omitir texto_busqueda y metadatos redundantes.
Incluir recetas completas por orden de relevancia mientras quepan; intentar las siguientes si una no cabe. No truncar JSON. Si ninguna cabe, omitir la llamada.
Una llamada por respuesta con coincidencias, sin historial ni herramientas; límite de salida de 300 tokens y un intento configurado. No se garantiza mínimo matemático de tokens ni calidad culinaria.

## Métricas y evidencia
contextEstimatedTokens y fullCatalogEstimatedTokens son estimaciones por bytes usando el mismo formato compacto para comparar justamente. promptEstimatedTokens añade sistema y consulta, pero excluye el envoltorio del protocolo del proveedor.
actualInputTokens/actualOutputTokens proceden del Usage de Spring AI. Una llamada omitida reporta 0; valores ausentes del proveedor permanecen null (o el valor que exponga el adaptador).
JUnit offline usa Usage simulado: comprueba propagación, NO facturación real. El test optativo ClaudeLiveTest hace dos llamadas reales y compara los tokens de entrada indexados frente al catálogo completo; no compara salida, precios ni calidad.

## Criterios de aceptación y trazabilidad
| Criterio | Evidencia JUnit |
|---|---|
| AC1: recuperación indexada normalizada y determinista, menos candidatos que catálogo en consulta selectiva | IndexedRecipeTest.AC1_* |
| AC2: filtros explícitos y validación | IndexedRecipeTest.AC2_* |
| AC3: una llamada, solo contexto seleccionado y reducción >90% frente a catálogo compacto en consulta de tortilla | IndexedRecipeTest.AC3_* |
| AC4: cero llamadas sin resultados o sin presupuesto | IndexedRecipeTest.AC4_* |
| AC5: presupuesto respetado y JSON completo | IndexedRecipeTest.AC5_* |
| AC6: REST offline, 400/503 y errores saneados | IndexedRecipeTest.AC6_* |
| AC7: arranque sin credenciales | ApplicationTest |
| AC8: reducción real de tokens de entrada, ejecución optativa con clave | ClaudeLiveTest |

## Límites de la PoC
Índice reconstruido desde classpath al reiniciar; sin edición del catálogo ni persistencia de cambios. Las etiquetas dietéticas son sintéticas y aproximadas. El prompt pide fidelidad y citas, pero no garantiza cumplimiento ni neutraliza por sí solo prompt injection. No ofrecer garantías sobre alergias. No registrar claves ni propagar cuerpos de error del proveedor.

## Implementación y verificación
RecipeIndex: catálogo/postings/ranking; RecipeAnswerService: selección/prompt/Usage; IndexedRecipeController: HTTP.
Primero mantener este contrato y criterios al cambiar requisitos; después código y pruebas. Ejecutar `mvn.cmd test`. Registrar resultados reales en README; no presentar simulaciones como consumo real.

## Referencias verificadas
- [Starter oficial de Anthropic](https://docs.spring.io/spring-ai/reference/1.0/api/chat/anthropic-chat.html)
- [Modelo Claude Haiku 4.5](https://platform.claude.com/docs/en/models/haiku-4-5/overview)
