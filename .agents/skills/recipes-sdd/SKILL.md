---
name: recipes-sdd
description: Implementar o modificar la API de recetas de esta PoC siguiendo su especificación SDD y sus criterios JUnit.
---

Leer `spring-ai-recetas-calibracion/specs/recipes-sdd.md` desde la raíz del repositorio antes de cambiar comportamiento. Actualizar contrato y criterios de aceptación si cambia el requisito; después implementar y verificar.

Mantener separadas recuperación local, composición de contexto y HTTP. La nueva API `/api/recipes` no usa embeddings ni OpenAI; conservar la calibración anterior salvo petición expresa.

Usar el starter `spring-ai-starter-model-anthropic` para Claude y configuración de entorno para credenciales. El perfil por defecto debe arrancar sin clave. No sustituir Claude por una respuesta inventada si falta configuración.

Al cambiar recuperación o prompts aplicar también la skill hermana `../recipes-token-evidence/SKILL.md`. Ejecutar Maven con Java 21 desde el módulo. Documentar resultados y límites comprobados en README. Las skills guían al agente de desarrollo; Spring no las ejecuta.
