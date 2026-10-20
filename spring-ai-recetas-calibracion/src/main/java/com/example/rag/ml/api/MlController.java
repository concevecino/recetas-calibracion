package com.example.rag.ml.api;

import com.example.rag.ml.application.MlService;
import com.example.rag.ml.domain.MlException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.example.rag.ml.domain.MlTypes.DatasetInput;
import static com.example.rag.ml.domain.MlTypes.ExperimentInput;
import static com.example.rag.ml.domain.MlTypes.SplitConfig;

@RestController
@RequestMapping("/api/ml")
public class MlController {

    private final MlService service;

    public MlController(MlService service) {
        this.service = service;
    }

    @PostMapping("/datasets")
    public ResponseEntity<?> importDataset(@RequestBody DatasetInput input) {
        var dataset = service.importDataset(input);
        return ResponseEntity.created(URI.create("/api/ml/datasets/" + dataset.datasetVersionId() + "/analysis"))
                .body(Map.of("datasetVersionId", dataset.datasetVersionId(), "hash", dataset.hash()));
    }

    @GetMapping("/datasets/{datasetVersionId}/analysis")
    public Object analysis(@PathVariable String datasetVersionId) {
        return service.analysis(datasetVersionId);
    }

    @PostMapping("/datasets/{datasetVersionId}/splits")
    public ResponseEntity<?> split(@PathVariable String datasetVersionId,
            @RequestBody(required = false) SplitConfig config) {
        var split = service.createSplit(datasetVersionId, config);
        return ResponseEntity.created(URI.create("/api/ml/splits/" + split.splitId() + "/manifest")).body(split);
    }

    @GetMapping("/splits/{splitId}/manifest")
    public Object manifest(@PathVariable String splitId) {
        return service.manifest(splitId);
    }

    @PostMapping("/experiments")
    public ResponseEntity<?> experiment(@RequestBody ExperimentInput input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var experiment = service.createExperiment(input, key);
        return ResponseEntity.accepted().location(URI.create("/api/ml/experiments/" + experiment.experimentId()))
                .body(experiment);
    }

    @GetMapping("/experiments/{experimentId}")
    public Object experiment(@PathVariable String experimentId) {
        return service.experiment(experimentId);
    }

    @PostMapping("/experiments/{experimentId}/validation")
    public ResponseEntity<?> validate(@PathVariable String experimentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return evaluate(experimentId, "validation", key);
    }

    @PostMapping("/experiments/{experimentId}/freeze")
    public Object freeze(@PathVariable String experimentId, @RequestBody FreezeRequest request) {
        return service.freeze(experimentId, request.validationId());
    }

    @PostMapping("/experiments/{experimentId}/test")
    public ResponseEntity<?> test(@PathVariable String experimentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return evaluate(experimentId, "test", key);
    }

    @GetMapping("/evaluations/{evaluationId}")
    public Object evaluation(@PathVariable String evaluationId) {
        return service.evaluation(evaluationId);
    }

    @GetMapping("/evaluations/{evaluationId}/predictions")
    public Object predictions(@PathVariable String evaluationId) {
        return service.evaluation(evaluationId).predictions();
    }

    @ExceptionHandler(MlException.class)
    public ResponseEntity<?> domainError(MlException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code(),
                "message", exception.getMessage(), "details", exception.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JSON", "message", "JSON inválido"));
    }

    private ResponseEntity<?> evaluate(String experimentId, String partition, String key) {
        var evaluation = service.evaluate(experimentId, partition, key);
        return ResponseEntity.accepted().location(URI.create("/api/ml/evaluations/" + evaluation.evaluationId()))
                .body(evaluation);
    }

    public record FreezeRequest(String validationId) {}
}
