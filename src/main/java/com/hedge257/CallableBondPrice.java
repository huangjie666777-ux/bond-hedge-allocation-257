package com.hedge257;

import java.util.List;

/**
 * Callable bond valuation, all prices per 100 of face value.
 *
 * @param dirtyPrice        callable full (gross) price
 * @param accruedInterest   accrued interest, reusing the plain bond ACT/365F rule
 * @param cleanPrice        callable net price = dirty - accrued
 * @param optionFreePrice   non-callable comparison price on the same calibrated tree
 * @param callSpread        option-free price minus callable price (option cost to the holder)
 * @param calibrationResiduals per calibrated layer i: reproduced state-price sum minus
 *                             D(grid date i+1) / D(settlement)
 * @param callSnapshots     per call-date node short rates, continuation values and exercise flags
 */
public record CallableBondPrice(double dirtyPrice, double accruedInterest, double cleanPrice,
                                double optionFreePrice, double callSpread,
                                List<Double> calibrationResiduals,
                                List<CallNodeSnapshot> callSnapshots) {
    public CallableBondPrice {
        calibrationResiduals = List.copyOf(calibrationResiduals);
        callSnapshots = List.copyOf(callSnapshots);
    }
}
