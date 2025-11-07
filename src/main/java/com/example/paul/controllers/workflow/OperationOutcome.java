package com.example.paul.controllers.workflow;

import org.springframework.http.HttpStatus;

import java.util.List;

public final class OperationOutcome<T, S> {
    private final OperationOutcomeType type;
    private final T payload;
    private final HttpStatus status;
    private final String message;
    private final List<S> decisionTrail;

    OperationOutcome(OperationOutcomeType type,
                     T payload,
                     HttpStatus status,
                     String message,
                     List<S> decisionTrail) {
        this.type = type;
        this.payload = payload;
        this.status = status;
        this.message = message;
        this.decisionTrail = decisionTrail;
    }

    public OperationOutcomeType getType() {
        return type;
    }

    public T getPayload() {
        return payload;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public List<S> getDecisionTrail() {
        return decisionTrail;
    }
}

