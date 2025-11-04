package com.helios.ruleengine.core.evaluation.predicates;

import com.helios.ruleengine.core.evaluation.context.EvaluationContext;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.model.Predicate;
import it.unimi.dsi.fastutil.ints.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StringOperatorEvaluator {
    private final EngineModel model;
    private final Map<Integer, FieldEvaluator> fieldEvaluators;
    private long candidatesFiltered = 0;
    private long fullVerifications = 0;

    public StringOperatorEvaluator(EngineModel model) {
        this.model = model;
        this.fieldEvaluators = new ConcurrentHashMap<>();
        initializeEvaluators();
    }

    private void initializeEvaluators() {
        model.getFieldToPredicates().forEach((fieldId, predicates) -> {
            List<StringPredicate> stringPredicates = predicates.stream()
                    .filter(p -> p.operator() == Predicate.Operator.CONTAINS)
                    .map(p -> new StringPredicate(model.getPredicateId(p), String.valueOf(p.value())))
                    .toList();

            if (!stringPredicates.isEmpty()) {
                fieldEvaluators.put(fieldId, new FieldEvaluator(stringPredicates));
            }
        });
    }

    public void evaluateContains(int fieldId, String value,
                                 EvaluationContext ctx, IntSet eligiblePredicateIds) {
        FieldEvaluator evaluator = fieldEvaluators.get(fieldId);
        if (evaluator == null) return;

        IntSet matches = evaluator.evaluate(value, eligiblePredicateIds);
        matches.forEach((int predId) -> {
            ctx.addTruePredicate(predId);
            ctx.incrementPredicatesEvaluatedCount();
        });
    }

    private class FieldEvaluator {
        private final StringPredicate[] predicates;
        private final Map<String, IntList> bigramIndex;

        FieldEvaluator(List<StringPredicate> preds) {
            this.predicates = preds.toArray(new StringPredicate[0]);
            this.bigramIndex = buildBigramIndex(predicates);
        }

        private Map<String, IntList> buildBigramIndex(StringPredicate[] preds) {
            Map<String, IntList> index = new HashMap<>();
            for (int i = 0; i < preds.length; i++) {
                String pattern = preds[i].pattern;
                if (pattern.length() >= 2) {
                    for (int j = 0; j < pattern.length() - 1; j++) {
                        String bigram = pattern.substring(j, j + 2);
                        index.computeIfAbsent(bigram, k -> new IntArrayList()).add(i);
                    }
                } else {
                    // Short patterns - check against all
                    index.computeIfAbsent("", k -> new IntArrayList()).add(i);
                }
            }
            return index;
        }

        IntSet evaluate(String value, IntSet eligiblePredicateIds) {
            IntSet matches = new IntOpenHashSet();
            Set<Integer> candidates = new HashSet<>();

            // Gather candidates from bigrams
            if (value.length() >= 2) {
                for (int i = 0; i < value.length() - 1; i++) {
                    String bigram = value.substring(i, i + 2);
                    IntList predIndices = bigramIndex.get(bigram);
                    if (predIndices != null) {
                        predIndices.forEach((int idx) -> candidates.add(idx));
                    }
                }
            }

            // Always check short patterns
            IntList shortPatterns = bigramIndex.get("");
            if (shortPatterns != null) {
                shortPatterns.forEach((int idx) -> candidates.add(idx));
            }

            candidatesFiltered += candidates.size();

            // Full verification
            for (int idx : candidates) {
                StringPredicate pred = predicates[idx];
                if (eligiblePredicateIds != null && !eligiblePredicateIds.contains(pred.id)) {
                    continue;
                }
                if (value.contains(pred.pattern)) {
                    matches.add(pred.id);
                }
                fullVerifications++;
            }

            return matches;
        }
    }

    private record StringPredicate(int id, String pattern) {
    }

    public Metrics getMetrics() {
        return new Metrics(candidatesFiltered, fullVerifications);
    }

    public record Metrics(long candidatesFiltered, long fullVerifications) {
    }
}
