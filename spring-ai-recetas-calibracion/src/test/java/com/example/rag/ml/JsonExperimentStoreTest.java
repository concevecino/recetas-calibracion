package com.example.rag.ml;

import com.example.rag.ml.infrastructure.JsonExperimentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.example.rag.ml.domain.MlTypes.IdempotencyEntry;
import static com.example.rag.ml.domain.MlTypes.State;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonExperimentStoreTest {

    @TempDir
    Path directory;

    @Test
    void transientAccessDenialRetriesAndPermanentFailurePreservesPublishedState() throws Exception {
        try (var store = new DeniedStore(directory)) {
            store.failuresRemaining = 3;
            store.save(State.empty());
            assertThat(store.attempts).isEqualTo(4);
            State previous = store.load();
            store.failuresRemaining = 100;
            store.attempts = 0;
            State changed = new State(Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of("key", new IdempotencyEntry("hash", "resource")), java.util.List.of());
            assertThatThrownBy(() -> store.save(changed)).isInstanceOf(IllegalStateException.class);
            assertThat(store.attempts).isEqualTo(8);
            assertThat(store.load()).isEqualTo(previous);
        }
    }

    private static class DeniedStore extends JsonExperimentStore {

        private int failuresRemaining;
        private int attempts;

        private DeniedStore(Path directory) throws IOException {
            super(new ObjectMapper(), directory.toString());
        }

        @Override
        protected void replaceFile(Path temporary) throws IOException {
            attempts++;
            if (failuresRemaining-- > 0) {
                throw new AccessDeniedException(temporary.toString());
            }
            super.replaceFile(temporary);
        }
    }
}
