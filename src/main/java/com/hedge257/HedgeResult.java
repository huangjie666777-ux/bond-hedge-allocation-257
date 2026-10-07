package com.hedge257;

import java.util.List;

/**
 * Delivered hedge solution, independently recomputed from the solved
 * quantities. All DV01 values are net-price DV01 in quote-rate buckets
 * (deposits followed by swaps, input order).
 *
 * @param candidateIds      candidate ids in candidate input order
 * @param quantities        signed solved quantities (positive buy, negative sell)
 * @param unitDirtyPrices   original full (dirty) price per 100 used for turnover
 * @param dv01Before        signed portfolio exposure b per bucket
 * @param riskMatrix        candidate DV01 matrix H, H[bucket][candidate]
 * @param dv01After         residual b + H x per bucket
 * @param worstResidual     max bucket |residual| / L
 * @param turnover          sum of unit dirty price * |x|
 * @param budget            input turnover budget B
 */
public record HedgeResult(List<String> candidateIds, double[] quantities,
                          double[] unitDirtyPrices,
                          List<Double> dv01Before, double[][] riskMatrix,
                          List<Double> dv01After,
                          double worstResidual, double turnover, double budget) {
    public HedgeResult {
        candidateIds = List.copyOf(candidateIds);
        quantities = quantities.clone();
        unitDirtyPrices = unitDirtyPrices.clone();
        dv01Before = List.copyOf(dv01Before);
        riskMatrix = deepCopy(riskMatrix);
        dv01After = List.copyOf(dv01After);
    }

    private static double[][] deepCopy(double[][] matrix) {
        double[][] copy = new double[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    public int bucketCount() {
        return dv01Before.size();
    }

    public int candidateCount() {
        return candidateIds.size();
    }
}
