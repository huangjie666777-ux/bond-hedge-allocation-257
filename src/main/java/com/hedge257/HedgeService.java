package com.hedge257;

/**
 * Application facade for contract validation, portfolio revaluation and hedge LP.
 */
public final class HedgeService {
    private final CurveConfig config;
    private final double shift;

    public HedgeService() {
        this(CurveConfig.defaults(), RiskEngine.ONE_BP);
    }

    public HedgeService(CurveConfig config) {
        this(config, RiskEngine.ONE_BP);
    }

    public HedgeService(CurveConfig config, double shift) {
        this.config = config == null ? CurveConfig.defaults() : config;
        if (!(shift > 0.0) || !Double.isFinite(shift)) {
            throw new IllegalArgumentException("shift must be positive and finite");
        }
        this.shift = shift;
    }

    public HedgeSolution hedge(HedgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("hedge request must not be null");
        }
        HedgeRiskModel model = new PortfolioRiskEngine(config, shift).build(request);
        return new HedgeOptimizer().optimize(request, model);
    }
}
