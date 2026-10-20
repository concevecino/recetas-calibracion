package com.example.rag.ml;

import com.example.rag.ml.domain.ConfusionMatrixCalculator;
import com.example.rag.ml.domain.DatasetRules;
import com.example.rag.ml.domain.DatasetSplitter;
import com.example.rag.ml.domain.MlException;
import com.example.rag.ml.domain.NaiveBayesClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.example.rag.ml.domain.MlTypes.DatasetInput;
import static com.example.rag.ml.domain.MlTypes.DatasetVersion;
import static com.example.rag.ml.domain.MlTypes.ModelArtifact;
import static com.example.rag.ml.domain.MlTypes.Prediction;
import static com.example.rag.ml.domain.MlTypes.Sample;
import static com.example.rag.ml.domain.MlTypes.SplitConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MlDomainTest {

    private final DatasetSplitter splitter = new DatasetSplitter();

    @Test
    void importRejectsInvalidIdsLabelsConflictsAndTemporalSources() {
        var valid = data(20);
        var duplicated = new ArrayList<>(valid.records());
        duplicated.add(valid.records().getFirst());
        assertThatThrownBy(() -> DatasetRules.validate(withRecords(valid, duplicated))).isInstanceOf(MlException.class);
        assertThatThrownBy(() -> DatasetRules.validate(withRecords(valid,
                List.of(new Sample("x", "hola", "unknown", null))))).isInstanceOf(MlException.class);
        assertThatThrownBy(() -> DatasetRules.validate(withRecords(valid,
                List.of(new Sample("x", " ", "a", null))))).isInstanceOf(MlException.class);
        assertThatThrownBy(() -> DatasetRules.validate(withRecords(valid, List.of(
                new Sample("x", "  Árbol   verde", "a", null),
                new Sample("y", "árbol verde  ", "b", null))))).isInstanceOf(MlException.class);
        assertThatThrownBy(() -> DatasetRules.validate(new DatasetInput("x", "1.0", valid.task(), valid.labels(),
                valid.records(), "TEMPORAL"))).isInstanceOf(MlException.class);
    }

    @Test
    void configurationRequiresIntervalsAndExactSum() {
        splitter.validate(SplitConfig.defaults());
        splitter.validate(new SplitConfig(80, 10, 10, 42, "STRATIFIED_GROUP", 5));
        assertThatThrownBy(() -> splitter.validate(new SplitConfig(80, 15, 15, 42, "STRATIFIED_GROUP", 5)))
                .isInstanceOf(MlException.class);
        assertThatThrownBy(() -> splitter.validate(new SplitConfig(60, 20, 20, 42, "STRATIFIED_GROUP", 5)))
                .isInstanceOf(MlException.class);
        assertThatThrownBy(() -> splitter.validate(new SplitConfig(70, 15, 15, 42, "STRATIFIED_GROUP", Double.NaN)))
                .isInstanceOf(MlException.class);
    }

    @Test
    void stratificationProducesExactBalancedCountsAndStableAssignments() {
        var data = DatasetRules.validate(data(500));
        var split = splitter.split("s", new DatasetVersion("d", "hash", data), SplitConfig.defaults());
        assertThat(split.counts()).containsEntry("train", 700)
                .containsEntry("validation", 150).containsEntry("test", 150);
        var reversed = new ArrayList<>(data.records());
        Collections.reverse(reversed);
        var reordered = DatasetRules.validate(withRecords(data, reversed));
        assertThat(splitter.split("s", new DatasetVersion("d", "hash", reordered), SplitConfig.defaults()))
                .isEqualTo(split);
        assertThat(split.assignments()).extracting(item -> item.id()).doesNotHaveDuplicates().hasSize(1000);
        var alternative = splitter.split("s", new DatasetVersion("d", "hash", data),
                new SplitConfig(80, 10, 10, 42, "STRATIFIED_GROUP", 5));
        assertThat(alternative.counts()).containsEntry("train", 800).containsEntry("validation", 100)
                .containsEntry("test", 100);
    }

    @Test
    void connectedDuplicatesAndGroupsNeverCrossPartitions() {
        var base = data(100);
        var records = new ArrayList<>(base.records());
        records.add(new Sample("dup-a", "worda example 0", "a", "related"));
        records.add(new Sample("dup-b", "another description", "a", "related"));
        var data = DatasetRules.validate(withRecords(base, records));
        var split = splitter.split("s", new DatasetVersion("d", "h", data), SplitConfig.defaults());
        Map<String, String> partitions = new HashMap<>();
        split.assignments().forEach(item -> partitions.put(item.id(), item.partition()));
        assertThat(partitions.get("dup-a")).isEqualTo(partitions.get("a-0000"));
        assertThat(partitions.get("dup-b")).isEqualTo(partitions.get("dup-a"));
        assertThat(DatasetRules.analyze(data).duplicateRecords()).isEqualTo(1);
    }

    @Test
    void tooFewIndependentGroupsAreRejected() {
        var base = data(10);
        var records = base.records().stream().map(sample ->
                new Sample(sample.id(), sample.text(), sample.label(), sample.label())).toList();
        assertThatThrownBy(() -> splitter.split("s", new DatasetVersion("d", "h",
                DatasetRules.validate(withRecords(base, records))), SplitConfig.defaults()))
                .isInstanceOfSatisfying(MlException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("SPLIT_NOT_FEASIBLE");
                });
    }

    @Test
    void naiveBayesMatchesHandCalculatedProbabilitiesAndRoundTrips() throws Exception {
        var classifier = new NaiveBayesClassifier();
        var model = classifier.train(List.of("a", "b"), List.of(
                new Sample("1", "red red blue", "a", null),
                new Sample("2", "blue green", "b", null)), 1);
        assertThat(Math.exp(model.logPriors().get("a"))).isCloseTo(0.5, within(1e-12));
        assertThat(Math.exp(model.logLikelihoods().get("a").get("red"))).isCloseTo(0.5, within(1e-12));
        assertThat(Math.exp(model.logLikelihoods().get("b").get("red"))).isCloseTo(0.2, within(1e-12));
        assertThat(classifier.predict(model, "red red")).isEqualTo("a");
        assertThat(classifier.predict(model, "green green")).isEqualTo("b");
        assertThat(model.vocabulary()).doesNotContain("testonly");
        assertThat(classifier.predict(model, "testonly")).isEqualTo("a");
        var mapper = new ObjectMapper();
        var recovered = mapper.readValue(mapper.writeValueAsBytes(model), ModelArtifact.class);
        assertThat(classifier.predict(recovered, "green green")).isEqualTo("b");
        assertThat(recovered).isEqualTo(model);
    }

    @Test
    void testOnlyWordDoesNotEnterTrainedVocabulary() {
        var dataset = DatasetRules.validate(data(100));
        var split = splitter.split("s", new DatasetVersion("d", "h", dataset), SplitConfig.defaults());
        var testIds = split.assignments().stream().filter(item -> item.partition().equals("test"))
                .map(item -> item.id()).toList();
        var modified = dataset.records().stream().map(sample -> testIds.contains(sample.id())
                ? new Sample(sample.id(), sample.text() + " holdoutsecret", sample.label(), null) : sample).toList();
        var train = modified.stream().filter(sample -> split.assignments().stream()
                .anyMatch(item -> item.partition().equals("train") && item.id().equals(sample.id()))).toList();
        assertThat(new NaiveBayesClassifier().train(dataset.labels(), train, 1).vocabulary())
                .doesNotContain("holdoutsecret");
    }

    @Test
    void smallestPositiveSmoothingRetainsFiniteLogLikelihoods() {
        var classifier = new NaiveBayesClassifier();
        var model = classifier.train(List.of("a", "b"), List.of(
                new Sample("1", "red red", "a", null),
                new Sample("2", "blue blue", "b", null)), Double.MIN_VALUE);
        assertThat(model.logLikelihoods().values()).allSatisfy(values ->
                assertThat(values.values()).allSatisfy(value -> assertThat(Double.isFinite(value)).isTrue()));
        assertThat(classifier.predict(model, "red")).isEqualTo("a");
    }

    @Test
    void binaryMatrixAndMetricsMatchKnownResults() {
        var predictions = new ArrayList<Prediction>();
        add(predictions, "negative", "negative", 40);
        add(predictions, "negative", "positive", 5);
        add(predictions, "positive", "negative", 10);
        add(predictions, "positive", "positive", 45);
        var report = new ConfusionMatrixCalculator()
                .calculate(List.of("negative", "positive"), predictions, "positive");
        assertThat(report.matrix()).isDeepEqualTo(new long[][] {{40, 5}, {10, 45}});
        assertThat(report.accuracy()).isEqualTo(0.85);
        assertThat(report.positiveMetrics().precision()).isEqualTo(0.9);
        assertThat(report.positiveMetrics().recall()).isCloseTo(45.0 / 55, within(1e-12));
        assertThat(report.positiveMetrics().f1()).isCloseTo(90.0 / 105, within(1e-12));
    }

    @Test
    void multiclassKeepsCatalogOrderAndUndefinedMetrics() {
        var calculator = new ConfusionMatrixCalculator();
        var report = calculator.calculate(List.of("c", "a", "b"), List.of(
                new Prediction("1", "c", "c", null, null, null),
                new Prediction("2", "a", "c", null, null, null)), null);
        assertThat(report.matrix()).isDeepEqualTo(new long[][] {{1, 0, 0}, {1, 0, 0}, {0, 0, 0}});
        assertThat(report.perClass().get("b").f1()).isNull();
        assertThat(report.perClass().get("a").precision()).isNull();
        assertThat(report.macroF1()).isNull();
        assertThat(report.balancedAccuracy()).isNull();
        assertThatThrownBy(() -> calculator.calculate(List.of("a", "b"), List.of(), null))
                .isInstanceOf(MlException.class);
        assertThatThrownBy(() -> calculator.calculate(List.of("a", "b"), List.of(
                new Prediction("1", "a", "unknown", null, null, null)), null)).isInstanceOf(MlException.class);
    }

    @Test
    void shippedFixturesExerciseBalancedImbalancedAndInvalidDatasets() throws Exception {
        var mapper = new ObjectMapper();
        for (String fixture : List.of("recipes-balanced", "recipes-imbalanced")) {
            try (var input = getClass().getResourceAsStream("/ml/" + fixture + ".json")) {
                var dataset = DatasetRules.validate(mapper.readValue(input, DatasetInput.class));
                var split = splitter.split("s", new DatasetVersion("d", "h", dataset), SplitConfig.defaults());
                assertThat(split.assignments()).hasSize(300);
                assertThat(split.distribution().values()).allSatisfy(distribution ->
                        assertThat(distribution.values()).allSatisfy(count -> assertThat(count).isPositive()));
            }
        }
        try (var input = getClass().getResourceAsStream("/ml/label-conflict.json")) {
            var dataset = mapper.readValue(input, DatasetInput.class);
            assertThatThrownBy(() -> DatasetRules.validate(dataset)).isInstanceOf(MlException.class);
        }
        try (var input = getClass().getResourceAsStream("/ml/too-small.json")) {
            var dataset = DatasetRules.validate(mapper.readValue(input, DatasetInput.class));
            assertThatThrownBy(() -> splitter.split("s", new DatasetVersion("d", "h", dataset),
                    SplitConfig.defaults())).isInstanceOf(MlException.class);
        }
    }

    static DatasetInput data(int perClass) {
        var samples = new ArrayList<Sample>();
        for (String label : List.of("a", "b")) {
            for (int i = 0; i < perClass; i++) {
                samples.add(new Sample(label + "-" + String.format("%04d", i), "word" + label + " example " + i,
                        label, null));
            }
        }
        return new DatasetInput("test", "1.0", "SINGLE_LABEL_CLASSIFICATION", List.of("a", "b"), samples, null);
    }

    private DatasetInput withRecords(DatasetInput input, List<Sample> samples) {
        return new DatasetInput(input.name(), input.schemaVersion(), input.task(), input.labels(), samples, null);
    }

    private void add(List<Prediction> predictions, String actual, String predicted, int count) {
        for (int i = 0; i < count; i++) {
            predictions.add(new Prediction(Integer.toString(predictions.size()), actual, predicted, null, null, null));
        }
    }
}
