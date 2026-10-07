package com.hedge257;

import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NoFeasibleSolutionException;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.linear.UnboundedSolutionException;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

import java.util.ArrayList;
import java.util.List;

/**
 * Global minimax linear program over candidate trades. Each signed trade x_j
 * is split into non-negative buy u_j and sell v_j legs, x_j = u_j - v_j;
 * the split is exact because the turnover budget prices both legs equally and
 * every other constraint is linear in x. Short sales, duplicate and linearly
 * dependent candidates are all allowed.
 *
 * Variables: u_0..u_{m-1}, v_0..v_{m-1}, z (worst standardized residual).
 */
final class HedgeOptimizer {
    private static final int MAX_ITERATIONS = 200_000;
    private static final double LP_EPSILON = 1.0e-12;

    private HedgeOptimizer() {
    }

    static Solution solve(double[] exposure, double[][] risk, double[] limits,
                          double[] minQuantity, double[] maxQuantity,
                          double[] unitDirty, double budget) {
        int buckets = exposure.length;
        int candidates = minQuantity.length;
        int variables = 2 * candidates + 1;
        int zIndex = variables - 1;

        double[] objective = new double[variables];
        objective[zIndex] = 1.0;

        List<LinearConstraint> constraints = new ArrayList<>(2 * buckets + 1 + 2 * candidates);
        for (int k = 0; k < buckets; k++) {
            double[] plus = new double[variables];
            double[] minus = new double[variables];
            for (int j = 0; j < candidates; j++) {
                double coefficient = risk[k][j] / limits[k];
                plus[j] = coefficient;
                plus[candidates + j] = -coefficient;
                minus[j] = -coefficient;
                minus[candidates + j] = coefficient;
            }
            plus[zIndex] = -1.0;
            minus[zIndex] = -1.0;
            constraints.add(new LinearConstraint(plus, Relationship.LEQ,
                    -exposure[k] / limits[k]));
            constraints.add(new LinearConstraint(minus, Relationship.LEQ,
                    exposure[k] / limits[k]));
        }

        double[] budgetRow = new double[variables];
        for (int j = 0; j < candidates; j++) {
            budgetRow[j] = unitDirty[j];
            budgetRow[candidates + j] = unitDirty[j];
        }
        constraints.add(new LinearConstraint(budgetRow, Relationship.LEQ, budget));

        for (int j = 0; j < candidates; j++) {
            double[] buyCap = new double[variables];
            buyCap[j] = 1.0;
            constraints.add(new LinearConstraint(buyCap, Relationship.LEQ,
                    Math.max(0.0, maxQuantity[j])));
            double[] buyFloor = new double[variables];
            buyFloor[j] = 1.0;
            constraints.add(new LinearConstraint(buyFloor, Relationship.GEQ,
                    Math.max(0.0, minQuantity[j])));
            double[] sellCap = new double[variables];
            sellCap[candidates + j] = 1.0;
            constraints.add(new LinearConstraint(sellCap, Relationship.LEQ,
                    Math.max(0.0, -minQuantity[j])));
            double[] sellFloor = new double[variables];
            sellFloor[candidates + j] = 1.0;
            constraints.add(new LinearConstraint(sellFloor, Relationship.GEQ,
                    Math.max(0.0, -maxQuantity[j])));
        }

        SimplexSolver solver = new SimplexSolver(LP_EPSILON);
        PointValuePair optimum;
        try {
            optimum = solver.optimize(new MaxIter(MAX_ITERATIONS), GoalType.MINIMIZE,
                    new LinearObjectiveFunction(objective, 0.0),
                    new LinearConstraintSet(constraints),
                    new NonNegativeConstraint(true));
        } catch (NoFeasibleSolutionException ex) {
            throw new HedgeInfeasibleException(
                    "no trade vector satisfies the trade bounds and turnover budget");
        } catch (UnboundedSolutionException | org.apache.commons.math3.exception.TooManyIterationsException ex) {
            throw new HedgeNumericalException("linear program did not converge: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new HedgeNumericalException(
                    "linear program failed numerically: " + ex.getMessage(), ex);
        }

        double[] point = optimum.getPoint();
        double[] quantities = new double[candidates];
        for (int j = 0; j < candidates; j++) {
            quantities[j] = point[j] - point[candidates + j];
            if (!Double.isFinite(quantities[j])) {
                throw new HedgeNumericalException("solver returned a non-finite quantity for candidate " + j);
            }
        }
        if (!Double.isFinite(optimum.getValue()) || optimum.getValue() < -LP_EPSILON) {
            throw new HedgeNumericalException(
                    "solver returned an invalid worst residual " + optimum.getValue());
        }
        return new Solution(quantities, Math.max(0.0, optimum.getValue()));
    }

    record Solution(double[] quantities, double worstResidual) {
    }
}
