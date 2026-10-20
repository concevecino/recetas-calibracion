---
name: recipe-search-backend
description: Diseñar e implementar el backend Java de búsqueda de recetas con SDD, Spring Boot, Spring AI y Claude, y calibrar recuperación con evaluaciones reproducibles.
---

Trabajar desde `spring-ai-recetas-calibracion/spring-ai-recetas-calibracion` (módulo Maven relativo a la carpeta que contiene `.agents`). Leer `specs/backend-search-sdd.md` antes de modificar el buscador y `specs/recipes-sdd.md` para el contrato existente.

Al escribir, refactorizar o revisar clases Java, aplicar también [java-code-practices](../java-code-practices/SKILL.md).

Para cada cambio de comportamiento, escribir primero requisito y criterio observable; vincularlo con un test y después implementar. Mantener el dominio de recetas independiente del proveedor de IA. Separar recuperación, filtros, construcción de contexto y generación.

Conservar Java 21, las versiones del POM y las APIs existentes salvo que la tarea requiera migrarlas. Consultar documentación oficial de la misma versión al incorporar APIs de Spring AI. No actualizar todo el stack por usar la última versión disponible.

Claude se configura en el perfil `claude`, con `ANTHROPIC_API_KEY` y `CLAUDE_MODEL`. No usar Claude como proveedor de embeddings. La ruta léxica debe funcionar sin claves. La ruta semántica heredada usa OpenAI y no forma parte de la ruta Claude; explicar esa diferencia al cambiar configuración.

Los filtros explícitos son obligatorios y se aplican antes del top K. Una consulta como «sin huevo» no equivale a una exclusión fiable en el buscador léxico actual. No afirmar comprensión de restricciones naturales sin implementar y probar su extracción y aplicación. Las etiquetas sintéticas no certifican seguridad alimentaria.

Calibrar primero recuperación offline. Usar `src/test/resources/search-gold.json` como regresión del catálogo vigente y `src/test/resources/recetas_pruebas.json` como fixture independiente con cantidades. No sustituir el catálogo de producción por el fixture sin adaptar sus juicios. Separar desarrollo y evaluación final antes de optimizar parámetros; no ajustar sobre holdout.

Medir Precision@5, Recall@10, MRR y NDCG@5; registrar consultas sin resultados, configuración, versión del catálogo y latencia. Comparar siempre contra la misma línea base. Los tests no deben depender de respuestas textuales exactas del LLM. Los dobles prueban integración y contratos; solo una evaluación real prueba calidad de Claude.

Ejecutar `mvn.cmd test` desde el módulo. Los tests con Claude real son optativos, facturables y requieren autorización para sus llamadas. Informar qué se ejecutó, métricas observadas y qué sigue siendo una propuesta en la especificación.
