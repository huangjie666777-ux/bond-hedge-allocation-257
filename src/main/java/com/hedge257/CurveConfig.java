package com.hedge257;

/**
 * Configurable root-finding budget and quote reproduction tolerance.
 *
 * @param absoluteTolerance Brent solver absolute tolerance on log-discount (must be positive)
 * @param maxEvaluations    maximum objective evaluations per instrument (must be positive)
 * @param reproduceTolerance max |reproduced quote - market quote| accepted per instrument
 */
public record CurveConfig(double absoluteTolerance,
                          int maxEvaluations,
                          double reproduceTolerance) {
    public CurveConfig {
        if (!(absoluteTolerance > 0.0) || !Double.isFinite(absoluteTolerance)) {
            throw new IllegalArgumentException("absoluteTolerance must be positive and finite");
        }
        if (maxEvaluations <= 0) {
            throw new IllegalArgumentException("maxEvaluations must be positive");
        }
        if (!(reproduceTolerance > 0.0) || !Double.isFinite(reproduceTolerance)) {
            throw new IllegalArgumentException("reproduceTolerance must be positive and finite");
        }
    }

    public static CurveConfig defaults() {
        return new CurveConfig(1.0e-12, 200, 1.0e-10);
    }
}
