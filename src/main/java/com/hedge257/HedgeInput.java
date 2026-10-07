package com.hedge257;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable hedge request. Quote buckets are deposits followed by swaps, in
 * input order. Every quote must carry a strictly positive finite risk limit L
 * in {@code limits}; the single non-negative finite turnover budget B applies
 * to candidate trades and charges buys and sells symmetrically.
 *
 * @param valueDate      curve valuation date
 * @param settlementDate bond settlement date
 * @param deposits       deposit quotes, at most 20 quotes in total with swaps
 * @param swaps          fixed-for-floating swap quotes
 * @param positions      signed holdings, at most 20
 * @param candidates     tradable plain bonds, at most 12
 * @param limits         positive risk limit L per quote id
 * @param budget         non-negative turnover budget B
 */
public record HedgeInput(LocalDate valueDate, LocalDate settlementDate,
                        List<DepositQuote> deposits, List<SwapQuote> swaps,
                        List<HedgePosition> positions, List<HedgeCandidate> candidates,
                        Map<String, Double> limits, double budget) {
    private static final int MAX_QUOTES = 20;
    private static final int MAX_POSITIONS = 20;
    private static final int MAX_CANDIDATES = 12;

    public HedgeInput {
        Validate.requireDate(valueDate, "value date");
        Validate.requireDate(settlementDate, "settlement date");
        if (!settlementDate.isAfter(valueDate)) {
            throw new IllegalArgumentException(
                    "settlement date " + settlementDate + " must be after the value date");
        }
        deposits = deposits == null ? List.of() : List.copyOf(deposits);
        swaps = swaps == null ? List.of() : List.copyOf(swaps);
        positions = positions == null ? List.of() : List.copyOf(positions);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (limits == null) {
            throw new IllegalArgumentException("risk limits must not be null");
        }
        if (!Double.isFinite(budget) || budget < 0.0) {
            throw new IllegalArgumentException("budget must be finite and non-negative");
        }

        int quoteCount = deposits.size() + swaps.size();
        if (quoteCount < 1 || quoteCount > MAX_QUOTES) {
            throw new IllegalArgumentException(
                    "quote count must be in [1, " + MAX_QUOTES + "], got " + quoteCount);
        }
        if (positions.isEmpty() || positions.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException(
                    "position count must be in [1, " + MAX_POSITIONS + "]");
        }
        if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "candidate count must be in [1, " + MAX_CANDIDATES + "]");
        }

        Set<String> quoteIds = new HashSet<>();
        for (DepositQuote quote : deposits) {
            if (!quoteIds.add(quote.id())) {
                throw new IllegalArgumentException("duplicate quote id: " + quote.id());
            }
        }
        for (SwapQuote quote : swaps) {
            if (!quoteIds.add(quote.id())) {
                throw new IllegalArgumentException("duplicate quote id: " + quote.id());
            }
        }
        for (String quoteId : quoteIds) {
            Double limit = limits.get(quoteId);
            if (limit == null) {
                throw new IllegalArgumentException("missing risk limit for quote " + quoteId);
            }
            if (!Double.isFinite(limit) || limit <= 0.0) {
                throw new IllegalArgumentException(
                        "risk limit for quote " + quoteId + " must be positive and finite, got " + limit);
            }
        }
        for (String quoteId : limits.keySet()) {
            if (!quoteIds.contains(quoteId)) {
                throw new IllegalArgumentException(
                        "risk limit for unknown quote " + quoteId);
            }
        }

        Set<String> contractIds = new HashSet<>(quoteIds);
        for (HedgePosition position : positions) {
            if (!contractIds.add(position.id())) {
                throw new IllegalArgumentException("duplicate contract id: " + position.id());
            }
        }
        for (HedgeCandidate candidate : candidates) {
            if (!contractIds.add(candidate.id())) {
                throw new IllegalArgumentException("duplicate contract id: " + candidate.id());
            }
        }
        limits = Map.copyOf(limits);
    }
}
