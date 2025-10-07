package com.helios.ruleengine.benchmark;

import com.helios.ruleengine.core.evaluation.RuleEvaluator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import com.helios.ruleengine.core.model.EngineModel;
import com.helios.ruleengine.core.compiler.DefaultRuleCompiler;
import com.helios.ruleengine.core.cache.BaseConditionCache;
import com.helios.ruleengine.core.cache.InMemoryBaseConditionCache;
import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Development Performance Benchmark - 2-3 Minute Runtime
 *
 * DESIGN RATIONALE:
 * - Total runtime: 2-3 minutes for comprehensive feedback
 * - Progressive testing: Simple → Medium → Complex scenarios
 * - Real-world cache patterns: Cold start → Warm → Hot
 * - Memory pressure testing without OOM
 * - Deduplication effectiveness validation
 *
 * RUNTIME BREAKDOWN (Default Configuration):
 * - Setup & Compilation: ~5 seconds
 * - Warmup Phase: 20 seconds (10 iterations × 2 sec)
 * - Measurement Phase: 100 seconds (20 iterations × 5 sec)
 * - Analysis & Reporting: ~5 seconds
 * - TOTAL: ~130 seconds (2m 10s)
 *
 * USAGE:
 *   # Standard 2-minute run
 *   mvn clean test-compile exec:java \
 *     -Dexec.mainClass="com.helios.ruleengine.benchmark.SimpleBenchmark" \
 *     -Dexec.classpathScope=test
 *
 *   # Quick 1-minute run for rapid iteration
 *   mvn test-compile exec:java [...] -Dbench.quick=true
 *
 *   # Extended 3-minute run for thorough testing
 *   mvn test-compile exec:java [...] -Dbench.extended=true
 *
 * CONFIGURATION:
 *   -Dbench.quick     : 1-minute quick mode
 *   -Dbench.extended  : 3-minute extended mode
 *   -Dbench.rules     : Override rule count (default: progressive)
 *   -Dbench.profile   : Enable detailed profiling
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseCompactObjectHeaders",
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "--add-modules=jdk.incubator.vector",
        "-Xms8g",
        "-Xmx8g",
        "-XX:+AlwaysPreTouch",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2000"
})
@Warmup(iterations = 10, time = 2)    // 20 seconds warmup
@Measurement(iterations = 20, time = 5) // 100 seconds measurement
public class SimpleBenchmark {

    // Runtime modes
    private static final boolean QUICK_MODE = Boolean.getBoolean("bench.quick");
    private static final boolean EXTENDED_MODE = Boolean.getBoolean("bench.extended");
    private static final boolean PROFILE = Boolean.getBoolean("bench.profile");

    // Adjust iterations based on mode
    private static final int WARMUP_ITERATIONS = QUICK_MODE ? 5 : (EXTENDED_MODE ? 15 : 10);
    private static final int WARMUP_TIME = QUICK_MODE ? 1 : 2;
    private static final int MEASUREMENT_ITERATIONS = QUICK_MODE ? 10 : (EXTENDED_MODE ? 30 : 20);
    private static final int MEASUREMENT_TIME = QUICK_MODE ? 3 : 5;

    // Test parameters (progressive complexity)
    @Param({"500", "2000", "5000"})  // Progressive rule counts
    private int ruleCount;

    @Param({"MIXED"})  // Default to realistic mixed workload
    private String workloadType;

    @Param({"HOT", "WARM", "COLD"})  // Cache scenarios
    private String cacheScenario;

    // State
    private static final Tracer NOOP_TRACER = OpenTelemetry.noop().getTracer("noop");
    private RuleEvaluator evaluator;
    private List<Event> eventPool;
    private final AtomicInteger eventIndex = new AtomicInteger(0);

    // Metrics tracking
    private EngineModel model;
    private final LongAdder totalEvaluations = new LongAdder();
    private final LongAdder totalMatches = new LongAdder();
    private final List<Long> latencyHistory = Collections.synchronizedList(new ArrayList<>());
    private long setupStartTime;
    private long compilationTime;

    // Memory tracking
    private long initialMemory;
    private long peakMemory;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        setupStartTime = System.nanoTime();

