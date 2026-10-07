package com.hedge257;

/**
 * The trade bounds together with the turnover budget admit no solution.
 * Kept separate from {@link HedgeFailureException} (market-data/contract
 * revaluation failure) and numerical solver failure.
 */
public class HedgeInfeasibleException extends RuntimeException {
    public HedgeInfeasibleException(String reason) {
        super(reason);
    }
}
