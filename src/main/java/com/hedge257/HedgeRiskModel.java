package com.hedge257;

import java.util.List;

/**
 * Finite matrix inputs to the hedge optimizer.
 */
final class HedgeRiskModel {
    private final List<String> quoteIds;
    private final double[] portfolioExposure;
    private final double[][] candidateRisk;
    private final double[] candidateDirtyPrices;

    HedgeRiskModel(List<String> quoteIds, double[] portfolioExposure,
                   double[][] candidateRisk, double[] candidateDirtyPrices) {
        this.quoteIds = List.copyOf(quoteIds);
        this.portfolioExposure = portfolioExposure.clone();
        this.candidateRisk = new double[candidateRisk.length][];
        for (int i = 0; i < candidateRisk.length; i++) {
            this.candidateRisk[i] = candidateRisk[i].clone();
        }
        this.candidateDirtyPrices = candidateDirtyPrices.clone();
    }

    List<String> quoteIds() {
        return quoteIds;
    }

    double[] portfolioExposure() {
        return portfolioExposure.clone();
    }

    double[][] candidateRisk() {
        double[][] copy = new double[candidateRisk.length][];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = candidateRisk[i].clone();
        }
        return copy;
    }

    double[] candidateDirtyPrices() {
        return candidateDirtyPrices.clone();
    }
}
