package com.hedge257;

/**
 * Tradable ordinary bond. Bounds are signed quantities in units of 100 face.
 */
public record Candidate(String id, Bond bond, double minQuantity, double maxQuantity) {
    public Candidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (bond == null) {
            throw new IllegalArgumentException("candidate " + id + " bond must not be null");
        }
        Validate.requireFinite(minQuantity, "candidate " + id + " minimum quantity");
        Validate.requireFinite(maxQuantity, "candidate " + id + " maximum quantity");
        if (minQuantity > maxQuantity) {
            throw new IllegalArgumentException("candidate " + id
                    + " minimum quantity " + minQuantity + " exceeds maximum " + maxQuantity);
        }
    }
}
