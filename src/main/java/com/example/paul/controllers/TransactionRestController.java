package com.example.paul.controllers;

import com.example.paul.constants.ACTION;
import com.example.paul.models.Account;
import com.example.paul.services.AccountService;
import com.example.paul.services.TransactionService;
import com.example.paul.utils.*;
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

import static com.example.paul.constants.constants.*;

@RestController
@RequestMapping("api/v1")
public class TransactionRestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionRestController.class);

    private final AccountService accountService;
    private final TransactionService transactionService;

    @Autowired
    public TransactionRestController(AccountService accountService, TransactionService transactionService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
    }

    @PostMapping(value = "/transactions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> makeTransfer(
            @Valid @RequestBody TransactionInput transactionInput) {
        LOGGER.debug("Triggered TransactionRestController.makeTransfer");

        OperationOutcome<Boolean> outcome = evaluateTransfer(transactionInput);
        logOutcome(outcome, "transaction transfer");
        return convertOutcome(outcome,
                INVALID_TRANSACTION,
                INVALID_TRANSACTION,
                INVALID_TRANSACTION);
    }

    @PostMapping(value = "/withdraw",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> withdraw(
            @Valid @RequestBody WithdrawInput withdrawInput) {
        LOGGER.debug("Triggered TransactionRestController.withdraw");

        OperationOutcome<String> outcome = evaluateWithdrawal(withdrawInput);
        logOutcome(outcome, "withdrawal");
        return convertOutcome(outcome,
                NO_ACCOUNT_FOUND,
                INVALID_SEARCH_CRITERIA,
                INSUFFICIENT_ACCOUNT_BALANCE);
    }


    @PostMapping(value = "/deposit",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deposit(
            @Valid @RequestBody DepositInput depositInput) {
        LOGGER.debug("Triggered TransactionRestController.deposit");

        OperationOutcome<String> outcome = evaluateDeposit(depositInput);
        logOutcome(outcome, "deposit");
        return convertOutcome(outcome,
                NO_ACCOUNT_FOUND,
                INVALID_SEARCH_CRITERIA,
                INVALID_SEARCH_CRITERIA);
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
                return new ResponseEntity<>(resolveBody(outcome, invalidMessage), outcome.getStatus());
            case EMPTY_RESULT:
                return new ResponseEntity<>(resolveBody(outcome, emptyResultMessage), outcome.getStatus());
            case FAILURE:
                return new ResponseEntity<>(resolveBody(outcome, failureMessage), outcome.getStatus());
            default:
                throw new IllegalStateException("Unhandled outcome type: " + outcome.getType());
        }
    }

    private Object resolveBody(OperationOutcome<?> outcome, String fallbackMessage) {
        Object payload = outcome.getPayload();
        return payload != null ? payload : resolveMessage(outcome.getMessage(), fallbackMessage);
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

    private OperationOutcome<Boolean> evaluateTransfer(TransactionInput transactionInput) {
        OutcomeBuilder<Boolean> builder = OutcomeBuilder.<Boolean>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isSearchTransactionValid(transactionInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION);
        }

        builder.record(DecisionPath.TRANSFER_ATTEMPT);
        boolean completed = transactionService.makeTransfer(transactionInput);

        if (!completed) {
            builder.record(DecisionPath.TRANSFER_FAILED);
            return builder.buildFailure(Boolean.FALSE, HttpStatus.OK, INVALID_TRANSACTION);
        }

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(Boolean.TRUE, HttpStatus.OK);
    }

    private OperationOutcome<String> evaluateWithdrawal(WithdrawInput withdrawInput) {
        OutcomeBuilder<String> builder = OutcomeBuilder.<String>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isSearchCriteriaValid(withdrawInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account account = accountService.getAccount(withdrawInput.getSortCode(), withdrawInput.getAccountNumber());

        if (account == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.BALANCE_CHECK);
        if (!transactionService.isAmountAvailable(withdrawInput.getAmount(), account.getCurrentBalance())) {
            builder.record(DecisionPath.INSUFFICIENT_FUNDS);
            return builder.buildFailure(HttpStatus.OK, INSUFFICIENT_ACCOUNT_BALANCE);
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(account, withdrawInput.getAmount(), ACTION.WITHDRAW);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private OperationOutcome<String> evaluateDeposit(DepositInput depositInput) {
        OutcomeBuilder<String> builder = OutcomeBuilder.<String>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isAccountNoValid(depositInput.getTargetAccountNo())) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account account = accountService.getAccount(depositInput.getTargetAccountNo());

        if (account == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(account, depositInput.getAmount(), ACTION.DEPOSIT);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private enum OperationOutcomeType {
        SUCCESS,
        INVALID_INPUT,
        EMPTY_RESULT,
        FAILURE
    }

    private enum DecisionPath {
        PRE_VALIDATION,
        VALIDATION_FAILED_GENERIC,
        TRANSFER_ATTEMPT,
        TRANSFER_FAILED,
        ACCOUNT_LOOKUP,
        RESULT_EMPTY,
        BALANCE_CHECK,
        INSUFFICIENT_FUNDS,
        BALANCE_UPDATE,
        RESULT_SUCCESS
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
            return buildFailure(null, status, message);
        }

        OperationOutcome<T> buildFailure(T payload, HttpStatus status, String message) {
            return new OperationOutcome<>(OperationOutcomeType.FAILURE, payload, status, message, copyTrail());
        }

        private java.util.List<DecisionPath> copyTrail() {
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(trail));
        }
    }
}
