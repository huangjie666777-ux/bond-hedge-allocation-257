package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds curves at base and for every independently shifted quote. Callable
 * holdings recalibrate and exercise their tree on every rebuilt curve.
 */
final class PortfolioRiskEngine {
    private static final String BASE = "BASE";

    private final CurveBootstrapper bootstrapper;
    private final BondPricer bondPricer;
    private final CallableBondPricer callablePricer;
    private final double shift;
    private HedgeRequest request;
    private final List<RiskFailure> failures = new ArrayList<>();

    PortfolioRiskEngine(CurveConfig config, double shift) {
        if (!(shift > 0.0) || !Double.isFinite(shift)) {
            throw new IllegalArgumentException("shift must be positive and finite");
        }
        this.bootstrapper = new CurveBootstrapper(config == null ? CurveConfig.defaults() : config);
        this.bondPricer = new BondPricer();
        this.callablePricer = new CallableBondPricer();
        this.shift = shift;
    }

    HedgeRiskModel build(HedgeRequest hedgeRequest) {
        this.request = hedgeRequest;
        this.failures.clear();
        List<String> quoteIds = request.quoteIds();
        int buckets = quoteIds.size();

        DiscountCurve baseCurve = curve(BASE, request.deposits(), request.swaps());
        double[] candidateDirty = new double[request.candidates().size()];
        if (baseCurve != null) {
            for (Holding holding : request.holdings()) {
                baseDirty(holding, request.settlementDate(), baseCurve);
            }
            for (int i = 0; i < request.candidates().size(); i++) {
                Candidate candidate = request.candidates().get(i);
                candidateDirty[i] = plainBaseDirty(candidate.id(), candidate.bond(),
                        request.settlementDate(), baseCurve);
            }
        }

        double[] exposure = new double[buckets];
        double[][] hedgeRisk = new double[buckets][request.candidates().size()];
        for (int bucket = 0; bucket < buckets; bucket++) {
            String quoteId = quoteIds.get(bucket);
            boolean depositBucket = bucket < request.deposits().size();
            CurveMarket down = shifted(bucket, -shift, depositBucket);
            CurveMarket up = shifted(bucket, shift, depositBucket);
            DiscountCurve downCurve = curve(quoteId, down.deposits(), down.swaps());
            DiscountCurve upCurve = curve(quoteId, up.deposits(), up.swaps());
            if (downCurve == null || upCurve == null) {
                continue;
            }

            for (int h = 0; h < request.holdings().size(); h++) {
                Holding holding = request.holdings().get(h);
                double downPrice = clean(quoteId, holding, request.settlementDate(), downCurve);
                double upPrice = clean(quoteId, holding, request.settlementDate(), upCurve);
                double dv01 = dv01(quoteId, holding.id(), downPrice, upPrice);
                if (Double.isFinite(dv01)) {
                    exposure[bucket] += holding.quantity() * dv01;
                }
            }
            for (int c = 0; c < request.candidates().size(); c++) {
                Candidate candidate = request.candidates().get(c);
                double downPrice = plainClean(quoteId, candidate.id(), candidate.bond(),
                        request.settlementDate(), downCurve);
                double upPrice = plainClean(quoteId, candidate.id(), candidate.bond(),
                        request.settlementDate(), upCurve);
                double dv01 = dv01(quoteId, candidate.id(), downPrice, upPrice);
                if (Double.isFinite(dv01)) {
                    hedgeRisk[bucket][c] = dv01;
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new HedgeValuationException(List.copyOf(failures));
        }
        for (double value : exposure) {
            requireFinite(value);
        }
        for (double[] row : hedgeRisk) {
            for (double value : row) {
                requireFinite(value);
            }
        }
        for (double price : candidateDirty) {
            requireFinite(price);
            if (price <= 0.0) {
                throw new HedgeNumericalException("non-positive candidate dirty price", null);
            }
        }
        return new HedgeRiskModel(quoteIds, exposure, hedgeRisk, candidateDirty);
    }

    private record CurveMarket(List<DepositQuote> deposits, List<SwapQuote> swaps) { }

    private CurveMarket shifted(int bucket, double amount, boolean depositBucket) {
        if (depositBucket) {
            DepositQuote original = request.deposits().get(bucket);
            DepositQuote replacement = new DepositQuote(original.id(), original.endDate(),
                    original.rate() + amount);
            List<DepositQuote> deposits = new ArrayList<>(request.deposits());
            deposits.set(bucket, replacement);
            return new CurveMarket(deposits, request.swaps());
        }
        int swapIndex = bucket - request.deposits().size();
        SwapQuote original = request.swaps().get(swapIndex);
        SwapQuote replacement = new SwapQuote(original.id(), original.paymentDates(),
                original.fixedRate() + amount);
        List<SwapQuote> swaps = new ArrayList<>(request.swaps());
        swaps.set(swapIndex, replacement);
        return new CurveMarket(request.deposits(), swaps);
    }

    private DiscountCurve curve(String quoteId, List<DepositQuote> deposits,
                                List<SwapQuote> swaps) {
        try {
            return bootstrapper.bootstrap(request.valueDate(), deposits, swaps).curve();
        } catch (RuntimeException ex) {
            failures.add(new RiskFailure(quoteId, "CURVE", ex.getMessage()));
            return null;
        }
    }

    private double baseDirty(Holding holding, LocalDate settlementDate, DiscountCurve curve) {
        if (holding.callable()) {
            try {
                return callablePrice(holding, settlementDate, curve).dirtyPrice();
            } catch (RuntimeException ex) {
                failures.add(new RiskFailure(BASE, holding.id(), ex.getMessage()));
                return Double.NaN;
            }
        }
        return plainBaseDirty(holding.id(), holding.bond(), settlementDate, curve);
    }

    private double plainBaseDirty(String instrumentId, Bond bond, LocalDate settlementDate,
                                  DiscountCurve curve) {
        try {
            return bondPricer.price(bond, settlementDate, curve).dirtyPrice();
        } catch (RuntimeException ex) {
            failures.add(new RiskFailure(BASE, instrumentId, ex.getMessage()));
            return Double.NaN;
        }
    }

    private double clean(String quoteId, Holding holding, LocalDate settlementDate,
                         DiscountCurve curve) {
        if (holding.callable()) {
            try {
                return callablePrice(holding, settlementDate, curve).cleanPrice();
            } catch (RuntimeException ex) {
                failures.add(new RiskFailure(quoteId, holding.id(), ex.getMessage()));
                return Double.NaN;
            }
        }
        return plainClean(quoteId, holding.id(), holding.bond(), settlementDate, curve);
    }

    private CallableBondPrice callablePrice(Holding holding, LocalDate settlementDate,
                                            DiscountCurve curve) {
        return callablePricer.price(holding.bond(), settlementDate, curve,
                holding.sigma(), holding.stepDays(), holding.callSchedule());
    }

    private double plainClean(String quoteId, String instrumentId, Bond bond,
                              LocalDate settlementDate, DiscountCurve curve) {
        try {
            return bondPricer.price(bond, settlementDate, curve).cleanPrice();
        } catch (RuntimeException ex) {
            failures.add(new RiskFailure(quoteId, instrumentId, ex.getMessage()));
            return Double.NaN;
        }
    }

    private double dv01(String quoteId, String instrumentId, double down, double up) {
        if (!Double.isFinite(down) || !Double.isFinite(up)) {
            return Double.NaN;
        }
        double dv01 = (down - up) / 2.0;
        if (!Double.isFinite(dv01)) {
            failures.add(new RiskFailure(quoteId, instrumentId, "non-finite shifted DV01"));
            return Double.NaN;
        }
        return dv01;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new HedgeNumericalException("non-finite risk model value", null);
        }
    }
}
