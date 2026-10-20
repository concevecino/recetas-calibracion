package com.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.text.Normalizer;
import java.util.*;

@Service
public class RecipeService {
    private final List<JsonNode> recipes;
    private final List<JsonNode> queries;
    private final String key;
    private final String model;
    private final RestClient client;
    private final Map<String, double[]> vectors = new HashMap<>();

    public RecipeService(ObjectMapper mapper, @Value("${recipes.api-key:}") String key,
                         @Value("${recipes.embedding-model}") String model) throws Exception {
        this.key = key; this.model = model;
        try (var in = getClass().getResourceAsStream("/recetas_rag_calibracion.json")) {
            recipes = new ArrayList<>(); mapper.readTree(in).path("recetas").forEach(recipes::add);
        }
        try (var in = getClass().getResourceAsStream("/consultas_calibracion_recetas.json")) {
            queries = new ArrayList<>(); mapper.readTree(in).path("consultas").forEach(queries::add);
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        client = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).build();
    }

    public Map<String, Object> catalog() {
        return Map.of("recipes", recipes, "queries", queries, "semanticAvailable", !key.isBlank(), "model", model);
    }

    public record Options(String query, String mode, int topK, double threshold,
                          String diet, String category, int maxMinutes, boolean referenceFilters) {
        public void validate(boolean calibration) {
            if (!Set.of("lexical", "semantic", "hybrid").contains(mode == null ? "" : mode))
                throw new IllegalArgumentException("Modo de búsqueda no válido.");
            if (topK < 1 || topK > 61 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1 || maxMinutes < 0)
                throw new IllegalArgumentException("Revisa Top K (1–61), umbral (0–1) y tiempo máximo.");
            if (!calibration && (query == null || query.isBlank() || query.length() > 2000))
                throw new IllegalArgumentException("Escribe una consulta de entre 1 y 2000 caracteres.");
        }
    }
    public record Hit(JsonNode recipe, double score) {}
    public record Metrics(double precision5, double recall10, double mrr, double ndcg5) {}
    public record Evaluation(String id, String query, Metrics metrics, List<Hit> results, JsonNode relevance) {}

    public List<Hit> search(Options options) {
        options.validate(false);
        return rank(options.query(), options, null).stream().limit(options.topK()).toList();
    }

    private List<Hit> rank(String query, Options o, JsonNode filters) {
        double[] embedding = o.mode().equals("lexical") ? null : embedding(query);
        Set<String> terms = tokens(query);
        return recipes.stream().filter(r -> matches(r, o, filters)).map(r -> {
            Set<String> words = tokens(content(r));
            double lexical = terms.isEmpty() ? 0 : (double) terms.stream().filter(words::contains).count() / terms.size();
            double semantic = embedding == null ? 0 : cosine(embedding, embedding(content(r)));
            double score = switch (o.mode()) {
                case "semantic" -> semantic;
                case "hybrid" -> .7 * semantic + .3 * lexical;
                default -> lexical;
            };
            return new Hit(r, score);
        }).filter(h -> h.score() >= o.threshold() && h.score() > 0)
                .sorted(Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparing(h -> h.recipe().path("id").asText())).toList();
    }

    private boolean matches(JsonNode r, Options o, JsonNode filters) {
        if (o.diet() != null && !o.diet().isBlank() && !contains(r.path("dietas"), o.diet())) return false;
        if (o.category() != null && !o.category().isBlank() && !r.path("categoria").asText().equals(o.category())) return false;
        if (o.maxMinutes() > 0 && r.path("tiempo_minutos").asInt(Integer.MAX_VALUE) > o.maxMinutes()) return false;
        if (filters != null) {
            var fields = filters.fields();
            while (fields.hasNext()) {
                var f = fields.next();
                String field = f.getKey();
                if (field.equals("tiempo_max_minutos")) {
                    if (r.path("tiempo_minutos").asInt(Integer.MAX_VALUE) > f.getValue().asInt()) return false;
                    continue;
                }
                if (field.equals("excluir_carnes")) {
                    if (f.getValue().asBoolean() && !contains(r.path("dietas"), "vegetariana") && !contains(r.path("dietas"), "vegana")) return false;
                    continue;
                }
                if (Set.of("ingredientes_incluidos", "ingredientes_excluidos", "excluir_nombres", "metodo_excluido").contains(field)) {
                    String source = switch (field) {
                        case "excluir_nombres" -> r.path("nombre").asText();
                        case "metodo_excluido" -> r.path("instrucciones").toString();
                        default -> r.path("ingredientes").toString();
                    };
                    for (var value : f.getValue()) {
                        boolean present = normalize(source).contains(normalize(value.asText()));
                        if (field.equals("ingredientes_incluidos") ? !present : present) return false;
                    }
                    continue;
                }
                JsonNode actual = r.path(f.getKey());
                if (f.getValue().isArray() && !f.getValue().isEmpty()) {
                    boolean found = false;
                    for (var value : f.getValue()) {
                        if (actual.isArray() ? contains(actual, value.asText()) : actual.asText().equals(value.asText())) found = true;
                    }
                    if (!found) return false;
                } else if (f.getValue().isNumber() && r.path("tiempo_minutos").asInt() > f.getValue().asInt()) return false;
            }
        }
        return true;
    }

