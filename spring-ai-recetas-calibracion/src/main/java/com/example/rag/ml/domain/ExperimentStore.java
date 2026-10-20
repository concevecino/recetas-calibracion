package com.example.rag.ml.domain;

import static com.example.rag.ml.domain.MlTypes.State;

public interface ExperimentStore {

    State load();

    void save(State state);
}
