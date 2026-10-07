package com.hedge257;

import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.solvers.BrentSolver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sequential curve bootstrapper.
 *
 * Instruments are solved in ascending end-date order; duplicate end dates are
 * rejected. Within the segment being solved, swap payments interpolate against
 * the still-unknown end node (linear in log D).
 */
public final class CurveBootstrapper {
    private static final double BRACKET_LIMIT = 700.0;

    private final CurveConfig config;

    public CurveBootstrapper() {
        this(CurveConfig.defaults());
    }

    public CurveBootstrapper(CurveConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    public BootstrapResult bootstrap(LocalDate valueDate,
                                     List<DepositQuote> deposits,
                                     List<SwapQuote> swaps) {
        Validate.requireDate(valueDate, "value date");
        List<DepositQuote> depositList = deposits == null ? List.of() : List.copyOf(deposits);
        List<SwapQuote> swapList = swaps == null ? List.of() : List.copyOf(swaps);

        List<Instrument> instruments = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (DepositQuote quote : depositList) {
            if (!ids.add(quote.id())) {
                throw new IllegalArgumentException("duplicate instrument id: " + quote.id());
            }
            if (!quote.endDate().isAfter(valueDate)) {
                throw new IllegalArgumentException(
                        "deposit " + quote.id() + " must end after the value date");
            }
            instruments.add(new Instrument(quote.id(), quote.endDate(), quote.rate(), true, null));
        }
        for (SwapQuote quote : swapList) {
            if (!ids.add(quote.id())) {
                throw new IllegalArgumentException("duplicate instrument id: " + quote.id());
            }
            if (!quote.paymentDates().get(0).isAfter(valueDate)) {
                throw new IllegalArgumentException(
                        "swap " + quote.id() + " first payment must be after the value date");
            }
            instruments.add(new Instrument(quote.id(), quote.endDate(), quote.fixedRate(), false,
                    quote.paymentDates()));
        }
        if (instruments.isEmpty()) {
            throw new IllegalArgumentException("at least one quote is required");
        }
        instruments.sort(Comparator.comparing(Instrument::endDate));
        for (int i = 1; i < instruments.size(); i++) {
            if (instruments.get(i).endDate().equals(instruments.get(i - 1).endDate())) {
                throw new IllegalArgumentException(
                        "duplicate instrument end date " + instruments.get(i).endDate()
                                + " for " + instruments.get(i - 1).id()
                                + " and " + instruments.get(i).id());
            }
        }

        List<LocalDate> nodeDates = new ArrayList<>();
        List<Double> nodeDiscounts = new ArrayList<>();
        nodeDates.add(valueDate);
        nodeDiscounts.add(1.0);
        List<InstrumentRepricing> repricings = new ArrayList<>();

        for (Instrument instrument : instruments) {
            if (instrument.deposit()) {
                solveDeposit(valueDate, instrument, nodeDates, nodeDiscounts, repricings);
            } else {
                solveSwap(valueDate, instrument, nodeDates, nodeDiscounts, repricings);
            }
        }

        LocalDate[] dates = nodeDates.toArray(LocalDate[]::new);
        double[] discounts = nodeDiscounts.stream().mapToDouble(Double::doubleValue).toArray();
        DiscountCurve curve = new DiscountCurve(valueDate, dates, discounts);
        return new BootstrapResult(curve, repricings);
    }

    private void solveDeposit(LocalDate valueDate, Instrument instrument,
                              List<LocalDate> nodeDates, List<Double> nodeDiscounts,
                              List<InstrumentRepricing> repricings) {
        double alpha = DayCount.act365f(valueDate, instrument.endDate());
        double denominator = 1.0 + instrument.rate() * alpha;
        if (!Double.isFinite(denominator) || denominator <= 0.0) {
            fail(instrument.id(), "non-positive or invalid deposit denominator " + denominator,
                    repricings);
        }
        double discount = 1.0 / denominator;
        if (!Double.isFinite(discount) || discount <= 0.0) {
            fail(instrument.id(), "deposit produced non-positive discount factor", repricings);
        }
        double reproduced = (1.0 / discount - 1.0) / alpha;
        accept(instrument, discount, reproduced, nodeDates, nodeDiscounts, repricings);
    }

    private void solveSwap(LocalDate valueDate, Instrument instrument,
                           List<LocalDate> nodeDates, List<Double> nodeDiscounts,
                           List<InstrumentRepricing> repricings) {
        int fixedCount = nodeDates.size();
        double[] workTimes = new double[fixedCount + 1];
        double[] workLogD = new double[fixedCount + 1];
        for (int i = 0; i < fixedCount; i++) {
            workTimes[i] = DayCount.act365f(valueDate, nodeDates.get(i));
            workLogD[i] = Math.log(nodeDiscounts.get(i));
        }
        double endTime = DayCount.act365f(valueDate, instrument.endDate());
        workTimes[fixedCount] = endTime;

        List<LocalDate> payments = instrument.paymentDates();
        double[] alphas = new double[payments.size()];
        double[] paymentTimes = new double[payments.size()];
        int split = payments.size();
        for (int i = 0; i < payments.size(); i++) {
            LocalDate start = i == 0 ? valueDate : payments.get(i - 1);
            alphas[i] = DayCount.act365f(start, payments.get(i));
            paymentTimes[i] = DayCount.act365f(valueDate, payments.get(i));
            if (!Double.isFinite(alphas[i]) || alphas[i] <= 0.0) {
                fail(instrument.id(), "invalid swap period length at " + payments.get(i),
                        repricings);
            }
            if (i < split
                    && paymentTimes[i] > workTimes[fixedCount - 1]) {
                split = i;
            }
        }
        final int firstPaymentInNewSegment = split;

        UnivariateFunction objective = x -> {
            workLogD[fixedCount] = x;
            double fixedLeg = 0.0;
            for (int i = 0; i < payments.size(); i++) {
                double logD = i < firstPaymentInNewSegment
                        ? DiscountCurve.interpolateLog(workTimes, workLogD, paymentTimes[i])
                        : interpolateNewSegment(workTimes, workLogD, fixedCount, paymentTimes[i]);
                fixedLeg += alphas[i] * Math.exp(logD);
            }
            return instrument.rate() * fixedLeg - 1.0 + Math.exp(x);
        };

        double root = findRoot(instrument.id(), objective, repricings);
        double discount = Math.exp(root);
        if (!Double.isFinite(discount) || discount <= 0.0) {
            fail(instrument.id(), "swap produced non-positive discount factor", repricings);
        }

        workLogD[fixedCount] = root;
        double annuity = 0.0;
        for (int i = 0; i < payments.size(); i++) {
            double logD = i < firstPaymentInNewSegment
                    ? DiscountCurve.interpolateLog(workTimes, workLogD, paymentTimes[i])
                    : interpolateNewSegment(workTimes, workLogD, fixedCount, paymentTimes[i]);
            annuity += alphas[i] * Math.exp(logD);
        }
        if (!Double.isFinite(annuity) || annuity <= 0.0) {
            fail(instrument.id(), "swap annuity must be positive, got " + annuity, repricings);
        }
        double reproduced = (1.0 - discount) / annuity;
        accept(instrument, discount, reproduced, nodeDates, nodeDiscounts, repricings);
    }

    private double findRoot(String id, UnivariateFunction objective,
                            List<InstrumentRepricing> repricings) {
        double lo = -1.0;
        double hi = 1.0;
        double fLo = objective.value(lo);
        double fHi = objective.value(hi);
        while (Double.isFinite(fLo) && fLo > 0.0 && lo > -BRACKET_LIMIT) {
            lo *= 2.0;
            fLo = objective.value(lo);
        }
        while (Double.isFinite(fHi) && fHi <= 0.0 && hi < BRACKET_LIMIT) {
            hi *= 2.0;
            fHi = objective.value(hi);
        }
        if (!Double.isFinite(fLo) || !Double.isFinite(fHi) || fLo > 0.0 || fHi <= 0.0) {
            fail(id, "could not bracket a swap root", repricings);
        }
        BrentSolver solver = new BrentSolver(config.absoluteTolerance());
        try {
            return solver.solve(config.maxEvaluations(), objective, lo, hi);
        } catch (RuntimeException ex) {
            fail(id, "root solving failed: " + ex.getMessage(), repricings);
            return Double.NaN;
        }
    }

    /**
     * Linear log-D interpolation inside the segment ending at the node being solved.
     */
    private static double interpolateNewSegment(double[] workTimes, double[] workLogD,
                                                int fixedCount, double t) {
        int lo = fixedCount - 1;
        int hi = fixedCount;
        double span = workTimes[hi] - workTimes[lo];
        double weight = (t - workTimes[lo]) / span;
        return workLogD[lo] + weight * (workLogD[hi] - workLogD[lo]);
    }

    private void accept(Instrument instrument, double discount, double reproduced,
                        List<LocalDate> nodeDates, List<Double> nodeDiscounts,
                        List<InstrumentRepricing> repricings) {
        double residual = reproduced - instrument.rate();
        InstrumentRepricing repricing =
                new InstrumentRepricing(instrument.id(), instrument.rate(), reproduced, residual);
        if (!Double.isFinite(reproduced) || Math.abs(residual) > config.reproduceTolerance()) {
            List<InstrumentRepricing> snapshot = new ArrayList<>(repricings);
            snapshot.add(repricing);
            throw new BootstrapException(instrument.id(),
                    "reproduced quote " + reproduced + " misses tolerance "
                            + config.reproduceTolerance() + " (residual " + residual + ")",
                    snapshot);
        }
        nodeDates.add(instrument.endDate());
        nodeDiscounts.add(discount);
        repricings.add(repricing);
    }

    private static void fail(String id, String reason, List<InstrumentRepricing> repricings) {
        throw new BootstrapException(id, reason, repricings);
    }

    private record Instrument(String id, LocalDate endDate, double rate, boolean deposit,
                              List<LocalDate> paymentDates) {
    }
}
