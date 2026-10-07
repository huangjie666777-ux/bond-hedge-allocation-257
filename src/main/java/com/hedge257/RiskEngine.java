package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the full curve for every quote shifted independently down and up by
 * 1 bp (default 0.0001) and computes the net-price DV01. A failed shift keeps
 * the reason instead of writing a zero.
 */
public final class RiskEngine {
    public static final double ONE_BP = 1.0e-4;

    private final CurveBootstrapper bootstrapper;
    private final BondPricer pricer;
    private final double shift;

    public RiskEngine(CurveConfig config) {
        this(config, ONE_BP);
    }

    public RiskEngine(CurveConfig config, double shift) {
        if (!(shift > 0.0) || !Double.isFinite(shift)) {
            throw new IllegalArgumentException("shift must be positive and finite");
        }
        this.bootstrapper = new CurveBootstrapper(config == null ? CurveConfig.defaults() : config);
        this.pricer = new BondPricer();
        this.shift = shift;
    }

    public Dv01Report dv01(LocalDate valueDate, List<DepositQuote> deposits,
                          List<SwapQuote> swaps, Bond bond, LocalDate settlementDate) {
        List<DepositQuote> depositList = deposits == null ? List.of() : List.copyOf(deposits);
        List<SwapQuote> swapList = swaps == null ? List.of() : List.copyOf(swaps);
        List<QuoteDv01> results = new ArrayList<>();

        for (DepositQuote quote : depositList) {
            List<DepositQuote> down = replace(depositList, quote,
                    new DepositQuote(quote.id(), quote.endDate(), quote.rate() - shift));
            List<DepositQuote> up = replace(depositList, quote,
                    new DepositQuote(quote.id(), quote.endDate(), quote.rate() + shift));
            results.add(shifted(valueDate, down, swapList, up, swapList, bond,
                    settlementDate, quote.id()));
        }
        for (SwapQuote quote : swapList) {
            List<SwapQuote> down = replace(swapList, quote,
                    new SwapQuote(quote.id(), quote.paymentDates(), quote.fixedRate() - shift));
            List<SwapQuote> up = replace(swapList, quote,
                    new SwapQuote(quote.id(), quote.paymentDates(), quote.fixedRate() + shift));
            results.add(shifted(valueDate, depositList, down, depositList, up, bond,
                    settlementDate, quote.id()));
        }
        return new Dv01Report(results);
    }

    private QuoteDv01 shifted(LocalDate valueDate,
                              List<DepositQuote> depositsDown, List<SwapQuote> swapsDown,
                              List<DepositQuote> depositsUp, List<SwapQuote> swapsUp,
                              Bond bond, LocalDate settlementDate, String id) {
        double cleanDown;
        double cleanUp;
        try {
            DiscountCurve curveDown = bootstrapper.bootstrap(
                    valueDate, depositsDown, swapsDown).curve();
            cleanDown = pricer.price(bond, settlementDate, curveDown).cleanPrice();
        } catch (RuntimeException ex) {
            return QuoteDv01.failed(id, "down shift failed: " + ex.getMessage());
        }
        try {
            DiscountCurve curveUp = bootstrapper.bootstrap(
                    valueDate, depositsUp, swapsUp).curve();
            cleanUp = pricer.price(bond, settlementDate, curveUp).cleanPrice();
        } catch (RuntimeException ex) {
            return QuoteDv01.failed(id, "up shift failed: " + ex.getMessage());
        }
        if (!Double.isFinite(cleanDown) || !Double.isFinite(cleanUp)) {
            return QuoteDv01.failed(id, "non-finite shifted price");
        }
        return QuoteDv01.of(id, (cleanDown - cleanUp) / 2.0);
    }

    private static <T> List<T> replace(List<T> list, T original, T replacement) {
        List<T> copy = new ArrayList<>(list);
        int index = copy.indexOf(original);
        if (index < 0) {
            throw new IllegalStateException("missing quote " + original);
        }
        copy.set(index, replacement);
        return List.copyOf(copy);
    }
}
