package os.toolset.ruleengine.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.roaringbitmap.RoaringBitmap;
import os.toolset.ruleengine.model.Predicate;
import os.toolset.ruleengine.model.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable compiled rule engine model using high-performance collections.
 */
public final class EngineModel {
    private final Object2IntMap<Predicate> predicateRegistry;
    private final Int2ObjectMap<Predicate> predicateLookup;
    private final Int2ObjectMap<RoaringBitmap> invertedIndex;
    private final Rule[] ruleStore;
    // A rule code can map to multiple internal rules after expansion
    private final Object2ObjectMap<String, List<Rule>> rulesByCode;
    private final EngineStats stats;

    private EngineModel(Builder builder) {
        this.predicateRegistry = builder.predicateRegistry;
        this.predicateLookup = builder.predicateLookup;
        this.invertedIndex = builder.invertedIndex;
        this.ruleStore = builder.ruleStore.toArray(new Rule[0]);
        this.rulesByCode = builder.rulesByCode;
        this.stats = builder.stats;
    }

    public Object2IntMap<Predicate> getPredicateRegistry() { return predicateRegistry; }
    public Int2ObjectMap<RoaringBitmap> getInvertedIndex() { return invertedIndex; }
    public Rule[] getRuleStore() { return ruleStore.clone(); }
    public Rule getRule(int id) { return id >= 0 && id < ruleStore.length ? ruleStore[id] : null; }
    public Predicate getPredicate(int id) { return predicateLookup.get(id); }
    public int getPredicateId(Predicate p) { return predicateRegistry.getInt(p); }
    public List<Rule> getRulesByCode(String code) { return rulesByCode.get(code); }
    public EngineStats getStats() { return stats; }

    public static class Builder {
        final Object2IntMap<Predicate> predicateRegistry = new Object2IntOpenHashMap<>();
        final Int2ObjectMap<Predicate> predicateLookup = new Int2ObjectOpenHashMap<>();
        final Int2ObjectMap<RoaringBitmap> invertedIndex = new Int2ObjectOpenHashMap<>();
        final List<Rule> ruleStore = new ArrayList<>();
        final Object2ObjectMap<String, List<Rule>> rulesByCode = new Object2ObjectOpenHashMap<>();
        EngineStats stats;

        public int registerPredicate(Predicate predicate) {
            return predicateRegistry.computeIfAbsent(predicate, (Predicate p) -> {
                int id = predicateRegistry.size();
                predicateLookup.put(id, p);
                return id;
            });
        }

        public int getPredicateCount() {
            return predicateRegistry.size();
        }

        public Builder addRule(Rule rule) {
            ruleStore.add(rule);
            rulesByCode.computeIfAbsent(rule.getRuleCode(), k -> new ArrayList<>()).add(rule);

            for (int predicateId : rule.getPredicateIds()) {
                invertedIndex.computeIfAbsent(predicateId, k -> new RoaringBitmap()).add(rule.getId());
            }
            return this;
        }

        public Builder withStats(EngineStats stats) {
            this.stats = stats;
            return this;
        }

        public EngineModel build() {
            predicateRegistry.defaultReturnValue(-1);
            return new EngineModel(this);
        }
    }

    public record EngineStats(
            int totalRules,
            int totalPredicates,
            long compilationTimeNanos,
            Map<String, Object> metadata
    ) {}
}

