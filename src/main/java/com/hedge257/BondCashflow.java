package com.hedge257;

import java.time.LocalDate;

/**
 * One bond cash flow in face-value units. The last flow carries principal.
 *
 * @param paymentDate payment and period end date
 * @param periodStart accrual start date (issue date for the first period)
 * @param yearFraction ACT/365F length of the coupon period
 * @param coupon      coupon amount (face * rate * alpha); zero is possible
 * @param principal   face value at maturity, otherwise zero
 * @param amount      coupon + principal
 */
public record BondCashflow(LocalDate paymentDate, LocalDate periodStart, double yearFraction,
                           double coupon, double principal, double amount) {
}
