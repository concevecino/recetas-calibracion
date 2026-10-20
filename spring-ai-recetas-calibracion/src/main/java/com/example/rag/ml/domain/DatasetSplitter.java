package com.example.rag.ml.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.example.rag.ml.domain.MlTypes.Assignment;
import static com.example.rag.ml.domain.MlTypes.DatasetVersion;
import static com.example.rag.ml.domain.MlTypes.Sample;
import static com.example.rag.ml.domain.MlTypes.SplitConfig;
import static com.example.rag.ml.domain.MlTypes.SplitManifest;

public final class DatasetSplitter {

    private static final List<String> PARTITIONS = List.of("train", "validation", "test");

    public SplitManifest split(String id, DatasetVersion dataset, SplitConfig config) {
        validate(config);
        var data = dataset.data();
        var groups = new ArrayList<>(DatasetRules.groups(data.records()));
        int[][] targets = new int[data.labels().size()][3];
        int[][] counts = new int[data.labels().size()][3];
        for (int label = 0; label < data.labels().size(); label++) {
            String name = data.labels().get(label);
            int size = (int) data.records().stream().filter(sample -> sample.label().equals(name)).count();
            targets[label] = targets(size, config);
            if (Arrays.stream(targets[label]).anyMatch(value -> value == 0)
                    || groups.stream().filter(group -> group.stream()
                            .anyMatch(sample -> sample.label().equals(name))).count() < 3) {
                throw infeasible("Clase sin suficientes registros o grupos: " + name, Map.of("classSize", size));
            }
        }
        var assignments = new ArrayList<Assignment>();
        if (groups.stream().allMatch(group -> group.size() == 1)) {
            for (int label = 0; label < data.labels().size(); label++) {
                String name = data.labels().get(label);
                var rows = new ArrayList<>(data.records().stream()
                        .filter(sample -> sample.label().equals(name)).toList());
                Collections.shuffle(rows, new Random(config.seed() + label));
                int offset = 0;
                for (int partition = 0; partition < 3; partition++) {
                    counts[label][partition] = targets[label][partition];
                    for (int i = 0; i < targets[label][partition]; i++) {
                        assignments.add(assignment(rows.get(offset++), partition));
                    }
                }
            }
        } else {
            Collections.shuffle(groups, new Random(config.seed()));
            groups.sort(Comparator.<List<Sample>>comparingInt(List::size).reversed());
            for (var group : groups) {
                int[] additions = new int[data.labels().size()];
                group.forEach(sample -> additions[data.labels().indexOf(sample.label())]++);
                int best = 0;
                double bestChange = Double.POSITIVE_INFINITY;
                for (int partition = 0; partition < 3; partition++) {
                    double change = 0;
                    for (int label = 0; label < targets.length; label++) {
                        double before = counts[label][partition] - targets[label][partition];
                        double after = before + additions[label];
                        change += (after * after - before * before) / targets[label][partition];
                    }
                    if (change < bestChange) {
                        best = partition;
                        bestChange = change;
                    }
                }
                for (int label = 0; label < targets.length; label++) {
                    counts[label][best] += additions[label];
                }
                int selected = best;
                group.forEach(sample -> assignments.add(assignment(sample, selected)));
            }
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> distribution = new LinkedHashMap<>();
        for (int partition = 0; partition < 3; partition++) {
            int total = 0;
            int target = 0;
            Map<String, Integer> byClass = new LinkedHashMap<>();
            for (int label = 0; label < targets.length; label++) {
                int classSize = Arrays.stream(targets[label]).sum();
                double deviation = Math.abs(counts[label][partition] - targets[label][partition]) * 100.0 / classSize;
                if (counts[label][partition] == 0 || deviation > config.maxDeviationPercentagePoints()) {
                    throw infeasible("Desviación por clase; no se encontró reparto factible",
                            Map.of("label", data.labels().get(label), "partition", PARTITIONS.get(partition),
                                    "actual", counts[label][partition], "target", targets[label][partition],
                                    "groups", groups.stream()
                                            .map(group -> group.stream().map(Sample::id).toList()).toList()));
                }
                total += counts[label][partition];
                target += targets[label][partition];
                byClass.put(data.labels().get(label), counts[label][partition]);
            }
            if (Math.abs(total - target) * 100.0 / data.records().size() > config.maxDeviationPercentagePoints()) {
                throw infeasible("Desviación global excesiva", Map.of("actual", total, "target", target));
            }
            totals.put(PARTITIONS.get(partition), total);
            distribution.put(PARTITIONS.get(partition), Map.copyOf(byClass));
        }
        assignments.sort(Comparator.comparing(Assignment::id));
        return new SplitManifest(id, dataset.datasetVersionId(), config, "stratified-group-greedy-v1",
                List.copyOf(assignments), Map.copyOf(totals), Map.copyOf(distribution));
    }

    public void validate(SplitConfig config) {
        if (config == null || config.trainPercent() < 70 || config.trainPercent() > 80
                || config.validationPercent() < 10 || config.validationPercent() > 20
                || config.testPercent() < 10 || config.testPercent() > 20
                || config.trainPercent() + config.validationPercent() + config.testPercent() != 100
                || !"STRATIFIED_GROUP".equals(config.strategy())
                || !Double.isFinite(config.maxDeviationPercentagePoints())
                || config.maxDeviationPercentagePoints() < 0 || config.maxDeviationPercentagePoints() > 20) {
            throw new MlException(400, "INVALID_SPLIT",
                    "Porcentajes inválidos o suma distinta de 100; tolerancia 0–20");
        }
    }

    private int[] targets(int size, SplitConfig config) {
        int[] percentages = {config.trainPercent(), config.validationPercent(), config.testPercent()};
        int[] result = new int[3];
        var order = new ArrayList<>(List.of(0, 1, 2));
        for (int i = 0; i < 3; i++) {
            result[i] = size * percentages[i] / 100;
        }
        order.sort(Comparator.<Integer>comparingInt(i -> size * percentages[i] % 100).reversed()
                .thenComparingInt(Integer::intValue));
        int remaining = size - Arrays.stream(result).sum();
        for (int i = 0; i < remaining; i++) {
            result[order.get(i)]++;
        }
        return result;
    }

    private Assignment assignment(Sample sample, int partition) {
        return new Assignment(sample.id(), PARTITIONS.get(partition), DatasetRules.hash(sample.text()));
    }

    private MlException infeasible(String message, Map<String, ?> details) {
        return new MlException(422, "SPLIT_NOT_FEASIBLE", message, details);
    }
}
