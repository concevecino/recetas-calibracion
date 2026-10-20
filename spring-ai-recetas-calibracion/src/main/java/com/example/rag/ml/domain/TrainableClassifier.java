package com.example.rag.ml.domain;

import java.util.List;

import static com.example.rag.ml.domain.MlTypes.ModelArtifact;
import static com.example.rag.ml.domain.MlTypes.Sample;

public interface TrainableClassifier {

    ModelArtifact train(List<String> labels, List<Sample> samples, double alpha);

    String predict(ModelArtifact model, String text);
}
