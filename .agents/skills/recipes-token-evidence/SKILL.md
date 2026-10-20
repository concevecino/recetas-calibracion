---
name: recipes-token-evidence
description: Verificar presupuesto de contexto y reduccion de llamadas y tokens de la API de recetas mediante JUnit y metricas del proveedor.
---

Leer la seccion de metricas de `spring-ai-recetas-calibracion/specs/recipes-sdd.md` desde la raiz del repositorio.

Mantener busqueda local sin IA y probar ausencia de invocaciones cuando no hay resultados o ninguna receta cabe. Capturar el Prompt para comprobar IDs enviados, exclusion de metadatos redundantes y una sola llamada.

Comparar contexto seleccionado contra catalogo completo con identica proyeccion compacta y estimador. No llamar tokens reales a bytes UTF-8 ni a valores Usage simulados. No prometer minimo global de consumo. Mantener JSON valido e incluir solo recetas completas bajo presupuesto.

Ejecutar `mvn.cmd test` offline. `ClaudeLiveTest` requiere `RUN_CLAUDE_LIVE=true` y `ANTHROPIC_API_KEY`: hace dos solicitudes facturables, incluido el catalogo completo. Ejecutarlo cuando el usuario pida comprobacion real con credenciales disponibles. Informar por separado resultados offline y mediciones del proveedor; no imprimir la clave.
