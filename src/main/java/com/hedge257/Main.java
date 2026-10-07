package com.hedge257;

import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Valuation and risk example: one deposit, three annual swaps and a 3y bond.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        runValuationExample();
        runHedgeExample();
    }

    private static void runValuationExample() {
        LocalDate valueDate = LocalDate.of(2026, 6, 28);
        CurveConfig config = CurveConfig.defaults();
        CurveBootstrapper bootstrapper = new CurveBootstrapper(config);

        List<DepositQuote> deposits = List.of(
                new DepositQuote("DEP-6M", LocalDate.of(2026, 12, 28), 0.0250));
        List<SwapQuote> swaps = List.of(
                new SwapQuote("SWAP-1Y", List.of(LocalDate.of(2027, 6, 28)), 0.0300),
                new SwapQuote("SWAP-2Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)), 0.0325),
                new SwapQuote("SWAP-3Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                                LocalDate.of(2029, 6, 28)), 0.0350));

        BootstrapResult result = bootstrapper.bootstrap(valueDate, deposits, swaps);
        DiscountCurve curve = result.curve();

        System.out.println("hedge257 valuation and risk example");
        System.out.println("value date: " + valueDate);
        System.out.println();
        System.out.println("bootstrapped nodes (date, D):");
        for (int i = 0; i < curve.nodeCount(); i++) {
            System.out.printf("  %s  D = %.10f%n", curve.nodeDate(i),
                    curve.discountAtNode(i));
        }
        System.out.println();
        System.out.println("quote reproduction:");
        for (InstrumentRepricing r : result.repricings()) {
            System.out.printf("  %-8s market=%.6f reproduced=%.10f residual=%.2e%n",
                    r.id(), r.marketQuote(), r.reproducedQuote(), r.residual());
        }

        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)),
                100.0, 0.0400);
        LocalDate settlementDate = LocalDate.of(2026, 9, 15);
        BondPricer pricer = new BondPricer();
        BondPrice price = pricer.price(bond, settlementDate, curve);

        System.out.println();
        System.out.println("bond settlement: " + settlementDate);
        System.out.printf("dirty price per 100: %.6f%n", price.dirtyPrice());
        System.out.printf("accrued per 100:    %.6f%n", price.accruedInterest());
        System.out.printf("clean price per 100: %.6f%n", price.cleanPrice());
        System.out.println("surviving cash flows:");
        for (BondCashflow flow : price.cashflows()) {
            System.out.printf("  %s alpha=%.6f coupon=%.4f principal=%.2f%n",
                    flow.paymentDate(), flow.yearFraction(), flow.coupon(), flow.principal());
        }

        RiskEngine risk = new RiskEngine(config);
        Dv01Report report = risk.dv01(valueDate, deposits, swaps, bond, settlementDate);
        System.out.println();
        System.out.println("net-price DV01 per quote (bump +/-1 bp, full rebuild):");
        for (QuoteDv01 dv : report.results()) {
            if (dv.failed()) {
                System.out.printf("  %-8s FAILED: %s%n", dv.id(), dv.failureReason());
            } else {
                System.out.printf("  %-8s %.6f%n", dv.id(), dv.dv01());
            }
        }

        LocalDate callableSettlement = LocalDate.of(2026, 10, 6);
        Bond callableBond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2027, 1, 14),
                        LocalDate.of(2027, 4, 24), LocalDate.of(2027, 8, 2)),
                100.0, 0.0600);
        int stepDays = 100;
        double sigma = 0.0120;
        List<CallPrice> callSchedule = List.of(
                new CallPrice(LocalDate.of(2027, 1, 14), 101.50),
                new CallPrice(LocalDate.of(2027, 4, 24), 100.75));

        BondPrice comparison = new BondPricer()
                .price(callableBond, callableSettlement, curve);
        CallableBondPrice callable = new CallableBondPricer().price(
                callableBond, callableSettlement, curve, sigma, stepDays, callSchedule);

        System.out.println();
        System.out.println("issuer-callable bond on the same bootstrapped curve");
        System.out.println("settlement: " + callableSettlement
                + ", equal step: " + stepDays + " days");
        System.out.printf("annualized short-rate volatility sigma: %.4f%n", sigma);
        System.out.printf("non-callable curve dirty price per 100: %.6f%n",
                comparison.dirtyPrice());
        System.out.printf("non-callable tree dirty price per 100:  %.6f%n",
                callable.optionFreePrice());
        System.out.printf("callable dirty price per 100:           %.6f%n", callable.dirtyPrice());
        System.out.printf("accrued per 100:                        %.6f%n",
                callable.accruedInterest());
        System.out.printf("callable clean price per 100:           %.6f%n", callable.cleanPrice());
        System.out.printf("embedded call option cost per 100:      %.6f%n", callable.callSpread());
        double maxResidual = callable.calibrationResiduals().stream()
                .mapToDouble(Double::doubleValue).map(Math::abs).max().orElseThrow();
        System.out.printf("max curve-reproduction residual:        %.2e%n", maxResidual);
        System.out.println("call-date node decisions (ex-coupon comparison):");
        for (CallNodeSnapshot snapshot : callable.callSnapshots()) {
            System.out.printf("  %s j=%d r=%.6f continuation=%.6f %s%n", snapshot.date(),
                    snapshot.nodeIndex(), snapshot.shortRate(), snapshot.continuationValue(),
                    snapshot.exercised() ? "CALLED" : "continue");
        }
    }

    private static void runHedgeExample() {
        LocalDate valueDate = LocalDate.of(2026, 6, 28);
        LocalDate settlementDate = LocalDate.of(2026, 10, 6);

        List<DepositQuote> deposits = List.of(
                new DepositQuote("DEP-6M", LocalDate.of(2026, 12, 28), 0.0250));
        List<SwapQuote> swaps = List.of(
                new SwapQuote("SWAP-1Y", List.of(LocalDate.of(2027, 6, 28)), 0.0300),
                new SwapQuote("SWAP-2Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)), 0.0325),
                new SwapQuote("SWAP-3Y",
                        List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                                LocalDate.of(2029, 6, 28)), 0.0350));

        Bond callableBond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2027, 1, 14), LocalDate.of(2027, 4, 24),
                        LocalDate.of(2027, 8, 2)),
                100.0, 0.0600);
        List<CallPrice> callSchedule = List.of(
                new CallPrice(LocalDate.of(2027, 1, 14), 101.50),
                new CallPrice(LocalDate.of(2027, 4, 24), 100.75));
        HedgePosition callableHolding = new HedgePosition("H-CALLABLE", callableBond, 8.0,
                callSchedule, 0.0120, 100);
        Bond plainBond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)),
                100.0, 0.0400);
        HedgePosition plainHolding = new HedgePosition("H-PLAIN-3Y", plainBond, 5.0);

        Bond candidate1y = new Bond(valueDate,
                List.of(LocalDate.of(2027, 6, 28)), 100.0, 0.0);
        Bond candidate2y = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)),
                100.0, 0.0350);
        Bond candidate3y = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)),
                100.0, 0.0450);
        List<HedgeCandidate> candidates = List.of(
                new HedgeCandidate("C-1Y", candidate1y, -20.0, 20.0),
                new HedgeCandidate("C-2Y", candidate2y, -20.0, 20.0),
                new HedgeCandidate("C-3Y", candidate3y, -20.0, 20.0));

        Map<String, Double> limits = new LinkedHashMap<>();
        limits.put("DEP-6M", 0.05);
        limits.put("SWAP-1Y", 0.10);
        limits.put("SWAP-2Y", 0.15);
        limits.put("SWAP-3Y", 0.20);
        double budget = 300.0;

        HedgeInput input = new HedgeInput(valueDate, settlementDate, deposits, swaps,
                List.of(callableHolding, plainHolding), candidates, limits, budget);
        HedgeResult result = new HedgeEngine(CurveConfig.defaults()).hedge(input);

        System.out.println();
        System.out.println("============================================================");
        System.out.println("budgeted hedge example (callable holding, constrained budget)");
        System.out.println("value date: " + valueDate + ", settlement: " + settlementDate);
        System.out.println("holdings: H-CALLABLE quantity 8 (100-day tree steps, sigma 1.20%),"
                + " H-PLAIN-3Y quantity 5");
        System.out.println();
        System.out.printf("turnover budget B: %.2f, used turnover: %.6f%n",
                result.budget(), result.turnover());
        System.out.println("trades (signed hundreds of face, +buy / -sell):");
        for (int j = 0; j < result.candidateCount(); j++) {
            System.out.printf("  %-6s x = %+10.6f  (original dirty per 100 = %.6f)%n",
                    result.candidateIds().get(j), result.quantities()[j],
                    result.unitDirtyPrices()[j]);
        }
        System.out.println();
        System.out.printf("%-8s %14s %14s %12s%n",
                "bucket", "DV01 before", "DV01 after", "|r| / L");
        java.util.List<String> bucketIds = new java.util.ArrayList<>(limits.keySet());
        for (int k = 0; k < result.bucketCount(); k++) {
            double normalized = Math.abs(result.dv01After().get(k)) / limits.get(bucketIds.get(k));
            System.out.printf("%-8s %14.6f %14.6f %12.6f%n", bucketIds.get(k),
                    result.dv01Before().get(k), result.dv01After().get(k), normalized);
        }
        System.out.printf("worst standardized residual: %.6f%n", result.worstResidual());
        System.out.println("each callable revaluation recalibrates the tree and re-decides"
                + " exercise on every +/-1 bp rebuild");
    }
}
