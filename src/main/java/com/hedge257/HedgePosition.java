package com.hedge257;

import java.time.LocalDate;
import java.util.List;

/**
 * A portfolio holding. Quantity is signed and continuous, expressed in units
 * of 100 of face value and is independent of {@link Bond#faceValue()}.
 * A non-null call schedule (together with sigma and stepDays) marks the
 * holding as issuer-callable; every revaluation then recalibrates the
 * short-rate tree and re-decides exercise on that tree.
 *
 * @param id       unique holding identifier
 * @param bond     underlying bond contract
 * @param quantity signed continuous quantity in hundreds of face value
 * @param calls    original issuer call schedule, or null/empty for a plain bond
 * @param sigma    annualized short-rate volatility used by the callable tree
 * @param stepDays equal tree step in days; ignored when there are no calls
 */
public record HedgePosition(String id, Bond bond, double quantity,
                            List<CallPrice> calls, double sigma, int stepDays) {
    public HedgePosition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("position id must not be blank");
        }
        if (bond == null) {
            throw new IllegalArgumentException("position " + id + " bond must not be null");
        }
        if (!Double.isFinite(quantity)) {
            throw new IllegalArgumentException("position " + id + " quantity must be finite");
        }
        if (!Double.isFinite(sigma) || sigma < 0.0) {
            throw new IllegalArgumentException(
                    "position " + id + " sigma must be finite and non-negative");
        }
        if (stepDays <= 0) {
            throw new IllegalArgumentException(
                    "position " + id + " stepDays must be positive");
        }
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public boolean callable() {
        return !calls.isEmpty();
    }

    /**
     * Convenience constructor for a plain (non-callable) holding.
     */
    public HedgePosition(String id, Bond bond, double quantity) {
        this(id, bond, quantity, List.of(), 0.0, 1);
    }
}
