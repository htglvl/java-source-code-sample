package com.example.paul.controllers;

import com.example.paul.constants.constants;
import com.example.paul.models.Account;
import com.example.paul.services.AccountService;
import com.example.paul.utils.AccountInput;
import com.example.paul.utils.CreateAccountInput;
import com.example.paul.utils.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("api/v1")
public class AccountRestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountRestController.class);

    private final AccountService accountService;

    @Autowired
    public AccountRestController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping(value = "/accounts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> checkAccountBalance(
            // TODO In the future support searching by card number in addition to sort code and account number
            @Valid @RequestBody AccountInput accountInput) {
        LOGGER.debug("Triggered AccountRestController.accountInput");

        OperationOutcome<Account> outcome = evaluateAccountLookup(accountInput);
        logOutcome(outcome, "account lookup");
        return convertOutcome(outcome,
                constants.NO_ACCOUNT_FOUND,
                constants.INVALID_SEARCH_CRITERIA,
                constants.NO_ACCOUNT_FOUND);
    }


    @PutMapping(value = "/accounts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createAccount(
            @Valid @RequestBody CreateAccountInput createAccountInput) {
        LOGGER.debug("Triggered AccountRestController.createAccountInput");

        OperationOutcome<Account> outcome = evaluateAccountCreation(createAccountInput);
        logOutcome(outcome, "account creation");
        return convertOutcome(outcome,
                constants.CREATE_ACCOUNT_FAILED,
                constants.INVALID_SEARCH_CRITERIA,
                constants.CREATE_ACCOUNT_FAILED);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, String> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return errors;
    }

    private ResponseEntity<?> convertOutcome(OperationOutcome<?> outcome,
                                             String emptyResultMessage,
                                             String invalidMessage,
                                             String failureMessage) {
        switch (outcome.getType()) {
            case SUCCESS:
                return new ResponseEntity<>(outcome.getPayload(), outcome.getStatus());
            case INVALID_INPUT:
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), invalidMessage), outcome.getStatus());
            case EMPTY_RESULT:
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), emptyResultMessage), outcome.getStatus());
            case FAILURE:
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), failureMessage), outcome.getStatus());
            default:
                throw new IllegalStateException("Unhandled outcome type: " + outcome.getType());
        }
    }

    private String resolveMessage(String preferredMessage, String fallbackMessage) {
        return preferredMessage == null ? fallbackMessage : preferredMessage;
    }

    private void logOutcome(OperationOutcome<?> outcome, String context) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Outcome for {} -> type={}, status={}, trail={}",
                    context, outcome.getType(), outcome.getStatus(), outcome.getDecisionTrail());
        }
    }

    private OperationOutcome<Account> evaluateAccountLookup(AccountInput accountInput) {
        OutcomeBuilder<Account> builder = OutcomeBuilder.begin()
                .record(DecisionPath.PRE_VALIDATION);

        String sanitizedSortCode = sanitizeAndRecord(accountInput.getSortCode(),
                DecisionPath.SORT_CODE_SANITIZED, builder);
        String sanitizedAccountNumber = sanitizeAndRecord(accountInput.getAccountNumber(),
                DecisionPath.ACCOUNT_NUMBER_SANITIZED, builder);

        if (isBlank(sanitizedSortCode) || isBlank(sanitizedAccountNumber)) {
            builder.record(DecisionPath.VALIDATION_FAILED_MISSING_FIELDS);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        if (!InputValidator.isSearchCriteriaValid(accountInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.SERVICE_INVOCATION);
        Account account = accountService.getAccount(sanitizedSortCode, sanitizedAccountNumber);

        if (account == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, constants.NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(account, HttpStatus.OK);
    }

    private OperationOutcome<Account> evaluateAccountCreation(CreateAccountInput createAccountInput) {
        OutcomeBuilder<Account> builder = OutcomeBuilder.<Account>begin()
                .record(DecisionPath.PRE_VALIDATION);

        String sanitizedBankName = sanitizeAndRecord(createAccountInput.getBankName(),
                DecisionPath.BANK_NAME_SANITIZED, builder);
        String sanitizedOwnerName = sanitizeAndRecord(createAccountInput.getOwnerName(),
                DecisionPath.OWNER_NAME_SANITIZED, builder);

        if (isBlank(sanitizedBankName) || isBlank(sanitizedOwnerName)) {
            builder.record(DecisionPath.VALIDATION_FAILED_MISSING_FIELDS);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        if (!InputValidator.isCreateAccountCriteriaValid(createAccountInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.CREATION_ATTEMPT);
        Account account = accountService.createAccount(sanitizedBankName, sanitizedOwnerName);

        if (account == null) {
            builder.record(DecisionPath.CREATION_FAILURE);
            return builder.buildEmpty(HttpStatus.OK, constants.CREATE_ACCOUNT_FAILED);
        }

        builder.record(DecisionPath.CREATION_SUCCESS);
        return builder.buildSuccess(account, HttpStatus.OK);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> String sanitizeAndRecord(String value, DecisionPath path, OutcomeBuilder<T> builder) {
        if (value == null) {
            return null;
        }
        String sanitized = value.trim();
        if (!sanitized.equals(value)) {
            builder.record(path);
        }
        return sanitized;
    }

    private enum OperationOutcomeType {
        SUCCESS,
        INVALID_INPUT,
        EMPTY_RESULT,
        FAILURE
    }

    private enum DecisionPath {
        PRE_VALIDATION,
        VALIDATION_FAILED_MISSING_FIELDS,
        VALIDATION_FAILED_GENERIC,
        SORT_CODE_SANITIZED,
        ACCOUNT_NUMBER_SANITIZED,
        BANK_NAME_SANITIZED,
        OWNER_NAME_SANITIZED,
        SERVICE_INVOCATION,
        RESULT_EMPTY,
        RESULT_SUCCESS,
        CREATION_ATTEMPT,
        CREATION_FAILURE,
        CREATION_SUCCESS
    }

    private static final class OperationOutcome<T> {
        private final OperationOutcomeType type;
        private final T payload;
        private final HttpStatus status;
        private final String message;
        private final java.util.List<DecisionPath> decisionTrail;

        private OperationOutcome(OperationOutcomeType type,
                                T payload,
                                HttpStatus status,
                                String message,
                                java.util.List<DecisionPath> decisionTrail) {
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

        public java.util.List<DecisionPath> getDecisionTrail() {
            return decisionTrail;
        }
    }

    private static final class OutcomeBuilder<T> {
        private final java.util.List<DecisionPath> trail = new java.util.ArrayList<>();

        private OutcomeBuilder() {
        }

        static <T> OutcomeBuilder<T> begin() {
            return new OutcomeBuilder<>();
        }

        OutcomeBuilder<T> record(DecisionPath path) {
            trail.add(path);
            return this;
        }

        OperationOutcome<T> buildSuccess(T payload, HttpStatus status) {
            return new OperationOutcome<>(OperationOutcomeType.SUCCESS, payload, status, null, copyTrail());
        }

        OperationOutcome<T> buildInvalid(HttpStatus status, String message) {
            return new OperationOutcome<>(OperationOutcomeType.INVALID_INPUT, null, status, message, copyTrail());
        }

        OperationOutcome<T> buildEmpty(HttpStatus status, String message) {
            return new OperationOutcome<>(OperationOutcomeType.EMPTY_RESULT, null, status, message, copyTrail());
        }

        OperationOutcome<T> buildFailure(HttpStatus status, String message) {
            return new OperationOutcome<>(OperationOutcomeType.FAILURE, null, status, message, copyTrail());
        }

        private java.util.List<DecisionPath> copyTrail() {
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(trail));
        }
    }
}
