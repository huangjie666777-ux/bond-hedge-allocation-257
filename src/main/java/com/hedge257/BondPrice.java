package com.hedge257;

import java.util.List;

/**
 * Bond valuation output, all prices per 100 of face value.
 *
 * @param cashflows          surviving flows (payment date strictly after settlement)
 * @param dirtyPrice         full (gross) price per 100
 * @param accruedInterest    accrued interest per 100 (zero on a coupon date)
 * @param cleanPrice         net price per 100 = dirty - accrued
 * @param settlementDf       discount factor at settlement
 */
public record BondPrice(List<BondCashflow> cashflows, double dirtyPrice,
                        double accruedInterest, double cleanPrice, double settlementDf) {
    public BondPrice {
        cashflows = List.copyOf(cashflows);
    }
}
