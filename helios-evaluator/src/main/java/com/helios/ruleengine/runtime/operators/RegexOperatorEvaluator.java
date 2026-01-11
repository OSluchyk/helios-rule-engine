package com.helios.ruleengine.runtime.operators;

import com.helios.ruleengine.runtime.context.EvaluationContext;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.api.model.Predicate;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RegexOperatorEvaluator {
    private static final Logger logger = Logger.getLogger(RegexOperatorEvaluator.class.getName());

    private final EngineModel model;
    private final Map<Integer, FieldEvaluator> fieldEvaluators;
    private long successfulMatches = 0;
    private long failedMatches = 0;
    private long errors = 0;

    public RegexOperatorEvaluator(EngineModel model) {
        this.model = model;
        this.fieldEvaluators = new ConcurrentHashMap<>();
        initializeEvaluators();
    }

    private void initializeEvaluators() {
        model.getFieldToPredicates().forEach((fieldId, predicates) -> {
            List<RegexPredicate> regexPredicates = predicates.stream()
                    .filter(p -> p.operator() == Predicate.Operator.REGEX)
                    .map(p -> {
                        try {
                            String patternStr = (String) p.value();
                            Pattern compiled = Pattern.compile(patternStr);
                            return new RegexPredicate(model.getPredicateId(p), compiled, patternStr);
                        } catch (PatternSyntaxException e) {
                            logger.warning("Invalid regex pattern: " + p.value());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (!regexPredicates.isEmpty()) {
                fieldEvaluators.put(fieldId, new FieldEvaluator(regexPredicates));
            }
        });
    }

    public void evaluateRegex(int fieldId, String value,
                              EvaluationContext ctx, IntSet eligiblePredicateIds) {
        FieldEvaluator evaluator = fieldEvaluators.get(fieldId);
        if (evaluator == null) return;

        // Count all predicates that will be evaluated (not just matches)
        int predicatesEvaluatedCount = evaluator.countEligiblePredicates(eligiblePredicateIds);
        ctx.addPredicatesEvaluated(predicatesEvaluatedCount);

        IntSet matches = evaluator.evaluate(value, eligiblePredicateIds);
        matches.forEach((int predId) -> {
            ctx.addTruePredicate(predId);
        });
    }

    private class FieldEvaluator {
        private final RegexPredicate[] predicates;

        FieldEvaluator(List<RegexPredicate> preds) {
            this.predicates = preds.toArray(new RegexPredicate[0]);
        }

        IntSet evaluate(String value, IntSet eligiblePredicateIds) {
            IntSet matches = new IntOpenHashSet();

            for (RegexPredicate pred : predicates) {
                if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(pred.id)) {
                    continue;
                }

                try {
                    if (pred.compiledPattern.matcher(value).matches()) {
                        matches.add(pred.id);
                        successfulMatches++;
                    } else {
                        failedMatches++;
                    }
                } catch (Exception e) {
                    errors++;
                    logger.warning("Regex eval error for predicate " + pred.id + ": " + e.getMessage());
                }
            }

            return matches;
        }

        /**
         * Count the number of predicates that will be evaluated for this field.
         * Used for accurate statistics tracking.
         */
        int countEligiblePredicates(IntSet eligiblePredicateIds) {
            if (eligiblePredicateIds == null) {
                return predicates.length;
            }
            int count = 0;
            for (RegexPredicate pred : predicates) {
                if (eligiblePredicateIds.contains(pred.id)) {
                    count++;
                }
            }
            return count;
        }
    }

    private record RegexPredicate(int id, Pattern compiledPattern, String rawPattern) {}

    public Metrics getMetrics() {
        return new Metrics(successfulMatches, failedMatches, errors);
    }

    public record Metrics(long successfulMatches, long failedMatches, long errors) {}
}
