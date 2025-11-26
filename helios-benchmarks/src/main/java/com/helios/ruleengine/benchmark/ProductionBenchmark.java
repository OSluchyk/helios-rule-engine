package com.helios.ruleengine.benchmark;

import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.infra.telemetry.TracingService;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import com.helios.ruleengine.cache.BaseConditionCache;
import com.helios.ruleengine.cache.InMemoryBaseConditionCache;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.openjdk.jmh.annotations.Threads.MAX;

/**
 * Production-scale performance benchmarks targeting:
 * - 15-20M events/min throughput
 * - P99 < 0.8ms latency
 * - <6GB memory @ 100K rules
 */
@BenchmarkMode({ Mode.Throughput, Mode.SampleTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseCompactObjectHeaders", // Java 25 compact headers
        "-XX:+UseZGC", // ZGC for low latency
        "-XX:+ZGenerational", // Generational ZGC
        "-XX:+UseLargePages", // Large pages for TLB efficiency
        "--add-modules=jdk.incubator.vector",
        "-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0",
        "-XX:+UseNUMA", // NUMA awareness
        "-XX:MaxInlineLevel=15", // Aggressive inlining
        "-XX:InlineSmallCode=2000", // Larger inline threshold
        "-Xms8g", // Initial heap
        "-Xmx8g", // Max heap
        "-XX:MaxDirectMemorySize=4g", // Direct memory for off-heap
        "-XX:+AlwaysPreTouch", // Pre-touch pages
        "-XX:+UseStringDeduplication", // String deduplication
        "-Djava.lang.Integer.IntegerCache.high=10000" // Larger integer cache
})
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 20, time = 5)
@Threads(MAX) // Simulating concurrent load
public class ProductionBenchmark {

    @Param({ "1000", "10000", "50000", "100000" })
    private int ruleCount;

    @Param({ "SIMPLE", "MEDIUM", "COMPLEX", "MIXED" })
    private String workloadType;

    private RuleEvaluator evaluator;
    private List<Event> eventPool;
    private AtomicLong eventIndex;
    private EngineModel model;

    // Monitoring
    private static final boolean ENABLE_MONITORING = true;
    private PerformanceMonitor monitor;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        System.out.println("=== Production Performance Benchmark ===");
        System.out.printf("Rules: %d, Workload: %s%n", ruleCount, workloadType);

        // Create rules based on workload type
        Path rulesPath = createProductionRules(ruleCount, workloadType);

        // Compile with production optimizations
        RuleCompiler compiler = new RuleCompiler(TracingService.getInstance().getTracer());
        model = compiler.compile(rulesPath);

        // Print model statistics
        printModelStats();

        // Initialize evaluator with production cache
        BaseConditionCache cache = createProductionCache();
        evaluator = new RuleEvaluator(model, TracingService.getInstance().getTracer(), true);

        // Pre-generate event pool
        eventPool = generateEventPool(10000, workloadType);
        eventIndex = new AtomicLong(0);

        // Initialize monitoring
        if (ENABLE_MONITORING) {
            monitor = new PerformanceMonitor(model);
            monitor.start();
        }

        // Warm up cache
        warmupCache();

