package com.hedge257;

/**
 * The global linear program could not be solved numerically or the solved
 * trades failed the independent recomputation of the constraints. No partial
 * trade list is delivered.
 */
public class HedgeNumericalException extends RuntimeException {
    public HedgeNumericalException(String reason) {
        super(reason);
    }

    public HedgeNumericalException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
