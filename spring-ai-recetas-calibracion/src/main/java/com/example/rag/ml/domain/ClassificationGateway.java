package com.example.rag.ml.domain;

import java.util.List;
import java.util.Map;

import static com.example.rag.ml.domain.MlTypes.Experiment;
import static com.example.rag.ml.domain.MlTypes.Prediction;
import static com.example.rag.ml.domain.MlTypes.Sample;

public interface ClassificationGateway {

    Map<String, String> configuration();

    Prediction predict(Experiment experiment, List<String> labels, List<Sample> examples, Sample sample);
}
