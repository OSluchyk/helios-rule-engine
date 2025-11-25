package com.helios.ruleengine.benchmark;

import com.helios.ruleengine.runtime.evaluation.RuleEvaluator;
import io.opentelemetry.api.trace.Tracer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import com.helios.ruleengine.runtime.model.EngineModel;
import com.helios.ruleengine.compiler.RuleCompiler;
import com.helios.ruleengine.infra.telemetry.TracingService;
import com.helios.ruleengine.api.model.Event;
import com.helios.ruleengine.api.model.MatchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GRANULAR CLIFF ANALYSIS BENCHMARK
 *
 * Identifies the exact rule count where performance degrades.
 * Tests fine-grained rule counts from 500 to 5000.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+PrintCompilation",
        "-XX:+LogCompilation",
        "-XX:LogFile=cliff_compilation.log",
        "-Xms8g",
        "-Xmx8g",
        // Optional additions:
        "--add-modules=jdk.incubator.vector",  // If using vectorization
        "-Djdk.attach.allowAttachSelf"         // If using profiler
})
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 10, time = 5)
public class GranularCliffBenchmark {

    // Fine-grained progression to find the cliff
    @Param({
            "500",   // Baseline
            "1000",  // 2x
            "1500",  // 3x
            "1800",  // Just before suspected cliff
            "2000",  // Known problem area
            "2200",  // Just after cliff
            "2500",  // 5x
            "3000",  // 6x
            "4000",  // 8x
            "5000"   // 10x - paradoxically faster
    })
    private int ruleCount;

    private static final Tracer TRACER = TracingService.getInstance().getTracer();

    private RuleEvaluator evaluator;
    private EngineModel model;
    private List<Event> eventPool;
    private final AtomicInteger eventIndex = new AtomicInteger(0);

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("🔍 CLIFF ANALYSIS - Rule Count: %d\n", ruleCount);
        System.out.println("=".repeat(80));

        // Create test rules
        Path rulesPath = createTestRules(ruleCount);

        // Compile
        RuleCompiler compiler = new RuleCompiler(TRACER);
        model = compiler.compile(rulesPath);

        // Create evaluator
        evaluator = new RuleEvaluator(model, TRACER, true);

        // Generate diverse events
        eventPool = generateEvents(10_000);

        System.out.printf("✓ Setup complete: %d rules, %d events\n",
                model.getNumRules(), eventPool.size());

        Files.deleteIfExists(rulesPath);
    }

    @Benchmark
    public void evaluateSingle(Blackhole bh) {
        Event event = eventPool.get(eventIndex.getAndIncrement() % eventPool.size());
        MatchResult result = evaluator.evaluate(event);
        bh.consume(result);
    }

    @TearDown(Level.Trial)
    public void teardownTrial() {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("📊 COMPLETED - Rule Count: %d\n", ruleCount);
        System.out.println("=".repeat(80));
    }

    // Helper: Create test rules
    private Path createTestRules(int count) throws IOException {
        Path path = Files.createTempFile("cliff_rules_", ".json");
        List<String> rules = new ArrayList<>();
        Random rand = new Random(42);

        for (int i = 0; i < count; i++) {
            String rule = String.format(
                    "[{\"rule_code\":\"%d\",\"conditions\":[{\"field\":\"field_%d\",\"operator\":\"EQUAL_TO\",\"value\":\"%d\"}]}]",
                    i, rand.nextInt(50), rand.nextInt(1000)
            );
            rules.add(rule);
        }

        Files.write(path, String.join("\n", rules).getBytes());
        return path;
    }

    // Helper: Generate test events
    private List<Event> generateEvents(int count) {
        List<Event> events = new ArrayList<>();
        Random rand = new Random(123);

        for (int i = 0; i < count; i++) {
            Map<String, Object> attributes = new HashMap<>();
            int fieldCount = 5 + rand.nextInt(10);

            for (int j = 0; j < fieldCount; j++) {
                attributes.put("field_" + rand.nextInt(50),
                        String.valueOf(rand.nextInt(1000)));
            }

            events.add(new Event("evt-" + i, "TEST", attributes));
        }

        return events;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(GranularCliffBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .shouldFailOnError(true)
                .build();

        System.out.println("\n🔬 GRANULAR CLIFF ANALYSIS");
        System.out.println("Expected runtime: 30-40 minutes\n");

        new Runner(opt).run();
    }
}