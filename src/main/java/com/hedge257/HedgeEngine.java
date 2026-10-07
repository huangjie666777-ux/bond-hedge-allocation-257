package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Budgeted bond-portfolio hedge over quote-rate DV01 buckets.
 * <p>
 * For every quote bucket the full curve is independently rebuilt with the
 * quote moved down and up by 1 bp; each contract is then revalued on both
 * rebuilt curves and its per-hundred DV01 is
 * {@code (clean(down) - clean(up)) / 2}. Callable holdings recalibrate the
 * short-rate tree and re-run the issuer exercise decision on both curves; no
 * exercise policy is frozen and only the original curve/pricing chain is used.
 * Signed holdings aggregate into the bucket exposure b and plain candidates
 * form the risk matrix H. Trades minimize max bucket |b + H x| / L under
 * candidate trade bounds and the turnover budget
 * sum of original per-hundred full price times |x|.
 */
public final class HedgeEngine {
    private static final double CONSTRAINT_TOLERANCE = 1.0e-8;

    private final CurveBootstrapper bootstrapper;
    private final BondPricer bondPricer;
    private final CallableBondPricer callablePricer;
    private final double shift;

    public HedgeEngine() {
        this(CurveConfig.defaults(), RiskEngine.ONE_BP);
    }

    public HedgeEngine(CurveConfig config) {
        this(config, RiskEngine.ONE_BP);
    }

    public HedgeEngine(CurveConfig config, double shift) {
        if (!(shift > 0.0) || !Double.isFinite(shift)) {
            throw new IllegalArgumentException("shift must be positive and finite");
        }
        this.bootstrapper = new CurveBootstrapper(
                config == null ? CurveConfig.defaults() : config);
        this.bondPricer = new BondPricer();
        this.callablePricer = new CallableBondPricer();
        this.shift = shift;
    }

    public HedgeResult hedge(HedgeInput input) {
        if (input == null) {
            throw new IllegalArgumentException("hedge input must not be null");
        }
        LocalDate valueDate = input.valueDate();
        LocalDate settlement = input.settlementDate();
        List<DepositQuote> deposits = input.deposits();
        List<SwapQuote> swaps = input.swaps();

        DiscountCurve baseCurve;
        try {
            baseCurve = bootstrapper.bootstrap(valueDate, deposits, swaps).curve();
        } catch (RuntimeException ex) {
            throw new HedgeFailureException("BASE-CURVE", "-",
                    "base curve bootstrap failed: " + ex.getMessage());
        }

        List<HedgePosition> positions = input.positions();
        List<HedgeCandidate> candidates = input.candidates();

        for (HedgePosition position : positions) {
            baseFullPrice(position, settlement, baseCurve);
        }
        double[] candidateDirty = new double[candidates.size()];
        for (int j = 0; j < candidates.size(); j++) {
            candidateDirty[j] = baseFullPrice(candidates.get(j), settlement, baseCurve);
        }

        int buckets = deposits.size() + swaps.size();
        List<String> bucketIds = new ArrayList<>(buckets);
        for (DepositQuote quote : deposits) {
            bucketIds.add(quote.id());
        }
        for (SwapQuote quote : swaps) {
            bucketIds.add(quote.id());
        }

        double[] exposure = new double[buckets];
        double[][] risk = new double[buckets][candidates.size()];
        for (int bucket = 0; bucket < buckets; bucket++) {
            String quoteId = bucketIds.get(bucket);
            DiscountCurve downCurve = rebuild(valueDate, deposits, swaps, bucket, -shift,
                    quoteId);
            DiscountCurve upCurve = rebuild(valueDate, deposits, swaps, bucket, shift,
                    quoteId);

            for (int i = 0; i < positions.size(); i++) {
                HedgePosition position = positions.get(i);
                double down = cleanPrice(position, settlement, downCurve, quoteId);
                double up = cleanPrice(position, settlement, upCurve, quoteId);
                double dv01 = dv01(quoteId, position.id(), down, up);
                exposure[bucket] += position.quantity() * dv01;
            }
            for (int j = 0; j < candidates.size(); j++) {
                HedgeCandidate candidate = candidates.get(j);
                double down = cleanPrice(candidate, settlement, downCurve, quoteId);
                double up = cleanPrice(candidate, settlement, upCurve, quoteId);
                risk[bucket][j] = dv01(quoteId, candidate.id(), down, up);
            }
        }

        double[] limits = new double[buckets];
        double[] minQuantity = new double[candidates.size()];
        double[] maxQuantity = new double[candidates.size()];
        for (int k = 0; k < buckets; k++) {
            limits[k] = input.limits().get(bucketIds.get(k));
        }
        for (int j = 0; j < candidates.size(); j++) {
            minQuantity[j] = candidates.get(j).minQuantity();
            maxQuantity[j] = candidates.get(j).maxQuantity();
        }

        HedgeOptimizer.Solution solution = HedgeOptimizer.solve(exposure, risk, limits,
                minQuantity, maxQuantity, candidateDirty, input.budget());
        return assemble(input, bucketIds, candidateDirty, exposure, risk, limits,
                solution.quantities(), solution.worstResidual());
    }

