package com.hedge257;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * Discount-factor curve anchored at D(valueDate) = 1.
 * Nodes are sorted by time and log D is linearly interpolated between
 * adjacent nodes. Queries outside [valueDate, last node date] are rejected.
 */
public final class DiscountCurve {
    private final LocalDate valueDate;
    private final LocalDate[] dates;
    private final double[] times;
    private final double[] logDiscounts;

    public DiscountCurve(LocalDate valueDate, LocalDate[] nodeDates, double[] discounts) {
        Validate.requireDate(valueDate, "value date");
        if (nodeDates == null || discounts == null
                || nodeDates.length != discounts.length || nodeDates.length < 1) {
            throw new IllegalArgumentException("curve needs aligned, non-empty node arrays");
        }
        int n = nodeDates.length;
        for (int i = 0; i < n; i++) {
            Validate.requireDate(nodeDates[i], "node date");
            if (!Double.isFinite(discounts[i]) || discounts[i] <= 0.0) {
                throw new IllegalArgumentException(
                        "discount factor must be positive and finite at " + nodeDates[i]);
            }
        }
        if (!nodeDates[0].equals(valueDate) || discounts[0] != 1.0) {
            throw new IllegalArgumentException("first node must be (valueDate, 1.0)");
        }
        for (int i = 1; i < n; i++) {
            if (!nodeDates[i].isAfter(nodeDates[i - 1])) {
                throw new IllegalArgumentException("node dates must be strictly increasing");
            }
        }
        this.valueDate = valueDate;
        this.dates = Arrays.copyOf(nodeDates, n);
        this.times = new double[n];
        this.logDiscounts = new double[n];
        for (int i = 0; i < n; i++) {
            this.times[i] = DayCount.act365f(valueDate, nodeDates[i]);
            this.logDiscounts[i] = Math.log(discounts[i]);
        }
    }

    public LocalDate valueDate() {
        return valueDate;
    }

    public int nodeCount() {
        return dates.length;
    }

    public LocalDate nodeDate(int index) {
        return dates[index];
    }

    public double discountAtNode(int index) {
        return Math.exp(logDiscounts[index]);
    }

    /**
     * Year fraction from the valuation date using ACT/365F.
     */
    public double time(LocalDate date) {
        Validate.requireDate(date, "query date");
        return DayCount.act365f(valueDate, date);
    }

    /**
     * Discount factor at the given date. Queries before the valuation date or
     * after the last curve node are rejected.
     */
    public double discountFactor(LocalDate date) {
        double t = time(date);
        if (t < -1.0e-12 || t > times[times.length - 1] + 1.0e-12) {
            throw new IllegalArgumentException(
                    "query date " + date + " is outside the curve range");
        }
        return Math.exp(logDiscount(t));
    }

    /**
     * Interpolated log discount at a time expressed in ACT/365F years from the
     * value date. Used both for finished curves and while bootstrapping a new
     * segment whose terminal value is still unknown.
     *
     * @param times2          known node times, strictly increasing, first one zero
     * @param logDiscounts2   log D at known nodes; the last entry is mutated by
     *                        the caller while solving for the next node
     * @param t               query time inside [0, last known time]
     */
    static double interpolateLog(double[] times2, double[] logDiscounts2, double t) {
        int last = times2.length - 1;
        if (t <= 0.0) {
            return 0.0;
        }
        if (t >= times2[last]) {
            return logDiscounts2[last];
        }
        int lo = 0;
        int hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times2[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = times2[hi] - times2[lo];
        if (span == 0.0) {
            return logDiscounts2[lo];
        }
        double weight = (t - times2[lo]) / span;
        return logDiscounts2[lo] + weight * (logDiscounts2[hi] - logDiscounts2[lo]);
    }

    private double logDiscount(double t) {
        return interpolateLog(times, logDiscounts, t);
    }
}
