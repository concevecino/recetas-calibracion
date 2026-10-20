package com.example.rag.ml.infrastructure;

import com.example.rag.ml.domain.ExperimentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.example.rag.ml.domain.MlTypes.State;

/** Single-process PoC repository; atomic state publication requires an atomic-move filesystem. */
@Component
public class JsonExperimentStore implements ExperimentStore, AutoCloseable {

    private final ObjectMapper mapper;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public JsonExperimentStore(ObjectMapper mapper, @Value("${ml.storage-directory:data/ml}") String directory)
            throws IOException {
        this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("El repositorio ML ya está abierto por otro proceso");
            }
        } catch (IOException | RuntimeException exception) {
            lockChannel.close();
            throw exception;
        }
    }

    @Override
    public synchronized State load() {
        try {
            Path file = directory.resolve("state.json");
            return Files.exists(file) ? mapper.readValue(file.toFile(), State.class) : State.empty();
        } catch (IOException exception) {
            throw new IllegalStateException("No se puede recuperar el estado ML", exception);
        }
    }

    @Override
    public synchronized void save(State state) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "state-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveAtomically(temporary);
        } catch (IOException exception) {
            throw new IllegalStateException("No se puede publicar el estado ML de forma atómica", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A stale temporary file is never read as published state.
                }
            }
        }
    }

    protected void replaceFile(Path temporary) throws IOException {
        Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void moveAtomically(Path temporary) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                replaceFile(temporary);
                return;
            } catch (AccessDeniedException exception) {
                if (attempt >= 7 || Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                // Windows indexers/antivirus can briefly open the replaced file without delete sharing.
                LockSupport.parkNanos(Math.min(25L << attempt, 200L) * 1_000_000);
            }
        }
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        if (lock.isValid()) {
            lock.release();
        }
        lockChannel.close();
    }
}
