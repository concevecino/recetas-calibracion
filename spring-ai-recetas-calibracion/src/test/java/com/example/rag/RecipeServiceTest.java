package com.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RecipeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RecipeService service() throws Exception { return new RecipeService(mapper, "", "text-embedding-3-small"); }
    private RecipeService.Options options(String query, String mode, int k, double threshold, String diet, String category, int minutes, boolean filters) {
        return new RecipeService.Options(query, mode, k, threshold, diet, category, minutes, filters);
    }
    @Test void localSearchRespectsDietTimeAndThreshold() throws Exception {
        var hits = service().search(options("garbanzos", "lexical", 10, .1, "vegana", "", 60, false));
        assertThat(hits).isNotEmpty().allSatisfy(h -> {
            assertThat(h.recipe().path("dietas").toString()).contains("vegana");
            assertThat(h.recipe().path("tiempo_minutos").asInt()).isLessThanOrEqualTo(60);
            assertThat(h.score()).isGreaterThanOrEqualTo(.1);
        });
    }
    @Test void metricsUseRankingAndFullReferenceDenominator() throws Exception {
        var hits = List.of(new RecipeService.Hit(mapper.readTree("{\"id\":\"irrelevant\"}"),1),
                new RecipeService.Hit(mapper.readTree("{\"id\":\"a\"}"),.8));
        var metrics = RecipeService.metrics(hits, Map.of("a",3,"b",1));
        assertThat(metrics.precision5()).isEqualTo(.2);
        assertThat(metrics.recall10()).isEqualTo(.5);
        assertThat(metrics.mrr()).isEqualTo(.5);
        double expected = (7 / (Math.log(3)/Math.log(2))) / (7 + 1 / (Math.log(3)/Math.log(2)));
        assertThat(metrics.ndcg5()).isCloseTo(expected, within(1e-9));
    }
    @Test void calibrationEvaluatesAllThirtyWithAndWithoutFilters() throws Exception {
        var service = service();
        for (boolean filters : List.of(false,true)) {
            var result = service.calibrate(options("", "lexical", 10, 0, "", "", 0, filters));
            assertThat((List<?>) result.get("evaluations")).hasSize(30);
            assertThat(((RecipeService.Metrics) result.get("average")).ndcg5()).isBetween(0.0,1.0);
        }
    }
    @Test void missingKeyAndInvalidInputsFailClearly() throws Exception {
        var service=service();
        assertThatThrownBy(()->service.search(options("pollo","semantic",10,0,"","",0,false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OPENAI_API_KEY");
        assertThatThrownBy(()->service.search(options("pollo","lexical",0,0,"","",0,false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.search(options("pollo","lexical",10,Double.NaN,"","",0,false))).isInstanceOf(IllegalArgumentException.class);
    }
}