        Files.delete(rulesPath);
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Reset counters per invocation if needed
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (monitor != null) {
            monitor.stop();
            monitor.printReport();
        }
    }

    @Benchmark
    public MatchResult evaluateSingleEvent() {
        long idx = eventIndex.getAndIncrement();
        Event event = eventPool.get((int) (idx % eventPool.size()));
        return evaluator.evaluate(event);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void evaluateBatch(Blackhole blackhole) {
        // Batch evaluation for better throughput measurement
        for (int i = 0; i < 100; i++) {
            long idx = eventIndex.getAndIncrement();
            Event event = eventPool.get((int) (idx % eventPool.size()));
            blackhole.consume(evaluator.evaluate(event));
        }
    }

    private Path createProductionRules(int count, String workloadType) throws IOException {
        Path path = Files.createTempFile("prod_rules_", ".json");
        List<String> rules = new ArrayList<>();

        switch (workloadType) {
            case "SIMPLE":
                // Simple rules with 1-3 conditions
                for (int i = 0; i < count; i++) {
                    rules.add(String.format("""
                            {"rule_code":"SIMPLE_%d", "priority":%d, "conditions": [
                                {"field":"status", "operator":"EQUAL_TO", "value":"ACTIVE"},
                                {"field":"amount", "operator":"GREATER_THAN", "value":%d}
                            ]}""", i, i % 100, i % 1000));
                }
                break;

            case "MEDIUM":
                // Medium complexity with IS_ANY_OF
                for (int i = 0; i < count; i++) {
                    rules.add(String.format("""
                            {"rule_code":"MEDIUM_%d", "priority":%d, "conditions": [
                                {"field":"country", "operator":"IS_ANY_OF", "value":["US","UK","CA","AU"]},
                                {"field":"tier", "operator":"IS_ANY_OF", "value":["GOLD","PLATINUM"]},
                                {"field":"amount", "operator":"BETWEEN", "value":[100,%d]}
                            ]}""", i, i % 100, 1000 + i % 5000));
                }
                break;

            case "COMPLEX":
                // Complex rules with many conditions and expansions
                for (int i = 0; i < count; i++) {
                    List<String> countries = generateCountryList(10);
                    List<String> products = generateProductList(5);

                    rules.add(String.format("""
                            {"rule_code":"COMPLEX_%d", "priority":%d, "conditions": [
                                {"field":"country", "operator":"IS_ANY_OF", "value":%s},
                                {"field":"product", "operator":"IS_ANY_OF", "value":%s},
                                {"field":"status", "operator":"EQUAL_TO", "value":"ACTIVE"},
                                {"field":"tier", "operator":"IS_ANY_OF", "value":["GOLD","PLATINUM","DIAMOND"]},
                                {"field":"amount", "operator":"GREATER_THAN", "value":%d},
                                {"field":"risk_score", "operator":"LESS_THAN", "value":%d}
                            ]}""",
                            i, i % 100,
                            toJsonArray(countries),
                            toJsonArray(products),
                            i % 10000,
                            100 - (i % 50)));
                }
                break;

            case "MIXED":
                // Realistic mix: 60% simple, 30% medium, 10% complex
                for (int i = 0; i < count; i++) {
                    if (i % 10 < 6) {
                        // Simple rule
                        rules.add(String.format("""
                                {"rule_code":"MIX_S_%d", "priority":%d, "conditions": [
                                    {"field":"type", "operator":"EQUAL_TO", "value":"ORDER"},
                                    {"field":"amount", "operator":"GREATER_THAN", "value":%d}
                                ]}""", i, i % 100, i % 1000));
                    } else if (i % 10 < 9) {
                        // Medium rule
                        rules.add(String.format("""
                                {"rule_code":"MIX_M_%d", "priority":%d, "conditions": [
                                    {"field":"region", "operator":"IS_ANY_OF", "value":["NA","EU","APAC"]},
                                    {"field":"status", "operator":"EQUAL_TO", "value":"ACTIVE"},
                                    {"field":"score", "operator":"BETWEEN", "value":[50,95]}
                                ]}""", i, i % 100));
                    } else {
                        // Complex rule
                        rules.add(String.format(
                                """
                                        {"rule_code":"MIX_C_%d", "priority":%d, "conditions": [
                                            {"field":"country", "operator":"IS_ANY_OF", "value":["US","UK","DE","FR","JP","CN","IN","BR"]},
                                            {"field":"category", "operator":"IS_ANY_OF", "value":["ELECTRONICS","FASHION","HOME"]},
                                            {"field":"payment", "operator":"IS_ANY_OF", "value":["CARD","PAYPAL","CRYPTO"]},
                                            {"field":"verified", "operator":"EQUAL_TO", "value":true},
                                            {"field":"amount", "operator":"GREATER_THAN", "value":%d}
                                        ]}""",
                                i, i % 100, i % 50000));
                    }
                }
                break;
        }

        Files.writeString(path, "[" + String.join(",\n", rules) + "]");
        return path;
    }

    private List<Event> generateEventPool(int size, String workloadType) {
        List<Event> events = new ArrayList<>(size);
        Random random = new Random(42); // Deterministic seed

        String[] countries = { "US", "UK", "CA", "AU", "DE", "FR", "JP", "CN", "IN", "BR" };
        String[] tiers = { "BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND" };
        String[] statuses = { "ACTIVE", "INACTIVE", "PENDING", "SUSPENDED" };
        String[] types = { "ORDER", "PAYMENT", "REFUND", "SUBSCRIPTION" };
        String[] products = { "ELECTRONICS", "FASHION", "HOME", "SPORTS", "BOOKS" };

        for (int i = 0; i < size; i++) {
            Map<String, Object> attrs = new HashMap<>();

            // Common attributes
            attrs.put("country", countries[random.nextInt(countries.length)]);
            attrs.put("status", statuses[random.nextInt(statuses.length)]);
            attrs.put("amount", random.nextInt(100000));

            // Workload-specific attributes
            switch (workloadType) {
                case "SIMPLE":
                    attrs.put("type", types[random.nextInt(types.length)]);
                    break;

                case "MEDIUM":
                    attrs.put("tier", tiers[random.nextInt(tiers.length)]);
                    attrs.put("score", random.nextInt(100));
                    attrs.put("region", random.nextBoolean() ? "NA" : "EU");
                    break;

                case "COMPLEX":
                case "MIXED":
                    attrs.put("tier", tiers[random.nextInt(tiers.length)]);
                    attrs.put("product", products[random.nextInt(products.length)]);
                    attrs.put("risk_score", random.nextInt(100));
                    attrs.put("verified", random.nextBoolean());
                    attrs.put("category", products[random.nextInt(3)]);
                    attrs.put("payment", random.nextBoolean() ? "CARD" : "PAYPAL");
                    attrs.put("region", List.of("NA", "EU", "APAC").get(random.nextInt(3)));
                    attrs.put("type", types[random.nextInt(types.length)]);
                    attrs.put("score", 50 + random.nextInt(50));
                    break;
            }

            events.add(new Event("evt_" + i, "BENCH_EVENT", attrs));
        }

        return events;
    }

    private void warmupCache() {
        System.out.println("Warming up cache...");

        // Evaluate subset of events to warm cache
        for (int i = 0; i < Math.min(1000, eventPool.size()); i++) {
            evaluator.evaluate(eventPool.get(i));
        }

        // Print cache stats
        Map<String, Object> metrics = evaluator.getMetrics().getSnapshot();
        System.out.printf("Cache warmup complete. Hit rate: %.2f%%%n",
                metrics.getOrDefault("cacheHitRate", 0.0));
    }

    private void printModelStats() {
        System.out.println("=== Model Statistics ===");
        System.out.printf("Logical Rules: %d%n",
                model.getStats().metadata().get("logicalRules"));
        System.out.printf("Expanded Combinations: %d%n",
                model.getStats().metadata().get("totalExpandedCombinations"));
        System.out.printf("Unique Combinations: %d%n",
                model.getStats().metadata().get("uniqueCombinations"));
        System.out.printf("Deduplication Rate: %s%%%n",
                model.getStats().metadata().get("deduplicationRatePercent"));
        System.out.printf("Unique Predicates: %d%n",
                model.getUniquePredicates().length);

        // Memory estimate
        long memoryEstimate = estimateMemoryUsage();
        System.out.printf("Estimated Memory: %.2f MB%n",
                memoryEstimate / (1024.0 * 1024.0));
        System.out.println("========================");
    }

    private long estimateMemoryUsage() {
        // Rough memory estimation
        int numRules = model.getNumRules();
        int numPredicates = model.getUniquePredicates().length;

        // SoA arrays
        long soaMemory = numRules * (4 + 4 + 4 + 8); // counts, priorities, etc.

        // Inverted index (assuming RoaringBitmap average)
        long indexMemory = numPredicates * 1024; // Conservative estimate

        // Dictionaries
        long dictMemory = (model.getFieldDictionary().size() +
                model.getValueDictionary().size()) * 64;

        return soaMemory + indexMemory + dictMemory;
    }

    private BaseConditionCache createProductionCache() {
        // Use in-memory for benchmark to avoid network latency
        return new InMemoryBaseConditionCache.Builder()
                .maxSize(50_000)
                .defaultTtl(5, TimeUnit.MINUTES)
                .build();
    }

    private List<String> generateCountryList(int size) {
        String[] all = { "US", "UK", "CA", "AU", "DE", "FR", "JP", "CN", "IN", "BR",
                "MX", "IT", "ES", "NL", "SE", "NO", "DK", "FI", "PL", "RU" };
        List<String> result = new ArrayList<>();
        Random r = new Random();
        for (int i = 0; i < Math.min(size, all.length); i++) {
            result.add(all[r.nextInt(all.length)]);
        }
        return result;
    }

    private List<String> generateProductList(int size) {
        String[] all = { "ELECTRONICS", "FASHION", "HOME", "SPORTS", "BOOKS",
                "TOYS", "FOOD", "BEAUTY", "AUTO", "GARDEN" };
        List<String> result = new ArrayList<>();
        Random r = new Random();
        for (int i = 0; i < Math.min(size, all.length); i++) {
            result.add(all[r.nextInt(all.length)]);
        }
        return result;
    }

    private String toJsonArray(List<String> list) {
        return "[" + list.stream()
                .map(s -> "\"" + s + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    /**
     * Performance monitor for detailed metrics
     */
    private static class PerformanceMonitor {
        private final EngineModel model;
        private final Timer timer;
        private long startTime;
        private long totalEvents = 0;
        private long totalLatency = 0;
        private final List<Long> latencies = new ArrayList<>();

        public PerformanceMonitor(EngineModel model) {
            this.model = model;
            this.timer = new Timer(true);
        }

        public void start() {
            startTime = System.nanoTime();

            // Monitor every second
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    printSnapshot();
                }
            }, 1000, 1000);
        }

        public void stop() {
            timer.cancel();
        }

        public void recordEvaluation(long latencyNanos) {
            totalEvents++;
            totalLatency += latencyNanos;
            latencies.add(latencyNanos);
        }

        private void printSnapshot() {
            if (totalEvents == 0)
                return;

            long elapsed = System.nanoTime() - startTime;
            double throughput = totalEvents * 1_000_000_000.0 / elapsed;
            double avgLatency = totalLatency / (double) totalEvents / 1000; // microseconds

            System.out.printf("[Monitor] Throughput: %.0f events/sec, Avg Latency: %.1f μs%n",
                    throughput, avgLatency);
        }

        public void printReport() {
            if (latencies.isEmpty())
                return;

            Collections.sort(latencies);

            System.out.println("\n=== Performance Report ===");
            System.out.printf("Total Events: %d%n", totalEvents);
            System.out.printf("P50 Latency: %.1f μs%n",
                    percentile(latencies, 0.50) / 1000.0);
            System.out.printf("P90 Latency: %.1f μs%n",
                    percentile(latencies, 0.90) / 1000.0);
            System.out.printf("P99 Latency: %.1f μs%n",
                    percentile(latencies, 0.99) / 1000.0);
            System.out.printf("P99.9 Latency: %.1f μs%n",
                    percentile(latencies, 0.999) / 1000.0);
            System.out.printf("Max Latency: %.1f μs%n",
                    latencies.get(latencies.size() - 1) / 1000.0);

            long elapsed = System.nanoTime() - startTime;
            double throughput = totalEvents * 60_000_000_000.0 / elapsed;
            System.out.printf("Throughput: %.0f events/min%n", throughput);
            System.out.println("==========================");
        }

        private double percentile(List<Long> sorted, double p) {
            int index = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    // Main method to run benchmarks
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(ProductionBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result("benchmark-results-" + System.currentTimeMillis() + ".json")
                .build();

        new Runner(opt).run();
    }
}