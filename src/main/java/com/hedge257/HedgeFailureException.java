package com.hedge257;

/**
 * A contract could not be revalued on a bumped curve (bootstrap, cash-flow
 * pricing or tree calibration/valuation failure). The failed quote bucket and
 * contract are always identified; buckets are never dropped and no zero is
 * written in their place.
 */
public class HedgeFailureException extends RuntimeException {
    private final String quoteId;
    private final String contractId;

    public HedgeFailureException(String quoteId, String contractId, String reason) {
        super("revaluation failed for quote '" + quoteId + "', contract '" + contractId
                + "': " + reason);
        this.quoteId = quoteId;
        this.contractId = contractId;
    }

    public String quoteId() {
        return quoteId;
    }

    public String contractId() {
        return contractId;
    }
}
