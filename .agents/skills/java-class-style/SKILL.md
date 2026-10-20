---
name: java-class-style
description: Unificar el formato y la estructura de clases, interfaces, records, enums y tests Java del proyecto. Aplicar al crear, modificar, revisar o reformatear archivos .java; complementar con java-code-practices para decisiones de diseño.
---

# Estilo uniforme de clases Java

Aplicar este estándar al código Java escrito o reformateado. Para diseño y comportamiento, consultar [java-code-practices](../java-code-practices/SKILL.md). No modificar lógica, contratos, nombres públicos ni literales al hacer una tarea de formato.

Si existe un formateador configurado en el módulo, inspeccionar sus reglas y resolver discrepancias antes de introducir otro. Esta skill define el estilo para el agente; no instala un formateador ni hace cumplir las reglas en CI. Las clases heredadas pueden no cumplir todavía este estándar. No reformatear todo el repositorio salvo que la tarea lo pida.

## Formato del archivo

- UTF-8 sin BOM, saltos LF y salto final. Sin espacios al final de línea ni tabuladores.
- Cuatro espacios por nivel; ocho espacios adicionales para continuaciones. Máximo de 120 caracteres por línea; evitar partir URLs o literales si cambia su significado.
- Una declaración pública de nivel superior por archivo, con nombre coincidente. Permitir tipos anidados pequeños cuando pertenezcan exclusivamente al tipo contenedor.
- Orden del archivo: `package`, imports, documentación del tipo, anotaciones y declaración. Separar estas secciones con una línea en blanco, salvo documentación y anotaciones que pertenecen a la misma declaración.
- Imports explícitos: no usar comodines, incluidos imports estáticos. Ordenar lexicográficamente los imports normales en un bloque; después una línea vacía y los estáticos ordenados. No separar `java`, `org` y `com` en bloques adicionales.

## Llaves, espacios y saltos

- Abrir llaves en la misma línea de la declaración; cerrar en su propia línea. Escribir `} else {`, `} catch (...) {` y `} finally {`.
- Usar siempre llaves en `if`, `else`, `for`, `while` y `do`, incluso con una sola instrucción.
- Una instrucción y una variable declarada por línea. No compactar constructores, métodos, bucles o ramas en una sola línea.
- Dejar una línea en blanco entre métodos, constructores y tipos anidados; separar campos de constructores. Agrupar campos relacionados sin líneas vacías innecesarias.
- Espacio después de comas y alrededor de operadores binarios; espacio entre palabra de control y paréntesis: `if (valid)`. Sin espacio entre nombre de método y paréntesis: `search(query)`.
- Si una firma o llamada supera el límite, poner un parámetro o argumento por línea con indentación de continuación. En declaraciones, colocar `) {` tras el último parámetro; en llamadas, `);` tras el último argumento.
- En cadenas de llamadas largas, dejar la expresión inicial en su línea y cada llamada siguiente en una línea con indentación de continuación. No forzar streams para conseguir una apariencia concreta.
- Tipos vacíos pueden usar `{}`. Los records de varias líneas sin cuerpo llevan `{}` tras su firma. No añadir miembros o comentarios vacíos para completar una plantilla.

## Orden de miembros

Usar este orden cuando se cree una clase o se solicite normalizarla:

1. Constantes `static final`, incluido logger si existe.
2. Otros campos estáticos, si son necesarios.
3. Campos de instancia: dependencias inyectadas primero y estado después.
4. Constructores.
5. Métodos públicos; agrupar sobrecargas juntas.
6. Métodos protegidos y de paquete.
7. Métodos privados, agrupados por responsabilidad y orden de uso.
8. Tipos anidados (`record`, `enum`, clases o interfaces).

No mover inicializadores ni campos si su orden puede afectar a la inicialización. En enums, las constantes van primero por sintaxis. En records, respetar el orden contractual de componentes. No aplicar este orden a ciegas mediante reemplazos de texto.

## Nombres y declaraciones

- Tipos en `PascalCase`; métodos, parámetros y campos en `camelCase`; constantes en `UPPER_SNAKE_CASE`; paquetes en minúsculas.
- Usar nombres de dominio: `recipe`, `maxMinutes`, `search`; evitar `r`, `obj`, `data` si ocultan significado. Conservar nombres públicos existentes durante cambios solo de formato.
- Pruebas: clase `NombreClaseTest`, métodos `camelCase` que describan condición y resultado, por ejemplo `searchReturnsEmptyWhenNoRecipeMatches`.
- Preferir visibilidad mínima e indicar `@Override` cuando corresponda. Una anotación por línea, sin líneas vacías entre anotaciones y declaración.
- Usar tipos explícitos en campos, parámetros y retornos. Permitir `var` en locales cuando el tipo resulte claro en la misma expresión; no introducirlo ni eliminarlo masivamente por estilo.
- Documentar APIs públicas con contratos no evidentes, unidades o restricciones. No exigir Javadoc que repita el nombre de cada getter o método.

## Ejemplo de clase

El ejemplo muestra formato; no obliga a introducir esta clase ni una interfaz adicional.

```java
package com.example.recipes;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RecipeSearchService {

    private static final int MAX_TOP_K = 10;

    private final RecipeIndex recipeIndex;

    public RecipeSearchService(RecipeIndex recipeIndex) {
        this.recipeIndex = Objects.requireNonNull(recipeIndex);
    }

    public List<Recipe> search(String query, int topK) {
        validateTopK(topK);

        return recipeIndex.search(query, topK);
    }

    private void validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 10");
        }
    }
}
```

## Aplicación y comprobación

Al modificar una clase, dar formato a los miembros tocados y mantener coherencia local. Si el encargo pide normalizar una clase completa, aplicar todas las reglas a ese archivo. Mantener separadas correcciones funcionales y formato para facilitar la revisión.

Revisar imports, llaves, indentación, longitud de líneas y orden de miembros. Ejecutar el formateador existente si lo hay. Si se modifican archivos Java, compilar y ejecutar las comprobaciones pertinentes; no afirmar que todo el repositorio cumple a partir de revisar solo una clase. Si se modifica únicamente esta skill, validar el frontmatter y sus enlaces sin ejecutar toda la suite Java.
