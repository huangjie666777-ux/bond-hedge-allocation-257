package com.hedge257;

import java.time.LocalDate;

/**
 * Shared validation helpers. Quotations never contain NaN or infinity.
 */
public final class Validate {
    private Validate() {
    }

    public static void requireDate(LocalDate date, String description) {
        if (date == null) {
            throw new IllegalArgumentException(description + " must not be null");
        }
    }

    /**
     * Rejects NaN and positive/negative infinity; zero and negative values pass.
     */
    public static double requireFinite(double value, String description) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(description + " must be finite, got " + value);
        }
        return value;
    }
}
