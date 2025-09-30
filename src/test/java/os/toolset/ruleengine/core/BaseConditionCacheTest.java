package os.toolset.ruleengine.core;

import org.junit.jupiter.api.*;
import os.toolset.ruleengine.core.cache.BaseConditionCache;
import os.toolset.ruleengine.core.cache.InMemoryBaseConditionCache;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for base condition cache functionality.
 * Verifies cache hit rates, performance improvements, and correctness.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BaseConditionCacheTest {

    private EngineModel model;
    private RuleEvaluator evaluatorWithCache;
    private RuleEvaluator evaluatorNoCache;
    private BaseConditionCache cache;
    private static Path tempDir;

    @BeforeAll
    static void beforeAll() throws IOException {
        tempDir = Files.createTempDirectory("rule_engine_cache_test");
    }

    @AfterAll
    static void afterAll() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path rulesFile = tempDir.resolve("cache_test_rules.json");
        Files.writeString(rulesFile, getCacheTestRulesJson());

        model = new RuleCompiler().compile(rulesFile);

        // Create cache with small size to test eviction
        cache = new InMemoryBaseConditionCache.Builder()
                .maxSize(100)
                .defaultTtl(1, TimeUnit.MINUTES)
                .build();

        evaluatorWithCache = new RuleEvaluator(model, cache, true);
        evaluatorNoCache = new RuleEvaluator(model, null, false);
    }

    @Test
    @Order(1)
    @DisplayName("Should reduce predicate evaluations with base condition cache")
    void testPredicateReduction() {
        // First evaluation - cache miss
        Event event1 = new Event("evt1", "ORDER", Map.of(
                "country", "US",
                "status", "ACTIVE",
                "customer_type", "PREMIUM",
                "amount", 5000
        ));

        MatchResult result1 = evaluatorWithCache.evaluate(event1);
        assertThat(result1.predicatesEvaluated()).isGreaterThan(0);
        int firstEvalPredicates = result1.predicatesEvaluated();

        // Second evaluation with same base conditions - cache hit
        Event event2 = new Event("evt2", "ORDER", Map.of(
                "country", "US",
                "status", "ACTIVE",
                "customer_type", "PREMIUM",
                "amount", 8000  // Different amount, but same base conditions
        ));

        MatchResult result2 = evaluatorWithCache.evaluate(event2);

        // Should evaluate fewer predicates due to cache hit on base conditions
        assertThat(result2.predicatesEvaluated()).isLessThan(firstEvalPredicates);

        // Verify cache metrics
        Map<String, Object> cacheMetrics = evaluatorWithCache.getCacheMetrics();
        assertThat(cacheMetrics).containsKey("cacheHitRate");
        double hitRate = (double) cacheMetrics.get("cacheHitRate");
        assertThat(hitRate).isGreaterThan(0.0);
    }

    @Test
    @Order(2)
    @DisplayName("Should maintain correctness with caching")
    void testCorrectnessWithCache() {
        // Generate test events
        List<Event> testEvents = generateTestEvents(100);

        // Evaluate with both cached and non-cached evaluators
        for (Event event : testEvents) {
            MatchResult cachedResult = evaluatorWithCache.evaluate(event);
            MatchResult nonCachedResult = evaluatorNoCache.evaluate(event);

            // Results should be identical
            assertThat(cachedResult.matchedRules().size())
                    .isEqualTo(nonCachedResult.matchedRules().size());

            // Verify rule codes match
            Set<String> cachedRuleCodes = cachedResult.matchedRules().stream()
                    .map(MatchResult.MatchedRule::ruleCode)
                    .collect(java.util.stream.Collectors.toSet());

            Set<String> nonCachedRuleCodes = nonCachedResult.matchedRules().stream()
                    .map(MatchResult.MatchedRule::ruleCode)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(cachedRuleCodes).isEqualTo(nonCachedRuleCodes);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Should achieve high cache hit rate for similar events")
    void testCacheHitRate() {
        // Warm up cache with initial events
        for (int i = 0; i < 10; i++) {
            Event warmupEvent = new Event("warmup_" + i, "ORDER", Map.of(
                    "country", "US",
                    "status", "ACTIVE",
                    "customer_type", i % 2 == 0 ? "PREMIUM" : "STANDARD",
                    "amount", 1000 * (i + 1)
            ));
            evaluatorWithCache.evaluate(warmupEvent);
        }

        // Reset metrics
        Map<String, Object> initialMetrics = evaluatorWithCache.getCacheMetrics();
        long initialEvals = (long) initialMetrics.get("totalEvaluations");

        // Evaluate similar events (same base conditions)
        for (int i = 0; i < 100; i++) {
            Event event = new Event("test_" + i, "ORDER", Map.of(
                    "country", "US",  // Same base condition
                    "status", "ACTIVE",  // Same base condition
                    "customer_type", i % 2 == 0 ? "PREMIUM" : "STANDARD",  // Alternating
                    "amount", 1000 + i * 10  // Different amounts
            ));
            evaluatorWithCache.evaluate(event);
        }

        // Check cache hit rate
        Map<String, Object> finalMetrics = evaluatorWithCache.getCacheMetrics();
        double cacheHitRate = (double) finalMetrics.get("cacheHitRate");

        // Should achieve >90% cache hit rate for similar events
        assertThat(cacheHitRate).isGreaterThan(0.9);

        System.out.println("Cache Hit Rate: " + String.format("%.2f%%", cacheHitRate * 100));
    }

    @Test
    @Order(4)
    @DisplayName("Should improve performance with caching")
    void testPerformanceImprovement() {
        // Generate consistent test events
        List<Event> testEvents = generateTestEvents(1000);

        // Warm up both evaluators
        for (int i = 0; i < 100; i++) {
            Event event = testEvents.get(i);
            evaluatorWithCache.evaluate(event);
            evaluatorNoCache.evaluate(event);
        }

        // Measure with cache
        long startCached = System.nanoTime();
        int totalPredicatesCached = 0;
        for (Event event : testEvents) {
            MatchResult result = evaluatorWithCache.evaluate(event);
            totalPredicatesCached += result.predicatesEvaluated();
        }
        long timeCached = System.nanoTime() - startCached;

        // Measure without cache
        long startNoCached = System.nanoTime();
        int totalPredicatesNoCached = 0;
        for (Event event : testEvents) {
            MatchResult result = evaluatorNoCache.evaluate(event);
            totalPredicatesNoCached += result.predicatesEvaluated();
        }
        long timeNoCached = System.nanoTime() - startNoCached;

        // Cache should reduce evaluation time
        double speedup = (double) timeNoCached / timeCached;
        double predicateReduction = 1.0 - ((double) totalPredicatesCached / totalPredicatesNoCached);

        System.out.println(String.format(
                "Performance with cache:\n" +
                        "  Time speedup: %.2fx\n" +
                        "  Predicate reduction: %.1f%%\n" +
                        "  Cached predicates: %d\n" +
                        "  Non-cached predicates: %d",
                speedup, predicateReduction * 100,
                totalPredicatesCached, totalPredicatesNoCached
        ));

        // Should achieve significant predicate reduction
        assertThat(predicateReduction).isGreaterThan(0.5); // >50% reduction
    }

    @Test
    @Order(5)
    @DisplayName("Should handle cache eviction correctly")
    void testCacheEviction() {
        // Fill cache beyond capacity
        for (int i = 0; i < 150; i++) {  // Cache max size is 100
            Event event = new Event("evict_" + i, "ORDER", Map.of(
                    "country", "COUNTRY_" + i,  // Unique base condition
                    "status", "STATUS_" + i,     // Unique base condition
                    "amount", 1000
            ));
            evaluatorWithCache.evaluate(event);
        }

        // Check cache metrics
        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();

        // Cache size should not exceed max
        assertThat(metrics.size()).isLessThanOrEqualTo(100);

        // Should have evictions
        assertThat(metrics.evictions()).isGreaterThan(0);
    }

    @Test
    @Order(6)
    @DisplayName("Should handle concurrent access correctly")
    void testConcurrentAccess() throws InterruptedException {
        int numThreads = 10;
        int eventsPerThread = 100;
        List<Thread> threads = new ArrayList<>();
        Map<String, Set<String>> results = new java.util.concurrent.ConcurrentHashMap<>();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < eventsPerThread; i++) {
                    Event event = new Event(
                            "thread_" + threadId + "_evt_" + i,
                            "ORDER",
                            Map.of(
                                    "country", threadId % 2 == 0 ? "US" : "UK",
                                    "status", "ACTIVE",
                                    "amount", 1000 * (i + 1)
                            )
                    );

                    MatchResult result = evaluatorWithCache.evaluate(event);

                    Set<String> ruleCodes = result.matchedRules().stream()
                            .map(MatchResult.MatchedRule::ruleCode)
                            .collect(java.util.stream.Collectors.toSet());

                    results.put(event.getEventId(), ruleCodes);
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify no errors occurred
        assertThat(results).hasSize(numThreads * eventsPerThread);

        // Check cache is still functioning
        BaseConditionCache.CacheMetrics metrics = cache.getMetrics();
        assertThat(metrics.hits()).isGreaterThan(0);
    }

    private List<Event> generateTestEvents(int count) {
        List<Event> events = new ArrayList<>();
        Random random = new Random(42); // Fixed seed for reproducibility

        String[] countries = {"US", "UK", "CA"};
        String[] statuses = {"ACTIVE", "INACTIVE"};
        String[] customerTypes = {"STANDARD", "PREMIUM", "VIP"};

        for (int i = 0; i < count; i++) {
            events.add(new Event(
                    "evt_" + i,
                    "ORDER",
                    Map.of(
                            "country", countries[random.nextInt(countries.length)],
                            "status", statuses[random.nextInt(statuses.length)],
                            "customer_type", customerTypes[random.nextInt(customerTypes.length)],
                            "amount", random.nextInt(10000)
                    )
            ));
        }

        return events;
    }

    private String getCacheTestRulesJson() {
        // Generate rules with shared base conditions
        StringBuilder json = new StringBuilder("[");

        String[] countries = {"US", "UK", "CA"};
        String[] statuses = {"ACTIVE", "INACTIVE"};
        String[] customerTypes = {"STANDARD", "PREMIUM", "VIP"};

        int ruleId = 0;
        for (String country : countries) {
            for (String status : statuses) {
                for (String customerType : customerTypes) {
                    if (ruleId > 0) json.append(",");

                    json.append(String.format("""
                        {
                            "rule_code": "RULE_%d",
                            "priority": %d,
                            "description": "Rule for %s %s %s customers",
                            "conditions": [
                                {"field": "country", "operator": "EQUAL_TO", "value": "%s"},
                                {"field": "status", "operator": "EQUAL_TO", "value": "%s"},
                                {"field": "customer_type", "operator": "EQUAL_TO", "value": "%s"},
                                {"field": "amount", "operator": "GREATER_THAN", "value": %d}
                            ]
                        }""",
                            ruleId++,
                            100 - ruleId,
                            country, status, customerType,
                            country, status, customerType,
                            ruleId * 100
                    ));
                }
            }
        }

        json.append("]");
        return json.toString();
    }
}