package com.hedge257;

/**
 * Net-price DV01 for shifting one quoted instrument by +/- 1 bp.
 * Exactly one of dv01 or failureReason is set; failures are never reported as zero.
 *
 * @param id             shifted instrument id
 * @param dv01           (clean price at quote - 1bp minus clean price at quote + 1bp) / 2
 * @param failureReason  why either shifted curve could not be built or priced
 */
public record QuoteDv01(String id, Double dv01, String failureReason) {
    public static QuoteDv01 of(String id, double dv01) {
        return new QuoteDv01(id, dv01, null);
    }

    public static QuoteDv01 failed(String id, String failureReason) {
        return new QuoteDv01(id, null, failureReason);
    }

    public boolean failed() {
        return dv01 == null;
    }
}
