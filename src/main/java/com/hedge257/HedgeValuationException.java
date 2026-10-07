package com.hedge257;

import java.util.List;

/**
 * Raised when base or shifted curve bootstrap/repricing fails. No bucket is
 * deleted and no DV01 is replaced with zero.
 */
public class HedgeValuationException extends RuntimeException {
    private final transient List<RiskFailure> failures;

    public HedgeValuationException(List<RiskFailure> failures) {
        super("portfolio revaluation failed: "
                + String.join("; ", failures.stream().map(RiskFailure::toString).toList()));
        this.failures = List.copyOf(failures);
    }

    public List<RiskFailure> failures() {
        return failures;
    }
}
