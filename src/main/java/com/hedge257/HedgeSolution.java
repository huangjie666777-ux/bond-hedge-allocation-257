package com.hedge257;

import java.util.List;

/**
 * Verified hedge result. Quantities preserve candidate input order.
 */
public record HedgeSolution(List<String> candidateIds, List<String> quoteIds,
                            double[] quantities, double[] beforeDv01,
                            double[] afterDv01, double worstStandardizedResidual,
                            double turnover) {
    public HedgeSolution {
        candidateIds = List.copyOf(candidateIds);
        quoteIds = List.copyOf(quoteIds);
        quantities = quantities.clone();
        beforeDv01 = beforeDv01.clone();
        afterDv01 = afterDv01.clone();
    }
}
