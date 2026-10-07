package com.hedge257;

import org.apache.commons.math3.exception.MathRuntimeException;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NoFeasibleSolutionException;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Globally minimizes max_k |b_k + H_k x| / L_k subject to signed bounds and
 * the full-price budget. x+, x- linearization handles short and long trades.
 */
final class HedgeOptimizer {
    private static final double SOLVER_EPSILON = 1.0e-10;
    private static final int SOLVER_ULPS = 100;
    private static final double VALIDATION_EPSILON = 1.0e-7;

    HedgeSolution optimize(HedgeRequest request, HedgeRiskModel model) {
        int candidates = request.candidates().size();
        int buckets = model.quoteIds().size();
        int variables = 2 * candidates + 1;
        int tIndex = 2 * candidates;
        double[] b = model.portfolioExposure();
        double[][] h = model.candidateRisk();
        double[] prices = model.candidateDirtyPrices();

        Collection<LinearConstraint> constraints = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            double[] low = new double[variables];
            low[i] = 1.0;
            low[i + candidates] = -1.0;
            constraints.add(new LinearConstraint(low, Relationship.GEQ,
                    request.candidates().get(i).minQuantity()));

            double[] high = new double[variables];
            high[i] = 1.0;
            high[i + candidates] = -1.0;
            constraints.add(new LinearConstraint(high, Relationship.LEQ,
                    request.candidates().get(i).maxQuantity()));
        }

        for (int k = 0; k < buckets; k++) {
            double[] upper = new double[variables];
            double[] lower = new double[variables];
            for (int i = 0; i < candidates; i++) {
                upper[i] = h[k][i];
                upper[i + candidates] = -h[k][i];
                lower[i] = -h[k][i];
                lower[i + candidates] = h[k][i];
            }
            upper[tIndex] = -request.riskLimits().get(k);
            lower[tIndex] = -request.riskLimits().get(k);
            constraints.add(new LinearConstraint(upper, Relationship.LEQ, -b[k]));
            constraints.add(new LinearConstraint(lower, Relationship.LEQ, b[k]));
        }

        double[] budgetCoefficients = new double[variables];
        for (int i = 0; i < candidates; i++) {
            budgetCoefficients[i] = prices[i];
            budgetCoefficients[i + candidates] = prices[i];
        }
        constraints.add(new LinearConstraint(budgetCoefficients, Relationship.LEQ,
                request.budget()));

        double[] objectiveCoefficients = new double[variables];
        objectiveCoefficients[tIndex] = 1.0;

        PointValuePair optimum;
        try {
            optimum = new SimplexSolver(SOLVER_EPSILON, SOLVER_ULPS, 1.0e-12)
                    .optimize(new LinearObjectiveFunction(objectiveCoefficients, 0.0),
                            new LinearConstraintSet(constraints),
                            GoalType.MINIMIZE,
                            new NonNegativeConstraint(true));
        } catch (NoFeasibleSolutionException ex) {
            throw new InfeasibleHedgeException(
                    "no trade satisfies quantity bounds and the non-negative cash budget");
        } catch (MathRuntimeException ex) {
            throw new HedgeNumericalException("linear program failed numerically", ex);
        }

        double[] point = optimum.getPoint();
        double[] quantities = new double[candidates];
        for (int i = 0; i < candidates; i++) {
            quantities[i] = cleanSigned(point[i], point[i + candidates], request, i);
        }
        return verified(request, model, quantities, optimum.getValue());
    }

    private double cleanSigned(double longAmount, double shortAmount, HedgeRequest request, int i) {
        double value = longAmount - shortAmount;
        double min = request.candidates().get(i).minQuantity();
        double max = request.candidates().get(i).maxQuantity();
        if (value < min && value >= min - VALIDATION_EPSILON) {
            return min;
        }
        if (value > max && value <= max + VALIDATION_EPSILON) {
            return max;
        }
        if (Math.abs(value) < VALIDATION_EPSILON) {
            return 0.0;
        }
        return value;
    }

    private HedgeSolution verified(HedgeRequest request, HedgeRiskModel model,
                                   double[] quantities, double objective) {
        int candidates = quantities.length;
        double turnover = 0.0;
        for (int i = 0; i < candidates; i++) {
            double x = quantities[i];
            if (!Double.isFinite(x)) {
                throw new HedgeNumericalException("optimizer returned a non-finite quantity", null);
            }
            double min = request.candidates().get(i).minQuantity();
            double max = request.candidates().get(i).maxQuantity();
            double scale = Math.max(1.0, Math.max(Math.abs(min), Math.abs(max)));
            if (x < min - VALIDATION_EPSILON * scale || x > max + VALIDATION_EPSILON * scale) {
                throw new HedgeNumericalException("optimizer violated a quantity bound", null);
            }
            quantities[i] = Math.min(Math.max(x, min), max);
            turnover += model.candidateDirtyPrices()[i] * Math.abs(quantities[i]);
        }
        double budgetScale = Math.max(1.0, request.budget());
        if (turnover > request.budget() + VALIDATION_EPSILON * budgetScale) {
            throw new HedgeNumericalException("optimizer violated the cash budget", null);
        }

        double[] before = model.portfolioExposure();
        double[][] h = model.candidateRisk();
        double[] after = new double[before.length];
        double worst = 0.0;
        for (int k = 0; k < before.length; k++) {
            double residual = before[k];
            for (int i = 0; i < candidates; i++) {
                residual += h[k][i] * quantities[i];
            }
            after[k] = residual;
            double standardized = Math.abs(residual) / request.riskLimits().get(k);
            worst = Math.max(worst, standardized);
        }
        if (!Double.isFinite(worst) || Math.abs(worst - Math.max(0.0, objective))
                > VALIDATION_EPSILON * Math.max(1.0, worst)) {
            throw new HedgeNumericalException("optimizer objective failed verification", null);
        }
        List<String> candidateIds = request.candidates().stream().map(Candidate::id).toList();
        return new HedgeSolution(candidateIds, model.quoteIds(), quantities, before, after,
                worst, turnover);
    }
}
