package com.example.rag.ml.application;

import com.example.rag.ml.domain.ClassificationGateway;
import com.example.rag.ml.domain.ConfusionMatrixCalculator;
import com.example.rag.ml.domain.DatasetRules;
import com.example.rag.ml.domain.DatasetSplitter;
import com.example.rag.ml.domain.ExperimentStore;
import com.example.rag.ml.domain.MlException;
import com.example.rag.ml.domain.NaiveBayesClassifier;
import com.example.rag.ml.domain.TrainableClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

import static com.example.rag.ml.domain.MlTypes.Analysis;
import static com.example.rag.ml.domain.MlTypes.DatasetInput;
import static com.example.rag.ml.domain.MlTypes.DatasetVersion;
import static com.example.rag.ml.domain.MlTypes.Evaluation;
import static com.example.rag.ml.domain.MlTypes.Experiment;
import static com.example.rag.ml.domain.MlTypes.ExperimentInput;
import static com.example.rag.ml.domain.MlTypes.IdempotencyEntry;
import static com.example.rag.ml.domain.MlTypes.ModelArtifact;
import static com.example.rag.ml.domain.MlTypes.Prediction;
import static com.example.rag.ml.domain.MlTypes.Sample;
import static com.example.rag.ml.domain.MlTypes.SplitConfig;
import static com.example.rag.ml.domain.MlTypes.SplitManifest;
import static com.example.rag.ml.domain.MlTypes.State;

