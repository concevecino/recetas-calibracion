package com.example.rag.ml.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.example.rag.ml.domain.MlTypes.ModelArtifact;
import static com.example.rag.ml.domain.MlTypes.Sample;

/** Multinomial NB with empirical priors and additive smoothing; unknown tokens are ignored. */
public final class NaiveBayesClassifier implements TrainableClassifier {

    public static final String PREPROCESSING_VERSION = "nfc-word-count-v1";

    @Override
    public ModelArtifact train(List<String> labels, List<Sample> samples, double alpha) {
        if (!Double.isFinite(alpha) || alpha <= 0 || samples.isEmpty()) {
            throw new MlException(400, "INVALID_MODEL", "alpha debe ser finito y positivo; train no puede estar vacío");
        }
        var vocabulary = new TreeSet<String>();
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        Map<String, Integer> documents = new TreeMap<>();
        labels.forEach(label -> {
            counts.put(label, new TreeMap<>());
            documents.put(label, 0);
        });
        for (Sample sample : samples) {
            documents.merge(sample.label(), 1, Integer::sum);
            for (String token : tokens(sample.text())) {
                vocabulary.add(token);
                counts.get(sample.label()).merge(token, 1, Integer::sum);
            }
        }
        if (vocabulary.isEmpty() || vocabulary.size() > 20_000
                || documents.values().stream().anyMatch(count -> count == 0)) {
            throw new MlException(422, "INVALID_TRAINING_DATA", "Se requieren tokens y ejemplos de todas las clases");
        }
        Map<String, Double> priors = new LinkedHashMap<>();
        Map<String, Map<String, Double>> likelihoods = new LinkedHashMap<>();
        String majority = labels.getFirst();
        for (String label : labels) {
            if (documents.get(label) > documents.get(majority)) {
                majority = label;
            }
            priors.put(label, Math.log((double) documents.get(label) / samples.size()));
            int total = counts.get(label).values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Double> probabilities = new TreeMap<>();
            for (String word : vocabulary) {
                probabilities.put(word, Math.log(counts.get(label).getOrDefault(word, 0) + alpha)
                        - Math.log(total + alpha * vocabulary.size()));
            }
            likelihoods.put(label, Map.copyOf(probabilities));
        }
        return new ModelArtifact(List.copyOf(labels), List.copyOf(vocabulary), Map.copyOf(priors),
                Map.copyOf(likelihoods), majority, alpha, PREPROCESSING_VERSION);
    }

    @Override
    public String predict(ModelArtifact model, String text) {
        String best = model.labels().getFirst();
        double bestScore = Double.NEGATIVE_INFINITY;
        List<String> tokens = tokens(text);
        for (String label : model.labels()) {
            double score = model.logPriors().get(label);
            for (String token : tokens) {
                score += model.logLikelihoods().get(label).getOrDefault(token, 0.0);
            }
            if (score > bestScore) {
                best = label;
                bestScore = score;
            }
        }
        return best;
    }

    private List<String> tokens(String text) {
        return Arrays.stream(DatasetRules.normalize(text).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isBlank()).toList();
    }
}