    private DiscountCurve rebuild(LocalDate valueDate, List<DepositQuote> deposits,
                                  List<SwapQuote> swaps, int bucket, double delta,
                                  String quoteId) {
        int depositCount = deposits.size();
        List<DepositQuote> bumpedDeposits = deposits;
        List<SwapQuote> bumpedSwaps = swaps;
        if (bucket < depositCount) {
            DepositQuote quote = deposits.get(bucket);
            bumpedDeposits = replaceQuote(deposits, bucket,
                    new DepositQuote(quote.id(), quote.endDate(), quote.rate() + delta));
        } else {
            SwapQuote quote = swaps.get(bucket - depositCount);
            bumpedSwaps = replaceQuote(swaps, bucket - depositCount,
                    new SwapQuote(quote.id(), quote.paymentDates(), quote.fixedRate() + delta));
        }
        try {
            return bootstrapper.bootstrap(valueDate, bumpedDeposits, bumpedSwaps).curve();
        } catch (RuntimeException ex) {
            throw new HedgeFailureException(quoteId, "-",
                    (delta < 0.0 ? "down" : "up") + "-shift curve bootstrap failed: "
                            + ex.getMessage());
        }
    }

    private double dv01(String quoteId, String contractId, double cleanDown, double cleanUp) {
        if (!Double.isFinite(cleanDown) || !Double.isFinite(cleanUp)) {
            throw new HedgeFailureException(quoteId, contractId, "non-finite shifted clean price");
        }
        double dv01 = (cleanDown - cleanUp) / 2.0;
        if (!Double.isFinite(dv01)) {
            throw new HedgeFailureException(quoteId, contractId, "non-finite DV01");
        }
        return dv01;
    }

    private double cleanPrice(HedgePosition position, LocalDate settlement,
                              DiscountCurve curve, String quoteId) {
        try {
            if (position.callable()) {
                return callablePricer.price(position.bond(), settlement, curve,
                        position.sigma(), position.stepDays(), position.calls()).cleanPrice();
            }
            return bondPricer.price(position.bond(), settlement, curve).cleanPrice();
        } catch (RuntimeException ex) {
            throw new HedgeFailureException(quoteId, position.id(),
                    "holding revaluation failed: " + ex.getMessage());
        }
    }

    private double cleanPrice(HedgeCandidate candidate, LocalDate settlement,
                              DiscountCurve curve, String quoteId) {
        try {
            return bondPricer.price(candidate.bond(), settlement, curve).cleanPrice();
        } catch (RuntimeException ex) {
            throw new HedgeFailureException(quoteId, candidate.id(),
                    "candidate revaluation failed: " + ex.getMessage());
        }
    }

