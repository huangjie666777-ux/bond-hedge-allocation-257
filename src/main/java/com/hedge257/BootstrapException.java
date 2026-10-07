package com.hedge257;

import java.util.List;

/**
 * Thrown when a curve cannot be built: invalid market data (IllegalArgumentException
 * style input errors are thrown separately) or a root cannot be found / the
 * reproduced quote misses the tolerance. Repricings gathered so far, including
 * the failing instrument, are attached.
 */
public class BootstrapException extends RuntimeException {
    private final String instrumentId;
    private final transient List<InstrumentRepricing> repricings;

    public BootstrapException(String instrumentId, String reason,
                              List<InstrumentRepricing> repricings) {
        super("instrument '" + instrumentId + "': " + reason);
        this.instrumentId = instrumentId;
        this.repricings = List.copyOf(repricings);
    }

    public String instrumentId() {
        return instrumentId;
    }

    public List<InstrumentRepricing> repricings() {
        return repricings;
    }
}
