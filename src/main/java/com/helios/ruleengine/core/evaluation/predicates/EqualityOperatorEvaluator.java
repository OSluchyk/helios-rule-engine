package com.helios.ruleengine.core.evaluation.predicates;

import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.model.Predicate;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.List;

public final class EqualityOperatorEvaluator {
    private final EngineModel model;

    public EqualityOperatorEvaluator(EngineModel model) {
        this.model = model;
    }

    /**
     * Evaluate EQUAL_TO and IS_ANY_OF predicates.
     * Note: IS_ANY_OF is expanded at compile time, so it's evaluated as EQUAL_TO here.
     */
    public void evaluateEquality(int fieldId, Object value,
                                 EvaluationContext ctx, IntSet eligiblePredicateIds) {
        List<Predicate> predicates = model.getFieldToPredicates().get(fieldId);
        if (predicates == null) return;

        for (Predicate p : predicates) {
            if (p.operator() != Predicate.Operator.EQUAL_TO) {
                continue; // Only handle EQUAL_TO here
            }

            int predId = model.getPredicateId(p);
            if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(predId)) {
                continue;
            }

            ctx.incrementPredicatesEvaluatedCount();
            if (p.evaluate(value)) {
                ctx.addTruePredicate(predId);
            }
        }
    }
}
