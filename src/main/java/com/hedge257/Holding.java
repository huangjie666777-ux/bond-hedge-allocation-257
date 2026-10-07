package com.hedge257;

import java.util.List;

/**
 * Signed portfolio holding. Quantity is continuously sized face amount in
 * units of 100 face, independently of the bond's contractual face value.
 */
public record Holding(String id, Bond bond, double quantity,
                      List<CallPrice> callSchedule, double sigma, int stepDays) {
    public Holding {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("holding id must not be blank");
        }
        if (bond == null) {
            throw new IllegalArgumentException("holding " + id + " bond must not be null");
        }
        Validate.requireFinite(quantity, "holding " + id + " quantity");
        callSchedule = callSchedule == null ? List.of() : List.copyOf(callSchedule);
        if (!callSchedule.isEmpty()) {
            if (!Double.isFinite(sigma) || sigma < 0.0) {
                throw new IllegalArgumentException(
                        "callable holding " + id + " sigma must be finite and non-negative");
            }
            if (stepDays <= 0) {
                throw new IllegalArgumentException(
                        "callable holding " + id + " step days must be positive");
            }
        }
    }

    public static Holding plain(String id, Bond bond, double quantity) {
        return new Holding(id, bond, quantity, List.of(), 0.0, 1);
    }

    public static Holding callable(String id, Bond bond, double quantity,
                                   List<CallPrice> callSchedule, double sigma, int stepDays) {
        return new Holding(id, bond, quantity, callSchedule, sigma, stepDays);
    }

    public boolean callable() {
        return !callSchedule.isEmpty();
    }
}