    private double fullPrice(HedgePosition position, LocalDate settlement, DiscountCurve curve) {
        if (position.callable()) {
            return callablePricer.price(position.bond(), settlement, curve,
                    position.sigma(), position.stepDays(), position.calls()).dirtyPrice();
        }
        return bondPricer.price(position.bond(), settlement, curve).dirtyPrice();
    }

    private double fullPrice(HedgeCandidate candidate, LocalDate settlement, DiscountCurve curve) {
        return bondPricer.price(candidate.bond(), settlement, curve).dirtyPrice();
    }

    private double baseFullPrice(HedgePosition position, LocalDate settlement,
                                 DiscountCurve curve) {
        try {
            return fullPrice(position, settlement, curve);
        } catch (RuntimeException ex) {
            throw new HedgeFailureException("BASE-CURVE", position.id(),
                    "base-curve holding valuation failed: " + ex.getMessage());
        }
    }

    private double baseFullPrice(HedgeCandidate candidate, LocalDate settlement,
                                 DiscountCurve curve) {
        try {
            return fullPrice(candidate, settlement, curve);
        } catch (RuntimeException ex) {
            throw new HedgeFailureException("BASE-CURVE", candidate.id(),
                    "base-curve candidate valuation failed: " + ex.getMessage());
        }
    }

    private HedgeResult assemble(HedgeInput input, List<String> bucketIds,
                                 double[] candidateDirty, double[] exposure,
                                 double[][] risk, double[] limits,
                                 double[] quantities, double solvedWorst) {
        int candidates = quantities.length;
        double turnover = 0.0;
        double worst = 0.0;
        double[] residual = new double[bucketIds.size()];
        for (int j = 0; j < candidates; j++) {
            HedgeCandidate candidate = input.candidates().get(j);
            double x = quantities[j];
            double slack = CONSTRAINT_TOLERANCE * Math.max(1.0, Math.abs(x));
            if (x < candidate.minQuantity() - slack || x > candidate.maxQuantity() + slack) {
                throw new HedgeNumericalException("solved trade for candidate " + candidate.id()
                        + " violates its bounds: x=" + x + ", bounds=["
                        + candidate.minQuantity() + ", " + candidate.maxQuantity() + "]");
            }
            quantities[j] = clamp(x, candidate.minQuantity(), candidate.maxQuantity());
            turnover += candidateDirty[j] * Math.abs(quantities[j]);
        }
        double budgetSlack = CONSTRAINT_TOLERANCE * Math.max(1.0, input.budget());
        if (turnover > input.budget() + budgetSlack) {
            throw new HedgeNumericalException(
                    "solved trades use turnover " + turnover + " above budget " + input.budget());
        }
        turnover = Math.min(turnover, input.budget() + budgetSlack);

        List<Double> before = new ArrayList<>(bucketIds.size());
        List<Double> after = new ArrayList<>(bucketIds.size());
        List<String> candidateIds = new ArrayList<>(input.candidates().size());
        for (HedgeCandidate candidate : input.candidates()) {
            candidateIds.add(candidate.id());
        }
        for (int k = 0; k < bucketIds.size(); k++) {
            before.add(exposure[k]);
            double value = exposure[k];
            for (int j = 0; j < candidates; j++) {
                value += risk[k][j] * quantities[j];
            }
            residual[k] = value;
            after.add(value);
            worst = Math.max(worst, Math.abs(value) / limits[k]);
        }
        if (worst > solvedWorst + 1.0e-6 * Math.max(1.0, solvedWorst) + 1.0e-9) {
            throw new HedgeNumericalException(
                    "recomputed worst residual " + worst + " disagrees with solver " + solvedWorst);
        }

        return new HedgeResult(candidateIds, quantities, candidateDirty, before, risk,
                after, worst, turnover, input.budget());
    }

    private static double clamp(double value, double low, double high) {
        return Math.min(high, Math.max(low, value));
    }

    private static <T> List<T> replaceQuote(List<T> list, int index, T replacement) {
        List<T> copy = new ArrayList<>(list);
        copy.set(index, replacement);
        return List.copyOf(copy);
    }
}
