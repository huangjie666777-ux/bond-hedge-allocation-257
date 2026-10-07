package com.hedge257;

/**
 * Thrown when the recombining short-rate tree cannot be calibrated to the
 * discount curve (non-finite level, degenerated state-price mass or a layer
 * that fails to reproduce the curve discount factor within tolerance).
 */
public class TreeCalibrationException extends RuntimeException {
    public TreeCalibrationException(String message) {
        super(message);
    }
}
