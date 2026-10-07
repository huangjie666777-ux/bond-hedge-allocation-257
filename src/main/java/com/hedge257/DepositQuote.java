package com.hedge257;

import java.time.LocalDate;

/**
 * Deposit quoted from the curve valuation date. Pricing equation:
 * D(end) = 1 / (1 + rate * alpha), alpha = ACT/365F(valueDate, end).
 */
public record DepositQuote(String id, LocalDate endDate, double rate) {
    public DepositQuote {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("deposit id must not be blank");
        }
        Validate.requireDate(endDate, "deposit end date");
        Validate.requireFinite(rate, "deposit rate");
    }
}
