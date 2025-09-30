package os.toolset.ruleengine.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.core.TracingService;
import os.toolset.ruleengine.model.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseCompactObjectHeaders",
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "--add-modules=jdk.incubator.vector",
        "-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0"
})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class HeliosJMHBenchmark {

    private RuleEvaluator evaluator;
    private List<Event> events;
    private int eventIndex;

    @Setup(Level.Trial)
    public void setup() throws IOException, RuleCompiler.CompilationException {
        System.out.println("--- Setting up JMH Benchmark ---");

        // --- Create a realistic, mixed-workload rule set ---
        Path rulesPath = createRuleFile("jmh_mixed_rules", 10_000);
        RuleCompiler compiler = new RuleCompiler(TracingService.getInstance().getTracer());
        EngineModel model = compiler.compile(rulesPath);

        System.out.println("--- Model Stats ---");
        System.out.printf("Unique Combinations: %d%n", model.getNumRules());
        System.out.printf("Unique Predicates: %d%n", model.getPredicateRegistry().size());

        this.evaluator = new RuleEvaluator(model);

        // --- Pre-generate events to avoid measuring event creation time ---
        this.events = generateEvents(2000); // A pool of 2000 unique events
        this.eventIndex = 0;

        Files.delete(rulesPath);
        System.out.println("--- Setup Complete. Starting Benchmarks... ---");
    }

    @Benchmark
    public void evaluateEvent(Blackhole blackhole) {
        // Cycle through the pre-generated events
        Event event = events.get(eventIndex++);
        if (eventIndex >= events.size()) {
            eventIndex = 0;
        }
        // The "blackhole" consumes the result to prevent the JIT from optimizing away the call
        blackhole.consume(evaluator.evaluate(event));
    }

    // --- Data Generation Helper Methods ---

    private List<Event> generateEvents(int count) {
        List<Event> eventList = new ArrayList<>(count);
        Random random = new Random(123);
        String[] countries = {"US", "UK", "CA", "DE", "FR", "JP", "AU", "NZ", "SG", "IN"};
        String[] statuses = {"ACTIVE", "INACTIVE", "PENDING", "CLOSED"};
        for (int i = 0; i < count; i++) {
            eventList.add(new Event("evt_" + i, "BENCHMARK", Map.of(
                    "country", countries[random.nextInt(countries.length)],
                    "status", statuses[random.nextInt(statuses.length)],
                    "amount", random.nextInt(10000)
            )));
        }
        return eventList;
    }

    private Path createRuleFile(String name, int numRules) throws IOException {
        Path path = Files.createTempFile(name, ".json");
        Random random = new Random(456);
        List<String> rules = new ArrayList<>(numRules);
        for (int i = 0; i < numRules; i++) {
            rules.add(String.format("""
             {"rule_code":"MIXED_%d", "conditions": [{"field":"country", "operator":"IS_ANY_OF", "value":["DE","FR","IN"]}, {"field":"amount", "operator":"GREATER_THAN", "value":%d}]}
             """, i, random.nextInt(5000)));
        }
        Files.writeString(path, "[" + String.join(",", rules) + "]");
        return path;
    }
}