package com.hedge257;

/**
 * A tradable plain bond. Quantities are signed and continuous, in hundreds of
 * face value: positive results are buys, negative results are sells.
 *
 * @param id           unique candidate identifier
 * @param bond         plain bond contract (no embedded calls)
 * @param minQuantity  inclusive lower trade bound, finite
 * @param maxQuantity  inclusive upper trade bound, finite and not below minQuantity
 */
public record HedgeCandidate(String id, Bond bond, double minQuantity, double maxQuantity) {
    public HedgeCandidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (bond == null) {
            throw new IllegalArgumentException("candidate " + id + " bond must not be null");
        }
        if (!Double.isFinite(minQuantity) || !Double.isFinite(maxQuantity)) {
            throw new IllegalArgumentException(
                    "candidate " + id + " trade bounds must be finite");
        }
        if (minQuantity > maxQuantity) {
            throw new IllegalArgumentException("candidate " + id
                    + " has contradictory trade bounds [" + minQuantity + ", " + maxQuantity + "]");
        }
    }
}
