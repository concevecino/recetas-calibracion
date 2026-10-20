package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class SearchGoldTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode resource(String name) throws Exception {
        try (var in = getClass().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return mapper.readTree(in);
        }
    }
    @Test void goldQueriesRespectRankingFiltersAndEmptyResults() throws Exception {
        var index = new RecipeIndex(mapper);
        var catalogIds = new HashSet<String>();
        index.catalog().forEach(r -> catalogIds.add(r.path("id").asText()));
        for (var c : resource("/search-gold.json").path("cases")) {
            var hits = index.search(c.path("query").asText(), 5,
                c.path("diet").asText(), c.path("maxMinutes").asInt()).recipes();
            if (c.path("empty").asBoolean()) {
                assertThat(hits).as(c.toString()).isEmpty();
            } else {
                assertThat(catalogIds).contains(c.path("expectedFirst").asText());
                assertThat(hits).as(c.toString()).isNotEmpty();
                assertThat(hits.getFirst().path("id").asText())
                    .as(c.path("query").asText()).isEqualTo(c.path("expectedFirst").asText());
            }
            for (var hit : hits) {
                if (!c.path("diet").asText().isBlank()) {
                    var diets = new HashSet<String>();
                    hit.path("dietas").forEach(d -> diets.add(d.asText()));
                    assertThat(diets).contains(c.path("diet").asText());
                }
                if (c.path("maxMinutes").asInt() > 0)
                    assertThat(hit.path("tiempo_minutos").asInt()).isLessThanOrEqualTo(c.path("maxMinutes").asInt());
            }
        }
    }
    @Test void fixtureHasUsableRecipes() throws Exception {
        var recipes = resource("/recetas_pruebas.json").path("recetas");
        assertThat(recipes.isArray()).isTrue();
        assertThat(recipes.size()).isGreaterThanOrEqualTo(6);
        var ids = new HashSet<String>();
        for (var r : recipes) {
            assertThat(r.path("id").asText()).isNotBlank();
            assertThat(ids.add(r.path("id").asText())).as("unique ID").isTrue();
            assertThat(r.path("nombre").asText()).isNotBlank();
            assertThat(r.path("raciones").asInt()).isPositive();
            assertThat(r.path("tiempo_minutos").asInt()).isPositive();
            assertThat(r.path("ingredientes").size()).isPositive();
            assertThat(r.path("instrucciones").size()).isPositive();
            for (var ingredient : r.path("ingredientes")) {
                assertThat(ingredient.path("nombre").asText()).isNotBlank();
                assertThat(ingredient.path("cantidad").isNumber()).isTrue();
                assertThat(ingredient.path("cantidad").asDouble()).isPositive();
                assertThat(ingredient.path("unidad").asText()).isNotBlank();
            }
        }
    }
}
