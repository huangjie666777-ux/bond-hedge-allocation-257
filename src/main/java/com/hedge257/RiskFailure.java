package com.hedge257;

/**
 * Contract-level reason for one failed base or bumped-market revaluation.
 */
public record RiskFailure(String quoteId, String instrumentId, String reason) {
    @Override
    public String toString() {
        return "quote=" + quoteId + ", instrument=" + instrumentId + ", reason=" + reason;
    }
}
