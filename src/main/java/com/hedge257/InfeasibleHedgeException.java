package com.hedge257;

/**
 * Raised when bounds and the cash budget make the linear program infeasible.
 */
public class InfeasibleHedgeException extends RuntimeException {
    public InfeasibleHedgeException(String message) {
        super(message);
    }
}
