package com.example.rag.ml.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.rag.ml.domain.MlTypes.Analysis;
import static com.example.rag.ml.domain.MlTypes.DatasetInput;
import static com.example.rag.ml.domain.MlTypes.Sample;

public final class DatasetRules {

    public static final String NORMALIZATION_VERSION = "nfc-root-space-v1";

    private DatasetRules() {}

    public static DatasetInput validate(DatasetInput input) {
        if (input == null || blank(input.name()) || !"1.0".equals(input.schemaVersion())
                || !"SINGLE_LABEL_CLASSIFICATION".equals(input.task())
                || input.labels() == null || input.labels().size() < 2 || input.labels().size() > 100
                || input.records() == null || input.records().isEmpty() || input.records().size() > 20_000) {
            throw invalid("Se requiere nombre, esquema 1.0, clasificación, 2–100 clases y 1–20000 registros");
        }
        String source = input.sourceType() == null ? "INDEPENDENT" : input.sourceType();
        if (!Set.of("INDEPENDENT", "GROUPED").contains(source)) {
            throw invalid("Fuente no soportada; los datos temporales requieren otro protocolo");
        }
        Set<String> labels = new HashSet<>();
        for (String label : input.labels()) {
            if (blank(label) || label.length() > 100 || !labels.add(label)) {
                throw invalid("Etiquetas vacías, duplicadas o demasiado largas");
            }
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> textLabels = new HashMap<>();
        long totalCharacters = 0;
        for (Sample sample : input.records()) {
            if (sample == null || blank(sample.id()) || sample.id().length() > 200 || !ids.add(sample.id())
                    || blank(sample.text()) || sample.text().length() > 10_000 || !labels.contains(sample.label())
                    || (sample.groupId() != null && sample.groupId().length() > 200)
                    || (source.equals("GROUPED") && blank(sample.groupId()))) {
                throw invalid("Registro inválido: ID único, texto, etiqueta conocida y grupo si la fuente es GROUPED");
            }
            totalCharacters += sample.text().length();
            if (totalCharacters > 2_000_000) {
                throw invalid("El dataset supera 2000000 caracteres de texto");
            }
            String previous = textLabels.putIfAbsent(normalize(sample.text()), sample.label());
            if (previous != null && !previous.equals(sample.label())) {
                throw invalid("Texto duplicado con etiquetas contradictorias: " + sample.id());
            }
        }
        return new DatasetInput(input.name(), input.schemaVersion(), input.task(), List.copyOf(input.labels()),
                input.records().stream().sorted(Comparator.comparing(Sample::id)).toList(), source);
    }

    public static Analysis analyze(DatasetInput input) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        input.labels().forEach(label -> distribution.put(label, 0));
        input.records().forEach(sample -> distribution.merge(sample.label(), 1, Integer::sum));
        var lengths = input.records().stream().mapToInt(sample -> sample.text().length()).summaryStatistics();
        var groups = groups(input.records());
        int uniqueTexts = (int) input.records().stream().map(sample -> normalize(sample.text())).distinct().count();
        int mixedGroups = (int) groups.stream()
                .filter(group -> group.stream().map(Sample::label).distinct().count() > 1).count();
        return new Analysis(input.records().size(), distribution, lengths.getMin(), lengths.getMax(),
                lengths.getAverage(), input.records().size() - uniqueTexts, groups.size(), mixedGroups,
                NORMALIZATION_VERSION);
    }

    public static List<List<Sample>> groups(List<Sample> samples) {
        Map<String, String> parents = new HashMap<>();
        Map<String, String> first = new HashMap<>();
        samples.forEach(sample -> parents.put(sample.id(), sample.id()));
        for (Sample sample : samples) {
            var keys = new ArrayList<String>();
            keys.add("text:" + hash(normalize(sample.text())));
            if (!blank(sample.groupId())) {
                keys.add("group:" + sample.groupId());
            }
            for (String key : keys) {
                String other = first.putIfAbsent(key, sample.id());
                if (other != null) {
                    String left = root(parents, sample.id());
                    String right = root(parents, other);
                    parents.put(left.compareTo(right) < 0 ? right : left, left.compareTo(right) < 0 ? left : right);
                }
            }
        }
        Map<String, List<Sample>> result = new java.util.TreeMap<>();
        samples.stream().sorted(Comparator.comparing(Sample::id)).forEach(sample ->
                result.computeIfAbsent(root(parents, sample.id()), ignored -> new ArrayList<>()).add(sample));
        return List.copyOf(result.values());
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String root(Map<String, String> parents, String id) {
        String current = id;
        while (!parents.get(current).equals(current)) {
            current = parents.get(current);
        }
        String root = current;
        current = id;
        while (!parents.get(current).equals(current)) {
            String next = parents.get(current);
            parents.put(current, root);
            current = next;
        }
        return root;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static MlException invalid(String message) {
        return new MlException(400, "INVALID_DATASET", message);
    }
}
