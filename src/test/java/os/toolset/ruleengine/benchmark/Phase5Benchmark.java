package os.toolset.ruleengine.benchmark;

import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.model.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Phase5Benchmark {

    private static final int WARMUP_ITERATIONS = 5_000;
    private static final int BENCHMARK_ITERATIONS = 50_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("   PHASE 5 PERFORMANCE BENCHMARK                ");
        System.out.println("      Vector API & Scoped Values               ");
        System.out.println("=================================================\n");
        new Phase5Benchmark().run();
    }

    private void run() throws Exception {
        Path numericRules = createNumericHeavyRuleFile(1000);
        RuleCompiler compiler = new RuleCompiler();
        EngineModel model = compiler.compile(numericRules);

        System.out.println("--- Model Stats ---");
        System.out.printf("Unique Combinations: %d%n", model.getNumRules());
        System.out.printf("Unique Predicates: %d%n", model.getPredicateRegistry().size());
        System.out.println();

        runBenchmark("Numeric-Heavy Workload", model);
        Files.delete(numericRules);
    }

    private void runBenchmark(String label, EngineModel model) {
        RuleEvaluator evaluator = new RuleEvaluator(model);
        List<Event> events = generateNumericEvents(WARMUP_ITERATIONS + BENCHMARK_ITERATIONS);

        System.out.println("--- Running Benchmark: " + label + " ---");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            evaluator.evaluate(events.get(i));
        }

        // Benchmark
        List<Long> latencies = new ArrayList<>(BENCHMARK_ITERATIONS);
        long startTime = System.nanoTime();
        for (int i = WARMUP_ITERATIONS; i < events.size(); i++) {
            latencies.add(evaluator.evaluate(events.get(i)).evaluationTimeNanos());
        }
        long totalTime = System.nanoTime() - startTime;

        Collections.sort(latencies);
        long p50 = latencies.get(latencies.size() / 2);
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        double throughput = (double) BENCHMARK_ITERATIONS * 1_000_000_000 / totalTime;

        System.out.printf("Throughput: %.0f events/sec%n", throughput);
        System.out.printf("P50 Latency: %.3f ms%n", p50 / 1_000_000.0);
        System.out.printf("P99 Latency: %.3f ms%n", p99 / 1_000_000.0);

        if (p99 / 1_000_000.0 < 0.8) {
            System.out.println("✅ SUCCESS: P99 latency is below the 0.8ms target.");
        } else {
            System.out.println("❌ NOTE: P99 latency is above the 0.8ms target.");
        }
    }

    private List<Event> generateNumericEvents(int count) {
        List<Event> events = new ArrayList<>(count);
        Random random = new Random(123);
        for (int i = 0; i < count; i++) {
            events.add(new Event("evt_" + i, "SALE", Map.of(
                    "amount", random.nextDouble() * 1000,
                    "quantity", random.nextInt(100),
                    "discount", random.nextDouble()
            )));
        }
        return events;
    }

    private Path createNumericHeavyRuleFile(int ruleCount) throws IOException {
        Path path = Files.createTempFile("numeric-rules-", ".json");
        Random random = new Random(456);
        List<String> rules = new ArrayList<>();
        for (int i = 0; i < ruleCount; i++) {
            rules.add(String.format("""
            {
                "rule_code": "NUMERIC_%d",
                "conditions": [
                    {"field": "amount", "operator": "GREATER_THAN", "value": %.2f},
                    {"field": "quantity", "operator": "LESS_THAN", "value": %d}
                ]
            }
            """, i, random.nextDouble() * 800, random.nextInt(1, 90)));
        }
        Files.writeString(path, "[" + String.join(",", rules) + "]");
        return path;
    }
}