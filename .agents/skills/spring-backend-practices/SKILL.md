---
name: spring-backend-practices
description: Implementar y revisar integraciones Spring AI con Claude para búsqueda de recetas, incluyendo inyección de ChatModel y ChatClient, prompts, contexto RAG, configuración Anthropic, tokens y evaluaciones offline. Usar al modificar generación, recuperación, herramientas o configuración de IA del backend.
---

# Buenas prácticas de Spring AI con Claude

Esta skill está orientada a Spring AI. Complementar [java-code-practices](../java-code-practices/SKILL.md) y [java-class-style](../java-class-style/SKILL.md). Para contratos y datasets, leer [recipe-search-backend](../recipe-search-backend/SKILL.md). El módulo Maven está en `../../../spring-ai-recetas-calibracion` respecto a esta carpeta.

## Compatibilidad y alcance

Leer el POM antes de proponer APIs. La base existente usa Java 21, Spring Boot 3.5.15 y Spring AI 1.1.8. Usar documentación de Spring AI 1.1; no copiar APIs de 2.x ni actualizar dependencias como efecto secundario de una refactorización.

Conservar el adaptador actual basado en `ChatModel` salvo que el cambio necesite `ChatClient`, advisors u otra abstracción. No añadir un vector store, memoria, herramientas o agentes solo por utilizar Spring AI. Esta skill guía al agente; no configura el runtime ni ejecuta validaciones automáticamente.

## Inyección de componentes de IA

- Usar un constructor único explícito y dependencias `private final`; omitir `@Autowired` en ese constructor. Mantener inyección por constructor para `ChatModel`, `ChatClient` y colaboradores; no moverla a campos.
- Preferir contratos de Spring AI en el adaptador y mantener el dominio independiente del framework. No construir manualmente un modelo Anthropic por petición ni replicar su protocolo HTTP si el starter cubre la necesidad.
- Si se incorpora `ChatClient`, inyectar `ChatClient.Builder`, configurar y construir el cliente una vez por servicio o bean. No guardar el builder mutable ni una especificación mutable de petición compartida entre usuarios.
- Con varios modelos o clientes, declarar beans con nombres claros y usar `@Qualifier` en los parámetros. No asumir que `@Primary` selecciona el proveedor correcto para todos los casos.
- El arranque offline del proyecto no requiere un modelo activo. Mantener `ObjectProvider<ChatModel>` o un adaptador condicional cuando corresponda; no introducir inyección obligatoria que rompa ese perfil. La operación que necesita Claude debe fallar explícitamente si no está configurado.
- No añadir Lombok únicamente para eliminar constructores. Si ya existe, comprobar que qualifiers y anotaciones necesarios llegan a los parámetros generados.

Ejemplo del patrón actual, sin `@Autowired`; el proveedor opcional conserva el arranque offline:

```java
@Service
public class ClaudeAvailability {

    private final ObjectProvider<ChatModel> models;

    public ClaudeAvailability(ObjectProvider<ChatModel> models) {
        this.models = models;
    }

    public ChatModel requireModel() {
        ChatModel model = models.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("Claude no está configurado");
        }

        return model;
    }
}
```

Ejemplo ilustrativo; no exige crear esta clase ni reemplazar el manejo de errores vigente. Si hay varios beans compatibles, resolver la ambigüedad mediante configuración y qualifiers.

## Configuración de Claude

- Usar el starter `spring-ai-starter-model-anthropic` y el perfil `claude` existente. Obtener credenciales de `ANTHROPIC_API_KEY` y modelo de `CLAUDE_MODEL`; no registrar claves ni incorporarlas en prompts o fixtures.
- Mantener las propiedades actuales `spring.ai.anthropic.chat.options.model`, `max-tokens` y `temperature`. Si se solicita hacer más parámetros configurables, enlazarlos explícitamente con entorno o configuración tipada y probar sus límites. Escribir una regla en esta skill no cambia el perfil de la aplicación.
- Comprobar compatibilidad de opciones con el modelo seleccionado. Temperatura cero no garantiza respuestas deterministas. No sustituir silenciosamente Claude por otro proveedor ante un fallo.
- Configurar timeouts y reintentos limitados en las APIs compatibles con la versión. Considerar coste y duplicación de llamadas; no reintentar errores de autenticación o parámetros inválidos como si fueran transitorios.
- Separar chat y embeddings: la ruta Claude no aporta embeddings por sí sola. La API semántica heredada utiliza OpenAI; no presentarla como una capacidad de Claude ni activarla implícitamente.

