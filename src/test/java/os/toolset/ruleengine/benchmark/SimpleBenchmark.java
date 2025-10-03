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
import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 0, jvmArgs = {
        // Keep startup light for quick runs. Add your tuning flags on “full” runs via CLI if needed.
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-Xms2g",
        "-Xmx2g",
        "-XX:+UseStringDeduplication",
        "-Dotel.disabled=true"
})
@Warmup(iterations = 1, time = 2000, timeUnit = TimeUnit.MILLISECONDS)          // quick warmup for dev/CI
@Measurement(iterations = 1, time = 5000, timeUnit = TimeUnit.MILLISECONDS)
public class SimpleBenchmark {

    // ----- TUNABLE KNOBS (no code edits for quick/full) -----
    @Param({"100"})            // quick: 1k; full: pass -p numRules=10000 on CLI
    public int numRules;

    @Param({"200"})            // size of pre-generated event pool
    public int eventPoolSize;

    @Param({"64"})              // how many events processed per @Benchmark invocation in batch mode
    public int batchSize;

    private RuleEvaluator evaluator;
    private List<Event> events;
    private int eventIndex;

    @Setup(Level.Trial)
    public void setup() throws IOException, RuleCompiler.CompilationException {
        System.out.println("--- Setting up JMH Benchmark ---");
        System.setProperty("otel.disabled", "true");

        // Create a realistic, mixed-workload rule set
        Path rulesPath = createRuleFile("jmh_mixed_rules", numRules);
        RuleCompiler compiler = new RuleCompiler(TracingService.getInstance().getTracer());
        EngineModel model = compiler.compile(rulesPath);

        System.out.println("--- Model Stats ---");
        System.out.printf("Unique Combinations (rules): %d%n", model.getNumRules());
        System.out.printf("Unique Predicates: %d%n", model.getPredicateRegistry().size());

        this.evaluator = new RuleEvaluator(model);

        // Pre-generate events (avoid measuring creation time)
        this.events = generateEvents(eventPoolSize);
        this.eventIndex = 0;

        Files.deleteIfExists(rulesPath);
        System.out.println("--- Setup Complete. Starting Benchmarks... ---");
    }

    // ---- Single-event path (kept for reference) ----
    @Benchmark
    public void evaluateEvent(Blackhole bh) {
        Event event = nextEvent();
        bh.consume(evaluator.evaluate(event));
    }

    // ---- Batch path: MUCH faster convergence to “real” numbers ----
    @Benchmark
    public void evaluateBatch(Blackhole bh) {
        // Process a small batch per invocation to amortize harness overhead
        for (int i = 0; i < batchSize; i++) {
            Event e = nextEvent();
            bh.consume(evaluator.evaluate(e));
        }
    }

    // ---------- Helpers ----------
    private Event nextEvent() {
        Event event = events.get(eventIndex++);
        if (eventIndex >= events.size()) eventIndex = 0;
        return event;
    }

    private List<Event> generateEvents(int count) {
        List<Event> eventList = new ArrayList<>(count);
        Random random = new Random(123);
        String[] countries = {"US", "UK", "CA", "DE", "FR", "JP", "AU", "NZ", "SG", "IN"};
        String[] statuses = {"ACTIVE", "INACTIVE", "PENDING", "CLOSED"};
        for (int i = 0; i < count; i++) {
            Map<String, Object> attrs = new HashMap<>(4);
            attrs.put("country", countries[random.nextInt(countries.length)]);
            attrs.put("status", statuses[random.nextInt(statuses.length)]);
            attrs.put("amount", random.nextInt(10_000));
            eventList.add(new Event("evt_" + i, "BENCHMARK", attrs));
        }
        return eventList;
    }

    private Path createRuleFile(String name, int n) throws IOException {
        Path path = Files.createTempFile(name, ".json");
        Random random = new Random(456);
        // Keep rules realistic but compact
        StringBuilder sb = new StringBuilder(64 * n + 32);
        sb.append('[');
        for (int i = 0; i < n; i++) {
            int val = random.nextInt(5000);
            sb.append("{\"rule_code\":\"MIXED_").append(i)
                    .append("\",\"conditions\":[")
                    .append("{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"DE\",\"FR\",\"IN\"]},")
                    .append("{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":").append(val).append("}]}")
                    .append(i == n - 1 ? "" : ",");
        }
        sb.append(']');
        Files.writeString(path, sb.toString());
        return path;
    }
}
