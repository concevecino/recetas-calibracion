package com.example.rag.ml.domain;

import java.util.Map;

public class MlException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, ?> details;

    public MlException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public MlException(int status, String code, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, ?> details() {
        return details;
    }
}
