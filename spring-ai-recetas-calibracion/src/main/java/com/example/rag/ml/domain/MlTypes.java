package com.example.rag.ml.domain;

import java.util.List;
import java.util.Map;

public final class MlTypes {

    private MlTypes() {}

    public record Sample(String id, String text, String label, String groupId) {}

    public record DatasetInput(String name, String schemaVersion, String task, List<String> labels,
            List<Sample> records, String sourceType) {}

    public record DatasetVersion(String datasetVersionId, String hash, DatasetInput data) {}

    public record Analysis(int count, Map<String, Integer> distribution, int minTextLength,
            int maxTextLength, double averageTextLength, int duplicateRecords, int connectedGroups,
            int mixedLabelGroups, String normalizationVersion) {}

    public record SplitConfig(int trainPercent, int validationPercent, int testPercent, long seed,
            String strategy, double maxDeviationPercentagePoints) {

        public static SplitConfig defaults() {
            return new SplitConfig(70, 15, 15, 42, "STRATIFIED_GROUP", 5);
        }
    }

    public record Assignment(String id, String partition, String contentHash) {}

    public record SplitManifest(String splitId, String datasetVersionId, SplitConfig config,
            String algorithmVersion, List<Assignment> assignments, Map<String, Integer> counts,
            Map<String, Map<String, Integer>> distribution) {}

    public record ExperimentInput(String splitId, String mode, String algorithm, Double alpha,
            String preprocessingVersion, String positiveLabel, String prompt, Integer fewShotExamples) {}

    public record ModelArtifact(List<String> labels, List<String> vocabulary, Map<String, Double> logPriors,
            Map<String, Map<String, Double>> logLikelihoods, String majorityLabel, double alpha,
            String preprocessingVersion) {}

    public record Experiment(String experimentId, ExperimentInput config, String datasetVersionId,
            String status, boolean frozen, String validationId, ModelArtifact model, String artifactHash,
            String createdAt, Map<String, String> provenance, String error) {}

    public record Prediction(String id, String actual, String predicted, String error,
            Long inputTokens, Long outputTokens) {}

    public record ClassMetrics(long tp, long fp, long fn, long tn, long support, Double precision,
            Double recall, Double f1, List<String> undefinedMetrics) {}

    public record Metrics(List<String> labels, String orientation, long[][] matrix, int evaluatedCount,
            Double accuracy, Map<String, ClassMetrics> perClass, Double macroF1, Double weightedF1,
            Double balancedAccuracy, String positiveLabel, ClassMetrics positiveMetrics) {}

    public record Evaluation(String evaluationId, String experimentId, String partition, String status,
            int requestedCount, int failureCount, double coverage, List<Prediction> predictions,
            Metrics metrics, Metrics baseline, String error) {}

    public record IdempotencyEntry(String fingerprint, String resourceId) {}

    public record State(Map<String, DatasetVersion> datasets, Map<String, SplitManifest> splits,
            Map<String, Experiment> experiments, Map<String, Evaluation> evaluations,
            Map<String, IdempotencyEntry> idempotency, List<String> exposedSplits) {

        public static State empty() {
            return new State(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        }
    }
}
