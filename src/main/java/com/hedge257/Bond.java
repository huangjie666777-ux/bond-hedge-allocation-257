package com.hedge257;

import java.time.LocalDate;
import java.util.List;

/**
 * Fixed coupon bond. Coupon dates must be strictly increasing; the first coupon
 * period starts at the issue date. Coupon amounts use ACT/365F year fractions.
 *
 * @param issueDate    first accrual start date
 * @param couponDates  strictly increasing payment dates, the last one is maturity
 * @param faceValue    positive face (notional)
 * @param annualCoupon non-negative annual coupon rate
 */
public record Bond(LocalDate issueDate, List<LocalDate> couponDates,
                   double faceValue, double annualCoupon) {
    public Bond {
        Validate.requireDate(issueDate, "issue date");
        if (couponDates == null || couponDates.isEmpty()) {
            throw new IllegalArgumentException("bond needs at least one coupon date");
        }
        if (!(faceValue > 0.0) || !Double.isFinite(faceValue)) {
            throw new IllegalArgumentException("face value must be positive and finite");
        }
        if (annualCoupon < 0.0 || !Double.isFinite(annualCoupon)) {
            throw new IllegalArgumentException("annual coupon must be non-negative and finite");
        }
        LocalDate previous = issueDate;
        for (LocalDate date : couponDates) {
            Validate.requireDate(date, "coupon date");
            if (!date.isAfter(previous)) {
                throw new IllegalArgumentException(
                        "coupon dates must be strictly increasing after the issue date");
            }
            previous = date;
        }
        couponDates = List.copyOf(couponDates);
    }

    public LocalDate maturityDate() {
        return couponDates.get(couponDates.size() - 1);
    }
}
