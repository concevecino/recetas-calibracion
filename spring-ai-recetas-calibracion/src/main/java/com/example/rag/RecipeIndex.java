package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.*;

/** Immutable inverted index built once; retrieval never calls a model. */
@Component
public class RecipeIndex {
    private final List<JsonNode> recipes;
    private final Map<String, Set<Integer>> postings = new HashMap<>();
    public RecipeIndex(ObjectMapper mapper) throws Exception {
        try (var in = getClass().getResourceAsStream("/recetas_rag_calibracion.json")) {
            if (in == null) throw new IllegalStateException("Falta el catálogo de recetas");
            recipes = new ArrayList<>();
            mapper.readTree(in).path("recetas").forEach(recipes::add);
        }
        for (int i = 0; i < recipes.size(); i++) {
            var r = recipes.get(i);
            for (String term : terms(r.path("nombre").asText() + " " + r.path("texto_busqueda").asText()
                    + " " + r.path("ingredientes") + " " + r.path("etiquetas"))) {
                postings.computeIfAbsent(term, ignored -> new HashSet<>()).add(i);
            }
        }
    }
    public record Result(List<JsonNode> recipes, int candidates, int catalogSize) {}
    public Result search(String query, int topK, String diet, int maxMinutes) {
        if (query == null || query.isBlank() || query.length() > 500 || topK < 1 || topK > 10 || maxMinutes < 0)
            throw new IllegalArgumentException("query: 1–500 caracteres; topK: 1–10; maxMinutes >= 0");
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : terms(query)) {
            Set<Integer> ids = postings.getOrDefault(term, Set.of());
            double weight = Math.log(1.0 + (double) recipes.size() / Math.max(1, ids.size()));
            ids.forEach(id -> scores.merge(id, weight, Double::sum));
        }
        var hits = scores.keySet().stream().filter(id -> {
            var r = recipes.get(id);
            boolean dietMatches = diet == null || diet.isBlank();
            for (var d : r.path("dietas")) if (normalize(d.asText()).equals(normalize(diet == null ? "" : diet))) dietMatches = true;
            return dietMatches && (maxMinutes == 0 || r.path("tiempo_minutos").asInt(Integer.MAX_VALUE) <= maxMinutes);
        }).sorted(Comparator.<Integer>comparingDouble(scores::get).reversed()
                .thenComparing(id -> recipes.get(id).path("id").asText()))
                .limit(topK).map(recipes::get).map(JsonNode::deepCopy).map(r -> (JsonNode) r).toList();
        return new Result(hits, scores.size(), recipes.size());
    }
    public List<JsonNode> catalog() { return recipes.stream().map(JsonNode::deepCopy).map(r -> (JsonNode) r).toList(); }
    static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
    static Set<String> terms(String text) {
        var terms = new HashSet<>(Arrays.asList(normalize(text).split("[^a-z0-9]+")));
        terms.removeAll(Set.of("", "de", "la", "el", "los", "las", "con", "y", "o", "para", "un", "una", "en", "sin", "que"));
        return terms;
    }
}
