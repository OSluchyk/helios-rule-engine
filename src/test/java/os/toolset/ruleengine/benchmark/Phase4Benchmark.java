package os.toolset.ruleengine.benchmark;

import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.model.Event;
import os.toolset.ruleengine.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Phase4Benchmark {

    private static final int BENCHMARK_ITERATIONS = 10000;

    public static void main(String[] args) throws Exception {
        new Phase4Benchmark().runBenchmark();
    }

    private void runBenchmark() throws Exception {
        Path largeRulesPath = createRuleFile("large", 10000);
        RuleCompiler compiler = new RuleCompiler();
        EngineModel largeModel = compiler.compile(largeRulesPath);
        printModelStats("Large (10,000 logical rules)", largeModel);
        runSingleThreadedBenchmark("Large Model", largeModel);
        Files.deleteIfExists(largeRulesPath);
    }

    private void printModelStats(String label, EngineModel model) {
        System.out.printf("%s:%n", label);
        System.out.printf("  Unique combinations: %d%n", model.getNumRules());
        System.out.printf("  Unique predicates: %d%n", model.getPredicateRegistry().size());
        System.out.printf("  Metadata: %s%n", model.getStats().metadata());
    }

    private void runSingleThreadedBenchmark(String label, EngineModel model) {
        RuleEvaluator evaluator = new RuleEvaluator(model);
        List<Event> testEvents = generateTestEvents(BENCHMARK_ITERATIONS);
        List<Long> latencies = new ArrayList<>();
        long startTime = System.nanoTime();

        for (Event event : testEvents) {
            long evalStart = System.nanoTime();
            evaluator.evaluate(event);
            latencies.add(System.nanoTime() - evalStart);
        }
        long totalTime = System.nanoTime() - startTime;
        Collections.sort(latencies);
        long p99 = latencies.get((int)(latencies.size() * 0.99));
        double throughput = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / totalTime;

        System.out.printf("%s Results:%n", label);
        System.out.printf("  Throughput: %.0f events/sec%n", throughput);
        System.out.printf("  P99 Latency: %.3f ms%n", p99 / 1_000_000.0);
    }

    private List<Event> generateTestEvents(int count) {
        List<Event> events = new ArrayList<>();
        Random random = new Random(42);
        String[] countries = {"US", "UK", "CA", "DE", "FR", "JP", "AU"};
        for (int i = 0; i < count; i++) {
            events.add(new Event("evt_" + i, "TRANSACTION", Map.of(
                    "amount", random.nextInt(100000),
                    "country", countries[random.nextInt(countries.length)],
                    "status", "ACTIVE"
            )));
        }
        return events;
    }

    private Path createRuleFile(String name, int numRules) throws IOException {
        Path path = Files.createTempFile("rules_" + name + "_", ".json");
        List<String> rules = new ArrayList<>();
        Random random = new Random(123);
        for (int i = 0; i < numRules; i++) {
            rules.add(String.format("""
                {"rule_code":"RULE_%d","priority":%d,"conditions":[
                    {"field":"amount","operator":"GREATER_THAN","value":%d},
                    {"field":"country","operator":"IS_ANY_OF","value":["US","UK","CA"]}
                ]}""", i, random.nextInt(100), random.nextInt(50000)));
        }
        Files.writeString(path, "[" + String.join(",", rules) + "]");
        return path;
    }
}