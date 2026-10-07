package com.hedge257;

import java.time.LocalDate;

/**
 * Backward-induction state at one node of a call date.
 *
 * @param date              call date / grid date
 * @param nodeIndex         tree node index j within the layer (0 = lowest short rate)
 * @param shortRate         calibrated short rate at this node (continuously compounded)
 * @param continuationValue per-100 value of continuing, after the current coupon is paid
 * @param exercised         true when the issuer calls: continuationValue strictly above call price
 */
public record CallNodeSnapshot(LocalDate date, int nodeIndex, double shortRate,
                               double continuationValue, boolean exercised) {
}
