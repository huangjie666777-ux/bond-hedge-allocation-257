package com.hedge257;

import java.util.List;

/**
 * Successful curve bootstrap output.
 */
public record BootstrapResult(DiscountCurve curve, List<InstrumentRepricing> repricings) {
    public BootstrapResult {
        repricings = List.copyOf(repricings);
    }
}
