package com.hedge257;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hedge257Test {
    private static final LocalDate V = LocalDate.of(2026, 6, 28);

    private static final CurveConfig TIGHT = new CurveConfig(1.0e-13, 200, 1.0e-9);

    private static List<DepositQuote> deposits() {
        return List.of(
                new DepositQuote("D3M", LocalDate.of(2026, 9, 28), 0.020),
                new DepositQuote("D6M", LocalDate.of(2026, 12, 28), 0.025));
    }

    private static List<SwapQuote> swaps() {
        return List.of(
                new SwapQuote("S1Y", List.of(LocalDate.of(2027, 6, 28)), 0.030),
                new SwapQuote("S2Y", List.of(LocalDate.of(2027, 6, 28),
                        LocalDate.of(2028, 6, 28)), 0.0325),
                new SwapQuote("S3Y", List.of(LocalDate.of(2027, 6, 28),
                        LocalDate.of(2028, 6, 28), LocalDate.of(2029, 6, 28)), 0.035));
    }

    @Test
    void dayCountUsesActualDaysOver365() {
        assertEquals(92.0 / 365.0, DayCount.act365f(V, LocalDate.of(2026, 9, 28)), 0.0);
        assertEquals(0.0, DayCount.act365f(V, V), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> DayCount.act365f(LocalDate.of(2026, 9, 28), V));
    }

    @Test
    void rejectsNonFiniteAndBadDatesAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> new DepositQuote("x", LocalDate.of(2026, 12, 28), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new DepositQuote("x", LocalDate.of(2026, 12, 28), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new DepositQuote("x", null, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new SwapQuote("s", List.of(LocalDate.of(2027, 6, 28),
                        LocalDate.of(2027, 6, 28)), 0.03));
        CurveBootstrapper b = new CurveBootstrapper(TIGHT);
        assertThrows(IllegalArgumentException.class,
                () -> b.bootstrap(V,
                        List.of(new DepositQuote("A", LocalDate.of(2026, 12, 28), 0.01),
                                new DepositQuote("A", LocalDate.of(2027, 1, 28), 0.01)),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> b.bootstrap(V,
                        List.of(new DepositQuote("A", LocalDate.of(2027, 6, 28), 0.01),
                                new DepositQuote("B", LocalDate.of(2027, 6, 28), 0.02)),
                        List.of()));
    }

    @Test
    void depositDiscountMatchesFormulaAndRejectsBadDenominator() {
        BootstrapResult r = new CurveBootstrapper(TIGHT)
                .bootstrap(V, List.of(new DepositQuote("D",
                        LocalDate.of(2026, 12, 28), -0.01)), List.of());
        double alpha = 183.0 / 365.0;
        assertEquals(1.0 / (1.0 - 0.01 * alpha), r.curve().discountAtNode(1), 1.0e-14);
        assertTrue(r.curve().discountAtNode(1) > 1.0);
        BootstrapException ex = assertThrows(BootstrapException.class,
                () -> new CurveBootstrapper(TIGHT).bootstrap(V,
                        List.of(new DepositQuote("BAD",
                                LocalDate.of(2026, 12, 28), -100.0)), List.of()));
        assertEquals("BAD", ex.instrumentId());
    }

    @Test
    void reproducesEveryQuoteWithinToleranceIncludingInterpolation() {
        BootstrapResult r = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        DiscountCurve curve = r.curve();
        assertEquals(1.0, curve.discountFactor(V), 0.0);
        for (InstrumentRepricing q : r.repricings()) {
            assertTrue(Double.isFinite(q.reproducedQuote()), q.id());
            assertEquals(q.marketQuote(), q.reproducedQuote(), 1.0e-9, q.id());
            assertTrue(Math.abs(q.residual()) <= 1.0e-9, q.id());
        }
        LocalDate mid = LocalDate.of(2027, 3, 29);
        double weight = 91.0 / 182.0;
        double expected = Math.exp((1.0 - weight) * Math.log(curve.discountAtNode(2))
                + weight * Math.log(curve.discountAtNode(3)));
        assertEquals(expected, curve.discountFactor(mid), 1.0e-14);
        assertThrows(IllegalArgumentException.class,
                () -> curve.discountFactor(curve.nodeDate(curve.nodeCount() - 1).plusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> curve.discountFactor(V.minusDays(1)));
    }

    @Test
    void failedToleranceFailsWholeCurveAndReportsResiduals() {
        CurveConfig impossible = new CurveConfig(1.0e-14, 3, 1.0e-14);
        BootstrapException ex = assertThrows(BootstrapException.class,
                () -> new CurveBootstrapper(impossible).bootstrap(V, deposits(), swaps()));
        assertFalse(ex.repricings().isEmpty());
        assertNotNull(ex.instrumentId());
    }

    @Test
    void negativeRatesBootstrap() {
        List<SwapQuote> neg = List.of(new SwapQuote("S1",
                List.of(LocalDate.of(2027, 6, 28)), -0.005));
        BootstrapResult r = new CurveBootstrapper(TIGHT)
                .bootstrap(V, List.of(new DepositQuote("D",
                        LocalDate.of(2026, 12, 28), -0.004)), neg);
        assertEquals(-0.005, r.repricings().get(1).reproducedQuote(), 1.0e-10);
    }

    @Test
    void bondPricesAccrualAndSurvivingFlows() {
        BootstrapResult r = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)), 100.0, 0.04);
        LocalDate settle = LocalDate.of(2026, 9, 15);
        BondPrice price = new BondPricer().price(bond, settle, r.curve());
        assertEquals(3, price.cashflows().size());
        assertEquals(4.0, price.cashflows().get(0).coupon(), 1.0e-12);
        assertEquals(100.0, price.cashflows().get(2).principal(), 0.0);
        double accruedDays = 79.0;
        assertEquals(100.0 * 0.04 * accruedDays / 365.0,
                price.accruedInterest(), 1.0e-12);
        assertEquals(price.dirtyPrice() - price.accruedInterest(),
                price.cleanPrice(), 1.0e-10);

        BondPrice onCoupon = new BondPricer()
                .price(bond, LocalDate.of(2027, 6, 28), r.curve());
        assertEquals(0.0, onCoupon.accruedInterest(), 0.0);
        assertEquals(2, onCoupon.cashflows().size());
    }

    @Test
    void rejectsInvalidBondInputsAndSettlementOutsideCurve() {
        assertThrows(IllegalArgumentException.class,
                () -> new Bond(V, List.of(LocalDate.of(2027, 6, 28)), 0.0, 0.04));
        assertThrows(IllegalArgumentException.class,
                () -> new Bond(V, List.of(LocalDate.of(2027, 6, 28)), 100.0, -0.01));
        BootstrapResult r = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)), 100.0, 0.04);
        assertThrows(IllegalArgumentException.class,
                () -> new BondPricer().price(bond, LocalDate.of(2029, 6, 28), r.curve()));
        assertThrows(IllegalArgumentException.class,
                () -> new BondPricer().price(bond, LocalDate.of(2024, 6, 27), r.curve()));
        assertThrows(IllegalArgumentException.class,
                () -> new BondPricer().price(bond, LocalDate.of(2029, 6, 29), r.curve()));
    }

    @Test
    void dv01BumpsEachQuoteAndFailureKeepsReason() {
        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)), 100.0, 0.04);
        LocalDate settle = LocalDate.of(2026, 9, 15);
        RiskEngine risk = new RiskEngine(TIGHT);
        Dv01Report report = risk.dv01(V, deposits(), swaps(), bond, settle);
        assertEquals(5, report.results().size());
        for (QuoteDv01 dv : report.results()) {
            assertFalse(dv.failed(), dv.id());
            assertTrue(Double.isFinite(dv.dv01()), dv.id());
        }
        double s3y = report.results().stream().filter(d -> d.id().equals("S3Y"))
                .findFirst().orElseThrow().dv01();
        assertTrue(s3y > 0.0, "P_down - P_up should be positive, got " + s3y);

        List<DepositQuote> explosive = List.of(new DepositQuote("BAD",
                LocalDate.of(2026, 9, 28), -100.0));
        Dv01Report bad = risk.dv01(V, explosive, swaps(), bond, settle);
        QuoteDv01 failed = bad.results().get(0);
        assertTrue(failed.failed());
        assertNotNull(failed.failureReason());
    }

    @Test
    void callableTreeReproducesCurveAndValuesEmbeddedCall() {
        BootstrapResult r = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        LocalDate settle = LocalDate.of(2026, 10, 6);
        Bond bond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2027, 1, 14),
                        LocalDate.of(2027, 4, 24), LocalDate.of(2027, 8, 2)),
                100.0, 0.06);
        List<CallPrice> calls = List.of(
                new CallPrice(LocalDate.of(2027, 1, 14), 101.50),
                new CallPrice(LocalDate.of(2027, 4, 24), 100.75));
        CallableBondPricer pricer = new CallableBondPricer();
        CallableBondPrice price =
                pricer.price(bond, settle, r.curve(), 0.012, 100, calls);

        BondPrice plain = new BondPricer().price(bond, settle, r.curve());
        assertEquals(3, price.calibrationResiduals().size());
        for (double residual : price.calibrationResiduals()) {
            assertEquals(0.0, residual, 1.0e-12);
        }
        assertEquals(plain.dirtyPrice(), price.optionFreePrice(), 1.0e-9);
        assertTrue(price.callSpread() > 0.0);
        assertEquals(price.optionFreePrice() - price.dirtyPrice(),
                price.callSpread(), 1.0e-12);
        assertEquals(price.dirtyPrice() - price.accruedInterest(),
                price.cleanPrice(), 1.0e-12);
        assertEquals(100.0 * 0.06 * 265.0 / 365.0,
                price.accruedInterest(), 1.0e-12);
        assertEquals(5, price.callSnapshots().size());
        assertTrue(price.callSnapshots().stream().anyMatch(CallNodeSnapshot::exercised));
        for (CallNodeSnapshot snapshot : price.callSnapshots()) {
            assertTrue(Double.isFinite(snapshot.shortRate()));
            assertTrue(Double.isFinite(snapshot.continuationValue()));
        }

        CallableBondPrice zeroVol = pricer.price(bond, settle, r.curve(), 0.0, 100, calls);
        assertEquals(plain.dirtyPrice(), zeroVol.optionFreePrice(), 1.0e-9);
        assertTrue(zeroVol.callSpread() >= 0.0);
        for (CallNodeSnapshot snapshot : zeroVol.callSnapshots()) {
            assertEquals(snapshot.shortRate(),
                    zeroVol.callSnapshots().stream()
                            .filter(other -> other.date().equals(snapshot.date()))
                            .findFirst().orElseThrow().shortRate(), 0.0);
        }
    }

    @Test
    void rejectsInvalidCallableInputs() {
        BootstrapResult r = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        LocalDate settle = LocalDate.of(2026, 10, 6);
        Bond bond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2027, 1, 14),
                        LocalDate.of(2027, 4, 24), LocalDate.of(2027, 8, 2)),
                100.0, 0.06);
        CallableBondPricer pricer = new CallableBondPricer();
        List<CallPrice> calls = List.of(
                new CallPrice(LocalDate.of(2027, 1, 14), 101.50),
                new CallPrice(LocalDate.of(2027, 4, 24), 100.75));

        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), Double.NaN, 100, calls));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), -0.001, 100, calls));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 99, calls));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 7, calls));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 100,
                        List.of(new CallPrice(LocalDate.of(2027, 2, 14), 100.0))));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 100,
                        List.of(new CallPrice(settle, 100.0))));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 100,
                        List.of(new CallPrice(LocalDate.of(2027, 8, 2), 100.0))));
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(bond, settle, r.curve(), 0.01, 100,
                        List.of(new CallPrice(LocalDate.of(2027, 1, 14), 100.0),
                                new CallPrice(LocalDate.of(2027, 1, 14), 101.0))));
        assertThrows(IllegalArgumentException.class,
                () -> new CallPrice(LocalDate.of(2027, 1, 14), 0.0));

        Bond beyondCurve = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2029, 8, 2)),
                100.0, 0.06);
        assertThrows(IllegalArgumentException.class,
                () -> pricer.price(beyondCurve, settle, r.curve(), 0.01, 1,
                        List.of()));
    }

    @Test
    void hedgesCallablePortfolioUnderBindingBudgetAndVerifiesResult() {
        LocalDate settle = LocalDate.of(2026, 10, 6);
        Bond callableBond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2027, 1, 14),
                        LocalDate.of(2027, 4, 24), LocalDate.of(2027, 8, 2)),
                100.0, 0.06);
        List<CallPrice> calls = List.of(
                new CallPrice(LocalDate.of(2027, 1, 14), 101.50),
                new CallPrice(LocalDate.of(2027, 4, 24), 100.75));
        Bond twoYear = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)),
                100.0, 0.04);
        Bond threeYear = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28),
                        LocalDate.of(2029, 6, 28)), 100.0, 0.04);
        HedgeRequest request = new HedgeRequest(V, settle, deposits(), swaps(),
                List.of(Holding.callable("CALL", callableBond, 10.0, calls, 0.012, 100)),
                List.of(new Candidate("C2", twoYear, -8.0, 8.0),
                        new Candidate("C3", threeYear, -8.0, 8.0)),
                List.of(4.0, 4.0, 4.0, 4.0, 4.0), 50.0);

        HedgeSolution solution = new HedgeService(TIGHT).hedge(request);

        assertEquals(List.of("C2", "C3"), solution.candidateIds());
        assertEquals(List.of("D3M", "D6M", "S1Y", "S2Y", "S3Y"),
                solution.quoteIds());
        assertTrue(Math.abs(solution.quantities()[0]) > 1.0e-8
                || Math.abs(solution.quantities()[1]) > 1.0e-8);
        assertTrue(solution.turnover() <= 50.0 + 1.0e-7);
        assertTrue(solution.turnover() >= 50.0 - 1.0e-5);
        double recomputedTurnover = 0.0;
        double worst = 0.0;
        Dv01Report twoYearRisk = new RiskEngine(TIGHT).dv01(V, deposits(), swaps(),
                twoYear, settle);
        Dv01Report threeYearRisk = new RiskEngine(TIGHT).dv01(V, deposits(), swaps(),
                threeYear, settle);
        for (int bucket = 0; bucket < solution.quoteIds().size(); bucket++) {
            double residual = solution.beforeDv01()[bucket]
                    + solution.quantities()[0] * twoYearRisk.results().get(bucket).dv01()
                    + solution.quantities()[1] * threeYearRisk.results().get(bucket).dv01();
            for (int candidate = 0; candidate < 2; candidate++) {
                assertTrue(solution.quantities()[candidate] >= -8.0 - 1.0e-8);
                assertTrue(solution.quantities()[candidate] <= 8.0 + 1.0e-8);
            }
            assertEquals(residual, solution.afterDv01()[bucket], 1.0e-8);
            worst = Math.max(worst, Math.abs(solution.afterDv01()[bucket]) / 4.0);
        }
        assertEquals(worst, solution.worstStandardizedResidual(), 1.0e-8);
        BootstrapResult base = new CurveBootstrapper(TIGHT).bootstrap(V, deposits(), swaps());
        recomputedTurnover += new BondPricer().price(twoYear, settle, base.curve()).dirtyPrice()
                * Math.abs(solution.quantities()[0]);
        recomputedTurnover += new BondPricer().price(threeYear, settle, base.curve()).dirtyPrice()
                * Math.abs(solution.quantities()[1]);
        assertEquals(recomputedTurnover, solution.turnover(), 1.0e-7);
    }

    @Test
    void distinguishesInfeasibleBudgetFromValuationFailureAndValidatesContract() {
        LocalDate settle = LocalDate.of(2026, 10, 6);
        Bond bond = new Bond(LocalDate.of(2024, 6, 28),
                List.of(LocalDate.of(2025, 6, 28), LocalDate.of(2026, 6, 28),
                        LocalDate.of(2027, 6, 28), LocalDate.of(2028, 6, 28)),
                100.0, 0.04);
        HedgeRequest forcedButNoBudget = new HedgeRequest(V, settle, deposits(), swaps(),
                List.of(Holding.plain("H", bond, 1.0)),
                List.of(new Candidate("C", bond, 1.0, 8.0)),
                List.of(4.0, 4.0, 4.0, 4.0, 4.0), 0.0);
        assertThrows(InfeasibleHedgeException.class,
                () -> new HedgeService(TIGHT).hedge(forcedButNoBudget));

        List<CallPrice> calls = List.of(new CallPrice(LocalDate.of(2027, 1, 14), 101.5));
        Bond callableBond = new Bond(LocalDate.of(2025, 1, 14),
                List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2027, 1, 14),
                        LocalDate.of(2027, 4, 24), LocalDate.of(2027, 8, 2)),
                100.0, 0.06);
        HedgeRequest badTree = new HedgeRequest(V, settle, deposits(), swaps(),
                List.of(Holding.callable("CALL", callableBond, 1.0, calls, 0.012, 99)),
                List.of(new Candidate("C", bond, -8.0, 8.0)),
                List.of(4.0, 4.0, 4.0, 4.0, 4.0), 1000.0);
        HedgeValuationException failure = assertThrows(HedgeValuationException.class,
                () -> new HedgeService(TIGHT).hedge(badTree));
        assertTrue(failure.failures().stream().anyMatch(f -> f.quoteId().equals("S3Y")
                && f.instrumentId().equals("CALL")));

        assertThrows(IllegalArgumentException.class,
                () -> new Candidate("C", bond, 2.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new HedgeRequest(V, settle, deposits(), swaps(),
                        List.of(Holding.plain("H", bond, 1.0),
                                Holding.plain("H", bond, 1.0)),
                        List.of(new Candidate("C", bond, -1.0, 1.0)),
                        List.of(4.0, 4.0, 4.0, 4.0, 4.0), 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new HedgeRequest(V, settle, deposits(), swaps(),
                        List.of(Holding.plain("H", bond, 1.0)),
                        List.of(new Candidate("C", bond, -1.0, 1.0)),
                        List.of(4.0, 4.0, 4.0, 4.0), 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new HedgeRequest(V, settle, deposits(), swaps(),
                        List.of(Holding.plain("H", bond, 1.0)),
                        List.of(new Candidate("C", bond, -1.0, 1.0)),
                        List.of(4.0, 0.0, 4.0, 4.0, 4.0), 1.0));
    }
}
