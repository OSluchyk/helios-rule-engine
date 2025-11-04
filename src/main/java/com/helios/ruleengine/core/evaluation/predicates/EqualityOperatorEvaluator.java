package com.helios.ruleengine.core.evaluation.predicates;

import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.model.Predicate;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Objects;

/**
 * L5-LEVEL EVALUATOR: Specialized for EQUAL_TO, NOT_EQUAL_TO, IS_ANY_OF.
 *
 * DESIGN:
 * - Handles all dictionary-encoded equality lookups
 * - IS_ANY_OF is expanded to EQUAL_TO by RuleCompiler, so this
 * evaluator only needs to handle EQUAL_TO and NOT_EQUAL_TO.
 *
 * ✅ RECOMMENDATION 3 / BUG FIX:
 * - Added case for `NOT_EQUAL_TO`.
 * - Fixed bug: Correctly uses `model.getPredicateId(p)` instead of `p.getId()`.
 * - Fixed bug: Correctly increments `predicatesEvaluatedCount`.
 *
 * @author Google L5 Engineering Standards
 */
public final class EqualityOperatorEvaluator {

    private final EngineModel model;

    public EqualityOperatorEvaluator(EngineModel model) {
        this.model = model;
    }

    /**
     * Evaluate EQUAL_TO and NOT_EQUAL_TO predicates for a given field and value.
     */
    public void evaluateEquality(int fieldId, Object eventValue,
                                 EvaluationContext ctx, IntSet eligiblePredicateIds) {

        // Get all predicates associated with this field
        List<Predicate> predicates = model.getFieldToPredicates().get(fieldId);
        if (predicates == null || predicates.isEmpty()) {
            return;
        }

        for (Predicate pred : predicates) {
            // ✅ BUG FIX: Get predId *from the model* before any checks
            int predId = model.getPredicateId(pred);

            // Check if this predicate is in the eligible set
            if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(predId)) {
                continue;
            }

            boolean matched = false;
            switch (pred.operator()) {
                case EQUAL_TO:
                    // 'value' from predicate is already dictionary-encoded (if it was a string)
                    matched = Objects.equals(eventValue, pred.value());
                    ctx.incrementPredicatesEvaluatedCount(); // ✅ FIX: Count this evaluation
                    break;

                case NOT_EQUAL_TO: // ✅ ADDED
                    // 'value' from predicate is already dictionary-encoded
                    matched = !Objects.equals(eventValue, pred.value());
                    ctx.incrementPredicatesEvaluatedCount(); // ✅ FIX: Count this evaluation
                    break;

                // IS_ANY_OF is expanded to EQUAL_TO by the compiler,
                // so we don't need a case for it here.
                default:
                    // Not an equality operator, skip it
                    continue; // Do not count as an evaluation for this evaluator
            }

            // If matched, add to true predicates
            if (matched) {
                ctx.addTruePredicate(predId);
            }
        }
    }
}

