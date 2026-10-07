package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Complete budgeted portfolio hedge request.
 */
public record HedgeRequest(LocalDate valueDate, LocalDate settlementDate,
                          List<DepositQuote> deposits, List<SwapQuote> swaps,
                          List<Holding> holdings, List<Candidate> candidates,
                          List<Double> riskLimits, double budget) {
    private static final int MAX_HOLDINGS = 20;
    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_QUOTES = 20;

    public HedgeRequest {
        Validate.requireDate(valueDate, "value date");
        Validate.requireDate(settlementDate, "settlement date");
        if (settlementDate.isBefore(valueDate)) {
            throw new IllegalArgumentException("settlement date must not precede value date");
        }
        deposits = deposits == null ? List.of() : List.copyOf(deposits);
        swaps = swaps == null ? List.of() : List.copyOf(swaps);
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        riskLimits = riskLimits == null ? List.of() : List.copyOf(riskLimits);

        int quoteCount = deposits.size() + swaps.size();
        if (quoteCount == 0) {
            throw new IllegalArgumentException("at least one quote is required");
        }
        if (quoteCount > MAX_QUOTES) {
            throw new IllegalArgumentException("at most " + MAX_QUOTES + " quotes are allowed");
        }
        if (holdings.isEmpty()) {
            throw new IllegalArgumentException("at least one holding is required");
        }
        if (holdings.size() > MAX_HOLDINGS) {
            throw new IllegalArgumentException("at most " + MAX_HOLDINGS + " holdings are allowed");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("at most " + MAX_CANDIDATES
                    + " candidates are allowed");
        }
        if (riskLimits.size() != quoteCount) {
            throw new IllegalArgumentException("expected " + quoteCount
                    + " positive risk limits but got " + riskLimits.size());
        }
        for (int i = 0; i < riskLimits.size(); i++) {
            double limit = riskLimits.get(i);
            if (!Double.isFinite(limit) || limit <= 0.0) {
                throw new IllegalArgumentException(
                        "risk limit at quote bucket " + i + " must be positive and finite");
            }
        }
        if (!Double.isFinite(budget) || budget < 0.0) {
            throw new IllegalArgumentException("trading budget must be finite and non-negative");
        }

        Set<String> ids = new HashSet<>();
        for (DepositQuote quote : deposits) {
            if (!ids.add(quote.id())) {
                throw new IllegalArgumentException("duplicate quote id " + quote.id());
            }
        }
        for (SwapQuote quote : swaps) {
            if (!ids.add(quote.id())) {
                throw new IllegalArgumentException("duplicate quote id " + quote.id());
            }
        }
        for (Holding holding : holdings) {
            if (!ids.add(holding.id())) {
                throw new IllegalArgumentException("duplicate id " + holding.id());
            }
        }
        for (Candidate candidate : candidates) {
            if (!ids.add(candidate.id())) {
                throw new IllegalArgumentException("duplicate id " + candidate.id());
            }
        }
    }

    public List<String> quoteIds() {
        List<String> ids = new ArrayList<>();
        deposits.forEach(quote -> ids.add(quote.id()));
        swaps.forEach(quote -> ids.add(quote.id()));
        return List.copyOf(ids);
    }
}
