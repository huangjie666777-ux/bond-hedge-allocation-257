package com.hedge257;

import java.time.LocalDate;

/**
 * Recombining binomial short-rate tree on an equal-daily-step grid.
 *
 * At layer i, node j carries the continuously compounded short rate
 * r(i, j) = a_i + (2j - i) * sigma * sqrt(dt) with risk-neutral up/down
 * probabilities of one half. The layer-0 node is the settlement date. Each
 * level a_i (i from 1; a_0 calibrates the first step) is solved analytically so
 * that the sum of forward state prices reaching the next grid date reproduces
 * D(next grid date) / D(settlement). Negative rates and sigma = 0 are allowed.
 */
public final class ShortRateTree {
    public static final int MAX_STEPS = 300;

    private final LocalDate settlementDate;
    private final LocalDate[] gridDates;
    private final double dt;
    private final double sigma;
    private final double sqrtDt;
    private final double[][] levels;
    private final double[] residuals;

    private ShortRateTree(LocalDate settlementDate, LocalDate maturityDate, int stepDays,
                          double sigma, DiscountCurve curve) {
        this.settlementDate = settlementDate;
        this.sigma = sigma;
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(settlementDate, maturityDate);
        if (stepDays <= 0) {
            throw new IllegalArgumentException("step days must be positive");
        }
        if (totalDays <= 0 || totalDays > (long) MAX_STEPS * stepDays
                || totalDays % stepDays != 0) {
            throw new IllegalArgumentException(
                    "settlement-to-maturity days " + totalDays + " must be a positive multiple"
                            + " of the " + stepDays + "-day step with at most " + MAX_STEPS
                            + " steps");
        }
        int steps = Math.toIntExact(totalDays / stepDays);
        this.dt = stepDays / DayCount.DAYS_PER_YEAR;
        this.sqrtDt = Math.sqrt(dt);

        this.gridDates = new LocalDate[steps + 1];
        for (int i = 0; i <= steps; i++) {
            gridDates[i] = settlementDate.plusDays((long) i * stepDays);
        }
        LocalDate curveEnd = curve.nodeDate(curve.nodeCount() - 1);
        if (maturityDate.isAfter(curveEnd)) {
            throw new IllegalArgumentException(
                    "tree maturity " + maturityDate + " is after the last curve date "
                            + curveEnd);
        }

        double settlementDf = curve.discountFactor(settlementDate);
        double spread = sigma * sqrtDt;
        this.levels = new double[steps + 1][];
        this.residuals = new double[steps];
        double[] statePrices = {1.0};

        for (int i = 0; i < steps; i++) {
            double target = curve.discountFactor(gridDates[i + 1]) / settlementDf;
            double mass = 0.0;
            for (int j = 0; j <= i; j++) {
                double drift = (2 * j - i) * spread;
                mass += statePrices[j] * Math.exp(-drift * dt);
            }
            if (!Double.isFinite(mass) || mass <= 0.0 || target <= 0.0
                    || !Double.isFinite(target)) {
                throw new TreeCalibrationException(
                        "invalid state-price mass or target discount at layer " + (i + 1));
            }
            double level = Math.log(mass / target) / dt;
            if (!Double.isFinite(level)) {
                throw new TreeCalibrationException(
                        "non-finite calibrated short rate at layer " + (i + 1));
            }
            this.levels[i] = new double[i + 1];
            for (int j = 0; j <= i; j++) {
                this.levels[i][j] = level + (2 * j - i) * spread;
            }
            double reproduced = mass * Math.exp(-level * dt);
            residuals[i] = reproduced - target;

            double[] nextStatePrices = new double[i + 2];
            for (int j = 0; j <= i; j++) {
                double bondFactor = Math.exp(-this.levels[i][j] * dt);
                double carried = statePrices[j] * bondFactor * 0.5;
                nextStatePrices[j] += carried;
                nextStatePrices[j + 1] += carried;
            }
            for (double value : nextStatePrices) {
                if (!Double.isFinite(value) || value < 0.0) {
                    throw new TreeCalibrationException(
                            "non-finite or negative state price at layer " + (i + 1));
                }
            }
            statePrices = nextStatePrices;
        }
        this.levels[steps] = new double[steps + 1];
    }

    /**
     * Validates inputs and builds/calibrates the tree. Every remaining bond
     * coupon date is checked separately by the callable pricer against the grid.
     */
    public static ShortRateTree calibrate(Bond bond, LocalDate settlementDate,
                                          DiscountCurve curve, double sigma, int stepDays) {
        if (bond == null) {
            throw new IllegalArgumentException("bond must not be null");
        }
        Validate.requireDate(settlementDate, "settlement date");
        if (curve == null) {
            throw new IllegalArgumentException("curve must not be null");
        }
        if (!Double.isFinite(sigma) || sigma < 0.0) {
            throw new IllegalArgumentException("short-rate volatility must be finite and non-negative");
        }
        return new ShortRateTree(settlementDate, bond.maturityDate(), stepDays, sigma, curve);
    }

    public int steps() {
        return gridDates.length - 1;
    }

    public LocalDate settlementDate() {
        return settlementDate;
    }

    public LocalDate gridDate(int layer) {
        return gridDates[layer];
    }

    public LocalDate maturityDate() {
        return gridDates[gridDates.length - 1];
    }

    public double dt() {
        return dt;
    }

    public double sigma() {
        return sigma;
    }

    public double shortRate(int layer, int node) {
        if (layer < 0 || layer >= gridDates.length - 1 || node < 0 || node > layer) {
            throw new IllegalArgumentException("tree node (" + layer + ", " + node + ") is invalid");
        }
        return levels[layer][node];
    }

    public double[] calibrationResiduals() {
        return residuals.clone();
    }
}
