package com.example.rag.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calibrador básico de recuperación semántica.
 *
 * Integra esta clase en tu aplicación existente (no crees una segunda
 * aplicación Spring Boot). Coloca los dos JSON en src/main/resources.
 *
 * Nota: la evaluación es semántica sin aplicar los filtros JSON todavía.
 */
@Component
@org.springframework.context.annotation.Profile("legacy-cli")
public class RecipeSearchCalibrationRunner implements CommandLineRunner {

    private static final int TOP_K = 10;
    private final VectorStore vectorStore;
    private final ObjectMapper mapper;

    public RecipeSearchCalibrationRunner(VectorStore vectorStore, ObjectMapper mapper) {
        this.vectorStore = vectorStore;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) throws Exception {
        // Ejecutar solo si se activa explícitamente:
        // --calibracion.recetas=true
        // En una app existente, también puedes cambiar esta condición.
        boolean enabled = Arrays.asList(args).contains("--calibracion.recetas=true");
        if (!enabled) {
            return;
        }

        int indexed = indexRecipes();
        System.out.printf("%nRecetas enviadas al VectorStore: %d%n", indexed);

        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/consultas_calibracion_recetas.json")) {
            if (in == null) {
                throw new IllegalStateException("No se encuentra consultas_calibracion_recetas.json en src/main/resources");
            }
            root = mapper.readTree(in);
        }

        List<Evaluation> evaluations = new ArrayList<>();
        for (JsonNode queryNode : root.path("consultas")) {
            String queryId = queryNode.path("id").asText();
            String query = queryNode.path("consulta").asText();
            Set<String> relevant = new LinkedHashSet<>();
            JsonNode relevance = queryNode.path("relevancia_esperada");
            addIds(relevant, relevance.path("alta"));
            addIds(relevant, relevance.path("media"));

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(TOP_K).build());

            List<String> rankedIds = results == null ? List.of() : results.stream()
                    .map(d -> d.getMetadata().get("recipeId"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .collect(Collectors.toList());

            double p5 = precisionAtK(rankedIds, relevant, 5);
            double r10 = recallAtK(rankedIds, relevant, 10);
            double mrr = reciprocalRank(rankedIds, relevant);
            double ndcg5 = ndcgAtK(rankedIds, relevance, 5);

            evaluations.add(new Evaluation(queryId, query, p5, r10, mrr, ndcg5, rankedIds));
            System.out.printf(Locale.ROOT,
                    "%s | P@5=%.3f | R@10=%.3f | MRR=%.3f | NDCG@5=%.3f | %s%n",
                    queryId, p5, r10, mrr, ndcg5, rankedIds.stream().limit(5).toList());
        }

        System.out.println("\n=== PROMEDIOS MACRO ===");
        System.out.printf(Locale.ROOT, "Precision@5: %.4f%n", average(evaluations, Evaluation::p5));
        System.out.printf(Locale.ROOT, "Recall@10:    %.4f%n", average(evaluations, Evaluation::r10));
        System.out.printf(Locale.ROOT, "MRR:          %.4f%n", average(evaluations, Evaluation::mrr));
        System.out.printf(Locale.ROOT, "NDCG@5:       %.4f%n", average(evaluations, Evaluation::ndcg5));
    }

    private int indexRecipes() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/recetas_rag_calibracion.json")) {
            if (in == null) {
                throw new IllegalStateException("No se encuentra recetas_rag_calibracion.json en src/main/resources");
            }
            root = mapper.readTree(in);
        }

        List<Document> documents = new ArrayList<>();
        for (JsonNode recipe : root.path("recetas")) {
            String id = recipe.path("id").asText();
            String content = recipe.path("texto_busqueda").asText("");
            if (content.isBlank()) {
                content = recipe.path("nombre").asText("") + ". "
                        + recipe.path("descripcion").asText("") + ". "
                        + recipe.path("ingredientes").toString() + ". "
                        + recipe.path("instrucciones").toString();
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("recipeId", id);
            metadata.put("nombre", recipe.path("nombre").asText(""));
            metadata.put("cocina", recipe.path("cocina").asText(""));
            metadata.put("categoria", recipe.path("categoria").asText(""));
            metadata.put("dietas", stringArray(recipe.path("dietas")));
            metadata.put("etiquetas", stringArray(recipe.path("etiquetas")));

            documents.add(Document.builder()
                    .id(id)
                    .text(content)
                    .metadata(metadata)
                    .build());
        }

        // Reejecutar puede depender de la política de IDs/upsert del VectorStore.
        // En producción, limpia la tabla de pruebas o usa una colección/tabla dedicada.
        vectorStore.add(documents);
        return documents.size();
    }

    private static List<String> stringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private static void addIds(Set<String> target, JsonNode node) {
        if (node != null && node.isArray()) {
            node.forEach(v -> target.add(v.asText()));
        }
    }

    private static double precisionAtK(List<String> ranked, Set<String> relevant, int k) {
        if (k <= 0) return 0;
        long hits = ranked.stream().limit(k).filter(relevant::contains).distinct().count();
        return (double) hits / k;
    }

    private static double recallAtK(List<String> ranked, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 0;
        long hits = ranked.stream().limit(k).filter(relevant::contains).distinct().count();
        return (double) hits / relevant.size();
    }

    private static double reciprocalRank(List<String> ranked, Set<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }

    private static double ndcgAtK(List<String> ranked, JsonNode relevance, int k) {
        Map<String, Integer> grades = new HashMap<>();
        addGrades(grades, relevance.path("alta"), 3);
        addGrades(grades, relevance.path("media"), 1);
        addGrades(grades, relevance.path("baja"), 0);

        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            int grade = grades.getOrDefault(ranked.get(i), 0);
            dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        List<Integer> ideal = grades.values().stream()
                .sorted(Comparator.reverseOrder()).limit(k).toList();
        double idcg = 0;
        for (int i = 0; i < ideal.size(); i++) {
            idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private static void addGrades(Map<String, Integer> grades, JsonNode node, int grade) {
        if (node != null && node.isArray()) node.forEach(v -> grades.put(v.asText(), grade));
    }

    private static double average(List<Evaluation> values, java.util.function.ToDoubleFunction<Evaluation> f) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(f).average().orElse(0);
    }

    private record Evaluation(String id, String query, double p5, double r10,
                              double mrr, double ndcg5, List<String> rankedIds) {}
}
