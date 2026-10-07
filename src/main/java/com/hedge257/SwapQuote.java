package com.hedge257;

import java.time.LocalDate;
import java.util.List;

/**
 * Fixed-for-floating swap that starts on the curve valuation date.
 * Payment dates must be strictly increasing and the last date is the swap end.
 * Period year fractions use adjacent dates (valuation date precedes the first
 * payment date) with ACT/365F. Pricing equation:
 * S * sum(alpha_i * D_i) = 1 - D_T.
 */
public record SwapQuote(String id, List<LocalDate> paymentDates, double fixedRate) {
    public SwapQuote {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("swap id must not be blank");
        }
        if (paymentDates == null || paymentDates.isEmpty()) {
            throw new IllegalArgumentException("swap " + id + " needs at least one payment date");
        }
        Validate.requireFinite(fixedRate, "swap fixed rate");
        LocalDate previous = null;
        for (LocalDate date : paymentDates) {
            Validate.requireDate(date, "swap payment date");
            if (previous != null && !date.isAfter(previous)) {
                throw new IllegalArgumentException(
                        "swap " + id + " payment dates must be strictly increasing");
            }
            previous = date;
        }
        paymentDates = java.util.List.copyOf(paymentDates);
    }

    public LocalDate endDate() {
        return paymentDates.get(paymentDates.size() - 1);
    }
}
