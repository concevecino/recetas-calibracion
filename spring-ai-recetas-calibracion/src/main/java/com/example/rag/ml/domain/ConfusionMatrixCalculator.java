package com.example.rag.ml.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.rag.ml.domain.MlTypes.ClassMetrics;
import static com.example.rag.ml.domain.MlTypes.Metrics;
import static com.example.rag.ml.domain.MlTypes.Prediction;

public final class ConfusionMatrixCalculator {

    public Metrics calculate(List<String> labels, List<Prediction> predictions, String positiveLabel) {
        if (predictions.isEmpty()) {
            throw new MlException(422, "EMPTY_EVALUATION", "No hay predicciones válidas para calcular métricas");
        }
        long[][] matrix = new long[labels.size()][labels.size()];
        for (Prediction prediction : predictions) {
            int actual = labels.indexOf(prediction.actual());
            int predicted = labels.indexOf(prediction.predicted());
            if (actual < 0 || predicted < 0 || prediction.error() != null) {
                throw new MlException(400, "INVALID_PREDICTION", "Etiqueta desconocida o predicción fallida");
            }
            matrix[actual][predicted]++;
        }
        long correct = 0;
        Double macro = 0.0;
        Double weighted = 0.0;
        Double balanced = 0.0;
        Map<String, ClassMetrics> perClass = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            long support = 0;
            long column = 0;
            for (int j = 0; j < labels.size(); j++) {
                support += matrix[i][j];
                column += matrix[j][i];
            }
            long tp = matrix[i][i];
            long fn = support - tp;
            long fp = column - tp;
            correct += tp;
            Double precision = ratio(tp, tp + fp);
            Double recall = ratio(tp, tp + fn);
            Double f1 = ratio(2 * tp, 2 * tp + fp + fn);
            var undefined = new ArrayList<String>();
            if (precision == null) {
                undefined.add("precision");
            }
            if (recall == null) {
                undefined.add("recall");
            }
            if (f1 == null) {
                undefined.add("f1");
            }
            perClass.put(labels.get(i), new ClassMetrics(tp, fp, fn, predictions.size() - tp - fn - fp,
                    support, precision, recall, f1, List.copyOf(undefined)));
            macro = macro == null || f1 == null ? null : macro + f1 / labels.size();
            weighted = weighted == null || f1 == null ? null : weighted + f1 * support / predictions.size();
            balanced = balanced == null || recall == null ? null : balanced + recall / labels.size();
        }
        return new Metrics(List.copyOf(labels), "ROWS_ACTUAL_COLUMNS_PREDICTED", matrix, predictions.size(),
                (double) correct / predictions.size(), Map.copyOf(perClass), macro, weighted, balanced,
                positiveLabel, positiveLabel == null ? null : perClass.get(positiveLabel));
    }

    private Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