@Service
public class MlService implements AutoCloseable {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MlService.class);

    private final ExperimentStore store;
    private final ClassificationGateway gateway;
    private final ObjectMapper mapper;
    private final TrainableClassifier classifier = new NaiveBayesClassifier();
    private final ConfusionMatrixCalculator calculator = new ConfusionMatrixCalculator();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "ml-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private State state;

    public MlService(ExperimentStore store, ClassificationGateway gateway, ObjectMapper mapper) {
        this.store = store;
        this.gateway = gateway;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        state = store.load();
        var experiments = new HashMap<>(state.experiments());
        experiments.replaceAll((id, experiment) -> pending(experiment.status())
                ? change(experiment, "FAILED", experiment.model(), "INTERRUPTED_ON_RESTART") : experiment);
        var evaluations = new HashMap<>(state.evaluations());
        evaluations.replaceAll((id, evaluation) -> pending(evaluation.status())
                ? evaluationState(evaluation, "FAILED", "INTERRUPTED_ON_RESTART") : evaluation);
        publish(new State(state.datasets(), state.splits(), Map.copyOf(experiments), Map.copyOf(evaluations),
                state.idempotency(), state.exposedSplits()));
    }

    public synchronized DatasetVersion importDataset(DatasetInput input) {
        DatasetInput data = DatasetRules.validate(input);
        DatasetVersion dataset = new DatasetVersion(id(), hash(data), data);
        publish(new State(put(state.datasets(), dataset.datasetVersionId(), dataset), state.splits(),
                state.experiments(), state.evaluations(), state.idempotency(), state.exposedSplits()));
        return dataset;
    }

    public synchronized Analysis analysis(String datasetId) {
        return DatasetRules.analyze(dataset(datasetId).data());
    }

    public synchronized SplitManifest createSplit(String datasetId, SplitConfig config) {
        SplitManifest split = new DatasetSplitter().split(id(), dataset(datasetId),
                config == null ? SplitConfig.defaults() : config);
        publish(new State(state.datasets(), put(state.splits(), split.splitId(), split), state.experiments(),
                state.evaluations(), state.idempotency(), state.exposedSplits()));
        return split;
    }

    public synchronized SplitManifest manifest(String splitId) {
        return require(state.splits(), splitId);
    }

    public synchronized Experiment experiment(String experimentId) {
        return require(state.experiments(), experimentId);
    }

    public synchronized Evaluation evaluation(String evaluationId) {
        return require(state.evaluations(), evaluationId);
    }

    public synchronized Experiment createExperiment(ExperimentInput input, String key) {
        ExperimentInput config = validateExperiment(input);
        String scope = "experiment:" + key(key);
        String existing = idempotent(scope, hash(config));
        if (existing != null) {
            return experiment(existing);
        }
        SplitManifest split = manifest(config.splitId());
        if (state.exposedSplits().contains(split.splitId())) {
            throw new MlException(409, "TEST_ALREADY_EXPOSED", "Crea un nuevo holdout antes de otro candidato");
        }
        Map<String, String> provenance = new HashMap<>(gateway.configuration());
        provenance.put("datasetHash", dataset(split.datasetVersionId()).hash());
        provenance.put("splitHash", hash(split));
        provenance.put("codeVersion", "ml-poc-v1");
        provenance.put("preprocessingVersion", config.preprocessingVersion());
        provenance.put("seed", Long.toString(split.config().seed()));
        Experiment experiment = new Experiment(id(), config, split.datasetVersionId(), "QUEUED", false, null,
                null, null, Instant.now().toString(), Map.copyOf(provenance), null);
        publish(new State(state.datasets(), state.splits(),
                put(state.experiments(), experiment.experimentId(), experiment),
                state.evaluations(), put(state.idempotency(), scope,
                        new IdempotencyEntry(hash(config), experiment.experimentId())), state.exposedSplits()));
        try {
            executor.execute(() -> runJob(experiment.experimentId(), false));
        } catch (RejectedExecutionException exception) {
            saveExperiment(change(experiment, "FAILED", null, "QUEUE_FULL"));
            throw new MlException(503, "QUEUE_FULL", "Cola de trabajos llena");
        }
        return experiment;
    }

    public synchronized Experiment freeze(String experimentId, String validationId) {
        Experiment experiment = experiment(experimentId);
        Evaluation validation = evaluation(validationId);
        if (!"SUCCEEDED".equals(experiment.status()) || !validation.experimentId().equals(experimentId)
                || !"validation".equals(validation.partition()) || !"SUCCEEDED".equals(validation.status())) {
            throw new MlException(409, "VALIDATION_REQUIRED", "Se requiere validación completa del mismo candidato");
        }
        if (experiment.frozen()) {
            if (!validationId.equals(experiment.validationId())) {
                throw new MlException(409, "CANDIDATE_FROZEN", "El candidato ya está congelado con otra validación");
            }
            return experiment;
        }
        if (state.exposedSplits().contains(experiment.config().splitId())) {
            throw new MlException(409, "TEST_ALREADY_EXPOSED", "El holdout ya fue utilizado");
        }
        if (validation.metrics().macroF1() == null) {
            throw new MlException(409, "UNSCORABLE_VALIDATION", "La validación no tiene macro-F1 definido");
        }
        for (Evaluation candidate : state.evaluations().values()) {
            Experiment other = experiment(candidate.experimentId());
            if (candidate.partition().equals("validation") && candidate.status().equals("SUCCEEDED")
                    && other.config().splitId().equals(experiment.config().splitId())
                    && other.config().mode().equals(experiment.config().mode())
                    && candidate.metrics().macroF1() != null
                    && (candidate.metrics().macroF1() > validation.metrics().macroF1()
                            || (candidate.metrics().macroF1().equals(validation.metrics().macroF1())
                                    && canonical(other.config()).compareTo(canonical(experiment.config())) < 0))) {
                throw new MlException(409, "BETTER_VALIDATED_CANDIDATE",
                        "Existe un candidato de validación preferente");
            }
        }
        Experiment frozen = new Experiment(experiment.experimentId(), experiment.config(),
                experiment.datasetVersionId(),
                experiment.status(), true, validationId, experiment.model(), experiment.artifactHash(),
                experiment.createdAt(), experiment.provenance(), null);
        saveExperiment(frozen);
        return frozen;
    }

    public synchronized Evaluation evaluate(String experimentId, String partition, String key) {
        Experiment experiment = experiment(experimentId);
        if (!Set.of("validation", "test").contains(partition)) {
            throw new MlException(400, "INVALID_PARTITION", "Solo se evalúa validation o test");
        }
        if (!"SUCCEEDED".equals(experiment.status()) || (partition.equals("test") && !experiment.frozen())) {
            throw new MlException(409, "CANDIDATE_NOT_READY", "Entrena, valida y congela antes de evaluar test");
        }
        boolean anotherTest = state.evaluations().values().stream().anyMatch(item -> item.partition().equals("test")
                && !item.experimentId().equals(experimentId)
                && experiment(item.experimentId()).config().splitId().equals(experiment.config().splitId()));
        if (anotherTest) {
            throw new MlException(409, "TEST_ALREADY_EXPOSED", "Otro candidato ya utilizó este holdout");
        }
        String scope = "evaluation:" + experimentId + ":" + key(key);
        String fingerprint = hash(partition);
        String previous = idempotent(scope, fingerprint);
        Evaluation result = previous == null ? state.evaluations().values().stream()
                .filter(item -> item.experimentId().equals(experimentId) && item.partition().equals(partition))
                .findFirst().orElse(null) : evaluation(previous);
        if (result != null && !Set.of("FAILED", "INCOMPLETE").contains(result.status())) {
            remember(scope, fingerprint, result.evaluationId());
            return result;
        }
        int count = samples(experiment, partition).size();
        if (experiment.config().mode().equals("CLAUDE_INFERENCE") && count > 300) {
            throw new MlException(422, "CALL_LIMIT_EXCEEDED", "Máximo 300 casos por evaluación Claude");
        }
        result = result == null ? new Evaluation(id(), experimentId, partition, "QUEUED", count, 0, 0,
                List.of(), null, null, null) : evaluationState(result, "QUEUED", null);
        List<String> exposed = new ArrayList<>(state.exposedSplits());
        if (partition.equals("test") && !exposed.contains(experiment.config().splitId())) {
            exposed.add(experiment.config().splitId());
        }
        publish(new State(state.datasets(), state.splits(), state.experiments(),
                put(state.evaluations(), result.evaluationId(), result), put(state.idempotency(), scope,
                        new IdempotencyEntry(fingerprint, result.evaluationId())), List.copyOf(exposed)));
        String evaluationId = result.evaluationId();
        try {
            executor.execute(() -> runJob(evaluationId, true));
        } catch (RejectedExecutionException exception) {
            saveEvaluation(evaluationState(result, "FAILED", "QUEUE_FULL"));
            throw new MlException(503, "QUEUE_FULL", "Cola de trabajos llena");
        }
        return result;
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void train(String experimentId) {
        Experiment experiment;
        synchronized (this) {
            experiment = experiment(experimentId);
            saveExperiment(change(experiment, "RUNNING", null, null));
        }
        try {
            List<Sample> train;
            List<String> labels;
            synchronized (this) {
                train = samples(experiment, "train");
                labels = dataset(experiment.datasetVersionId()).data().labels();
            }
            ModelArtifact model;
            if (experiment.config().mode().equals("LOCAL_TRAINABLE")) {
                model = classifier.train(labels, train, experiment.config().alpha());
            } else {
                String majority = labels.stream().min(java.util.Comparator.<String>comparingLong(label ->
                        -train.stream().filter(sample -> sample.label().equals(label)).count())
                        .thenComparingInt(labels::indexOf)).orElseThrow();
                model = new ModelArtifact(labels, List.of(), Map.of(), Map.of(), majority,
                        experiment.config().alpha(), experiment.config().preprocessingVersion());
            }
            synchronized (this) {
                saveExperiment(change(experiment, "SUCCEEDED", model, null));
            }
        } catch (Exception exception) {
            synchronized (this) {
                saveExperiment(change(experiment, "FAILED", null, errorCode(exception)));
            }
        }
    }

    private void runJob(String resourceId, boolean isEvaluation) {
        try {
            if (isEvaluation) {
                runEvaluation(resourceId);
            } else {
                train(resourceId);
            }
        } catch (RuntimeException exception) {
            synchronized (this) {
                State failed = isEvaluation
                        ? new State(state.datasets(), state.splits(), state.experiments(),
                                put(state.evaluations(), resourceId,
                                        evaluationState(evaluation(resourceId), "FAILED", "STORAGE_OR_JOB_FAILED")),
                                state.idempotency(), state.exposedSplits())
                        : new State(state.datasets(), state.splits(),
                                put(state.experiments(), resourceId, change(experiment(resourceId), "FAILED",
                                        experiment(resourceId).model(), "STORAGE_OR_JOB_FAILED")),
                                state.evaluations(), state.idempotency(), state.exposedSplits());
                try {
                    publish(failed);
                } catch (RuntimeException storageException) {
                    state = failed;
                    LOGGER.error("ML job {} failed; failure state is only available in memory", resourceId);
                }
            }
        }
    }

    private void runEvaluation(String evaluationId) {
        Evaluation evaluation;
        Experiment experiment;
        List<Sample> rows;
        List<Sample> examples;
        List<String> labels;
        synchronized (this) {
            evaluation = evaluation(evaluationId);
            experiment = experiment(evaluation.experimentId());
            rows = samples(experiment, evaluation.partition());
            examples = fewShot(experiment);
            labels = dataset(experiment.datasetVersionId()).data().labels();
            saveEvaluation(evaluationState(evaluation, "RUNNING", null));
        }
        Map<String, Prediction> existing = new HashMap<>();
        evaluation.predictions().stream().filter(item -> item.error() == null)
                .forEach(item -> existing.put(item.id(), item));
        var predictions = new ArrayList<Prediction>();
        try {
            for (Sample sample : rows) {
                Prediction prediction = existing.get(sample.id());
                if (prediction == null) {
                    try {
                        prediction = experiment.config().mode().equals("LOCAL_TRAINABLE")
                                ? new Prediction(sample.id(), sample.label(), classifier.predict(experiment.model(),
                                        sample.text()), null, null, null)
                                : gateway.predict(experiment, labels, examples, sample);
                        if (!labels.contains(prediction.predicted()) || !prediction.id().equals(sample.id())
                                || !prediction.actual().equals(sample.label()) || prediction.error() != null) {
                            throw new MlException(422, "INVALID_MODEL_OUTPUT", "Predicción incompatible");
                        }
                    } catch (Exception exception) {
                        prediction = new Prediction(sample.id(), sample.label(), null,
                                errorCode(exception), null, null);
                    }
                }
                predictions.add(prediction);
                if (experiment.config().mode().equals("CLAUDE_INFERENCE")) {
                    synchronized (this) {
                        saveEvaluation(new Evaluation(evaluationId, experiment.experimentId(), evaluation.partition(),
                                "RUNNING", rows.size(), 0, 0, List.copyOf(predictions), null, null, null));
                    }
                }
            }
            var valid = predictions.stream().filter(prediction -> prediction.error() == null).toList();
            var baselinePredictions = rows.stream().map(sample -> new Prediction(sample.id(), sample.label(),
                    experiment.model().majorityLabel(), null, null, null)).toList();
            int failures = rows.size() - valid.size();
            Evaluation completed = new Evaluation(evaluationId, experiment.experimentId(), evaluation.partition(),
                    failures == 0 ? "SUCCEEDED" : "INCOMPLETE", rows.size(), failures,
                    (double) valid.size() / rows.size(), List.copyOf(predictions),
                    valid.isEmpty() ? null : calculator.calculate(labels, valid, experiment.config().positiveLabel()),
                    calculator.calculate(labels, baselinePredictions, experiment.config().positiveLabel()), null);
            synchronized (this) {
                saveEvaluation(completed);
            }
        } catch (Exception exception) {
            synchronized (this) {
                saveEvaluation(evaluationState(evaluation(evaluationId), "FAILED", errorCode(exception)));
            }
        }
    }

    private ExperimentInput validateExperiment(ExperimentInput input) {
        if (input == null || input.splitId() == null) {
            throw new MlException(400, "INVALID_EXPERIMENT", "splitId obligatorio");
        }
        SplitManifest split = manifest(input.splitId());
        String mode = input.mode() == null ? "LOCAL_TRAINABLE" : input.mode();
        String expectedAlgorithm = mode.equals("CLAUDE_INFERENCE") ? "CLAUDE_FEW_SHOT" : "MULTINOMIAL_NAIVE_BAYES";
        String algorithm = input.algorithm() == null ? expectedAlgorithm : input.algorithm();
        double alpha = input.alpha() == null ? 1 : input.alpha();
        String preprocessing = input.preprocessingVersion() == null
                ? NaiveBayesClassifier.PREPROCESSING_VERSION : input.preprocessingVersion();
        String prompt = input.prompt() == null ? "" : input.prompt();
        int examples = input.fewShotExamples() == null ? 3 : input.fewShotExamples();
        if (!Set.of("LOCAL_TRAINABLE", "CLAUDE_INFERENCE").contains(mode)
                || !expectedAlgorithm.equals(algorithm) || !Double.isFinite(alpha) || alpha <= 0 || alpha > 100
                || !NaiveBayesClassifier.PREPROCESSING_VERSION.equals(preprocessing) || prompt.length() > 2000
                || examples < 0 || examples > 10 || (input.positiveLabel() != null
                        && !dataset(split.datasetVersionId()).data().labels().contains(input.positiveLabel()))) {
            throw new MlException(400, "INVALID_EXPERIMENT", "Configuración de experimento no soportada");
        }
        return new ExperimentInput(input.splitId(), mode, algorithm, alpha, preprocessing,
                input.positiveLabel(), prompt, examples);
    }

    private List<Sample> fewShot(Experiment experiment) {
        var train = samples(experiment, "train");
        var labels = dataset(experiment.datasetVersionId()).data().labels();
        var selected = new ArrayList<Sample>();
        int position = 0;
        while (selected.size() < experiment.config().fewShotExamples()) {
            boolean added = false;
            for (String label : labels) {
                var candidates = train.stream().filter(sample -> sample.label().equals(label)).toList();
                if (position < candidates.size() && selected.size() < experiment.config().fewShotExamples()) {
                    selected.add(candidates.get(position));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
            position++;
        }
        return List.copyOf(selected);
    }

    private List<Sample> samples(Experiment experiment, String partition) {
        Set<String> ids = manifest(experiment.config().splitId()).assignments().stream()
                .filter(item -> item.partition().equals(partition)).map(item -> item.id())
                .collect(java.util.stream.Collectors.toSet());
        return dataset(experiment.datasetVersionId()).data().records().stream()
                .filter(sample -> ids.contains(sample.id())).toList();
    }

    private DatasetVersion dataset(String id) {
        return require(state.datasets(), id);
    }

    private void saveExperiment(Experiment experiment) {
        publish(new State(state.datasets(), state.splits(),
                put(state.experiments(), experiment.experimentId(), experiment),
                state.evaluations(), state.idempotency(), state.exposedSplits()));
    }

    private void saveEvaluation(Evaluation evaluation) {
        publish(new State(state.datasets(), state.splits(), state.experiments(),
                put(state.evaluations(), evaluation.evaluationId(), evaluation),
                state.idempotency(), state.exposedSplits()));
    }

    private void remember(String scope, String fingerprint, String resourceId) {
        publish(new State(state.datasets(), state.splits(), state.experiments(), state.evaluations(),
                put(state.idempotency(), scope, new IdempotencyEntry(fingerprint, resourceId)), state.exposedSplits()));
    }

    private String idempotent(String scope, String fingerprint) {
        IdempotencyEntry existing = state.idempotency().get(scope);
        if (existing != null && !existing.fingerprint().equals(fingerprint)) {
            throw new MlException(409, "IDEMPOTENCY_CONFLICT", "La clave ya se utilizó con otro cuerpo");
        }
        return existing == null ? null : existing.resourceId();
    }

    private Experiment change(Experiment experiment, String status, ModelArtifact model, String error) {
        return new Experiment(experiment.experimentId(), experiment.config(), experiment.datasetVersionId(), status,
                experiment.frozen(), experiment.validationId(), model, model == null ? null : hash(model),
                experiment.createdAt(), experiment.provenance(), error);
    }

    private Evaluation evaluationState(Evaluation evaluation, String status, String error) {
        return new Evaluation(evaluation.evaluationId(), evaluation.experimentId(), evaluation.partition(), status,
                evaluation.requestedCount(), evaluation.failureCount(), evaluation.coverage(), evaluation.predictions(),
                evaluation.metrics(), evaluation.baseline(), error);
    }

    private void publish(State next) {
        store.save(next);
        state = next;
    }

    private String hash(Object value) {
        return DatasetRules.hash(canonical(value));
    }

    private String canonical(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("No se puede serializar el artefacto", exception);
        }
    }

    private String key(String key) {
        if (key != null && (key.isBlank() || key.length() > 200)) {
            throw new MlException(400, "INVALID_IDEMPOTENCY_KEY", "Clave vacía o demasiado larga");
        }
        return key == null ? id() : key;
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private boolean pending(String status) {
        return Set.of("QUEUED", "RUNNING").contains(status);
    }

    private String errorCode(Exception exception) {
        return exception instanceof MlException mlException ? mlException.code() : "EXECUTION_FAILED";
    }

    private <T> T require(Map<String, T> map, String id) {
        T value = id == null ? null : map.get(id);
        if (value == null) {
            throw new MlException(404, "NOT_FOUND", "Recurso ML inexistente");
        }
        return value;
    }

    private <T> Map<String, T> put(Map<String, T> map, String key, T value) {
        var copy = new HashMap<>(map);
        copy.put(key, value);
        return Map.copyOf(copy);
    }
}
