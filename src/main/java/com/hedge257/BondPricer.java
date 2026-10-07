package com.hedge257;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Prices fixed coupon bonds with no ex-dividend period: flows paying strictly
 * after the settlement date survive and are discounted by D(pay)/D(settle).
 */
public final class BondPricer {
    /**
     * Builds the complete schedule (including flows on or before settlement).
     */
    public List<BondCashflow> cashflows(Bond bond) {
        List<BondCashflow> flows = new ArrayList<>();
        for (int i = 0; i < bond.couponDates().size(); i++) {
            LocalDate periodStart = i == 0 ? bond.issueDate() : bond.couponDates().get(i - 1);
            LocalDate paymentDate = bond.couponDates().get(i);
            double alpha = DayCount.act365f(periodStart, paymentDate);
            double coupon = bond.faceValue() * bond.annualCoupon() * alpha;
            boolean last = i == bond.couponDates().size() - 1;
            double principal = last ? bond.faceValue() : 0.0;
            flows.add(new BondCashflow(paymentDate, periodStart, alpha, coupon, principal,
                    coupon + principal));
        }
        return flows;
    }

    public BondPrice price(Bond bond, LocalDate settlementDate, DiscountCurve curve) {
        Validate.requireDate(settlementDate, "settlement date");
        if (bond == null || curve == null) {
            throw new IllegalArgumentException("bond and curve must not be null");
        }
        if (settlementDate.isBefore(bond.issueDate()) || !settlementDate.isBefore(bond.maturityDate())) {
            throw new IllegalArgumentException(
                    "settlement " + settlementDate + " must be in [issue date, maturity date)");
        }
        double settlementDf = curve.discountFactor(settlementDate);

        List<BondCashflow> all = cashflows(bond);
        List<BondCashflow> surviving = new ArrayList<>();
        double presentValue = 0.0;
        for (BondCashflow flow : all) {
            if (flow.paymentDate().isAfter(settlementDate)) {
                double flowDf = curve.discountFactor(flow.paymentDate()) / settlementDf;
                presentValue += flow.amount() * flowDf;
                surviving.add(flow);
            }
        }
        if (surviving.isEmpty()) {
            throw new IllegalArgumentException("no surviving cash flows");
        }

        double accrued = accruedInterest(bond, settlementDate);
        double scale = 100.0 / bond.faceValue();
        double dirty = presentValue * scale;
        double accruedPer100 = accrued * scale;
        double clean = dirty - accruedPer100;
        return new BondPrice(surviving, dirty, accruedPer100, clean, settlementDf);
    }

    /**
     * Accrued interest in face-value units for the current coupon period.
     * On a coupon date the period just ended, so accrual is zero.
     */
    public double accruedInterest(Bond bond, LocalDate settlementDate) {
        if (settlementDate.equals(bond.issueDate())) {
            return 0.0;
        }
        LocalDate periodStart = bond.issueDate();
        for (LocalDate couponDate : bond.couponDates()) {
            if (settlementDate.isBefore(couponDate)) {
                double accruedAlpha = DayCount.act365f(periodStart, settlementDate);
                return bond.faceValue() * bond.annualCoupon() * accruedAlpha;
            }
            if (settlementDate.equals(couponDate)) {
                return 0.0;
            }
            periodStart = couponDate;
        }
        throw new IllegalArgumentException(
                "settlement " + settlementDate + " is on or after maturity");
    }
}
