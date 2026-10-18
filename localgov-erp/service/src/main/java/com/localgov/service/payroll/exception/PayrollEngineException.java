package com.localgov.service.payroll.exception;

/**
 * Thrown when the payroll engine encounters a condition it cannot
 * resolve: missing salary notch, missing compliance rule, invalid
 * period, etc. This is a hard failure — the run aborts and no
 * partial results are persisted.
 */
public class PayrollEngineException extends RuntimeException {
    public PayrollEngineException(String message) {
        super(message);
    }

    public PayrollEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
