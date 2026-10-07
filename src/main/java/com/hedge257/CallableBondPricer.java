package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices an issuer-callable fixed coupon bond on the calibrated short-rate
 * tree. The original ACT/365F cash-flow and accrued-interest rules are reused;
 * inputs are never modified. On a call date the current coupon is paid first,
 * then the issuer compares the ex-coupon continuation value with the call
 * price and takes the smaller one. Equality continues the bond, so the option
 * is exercised only when continuation is strictly more expensive.
 */
public final class CallableBondPricer {
    private static final double TREE_REPRODUCTION_TOLERANCE = 1.0e-10;
    private static final double NON_CALLABLE_TREE_TOLERANCE = 1.0e-8;

    private final BondPricer plainPricer = new BondPricer();

    public CallableBondPrice price(Bond bond, LocalDate settlementDate, DiscountCurve curve,
                                   double sigma, int stepDays, List<CallPrice> calls) {
        ShortRateTree tree = ShortRateTree.calibrate(bond, settlementDate, curve, sigma, stepDays);
        List<CallPrice> callList = calls == null ? List.of() : List.copyOf(calls);

        List<BondCashflow> allFlows = plainPricer.cashflows(bond);
        Map<LocalDate, Double> couponAtGrid = new HashMap<>();
        Set<LocalDate> survivingCouponDates = new HashSet<>();
        double scale = 100.0 / bond.faceValue();
        LocalDate maturity = bond.maturityDate();
        for (BondCashflow flow : allFlows) {
            if (!flow.paymentDate().isAfter(settlementDate)) {
                continue;
            }
            survivingCouponDates.add(flow.paymentDate());
            int layer = layerOf(tree, flow.paymentDate());
            if (flow.paymentDate().equals(maturity)) {
                if (layer != tree.steps()) {
                    throw new IllegalArgumentException("maturity must be the final grid date");
                }
            }
            couponAtGrid.put(flow.paymentDate(), flow.coupon() * scale);
        }
        double terminalAmount = scale * (bond.faceValue()
                + (allFlows.get(allFlows.size() - 1).coupon()));

        Map<LocalDate, Double> callPriceAt = new HashMap<>();
        for (CallPrice call : callList) {
            if (!call.date().isAfter(settlementDate) || !call.date().isBefore(maturity)) {
                throw new IllegalArgumentException(
                        "call date " + call.date() + " must be strictly between settlement and maturity");
            }
            if (!survivingCouponDates.contains(call.date())) {
                throw new IllegalArgumentException(
                        "call date " + call.date() + " must be a remaining bond coupon date");
            }
            if (callPriceAt.put(call.date(), call.price()) != null) {
                throw new IllegalArgumentException("duplicate call date " + call.date());
            }
            layerOf(tree, call.date());
        }

        double[] residuals = tree.calibrationResiduals();
        for (int i = 0; i < residuals.length; i++) {
            if (!Double.isFinite(residuals[i])
                    || Math.abs(residuals[i]) > TREE_REPRODUCTION_TOLERANCE) {
                throw new TreeCalibrationException(
                        "layer " + (i + 1) + " discount reproduction residual " + residuals[i]
                                + " exceeds tolerance " + TREE_REPRODUCTION_TOLERANCE);
            }
        }

        int steps = tree.steps();
        double[] plainValues = new double[steps + 1];
        double[] callableValues = new double[steps + 1];
        for (int j = 0; j <= steps; j++) {
            plainValues[j] = terminalAmount;
            callableValues[j] = terminalAmount;
        }

        List<CallNodeSnapshot> snapshots = new ArrayList<>();
        for (int i = steps - 1; i >= 0; i--) {
            LocalDate date = tree.gridDate(i);
            double coupon = couponAtGrid.getOrDefault(date, 0.0);
            Double callPrice = callPriceAt.get(date);
            double[] nextPlain = plainValues;
            double[] nextCallable = callableValues;
            double[] rolledPlain = new double[i + 1];
            double[] rolledCallable = new double[i + 1];
            for (int j = 0; j <= i; j++) {
                double discount = Math.exp(-tree.shortRate(i, j) * tree.dt());
                rolledPlain[j] = 0.5 * discount * (nextPlain[j] + nextPlain[j + 1]);
                rolledCallable[j] = 0.5 * discount * (nextCallable[j] + nextCallable[j + 1]);
            }
            for (int j = 0; j <= i; j++) {
                double plainValue = rolledPlain[j] + coupon;
                plainValues[j] = plainValue;

                double continuation = rolledCallable[j];
                double callableValue = continuation + coupon;
                if (callPrice != null) {
                    boolean exercised = continuation > callPrice;
                    if (exercised) {
                        callableValue = callPrice + coupon;
                    }
                    snapshots.add(new CallNodeSnapshot(date, j, tree.shortRate(i, j),
                            continuation, exercised));
                }
                callableValues[j] = callableValue;
            }
        }

        double optionFreeTree = plainValues[0];
        if (!Double.isFinite(optionFreeTree) || optionFreeTree <= 0.0) {
            throw new TreeCalibrationException("non-finite option-free tree price");
        }
        BondPrice plainPrice = plainPricer.price(bond, settlementDate, curve);
        if (Math.abs(optionFreeTree - plainPrice.dirtyPrice()) > NON_CALLABLE_TREE_TOLERANCE) {
            throw new TreeCalibrationException(
                    "option-free tree price " + optionFreeTree + " does not reproduce the curve price "
                            + plainPrice.dirtyPrice());
        }

        double dirty = callableValues[0];
        if (!Double.isFinite(dirty) || dirty <= 0.0) {
            throw new TreeCalibrationException("non-finite callable tree price");
        }
        double accrued = plainPricer.accruedInterest(bond, settlementDate) * scale;
        double clean = dirty - accrued;
        double spread = optionFreeTree - dirty;
        if (spread < -1.0e-12) {
            throw new TreeCalibrationException("call option produced a negative option cost");
        }
        snapshots.sort((left, right) -> {
            int byDate = left.date().compareTo(right.date());
            return byDate != 0 ? byDate : Integer.compare(left.nodeIndex(), right.nodeIndex());
        });
        List<Double> residualList = new ArrayList<>();
        for (double residual : residuals) {
            residualList.add(residual);
        }
        return new CallableBondPrice(dirty, accrued, clean, optionFreeTree, Math.max(0.0, spread),
                residualList, snapshots);
    }

    private static int layerOf(ShortRateTree tree, LocalDate date) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(tree.settlementDate(), date);
        long stepDays = Math.round(tree.dt() * DayCount.DAYS_PER_YEAR);
        if (days <= 0 || days % stepDays != 0 || days / stepDays > tree.steps()) {
            throw new IllegalArgumentException(
                    "date " + date + " does not lie on the pricing tree grid");
        }
        return Math.toIntExact(days / stepDays);
    }
}
