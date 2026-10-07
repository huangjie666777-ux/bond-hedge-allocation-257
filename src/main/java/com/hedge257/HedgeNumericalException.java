package com.hedge257;

/**
 * Raised for optimizer numerical failure, distinct from proven infeasibility.
 */
public class HedgeNumericalException extends RuntimeException {
    public HedgeNumericalException(String message, Throwable cause) {
        super(message, cause);
    }
}
