package com.example.paul.controllers.workflow;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OutcomeBuilder<T, S> {

    private final List<S> trail = new ArrayList<>();

    private OutcomeBuilder() {
    }

    public static <T, S> OutcomeBuilder<T, S> begin() {
        return new OutcomeBuilder<>();
    }

    public OutcomeBuilder<T, S> record(S step) {
        trail.add(step);
        return this;
    }

    public OperationOutcome<T, S> buildSuccess(T payload, HttpStatus status) {
        return new OperationOutcome<>(OperationOutcomeType.SUCCESS, payload, status, null, copyTrail());
    }

    public OperationOutcome<T, S> buildInvalid(HttpStatus status, String message) {
        return new OperationOutcome<>(OperationOutcomeType.INVALID_INPUT, null, status, message, copyTrail());
    }

    public OperationOutcome<T, S> buildEmpty(HttpStatus status, String message) {
        return new OperationOutcome<>(OperationOutcomeType.EMPTY_RESULT, null, status, message, copyTrail());
    }

    public OperationOutcome<T, S> buildFailure(HttpStatus status, String message) {
        return buildFailure(null, status, message);
    }

    public OperationOutcome<T, S> buildFailure(T payload, HttpStatus status, String message) {
        return new OperationOutcome<>(OperationOutcomeType.FAILURE, payload, status, message, copyTrail());
    }

    private List<S> copyTrail() {
        return Collections.unmodifiableList(new ArrayList<>(trail));
    }
}