        // Suppress logs for clean output
        java.util.logging.Logger.getLogger("io.opentelemetry")
                .setLevel(java.util.logging.Level.OFF);

        printHeader();

        // Track initial memory
        System.gc();
        initialMemory = getUsedMemory();

        // Create progressive rule set
        Path rulesPath = createProgressiveRules(ruleCount, workloadType);

        // Compile and measure
        long compileStart = System.nanoTime();
        DefaultRuleCompiler compiler = new DefaultRuleCompiler(NOOP_TRACER);
        model = compiler.compile(rulesPath);
        compilationTime = System.nanoTime() - compileStart;

        // Setup evaluator with cache
        BaseConditionCache cache = new InMemoryBaseConditionCache.Builder()
                .maxSize(50_000)
                .defaultTtl(5, TimeUnit.MINUTES)
                .build();

        evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        // Generate diverse event pool
        eventPool = generateProgressiveEvents(10_000);

        // Print setup summary
        printSetupSummary();

        Files.deleteIfExists(rulesPath);
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        // Reset per-iteration state
        eventIndex.set(0);

        // Simulate cache scenarios
        switch (cacheScenario) {
            case "COLD":
                // Clear cache, simulate cold start
                evaluator = new RuleEvaluator(model, NOOP_TRACER, true);
                break;
            case "WARM":
                // Partial warmup (10% of events)
                for (int i = 0; i < eventPool.size() / 10; i++) {
                    evaluator.evaluate(eventPool.get(i));
                }
                break;
            case "HOT":
                // Full warmup (50% of events)
                for (int i = 0; i < eventPool.size() / 2; i++) {
                    evaluator.evaluate(eventPool.get(i));
                }
                break;
        }
    }

    @TearDown(Level.Trial)
    public void teardownTrial() {
        // Track peak memory
        peakMemory = getUsedMemory();

        // Print comprehensive report
        printFinalReport();
    }

    // BENCHMARK 1: Throughput-focused batch evaluation
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput_batch100(Blackhole bh) {
        // Process batch of 100 events
        for (int i = 0; i < 100; i++) {
            Event event = getNextEvent();
            MatchResult result = evaluator.evaluate(event);
            bh.consume(result);

            // Track metrics
            totalEvaluations.increment();
            if (!result.matchedRules().isEmpty()) {
                totalMatches.increment();
            }
        }
    }

    // BENCHMARK 2: Latency-focused single evaluation
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public MatchResult latency_single() {
        Event event = getNextEvent();
        long start = System.nanoTime();
        MatchResult result = evaluator.evaluate(event);
        long latency = System.nanoTime() - start;

        // Track latency for percentile analysis
        if (latencyHistory.size() < 10_000) {
            latencyHistory.add(latency);
        }

        return result;
    }

    // BENCHMARK 3: Memory pressure test
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(4)  // Multi-threaded to stress memory
    public void throughput_concurrent(Blackhole bh) {
        Event event = getNextEvent();
        MatchResult result = evaluator.evaluate(event);
        bh.consume(result);
    }

    private Event getNextEvent() {
        int idx = eventIndex.getAndIncrement();
        return eventPool.get(idx % eventPool.size());
    }

    private void printHeader() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚀 DEVELOPMENT PERFORMANCE BENCHMARK");
        System.out.println("=".repeat(80));

        String mode = QUICK_MODE ? "QUICK (1 min)" :
                (EXTENDED_MODE ? "EXTENDED (3 min)" : "STANDARD (2 min)");

        System.out.printf("Mode:         %s\n", mode);
        System.out.printf("Total Time:   ~%d seconds\n",
                WARMUP_ITERATIONS * WARMUP_TIME + MEASUREMENT_ITERATIONS * MEASUREMENT_TIME + 10);
        System.out.printf("Warmup:       %d × %ds = %ds\n",
                WARMUP_ITERATIONS, WARMUP_TIME, WARMUP_ITERATIONS * WARMUP_TIME);
        System.out.printf("Measurement:  %d × %ds = %ds\n",
                MEASUREMENT_ITERATIONS, MEASUREMENT_TIME, MEASUREMENT_ITERATIONS * MEASUREMENT_TIME);
        System.out.println("=".repeat(80));
    }

    private void printSetupSummary() {
        System.out.println("\n📊 COMPILATION & MODEL STATISTICS:");
        System.out.println("─".repeat(50));

        Map<String, Object> metadata = model.getStats().metadata();

        System.out.printf("Compilation Time:      %.2f ms\n", compilationTime / 1_000_000.0);
        System.out.printf("Logical Rules:         %,d\n", metadata.get("logicalRules"));
        System.out.printf("Expanded Combinations: %,d\n", metadata.get("totalExpandedCombinations"));
        System.out.printf("Unique Combinations:   %,d\n", metadata.get("uniqueCombinations"));
        System.out.printf("Deduplication Rate:    %s%%\n", metadata.get("deduplicationRatePercent"));
        System.out.printf("Unique Predicates:     %,d\n", model.getPredicateRegistry().size());

        long estimatedMemory = estimateMemoryUsage();
        System.out.printf("Estimated Memory:      %.2f MB\n", estimatedMemory / (1024.0 * 1024.0));

        // Deduplication effectiveness
        int expanded = (int) metadata.get("totalExpandedCombinations");
        int unique = (int) metadata.get("uniqueCombinations");
        if (expanded > 0) {
            double compressionRatio = (double) expanded / unique;
            System.out.printf("Compression Ratio:     %.1fx\n", compressionRatio);
        }

        System.out.println("\n⚙️  TEST CONFIGURATION:");
        System.out.println("─".repeat(50));
        System.out.printf("Rule Count:            %,d\n", ruleCount);
        System.out.printf("Workload Type:         %s\n", workloadType);
        System.out.printf("Event Pool Size:       %,d\n", eventPool.size());
        System.out.printf("Cache Scenario:        %s\n", cacheScenario);
    }

    private void printFinalReport() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📈 FINAL PERFORMANCE REPORT");
        System.out.println("=".repeat(80));

        // Runtime summary
        long totalRuntime = System.nanoTime() - setupStartTime;
        System.out.printf("Total Runtime:         %.1f seconds\n", totalRuntime / 1_000_000_000.0);

        // Throughput metrics
        if (totalEvaluations.sum() > 0) {
            System.out.println("\n🎯 THROUGHPUT METRICS:");
            System.out.println("─".repeat(50));
            System.out.printf("Total Evaluations:     %,d\n", totalEvaluations.sum());
            System.out.printf("Total Matches:         %,d\n", totalMatches.sum());
            System.out.printf("Match Rate:            %.1f%%\n",
                    (totalMatches.sum() * 100.0) / totalEvaluations.sum());
        }

        // Latency percentiles
        if (!latencyHistory.isEmpty()) {
            System.out.println("\n⏱️  LATENCY PERCENTILES:");
            System.out.println("─".repeat(50));
            Collections.sort(latencyHistory);

            System.out.printf("P50 (Median):          %.1f µs\n", percentile(latencyHistory, 0.50) / 1000.0);
            System.out.printf("P90:                   %.1f µs\n", percentile(latencyHistory, 0.90) / 1000.0);
            System.out.printf("P95:                   %.1f µs\n", percentile(latencyHistory, 0.95) / 1000.0);
            System.out.printf("P99:                   %.1f µs\n", percentile(latencyHistory, 0.99) / 1000.0);
            System.out.printf("P99.9:                 %.1f µs\n", percentile(latencyHistory, 0.999) / 1000.0);
            System.out.printf("Max:                   %.1f µs\n",
                    latencyHistory.get(latencyHistory.size() - 1) / 1000.0);
        }

        // Memory analysis
        System.out.println("\n💾 MEMORY ANALYSIS:");
        System.out.println("─".repeat(50));
        System.out.printf("Initial Memory:        %.2f MB\n", initialMemory / (1024.0 * 1024.0));
        System.out.printf("Peak Memory:           %.2f MB\n", peakMemory / (1024.0 * 1024.0));
        System.out.printf("Memory Growth:         %.2f MB\n",
                (peakMemory - initialMemory) / (1024.0 * 1024.0));
        System.out.printf("Memory per Rule:       %.2f KB\n",
                (peakMemory - initialMemory) / (1024.0 * ruleCount));

        // Cache effectiveness
        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        if (metrics.containsKey("cacheHitRate")) {
            System.out.println("\n🎯 CACHE EFFECTIVENESS:");
            System.out.println("─".repeat(50));
            System.out.printf("Cache Hit Rate:        %.1f%%\n",
                    ((Double) metrics.get("cacheHitRate")) * 100);
            System.out.printf("Avg Predicates/Event:  %.1f\n",
                    metrics.getOrDefault("avgPredicatesPerEvent", 0.0));
            System.out.printf("Avg Rules/Event:       %.1f\n",
                    metrics.getOrDefault("avgRulesConsideredPerEvent", 0.0));
        }

        // Performance targets check
        System.out.println("\n✅ PERFORMANCE TARGETS:");
        System.out.println("─".repeat(50));

        boolean p99Target = !latencyHistory.isEmpty() &&
                percentile(latencyHistory, 0.99) < 800_000; // 800 µs = 0.8 ms
        boolean memoryTarget = (peakMemory - initialMemory) < 6L * 1024 * 1024 * 1024; // 6 GB

        System.out.printf("P99 < 0.8ms:           %s (actual: %.2f ms)\n",
                p99Target ? "✓ PASS" : "✗ FAIL",
                latencyHistory.isEmpty() ? 0.0 : percentile(latencyHistory, 0.99) / 1_000_000.0);

        System.out.printf("Memory < 6GB:          %s (actual: %.2f GB)\n",
                memoryTarget ? "✓ PASS" : "✗ FAIL",
                (peakMemory - initialMemory) / (1024.0 * 1024.0 * 1024.0));

        System.out.println("\n" + "=".repeat(80));
        System.out.println("🎉 Benchmark Complete!");
        System.out.println("=".repeat(80) + "\n");
    }

    private Path createProgressiveRules(int count, String type) throws IOException {
        Path path = Files.createTempFile("bench_rules_", ".json");
        List<String> rules = new ArrayList<>();
        Random rand = new Random(42);

        // Progressive complexity: mix gets more complex as count increases
        int simpleRatio = count <= 1000 ? 60 : (count <= 5000 ? 40 : 20);
        int mediumRatio = count <= 1000 ? 30 : (count <= 5000 ? 40 : 40);
        int complexRatio = 100 - simpleRatio - mediumRatio;

        for (int i = 0; i < count; i++) {
            int percentile = (i * 100) / count;

            if (percentile < simpleRatio) {
                // Simple rules
                rules.add(String.format(
                        "{\"rule_code\":\"S_%d\",\"priority\":%d,\"conditions\":[" +
                                "{\"field\":\"status\",\"operator\":\"EQUAL_TO\",\"value\":\"ACTIVE\"}," +
                                "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":%d}]}",
                        i, rand.nextInt(50), rand.nextInt(1000)
                ));
            } else if (percentile < simpleRatio + mediumRatio) {
                // Medium complexity
                rules.add(String.format(
                        "{\"rule_code\":\"M_%d\",\"priority\":%d,\"conditions\":[" +
                                "{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"US\",\"UK\",\"CA\",\"AU\"]}," +
                                "{\"field\":\"tier\",\"operator\":\"IS_ANY_OF\",\"value\":[\"GOLD\",\"PLATINUM\"]}," +
                                "{\"field\":\"amount\",\"operator\":\"BETWEEN\",\"value\":[%d,%d]}]}",
                        i, 50 + rand.nextInt(30), 100, 5000 + rand.nextInt(5000)
                ));
            } else {
                // Complex rules with high expansion
                List<String> countries = Arrays.asList("US", "UK", "CA", "AU", "DE", "FR", "JP", "CN");
                List<String> tiers = Arrays.asList("GOLD", "PLATINUM", "DIAMOND");
                List<String> products = Arrays.asList("ELECTRONICS", "FASHION", "HOME", "SPORTS");

                // Randomly select subsets for variety
                Collections.shuffle(countries, rand);
                Collections.shuffle(tiers, rand);
                Collections.shuffle(products, rand);

                String countryList = String.join("\",\"",
                        countries.subList(0, 3 + rand.nextInt(3)));
                String tierList = String.join("\",\"",
                        tiers.subList(0, 2 + rand.nextInt(2)));
                String productList = String.join("\",\"",
                        products.subList(0, 2 + rand.nextInt(2)));

                rules.add(String.format(
                        "{\"rule_code\":\"C_%d\",\"priority\":%d,\"conditions\":[" +
                                "{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\"]}," +
                                "{\"field\":\"tier\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\"]}," +
                                "{\"field\":\"product\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\"]}," +
                                "{\"field\":\"status\",\"operator\":\"EQUAL_TO\",\"value\":\"ACTIVE\"}," +
                                "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":%d}]}",
                        i, 70 + rand.nextInt(30), countryList, tierList, productList,
                        rand.nextInt(10000)
                ));
            }
        }

        Files.writeString(path, "[" + String.join(",\n", rules) + "]");
        return path;
    }

    private List<Event> generateProgressiveEvents(int count) {
        List<Event> events = new ArrayList<>(count);
        Random rand = new Random(123);

        String[] countries = {"US", "UK", "CA", "AU", "DE", "FR", "JP", "CN", "IN", "BR"};
        String[] tiers = {"BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND"};
        String[] statuses = {"ACTIVE", "INACTIVE", "PENDING", "SUSPENDED"};
        String[] products = {"ELECTRONICS", "FASHION", "HOME", "SPORTS", "BOOKS"};

        for (int i = 0; i < count; i++) {
            Map<String, Object> attrs = new HashMap<>();

            // Vary event complexity based on position in pool
            int complexity = (i * 3) / count; // 0, 1, or 2

            switch (complexity) {
                case 0: // Simple events (first third)
                    attrs.put("status", statuses[rand.nextInt(2)]);
                    attrs.put("amount", rand.nextInt(1000));
                    attrs.put("country", countries[0]); // Fixed country
                    break;

                case 1: // Medium complexity (second third)
                    attrs.put("status", statuses[rand.nextInt(statuses.length)]);
                    attrs.put("amount", rand.nextInt(10000));
                    attrs.put("country", countries[rand.nextInt(5)]);
                    attrs.put("tier", tiers[rand.nextInt(3)]);
                    attrs.put("score", 50 + rand.nextInt(50));
                    break;

                case 2: // Complex events (final third)
                    attrs.put("status", statuses[rand.nextInt(statuses.length)]);
                    attrs.put("amount", rand.nextInt(100000));
                    attrs.put("country", countries[rand.nextInt(countries.length)]);
                    attrs.put("tier", tiers[rand.nextInt(tiers.length)]);
                    attrs.put("product", products[rand.nextInt(products.length)]);
                    attrs.put("risk_score", rand.nextInt(100));
                    attrs.put("verified", rand.nextBoolean());
                    attrs.put("score", rand.nextInt(100));
                    attrs.put("user_segment", rand.nextInt(10));
                    attrs.put("category", products[rand.nextInt(3)]);
                    break;
            }

            events.add(new Event("evt_" + i, "BENCH", attrs));
        }

        // Shuffle to mix complexities
        Collections.shuffle(events, rand);

        return events;
    }

    private long estimateMemoryUsage() {
        int numRules = model.getNumRules();
        int numPredicates = model.getPredicateRegistry().size();

        // SoA arrays: counters, needs, priorities, etc.
        long soaMemory = numRules * 20L;

        // Inverted index with RoaringBitmaps
        long indexMemory = numPredicates * 2048L;

        // Dictionaries
        long dictMemory = (model.getFieldDictionary().size() +
                model.getValueDictionary().size()) * 128L;

        // Base condition cache overhead
        long cacheMemory = Math.min(numRules / 10, 1000) * 512L;

        return soaMemory + indexMemory + dictMemory + cacheMemory;
    }

    private long getUsedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private double percentile(List<Long> values, double p) {
        int index = (int) Math.ceil(p * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    public static void main(String[] args) throws RunnerException {
        // Configure for 2-3 minute runtime
        Options opt = new OptionsBuilder()
                .include(SimpleBenchmark.class.getSimpleName())
                .warmupIterations(WARMUP_ITERATIONS)
                .warmupTime(TimeValue.seconds(WARMUP_TIME))
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .measurementTime(TimeValue.seconds(MEASUREMENT_TIME))
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();

        System.out.println("\n🚀 Starting benchmark... Expected runtime: 2-3 minutes\n");

        new Runner(opt).run();
    }
}