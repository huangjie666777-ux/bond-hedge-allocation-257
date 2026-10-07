package com.hedge257;

import java.util.List;

/**
 * Per-quote DV01 report in quote-bootstrapping order.
 */
public record Dv01Report(List<QuoteDv01> results) {
    public Dv01Report {
        results = List.copyOf(results);
    }
}
