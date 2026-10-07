package com.hedge257;

import java.time.LocalDate;

/**
 * One issuer call opportunity, priced per 100 of face value. The call price
 * excludes the coupon paid on the same date: that coupon is always paid first.
 *
 * @param date  call date (must be a coupon date strictly inside (settlement, maturity))
 * @param price amount paid per 100 if the call is exercised; positive and finite
 */
public record CallPrice(LocalDate date, double price) {
    public CallPrice {
        Validate.requireDate(date, "call date");
        if (!(price > 0.0) || !Double.isFinite(price)) {
            throw new IllegalArgumentException("call price must be positive and finite");
        }
    }
}