## Prompts y contexto de recetas

- Separar instrucciones del sistema, consulta del usuario y contexto recuperado. Tratar consulta y documentos como datos no confiables; no interpolarlos como instrucciones privilegiadas.
- Versionar prompts y asociar sus cambios con casos de evaluación. Pedir respuestas basadas en el contexto, citas de IDs y reconocimiento de datos ausentes. No tratar el prompt como garantía de fidelidad o protección completa contra inyección.
- Aplicar filtros explícitos antes de top K y recuperar solo recetas relevantes. No enviar el catálogo completo por defecto ni relajar restricciones para llenar resultados.
- Componer contexto con recetas completas, orden estable y presupuesto definido. Si no hay resultados o ninguna receta cabe, responder sin invocar el modelo.
- Distinguir presupuesto del contexto, instrucciones, consulta, historial y salida. La estimación por bytes UTF-8 vigente es una aproximación, no el tokenizador de Claude ni una medición de ahorro real.
- No añadir memoria conversacional al buscador sin requisito. Si se necesita, aislarla por usuario y conversación, limitar retención y tamaño, y probar que no haya cruces de contexto.
- Si se introducen advisors RAG, comprobar el orden de ejecución y evitar recuperar o insertar contexto dos veces junto al servicio existente.

## Salidas y herramientas

- Preferir respuestas tipadas cuando el consumidor necesite campos estructurados. Validar deserialización, restricciones de dominio y que los IDs citados pertenezcan al contexto; JSON válido no implica contenido correcto.
- Verificar soporte de salida estructurada en la combinación exacta de Spring AI, proveedor y modelo. No asumir que `.entity(...)` por sí solo garantiza cumplimiento del esquema.
- Rechazar o gestionar de forma explícita salidas vacías, truncadas o inválidas. Limitar intentos de reparación y contabilizar sus llamadas; no crear bucles de autocorrección sin límites.
- Si se incorporan herramientas, exponer únicamente operaciones necesarias, validar sus argumentos y aplicar permisos en el servidor. No dar al modelo acceso genérico a SQL, archivos o ejecución de comandos. Acotar rondas y llamadas por petición.
- No atribuir al sistema garantías de ausencia de alérgenos a partir de etiquetas sintéticas o de una respuesta generativa.

## Uso, fallos y observabilidad

- Medir entrada y salida reales mediante Usage del proveedor cuando esté disponible. Mantener valores ausentes como desconocidos, sin sustituirlos por cero o por estimaciones.
- Registrar modelo, versión del prompt, IDs seleccionados, duración, número de llamadas y consumo; evitar prompts, respuestas y datos personales completos por defecto.
- Separar ausencia de resultados de fallo del modelo. Mantener el contrato 503 de proveedor no disponible y conservar internamente la causa sin exponer secretos.
- Mantener las llamadas remotas fuera de transacciones de base de datos. Acotar concurrencia y colas; no asumir que streaming reduce tokens facturables o garantiza una respuesta válida antes de completarse.

## Pruebas y calibración

- Separar recuperación y generación: evaluar Precision@5, Recall@10, MRR y NDCG@5 con juicios versionados; evaluar fidelidad, citas y comportamiento de Claude con un conjunto distinto de criterios.
- En la suite offline, simular `ChatModel` o el puerto de generación y comprobar selección de contexto, filtros, límite de salida solicitado, errores, propagación de Usage y número de llamadas. No exigir texto generado exacto.
- Verificar cero llamadas si no hay contexto y una llamada en el camino simple cuando no hay herramientas ni reparaciones. Si se añaden rondas, probar el límite y su consumo total.
- Probar perfil offline sin credenciales y resolución de beans cuando cambien qualifiers o condiciones. Los tests de prompts con un doble no demuestran calidad real de Claude.
- Mantener pruebas reales optativas y claramente identificadas como facturables. No ejecutarlas sin autorización para esas llamadas. No ajustar parámetros sobre el conjunto holdout.
- Desde el módulo, ejecutar `mvn.cmd test` con Java 21 si se modifica código. Para cambios solo documentales, validar la skill y sus enlaces. Informar resultados observados y capacidades que siguen pendientes.

## Referencias oficiales

- [ChatClient, Spring AI 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html).
- [Anthropic Chat, Spring AI 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/chat/anthropic-chat.html).
- [Salida estructurada, Spring AI 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html).
- [Inyección de constructores, Spring Framework 6.2](https://docs.spring.io/spring-framework/reference/6.2/core/beans/annotation-config/autowired.html).
