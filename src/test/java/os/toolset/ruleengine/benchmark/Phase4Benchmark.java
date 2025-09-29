package os.toolset.ruleengine.benchmark;

import os.toolset.ruleengine.core.*;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4 Performance Benchmark
 * Demonstrates the performance improvements from weighted evaluation and SoA layout.
 */
public class Phase4Benchmark {

    private static final int WARM_UP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final int NUM_THREADS = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("   PHASE 4 PERFORMANCE BENCHMARK                ");
        System.out.println("   Weighted Evaluation & Structure of Arrays    ");
        System.out.println("=================================================\n");

        Phase4Benchmark benchmark = new Phase4Benchmark();
        benchmark.runBenchmark();
    }

    private void runBenchmark() throws Exception {
        // Create test rules at different scales
        Path smallRulesPath = createRuleFile("small", 100);
        Path mediumRulesPath = createRuleFile("medium", 1000);
        Path largeRulesPath = createRuleFile("large", 10000);

        System.out.println("Compiling rule sets...\n");

        // Compile models
        RuleCompiler compiler = new RuleCompiler();
        EngineModel smallModel = compiler.compile(smallRulesPath);
        EngineModel mediumModel = compiler.compile(mediumRulesPath);
        EngineModel largeModel = compiler.compile(largeRulesPath);

        // Print model statistics
        printModelStats("Small (100 logical rules)", smallModel);
        printModelStats("Medium (1,000 logical rules)", mediumModel);
        printModelStats("Large (10,000 logical rules)", largeModel);

        System.out.println("\n" + "=".repeat(50));
        System.out.println("Running performance benchmarks...\n");

        // Run benchmarks
        runSingleThreadedBenchmark("Small Model", smallModel);
        runSingleThreadedBenchmark("Medium Model", mediumModel);
        runSingleThreadedBenchmark("Large Model", largeModel);

        System.out.println("\n" + "=".repeat(50));
        System.out.println("Multi-threaded performance test...\n");

        runMultiThreadedBenchmark("Large Model (Multi-threaded)", largeModel);

        // Clean up
        Files.deleteIfExists(smallRulesPath);
        Files.deleteIfExists(mediumRulesPath);
        Files.deleteIfExists(largeRulesPath);
    }

    private void printModelStats(String label, EngineModel model) {
        System.out.printf("%s:%n", label);
        System.out.printf("  Total internal rules: %d%n", model.getNumRules());
        System.out.printf("  Unique predicates: %d%n", model.getPredicateRegistry().size());
        System.out.printf("  Avg selectivity: %.3f%n",
                (float) model.getStats().metadata().getOrDefault("avgSelectivity", 0.5));
        System.out.printf("  Expansion factor: %.2fx%n",
                (double) model.getStats().metadata().getOrDefault("expansionFactor", 1.0));

        // Calculate memory usage estimate
        long memoryEstimate = estimateMemoryUsage(model);
        System.out.printf("  Estimated memory: %.2f MB%n", memoryEstimate / 1024.0 / 1024.0);
        System.out.println();
    }

    private long estimateMemoryUsage(EngineModel model) {
        // Rough estimation of memory usage
        int numRules = model.getNumRules();
        int numPredicates = model.getPredicateRegistry().size();

        // SoA arrays
        long soaMemory = numRules * (
                4 +     // priority (int)
                        4 +     // predicateCount (int)
                        64 +    // ruleCode (String estimate)
                        128 +   // description (String estimate)
                        40      // predicateIds list overhead
        );

        // Predicate storage
        long predicateMemory = numPredicates * (
                100 +   // Predicate object overhead
                        4 +     // weight (float)
                        4       // selectivity (float)
        );

        // Inverted index (RoaringBitmap)
        long indexMemory = numPredicates * 100; // Rough estimate

        return soaMemory + predicateMemory + indexMemory;
    }

    private void runSingleThreadedBenchmark(String label, EngineModel model) {
        RuleEvaluator evaluator = new RuleEvaluator(model);
        List<Event> testEvents = generateTestEvents(BENCHMARK_ITERATIONS);

        // Warm up
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            evaluator.evaluate(testEvents.get(i % testEvents.size()));
        }

        // Benchmark
        List<Long> latencies = new ArrayList<>();
        long startTime = System.nanoTime();

        for (Event event : testEvents) {
            long evalStart = System.nanoTime();
            MatchResult result = evaluator.evaluate(event);
            latencies.add(System.nanoTime() - evalStart);
        }

        long totalTime = System.nanoTime() - startTime;

        // Calculate statistics
        Collections.sort(latencies);
        long p50 = latencies.get(latencies.size() / 2);
        long p99 = latencies.get((int)(latencies.size() * 0.99));
        long max = latencies.get(latencies.size() - 1);
        double throughput = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / totalTime;

        System.out.printf("%s Results:%n", label);
        System.out.printf("  Throughput: %.0f events/sec%n", throughput);
        System.out.printf("  P50 Latency: %.3f ms%n", p50 / 1_000_000.0);
        System.out.printf("  P99 Latency: %.3f ms%n", p99 / 1_000_000.0);
        System.out.printf("  Max Latency: %.3f ms%n", max / 1_000_000.0);

        // Verify Phase 4 target: P99 < 2ms
        if (p99 / 1_000_000.0 < 2.0) {
            System.out.println("  ✓ Meets Phase 4 target (P99 < 2ms)");
        } else {
            System.out.println("  ✗ Does not meet Phase 4 target (P99 < 2ms)");
        }
        System.out.println();
    }

    private void runMultiThreadedBenchmark(String label, EngineModel model) throws Exception {
        RuleEvaluator evaluator = new RuleEvaluator(model);
        List<Event> testEvents = generateTestEvents(BENCHMARK_ITERATIONS * NUM_THREADS);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(NUM_THREADS);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong totalEvents = new AtomicLong(0);

        long startTime = System.nanoTime();

        for (int t = 0; t < NUM_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    int eventsPerThread = testEvents.size() / NUM_THREADS;
                    int startIdx = threadId * eventsPerThread;
                    int endIdx = Math.min(startIdx + eventsPerThread, testEvents.size());

                    for (int i = startIdx; i < endIdx; i++) {
                        long evalStart = System.nanoTime();
                        evaluator.evaluate(testEvents.get(i));
                        totalLatency.addAndGet(System.nanoTime() - evalStart);
                        totalEvents.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads
        completionLatch.await(); // Wait for completion
        executor.shutdown();

        long totalTime = System.nanoTime() - startTime;
        double throughput = (totalEvents.get() * 1_000_000_000.0) / totalTime;
        double avgLatency = totalLatency.get() / (double) totalEvents.get() / 1_000_000.0;

        System.out.printf("%s Results:%n", label);
        System.out.printf("  Threads: %d%n", NUM_THREADS);
        System.out.printf("  Total Events: %d%n", totalEvents.get());
        System.out.printf("  Throughput: %.0f events/sec%n", throughput);
        System.out.printf("  Avg Latency: %.3f ms%n", avgLatency);
        System.out.println();
    }

    private List<Event> generateTestEvents(int count) {
        List<Event> events = new ArrayList<>();
        Random random = new Random(42); // Fixed seed for reproducibility

        String[] countries = {"US", "UK", "CA", "DE", "FR", "JP", "AU"};
        String[] statuses = {"ACTIVE", "INACTIVE", "PENDING"};
        String[] tiers = {"BRONZE", "SILVER", "GOLD", "PLATINUM"};
        String[] regions = {"NA", "EU", "APAC", "LATAM"};

        for (int i = 0; i < count; i++) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("amount", random.nextInt(100000));
            attributes.put("country", countries[random.nextInt(countries.length)]);
            attributes.put("status", statuses[random.nextInt(statuses.length)]);
            attributes.put("tier", tiers[random.nextInt(tiers.length)]);
            attributes.put("region", regions[random.nextInt(regions.length)]);
            attributes.put("user_id", "USER_" + random.nextInt(10000));
            attributes.put("transaction_id", "TXN_" + i);

            events.add(new Event("evt_" + i, "TRANSACTION", attributes));
        }

        return events;
    }

    private Path createRuleFile(String name, int numRules) throws IOException {
        Path path = Files.createTempFile("rules_" + name + "_", ".json");

        List<String> rules = new ArrayList<>();
        Random random = new Random(123); // Fixed seed

        for (int i = 0; i < numRules; i++) {
            StringBuilder rule = new StringBuilder();
            rule.append("{");
            rule.append("\"rule_code\":\"RULE_").append(i).append("\",");
            rule.append("\"priority\":").append(random.nextInt(100)).append(",");
            rule.append("\"conditions\":[");

            // Add 2-5 conditions per rule
            int numConditions = 2 + random.nextInt(4);
            for (int j = 0; j < numConditions; j++) {
                if (j > 0) rule.append(",");

                // Mix of different operators
                int opType = random.nextInt(5);
                switch (opType) {
                    case 0: // EQUAL_TO
                        rule.append("{\"field\":\"status\",\"operator\":\"EQUAL_TO\",\"value\":\"ACTIVE\"}");
                        break;
                    case 1: // GREATER_THAN
                        rule.append("{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":")
                                .append(random.nextInt(50000)).append("}");
                        break;
                    case 2: // IS_ANY_OF
                        rule.append("{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"US\",\"UK\",\"CA\"]}");
                        break;
                    case 3: // BETWEEN
                        int low = random.nextInt(10000);
                        int high = low + random.nextInt(40000);
                        rule.append("{\"field\":\"amount\",\"operator\":\"BETWEEN\",\"value\":[")
                                .append(low).append(",").append(high).append("]}");
                        break;
                    case 4: // CONTAINS
                        rule.append("{\"field\":\"user_id\",\"operator\":\"CONTAINS\",\"value\":\"USER\"}");
                        break;
                }
            }

            rule.append("]}");
            rules.add(rule.toString());
        }

        String json = "[" + String.join(",", rules) + "]";
        Files.writeString(path, json);

        return path;
    }
}