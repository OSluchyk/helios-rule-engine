package com.helios.ruleengine.benchmark;

import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static java.lang.String.join;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Paths.*;

/**
 * Development Performance Benchmark - 2-3 Minute Runtime
 * <p>
 * DESIGN RATIONALE:
 * - Total runtime: 2-3 minutes for comprehensive feedback
 * - Progressive testing: Simple → Medium → Complex scenarios
 * - Real-world cache patterns: Cold start → Warm → Hot
 * - Memory pressure testing without OOM
 * - Deduplication effectiveness validation
 * <p>
 * RUNTIME BREAKDOWN (Default Configuration):
 * - Setup & Compilation: ~5 seconds
 * - Warmup Phase: 20 seconds (10 iterations × 2 sec)
 * - Measurement Phase: 100 seconds (20 iterations × 5 sec)
 * - Analysis & Reporting: ~5 seconds
 * - TOTAL: ~130 seconds (2m 10s)
 * <p>
 * USAGE:
 * # Standard 2-minute run
 * mvn clean test-compile exec:java \
 * -Dexec.mainClass="com.helios.ruleengine.benchmark.SimpleBenchmark" \
 * -Dexec.classpathScope=test
 * <p>
 * # Quick 1-minute run for rapid iteration
 * mvn test-compile exec:java [...] -Dbench.quick=true
 * <p>
 * # Extended 3-minute run for thorough testing
 * mvn test-compile exec:java [...] -Dbench.extended=true
 * <p>
 * CONFIGURATION:
 * -Dbench.quick : 1-minute quick mode
 * -Dbench.extended : 3-minute extended mode
 * -Dbench.rules : Override rule count (default: progressive)
 * -Dbench.profile : Enable JFR profiling (CRITICAL: see usage below)
 *
 * JFR PROFILING SETUP:
 * When -Dbench.profile=true is set, JMH will:
 * 1. Create a timestamped directory: jfr-reports-YYYY-MM-DD/
 * 2. Generate .jfr files for each benchmark method
 * 3. Force single fork and single thread for clean profiling
 *
 * IMPORTANT: The JFR profiler requires:
 * - JMH to be run via the main() method (not via Maven exec plugin directly)
 * - The benchmark JAR must be built first: mvn clean package
 * - Profiling adds significant overhead (~20-30% slower)
 *
 * USAGE WITH PROFILING:
 * # Build benchmark JAR
 * mvn clean package -pl helios-benchmarks -am -DskipTests
 *
 * # Run with profiling (recommended: use run-jfr.sh script)
 * ./helios-benchmarks/run-jfr.sh
 *
 * OR manually via JAR:
 *   java -Dbench.quick=true -Dbench.profile=true
 *        -jar helios-benchmarks/target/benchmarks.jar SimpleBenchmark
 *
 * Advanced: Control JFR profile settings via JVM args (in @Fork annotation):
 *   -XX:StartFlightRecording=settings=profile  # Balanced CPU + allocations
 *   -XX:StartFlightRecording=settings=default  # Lower overhead
 *
 * Analyze results:
 *   jmc jfr-reports-YYYY-MM-DD/SimpleBenchmark*.jfr
 *
 * TROUBLESHOOTING:
 * - Error "Cannot parse argument 'ettings=profile'": Fixed in this version
 *   The JMH JFR profiler only accepts 'dir=' parameter, not 'settings='
 * - JFR files not created: Ensure -Dbench.profile=true is set
 * - Permission denied: Check write permissions for jfr-reports-* directory
 *
 * KNOWN ISSUES (FIXED):
 * - v1.0: Syntax error "// <-- THE FIX .forks(1)" broke method chain
 * - v1.1: Invalid parameter ";settings=profile" caused ProfilerException
 * - v1.2: CURRENT - Uses only valid 'dir=' parameter
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
@Warmup(iterations = 10, time = 2) // 20 seconds warmup
@Measurement(iterations = 20, time = 5) // 100 seconds measurement
public class SimpleBenchmark {

    // ========================================================================
    // CONFIGURATION
    // ========================================================================

    // Runtime modes
    private static final boolean QUICK_MODE = Boolean.getBoolean("bench.quick");
    private static final boolean EXTENDED_MODE = Boolean.getBoolean("bench.extended");
    private static final boolean PROFILE = Boolean.getBoolean("bench.profile");

    // Adjust iterations based on mode
    private static final int WARMUP_ITERATIONS = QUICK_MODE ? 5 : (EXTENDED_MODE ? 15 : 10);
    private static final int WARMUP_TIME = QUICK_MODE ? 1 : 2;
    private static final int MEASUREMENT_ITERATIONS = QUICK_MODE ? 10 : (EXTENDED_MODE ? 30 : 20);
    private static final int MEASUREMENT_TIME = QUICK_MODE ? 3 : 5;

    // ========================================================================
    // TEST PARAMETERS
    // ========================================================================

    @Param({"500", "2000", "5000"}) // Progressive rule counts
    private int ruleCount;

    @Param({"MIXED"}) // Default to realistic mixed workload
    private String workloadType;

    @Param({"HOT", "WARM", "COLD"}) // Cache scenarios
    private String cacheScenario;

    // ========================================================================
    // STATE VARIABLES
    // ========================================================================

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

    // ========================================================================
    // SETUP METHODS
    // ========================================================================

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        setupStartTime = System.nanoTime();

        // Suppress logs for clean output
        java.util.logging.Logger.getLogger("io.opentelemetry")
                .setLevel(java.util.logging.Level.OFF);

        printHeader();

        // Track initial memory
        initialMemory = getUsedMemory();

        // Create progressive rule set
        Path rulesPath = createProgressiveRules(ruleCount, workloadType);

        // Compile and measure
        long compileStart = System.nanoTime();
        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        model = compiler.compile(rulesPath);
        compilationTime = System.nanoTime() - compileStart;

        // Create evaluator with cache enabled
        evaluator = new RuleEvaluator(model, NOOP_TRACER, true);

        if (evaluator.getBaseConditionEvaluator() == null) {
            throw new IllegalStateException(
                    "Base condition cache failed to initialize! " +
                            "This will cause poor performance.");
        }

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

    // ========================================================================
    // BENCHMARK METHODS
    // ========================================================================

    /**
     * BENCHMARK 1: Throughput-focused batch evaluation
     * Measures ops/sec for batches of 100 events
     */
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

    /**
     * BENCHMARK 2: Latency-focused single evaluation
     * Measures nanoseconds per single event
     */
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

    /**
     * BENCHMARK 3: Memory pressure test
     * Multi-threaded to stress memory and cache
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(4) // Multi-threaded to stress memory
    public void throughput_concurrent(Blackhole bh) {
        Event event = getNextEvent();
        MatchResult result = evaluator.evaluate(event);
        bh.consume(result);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private Event getNextEvent() {
        int idx = eventIndex.getAndIncrement();
        return eventPool.get(idx % eventPool.size());
    }

    private Path createProgressiveRules(int count, String type) throws IOException {
        Path path = Files.createTempFile("bench_rules_", ".json");
        List<String> rules = new ArrayList<>();
        Random rand = new Random(42);

        // Progressive complexity: mix gets more complex as count increases
        int simpleRatio = count <= 1000 ? 60 : (count <= 5000 ? 40 : 20);
        int mediumRatio = count <= 1000 ? 30 : (count <= 5000 ? 40 : 40);
        int complexRatio = 100 - simpleRatio - mediumRatio;

        String[] statuses = {"ACTIVE", "PENDING", "SUSPENDED", "CLOSED"};
        String[] countries = {"US", "CA", "UK", "DE", "FR", "JP", "AU", "BR", "IN", "CN"};
        String[] tiers = {"BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND"};
        String[] products = {"ELECTRONICS", "CLOTHING", "FOOD", "BOOKS", "TOYS"};

        for (int i = 0; i < count; i++) {
            StringBuilder rule = new StringBuilder();
            rule.append(String.format(
                    "{\"rule_code\":\"RULE_%d\",\"priority\":%d,\"conditions\":[",
                    i, 1000 - i));

            int complexity = (i * 100 / count) < simpleRatio ? 0
                    : (i * 100 / count) < (simpleRatio + mediumRatio) ? 1 : 2;

            List<String> conditions = new ArrayList<>();

            switch (complexity) {
                case 0: // Simple rules (2-3 conditions)
                    conditions.add(String.format(
                            "{\"field\":\"status\",\"operator\":\"EQUAL_TO\",\"value\":\"%s\"}",
                            statuses[rand.nextInt(2)]));
                    conditions.add(String.format(
                            "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":%d}",
                            rand.nextInt(1000)));
                    if (rand.nextBoolean()) {
                        conditions.add(String.format(
                                "{\"field\":\"country\",\"operator\":\"EQUAL_TO\",\"value\":\"%s\"}",
                                countries[0]));
                    }
                    break;

                case 1: // Medium rules (4-5 conditions)
                    conditions.add(String.format(
                            "{\"field\":\"status\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\"]}",
                            statuses[rand.nextInt(statuses.length)],
                            statuses[rand.nextInt(statuses.length)]));
                    conditions.add(String.format(
                            "{\"field\":\"amount\",\"operator\":\"BETWEEN\",\"value\":[%d,%d]}",
                            rand.nextInt(5000), 5000 + rand.nextInt(5000)));
                    conditions.add(String.format(
                            "{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\",\"%s\"]}",
                            countries[rand.nextInt(countries.length)],
                            countries[rand.nextInt(countries.length)],
                            countries[rand.nextInt(countries.length)]));
                    conditions.add(String.format(
                            "{\"field\":\"tier\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\"]}",
                            tiers[rand.nextInt(tiers.length)],
                            tiers[rand.nextInt(tiers.length)]));
                    break;

                case 2: // Complex rules (6-8 conditions)
                    conditions.add(String.format(
                            "{\"field\":\"status\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\",\"%s\"]}",
                            statuses[0], statuses[1], statuses[2]));
                    conditions.add(String.format(
                            "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":%d}",
                            rand.nextInt(10000)));
                    conditions.add(String.format(
                            "{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\",\"%s\",\"%s\"]}",
                            countries[rand.nextInt(countries.length)],
                            countries[rand.nextInt(countries.length)],
                            countries[rand.nextInt(countries.length)],
                            countries[rand.nextInt(countries.length)]));
                    conditions.add(String.format(
                            "{\"field\":\"tier\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\",\"%s\"]}",
                            tiers[0], tiers[1], tiers[2]));
                    conditions.add(String.format(
                            "{\"field\":\"product\",\"operator\":\"IS_ANY_OF\",\"value\":[\"%s\",\"%s\"]}",
                            products[rand.nextInt(products.length)],
                            products[rand.nextInt(products.length)]));
                    conditions.add(String.format(
                            "{\"field\":\"risk_score\",\"operator\":\"LESS_THAN\",\"value\":%d}",
                            50 + rand.nextInt(50)));
                    break;
            }

            rule.append(join(",", conditions));
            rule.append("]}");
            rules.add(rule.toString());
        }

        Files.writeString(path, "[" + join(",", rules) + "]");
        return path;
    }

    private List<Event> generateProgressiveEvents(int count) {
        List<Event> events = new ArrayList<>(count);
        Random rand = new Random(42);

        String[] statuses = {"ACTIVE", "PENDING", "SUSPENDED", "CLOSED"};
        String[] countries = {"US", "CA", "UK", "DE", "FR", "JP", "AU", "BR", "IN", "CN"};
        String[] tiers = {"BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND"};
        String[] products = {"ELECTRONICS", "CLOTHING", "FOOD", "BOOKS", "TOYS"};

        for (int i = 0; i < count; i++) {
            Map<String, Object> attrs = new HashMap<>();

            // Progressive complexity across the pool
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

    // ========================================================================
    // REPORTING METHODS
    // ========================================================================

    private void printHeader() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚀 DEVELOPMENT PERFORMANCE BENCHMARK");
        System.out.println("=".repeat(80));

        String mode = QUICK_MODE ? "QUICK (1 min)" : (EXTENDED_MODE ? "EXTENDED (3 min)" : "STANDARD (2 min)");
        System.out.println("Mode: " + mode);

        if (PROFILE) {
            System.out.println("⚠️  Profiling enabled - expect slower results");
        }

        System.out.println("=".repeat(80) + "\n");
    }

    private void printSetupSummary() {
        System.out.println("\n📋 TEST CONFIGURATION:");
        System.out.println("─".repeat(50));
        System.out.printf("Rule Count:            %,d\n", ruleCount);
        System.out.printf("Workload Type:         %s\n", workloadType);
        System.out.printf("Event Pool Size:       %,d\n", eventPool.size());
        System.out.printf("Cache Scenario:        %s\n", cacheScenario);
        System.out.printf("Compilation Time:      %.2f ms\n", compilationTime / 1_000_000.0);
        System.out.printf("Model Size (est):      %.2f MB\n", estimateMemoryUsage() / (1024.0 * 1024.0));
        System.out.println("─".repeat(50));
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

        printCacheMetrics();
    }

    private void printCacheMetrics() {
        System.out.println("\n📊 CACHE EFFECTIVENESS:");
        System.out.println("─".repeat(50));

        if (evaluator.getBaseConditionEvaluator() != null) {
            Map<String, Object> cacheMetrics = evaluator.getBaseConditionEvaluator().getMetrics();

            System.out.printf("Total Evaluations:        %,d%n",
                    cacheMetrics.get("totalEvaluations"));
            System.out.printf("Cache Hits:               %,d%n",
                    cacheMetrics.get("cacheHits"));
            System.out.printf("Cache Misses:             %,d%n",
                    cacheMetrics.get("cacheMisses"));
            System.out.printf("Cache Hit Rate:           %.1f%%%n",
                    (double) cacheMetrics.get("cacheHitRate") * 100);
            System.out.printf("Base Condition Sets:      %,d%n",
                    cacheMetrics.get("baseConditionSets"));
            System.out.printf("Base Reduction:           %.1f%%%n",
                    cacheMetrics.get("baseConditionReductionPercent"));

            // Validate cache is working
            double hitRate = (double) cacheMetrics.get("cacheHitRate");
            if (hitRate < 0.50) {
                System.out.println("\n⚠️  WARNING: Cache hit rate < 50% - " +
                        "cache may be too small or base condition extraction not working!");
            } else if (hitRate > 0.90) {
                System.out.println("\n✅ EXCELLENT: Cache hit rate > 90% - " +
                        "base condition optimization is effective!");
            }
        } else {
            System.out.println("Base Condition Cache: NOT ENABLED");
            System.out.println("⚠️  Performance will be poor without caching!");
        }

        System.out.println("\n" + "=".repeat(80));
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    private long estimateMemoryUsage() {
        int numRules = model.getNumRules();
        int numPredicates = model.getUniquePredicates().length;

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

    // ========================================================================
    // MAIN METHOD
    // ========================================================================

    public static void main(String[] args) throws RunnerException {
        System.out.println("ARGS: " + join(", ", args));
        // Configure for 2-3 minute runtime
        ChainedOptionsBuilder jmhBuilder = new OptionsBuilder()
                .include(SimpleBenchmark.class.getSimpleName())
                .warmupIterations(WARMUP_ITERATIONS)
                .warmupTime(TimeValue.seconds(WARMUP_TIME))
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .measurementTime(TimeValue.seconds(MEASUREMENT_TIME))
                .shouldFailOnError(true)
                .shouldDoGC(false);
        if (PROFILE) {
            LocalDate now = LocalDate.now();
            String jfrOutputDir = "jfr-reports-" + now;
            try {
                java.nio.file.Path outputPath = get(jfrOutputDir);
                createDirectories(outputPath);
                System.out.println("\n" + "=".repeat(80));
                System.out.println("🔍 JFR PROFILING ENABLED");
                System.out.println("=".repeat(80));
                System.out.println("Output directory: " + outputPath.toAbsolutePath());
                System.out.println("\nProfiler configuration:");
                System.out.println("  - JMH JFR profiler with dir=" + jfrOutputDir);
                System.out.println("  - Single fork (required for JFR)");
                System.out.println("  - Single thread (cleaner profiling data)");
                System.out.println("  - Default JFR settings (to customize, add JVM args in @Fork)");
                System.out.println("\nJFR files will be generated for each benchmark method:");
                System.out.println("  - SimpleBenchmark.throughput_batch100.jfr");
                System.out.println("  - SimpleBenchmark.latency_single.jfr");
                System.out.println("  - SimpleBenchmark.throughput_concurrent.jfr");
                System.out.println("\nAfter completion, analyze with:");
                System.out.println("  jmc " + jfrOutputDir + "/*.jfr");
                System.out.println("  OR upload to: https://jmc.openjdk.java.net");
                System.out.println("=".repeat(80) + "\n");
            } catch (java.io.IOException e) {
                System.err.println("⚠️  ERROR: Could not create JFR output directory: " + e.getMessage());
                System.err.println("Profiling will fail. Please check permissions for: " + jfrOutputDir);
            }

            // Configure JMH JFR profiler
            // Note: JFR profiler only accepts 'dir' parameter in JMH 1.x
            // The 'settings' parameter is controlled via JVM flight recorder options
            // For detailed profiling, use: -XX:StartFlightRecording:settings=profile
            jmhBuilder.addProfiler("jfr", "dir=" + jfrOutputDir)
                    .forks(1)  // Force single fork for profiling (required for JFR)
                    .threads(1);  // Single thread for cleaner profiling data
        }
        Options opt = jmhBuilder.build();

        System.out.println("\n🚀 Starting benchmark... Expected runtime: 2-3 minutes\n");

        new Runner(opt).run();
    }
}