    public Map<String, Object> calibrate(Options o) {
        o.validate(true);
        List<Evaluation> rows = new ArrayList<>();
        for (JsonNode q : queries) {
            var ranked = rank(q.path("consulta").asText(), o, o.referenceFilters() ? q.path("filtros") : null)
                    .stream().limit(o.topK()).toList();
            JsonNode relevance = q.path("relevancia_esperada");
            Map<String, Integer> grades = new HashMap<>();
            relevance.path("media").forEach(id -> grades.put(id.asText(), 1));
            relevance.path("alta").forEach(id -> grades.put(id.asText(), 3));
            rows.add(new Evaluation(q.path("id").asText(), q.path("consulta").asText(), metrics(ranked, grades), ranked, relevance));
        }
        Metrics average = new Metrics(rows.stream().mapToDouble(e -> e.metrics().precision5()).average().orElse(0),
                rows.stream().mapToDouble(e -> e.metrics().recall10()).average().orElse(0),
                rows.stream().mapToDouble(e -> e.metrics().mrr()).average().orElse(0),
                rows.stream().mapToDouble(e -> e.metrics().ndcg5()).average().orElse(0));
        return Map.of("options", o, "average", average, "evaluations", rows,
                "note", "Juicios iniciales. P@5 divide siempre entre 5; R@10 conserva todos los relevantes de referencia. Top K puede limitar ambas métricas.");
    }

    static Metrics metrics(List<Hit> hits, Map<String, Integer> grades) {
        int h5 = 0, h10 = 0; double rr = 0, dcg = 0;
        for (int i = 0; i < hits.size(); i++) {
            int grade = grades.getOrDefault(hits.get(i).recipe().path("id").asText(), 0);
            if (grade > 0) { if (i < 5) h5++; if (i < 10) h10++; if (rr == 0) rr = 1.0 / (i + 1); }
            if (i < 5) dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        var ideal = grades.values().stream().sorted(Comparator.reverseOrder()).limit(5).toList();
        double idcg = 0;
        for (int i = 0; i < ideal.size(); i++) idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        long relevant = grades.values().stream().filter(g -> g > 0).count();
        return new Metrics(h5 / 5.0, relevant == 0 ? 0 : (double) h10 / relevant, rr, idcg == 0 ? 0 : dcg / idcg);
    }

    private synchronized double[] embedding(String text) {
        if (key.isBlank()) throw new IllegalArgumentException("Configura OPENAI_API_KEY o recipes.api-key y reinicia para usar embeddings.");
        if (vectors.containsKey(text)) return vectors.get(text);
        try {
            JsonNode response = client.post().uri("/embeddings").headers(h -> h.setBearerAuth(key))
                    .body(Map.of("model", model, "input", text, "encoding_format", "float"))
                    .retrieve().body(JsonNode.class);
            JsonNode values = response.path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) throw new IllegalStateException();
            double[] vector = new double[values.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = values.get(i).asDouble();
            // Bound query cache; recipe vectors will be recomputed only if the cache fills.
            if (vectors.size() >= 2000) vectors.clear();
            vectors.put(text, vector); return vector;
        } catch (Exception ex) {
            // Provider bodies and authorization headers must not reach the browser or logs.
            throw new IllegalStateException("No se han podido obtener embeddings. Revisa la clave, el saldo y la conexión.");
        }
    }
    private static double cosine(double[] a, double[] b) {
        if (a.length != b.length) throw new IllegalStateException("Dimensiones de embeddings incompatibles.");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : Math.max(0, Math.min(1, dot / Math.sqrt(aa * bb)));
    }
    private static String content(JsonNode r) {
        return r.path("nombre").asText() + " " + r.path("texto_busqueda").asText() + " " + r.path("descripcion").asText()
                + " " + r.path("ingredientes") + " " + r.path("dietas") + " " + r.path("etiquetas");
    }
    private static boolean contains(JsonNode array, String value) {
        for (var item : array) if (item.asText().equals(value)) return true;
        return false;
    }
    private static Set<String> tokens(String text) {
        String normalized = normalize(text);
        Set<String> words = new HashSet<>(Arrays.asList(normalized.split("[^a-z0-9]+")));
        words.removeAll(Set.of("", "de", "la", "el", "los", "las", "con", "sin", "y", "o", "para", "un", "una", "que", "en", "algo"));
        return words;
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
