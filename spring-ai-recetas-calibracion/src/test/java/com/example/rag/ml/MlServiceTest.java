package com.example.rag.ml;

import com.example.rag.ml.api.MlController;
import com.example.rag.ml.application.MlService;
import com.example.rag.ml.domain.ClassificationGateway;
import com.example.rag.ml.domain.MlException;
import com.example.rag.ml.infrastructure.JsonExperimentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.example.rag.ml.domain.MlTypes.Evaluation;
import static com.example.rag.ml.domain.MlTypes.Experiment;
import static com.example.rag.ml.domain.MlTypes.ExperimentInput;
import static com.example.rag.ml.domain.MlTypes.Prediction;
import static com.example.rag.ml.domain.MlTypes.Sample;
import static com.example.rag.ml.domain.MlTypes.SplitConfig;
import static com.example.rag.ml.domain.MlTypes.State;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MlServiceTest {

    @TempDir
    Path temporary;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void localLifecycleFreezesBeforeTestAndSurvivesRestart() throws Exception {
        String experimentId;
        String testId;
        String splitId;
        var gateway = new FakeGateway();
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, gateway, mapper)) {
            var dataset = service.importDataset(MlDomainTest.data(100));
            var split = service.createSplit(dataset.datasetVersionId(), SplitConfig.defaults());
            splitId = split.splitId();
            var config = config(splitId, "LOCAL_TRAINABLE", 1.0);
            var experiment = service.createExperiment(config, "training");
            experimentId = experiment.experimentId();
            assertThat(service.createExperiment(config, "training").experimentId()).isEqualTo(experimentId);
            assertThatThrownBy(() -> service.createExperiment(config(splitId, "LOCAL_TRAINABLE", 0.1), "training"))
                    .isInstanceOfSatisfying(MlException.class, error -> assertThat(error.status()).isEqualTo(409));
            awaitExperiment(service, experimentId);
            assertThatThrownBy(() -> service.evaluate(experimentId, "test", "test"))
                    .isInstanceOf(MlException.class);
            var validation = service.evaluate(experimentId, "validation", "validation");
            awaitEvaluation(service, validation.evaluationId());
            service.freeze(experimentId, validation.evaluationId());
            var test = service.evaluate(experimentId, "test", "test");
            testId = test.evaluationId();
            awaitEvaluation(service, testId);
            var report = service.evaluation(testId);
            assertThat(report.coverage()).isEqualTo(1);
            assertThat(report.requestedCount()).isEqualTo(30);
            assertThat(report.metrics().accuracy()).isEqualTo(1);
            assertThat(report.metrics().accuracy()).isGreaterThan(report.baseline().accuracy());
            assertThat(service.evaluate(experimentId, "test", "another-key").evaluationId()).isEqualTo(testId);
            assertThatThrownBy(() -> service.evaluate(experimentId, "validation", "test"))
                    .isInstanceOf(MlException.class);
            assertThatThrownBy(() -> service.createExperiment(config(splitId, "LOCAL_TRAINABLE", 10.0), "new"))
                    .isInstanceOf(MlException.class);
            assertThat(gateway.calls.get()).isZero();
        }
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var recovered = new MlService(store, gateway, mapper)) {
            assertThat(recovered.experiment(experimentId).frozen()).isTrue();
            assertThat(recovered.experiment(experimentId).model().vocabulary()).contains("worda", "wordb");
            assertThat(recovered.evaluation(testId).metrics().accuracy()).isEqualTo(1);
            assertThat(recovered.evaluate(experimentId, "test", "test").evaluationId()).isEqualTo(testId);
        }
    }

    @Test
    void failedClaudeCasesAreReportedAndOnlyFailuresAreRetried() throws Exception {
        var gateway = new FakeGateway();
        gateway.failNext.set(true);
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, gateway, mapper)) {
            var dataset = service.importDataset(MlDomainTest.data(20));
            var split = service.createSplit(dataset.datasetVersionId(), SplitConfig.defaults());
            gateway.allowedTrainIds = split.assignments().stream().filter(item -> item.partition().equals("train"))
                    .map(item -> item.id()).toList();
            var experiment = service.createExperiment(config(split.splitId(), "CLAUDE_INFERENCE", 1.0), "claude");
            awaitExperiment(service, experiment.experimentId());
            assertThat(service.experiment(experiment.experimentId()).model().vocabulary()).isEmpty();
            var evaluation = service.evaluate(experiment.experimentId(), "validation", "evaluate");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    service.evaluation(evaluation.evaluationId()).status().equals("INCOMPLETE"));
            var incomplete = service.evaluation(evaluation.evaluationId());
            assertThat(incomplete.failureCount()).isEqualTo(1);
            assertThat(incomplete.metrics().evaluatedCount()).isEqualTo(incomplete.requestedCount() - 1);
            assertThat(incomplete.coverage()).isLessThan(1);
            assertThatThrownBy(() -> service.freeze(experiment.experimentId(), evaluation.evaluationId()))
                    .isInstanceOf(MlException.class);
            int firstCalls = gateway.calls.get();
            var retry = service.evaluate(experiment.experimentId(), "validation", "evaluate");
            assertThat(retry.evaluationId()).isEqualTo(evaluation.evaluationId());
            awaitEvaluation(service, retry.evaluationId());
            assertThat(gateway.calls.get()).isEqualTo(firstCalls + 1);
            assertThat(service.evaluation(retry.evaluationId()).predictions())
                    .allSatisfy(item -> assertThat(item.inputTokens()).isEqualTo(12L));
        }
    }

    @Test
    void selectionUsesValidationAndRejectsLosingTieCandidate() throws Exception {
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, new FakeGateway(), mapper)) {
            var dataset = service.importDataset(MlDomainTest.data(30));
            var split = service.createSplit(dataset.datasetVersionId(), SplitConfig.defaults());
            var first = service.createExperiment(config(split.splitId(), "LOCAL_TRAINABLE", 0.1), "a");
            var second = service.createExperiment(config(split.splitId(), "LOCAL_TRAINABLE", 1.0), "b");
            awaitExperiment(service, first.experimentId());
            awaitExperiment(service, second.experimentId());
            var validationA = service.evaluate(first.experimentId(), "validation", "a");
            var validationB = service.evaluate(second.experimentId(), "validation", "b");
            awaitEvaluation(service, validationA.evaluationId());
            awaitEvaluation(service, validationB.evaluationId());
            assertThatThrownBy(() -> service.freeze(second.experimentId(), validationB.evaluationId()))
                    .isInstanceOfSatisfying(MlException.class, error ->
                            assertThat(error.code()).isEqualTo("BETTER_VALIDATED_CANDIDATE"));
            assertThat(service.freeze(first.experimentId(), validationA.evaluationId()).frozen()).isTrue();
        }
    }

    @Test
    void restartMarksInterruptedJobsAndRepositoryPreventsConcurrentOpen() throws Exception {
        String experimentId;
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, new FakeGateway(), mapper)) {
            var dataset = service.importDataset(MlDomainTest.data(20));
            var split = service.createSplit(dataset.datasetVersionId(), SplitConfig.defaults());
            experimentId = service.createExperiment(config(split.splitId(), "LOCAL_TRAINABLE", 1.0), "x")
                    .experimentId();
            awaitExperiment(service, experimentId);
            State state = store.load();
            var original = state.experiments().get(experimentId);
            var interrupted = new Experiment(original.experimentId(), original.config(), original.datasetVersionId(),
                    "RUNNING", false, null, null, null, original.createdAt(), original.provenance(), null);
            store.save(new State(state.datasets(), state.splits(), Map.of(experimentId, interrupted),
                    state.evaluations(), state.idempotency(), state.exposedSplits()));
            assertThatThrownBy(() -> new JsonExperimentStore(mapper, temporary.toString()))
                    .isInstanceOf(Exception.class);
        }
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, new FakeGateway(), mapper)) {
            assertThat(service.experiment(experimentId).status()).isEqualTo("FAILED");
            assertThat(service.experiment(experimentId).error()).isEqualTo("INTERRUPTED_ON_RESTART");
        }
    }

    @Test
    void restEndpointsEnforceErrorsAndReturnLocations() throws Exception {
        try (var store = new JsonExperimentStore(mapper, temporary.toString());
                var service = new MlService(store, new FakeGateway(), mapper)) {
            var mvc = MockMvcBuilders.standaloneSetup(new MlController(service)).build();
            mvc.perform(post("/api/ml/datasets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATASET"));
            mvc.perform(get("/api/ml/evaluations/missing")).andExpect(status().isNotFound());
            mvc.perform(post("/api/ml/datasets").contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
            var imported = mvc.perform(post("/api/ml/datasets").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(MlDomainTest.data(20)))).andExpect(status().isCreated())
                    .andReturn();
            String datasetId = mapper.readTree(imported.getResponse().getContentAsString())
                    .path("datasetVersionId").asText();
            mvc.perform(get("/api/ml/datasets/" + datasetId + "/analysis"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(40));
            mvc.perform(post("/api/ml/datasets/" + datasetId + "/splits").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(new SplitConfig(80, 15, 15, 42, "STRATIFIED_GROUP", 5))))
                    .andExpect(status().isBadRequest());
            var split = mvc.perform(post("/api/ml/datasets/" + datasetId + "/splits"))
                    .andExpect(status().isCreated()).andReturn();
            String splitId = mapper.readTree(split.getResponse().getContentAsString()).path("splitId").asText();
            var created = mvc.perform(post("/api/ml/experiments").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "http-train")
                    .content(mapper.writeValueAsString(config(splitId, "LOCAL_TRAINABLE", 1.0))))
                    .andExpect(status().isAccepted()).andReturn();
            String experimentId = mapper.readTree(created.getResponse().getContentAsString())
                    .path("experimentId").asText();
            assertThat(created.getResponse().getHeader("Location")).endsWith(experimentId);
            awaitExperiment(service, experimentId);
            mvc.perform(post("/api/ml/experiments/" + experimentId + "/test"))
                    .andExpect(status().isConflict());
        }
    }

    private ExperimentInput config(String splitId, String mode, double alpha) {
        return new ExperimentInput(splitId, mode, null, alpha, null, "b", null, 2);
    }

    private void awaitExperiment(MlService service, String id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(service.experiment(id).status()).isEqualTo("SUCCEEDED"));
    }

    private void awaitEvaluation(MlService service, String id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(service.evaluation(id).status()).isEqualTo("SUCCEEDED"));
    }

    private static class FakeGateway implements ClassificationGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();
        private List<String> allowedTrainIds = List.of();

        @Override
        public Map<String, String> configuration() {
            return Map.of("model", "fake");
        }

        @Override
        public Prediction predict(Experiment experiment, List<String> labels, List<Sample> examples, Sample sample) {
            calls.incrementAndGet();
            assertThat(examples).allSatisfy(example -> assertThat(allowedTrainIds).contains(example.id()));
            if (failNext.getAndSet(false)) {
                throw new MlException(503, "SIMULATED_FAILURE", "Fallo sintético");
            }
            String predicted = sample.text().contains("worda") ? "a" : "b";
            return new Prediction(sample.id(), sample.label(), predicted, null, 12L, 1L);
        }
    }
}
