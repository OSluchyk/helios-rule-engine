package os.toolset.ruleengine.benchmark;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import os.toolset.ruleengine.core.EngineModel;
import os.toolset.ruleengine.core.RuleCompiler;
import os.toolset.ruleengine.core.RuleEvaluator;
import os.toolset.ruleengine.model.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Ultra-fast benchmark with NO tracing output.
 *
 * Usage:
 *   mvn clean test-compile exec:java \
 *       -Dexec.mainClass="os.toolset.ruleengine.benchmark.SimpleBenchmark" \
 *       -Dexec.classpathScope=test
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(0)
@Warmup(iterations = 0)
@Measurement(iterations = 2, time = 2)
public class SimpleBenchmark {

    // CRITICAL: Create noop tracer BEFORE any other code runs
    private static final Tracer NOOP_TRACER = OpenTelemetry.noop().getTracer("noop");

    private RuleEvaluator evaluator;
    private List<Event> events;
    private int eventIndex;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        java.util.logging.Logger.getLogger("io.opentelemetry")
                .setLevel(java.util.logging.Level.OFF);

        System.out.println("\n⚡ Fast Benchmark");

        Path rulesPath = createRules(100);

        // Use noop tracer directly - never call TracingService
        RuleCompiler compiler = new RuleCompiler(NOOP_TRACER);
        EngineModel model = compiler.compile(rulesPath);

        // Use noop tracer - disable base condition cache for simplicity
        this.evaluator = new RuleEvaluator(model, NOOP_TRACER, false);

        this.events = generateEvents(200);
        this.eventIndex = 0;

        Files.deleteIfExists(rulesPath);
        System.out.println("✅ Ready\n");
    }

    @Benchmark
    public void evaluateBatch(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            Event e = events.get(eventIndex++ % events.size());
            bh.consume(evaluator.evaluate(e));
        }
    }

    private List<Event> generateEvents(int count) {
        List<Event> list = new ArrayList<>(count);
        Random rand = new Random(123);
        String[] countries = {"US", "UK", "CA", "DE", "FR"};
        String[] statuses = {"ACTIVE", "INACTIVE", "PENDING"};

        for (int i = 0; i < count; i++) {
            Map<String, Object> attrs = Map.of(
                    "country", countries[rand.nextInt(countries.length)],
                    "status", statuses[rand.nextInt(statuses.length)],
                    "amount", rand.nextInt(10_000)
            );
            list.add(new Event("evt_" + i, "BENCH", attrs));
        }
        return list;
    }

    private Path createRules(int n) throws IOException {
        Path path = Files.createTempFile("rules", ".json");
        Random rand = new Random(456);
        StringBuilder sb = new StringBuilder("[");

        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(
                    "{\"rule_code\":\"R%d\",\"priority\":%d,\"conditions\":[" +
                            "{\"field\":\"country\",\"operator\":\"IS_ANY_OF\",\"value\":[\"DE\",\"FR\",\"IN\"]}," +
                            "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":%d}]}",
                    i, rand.nextInt(100), rand.nextInt(5000)
            ));
        }
        sb.append(']');

        Files.writeString(path, sb.toString());
        return path;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SimpleBenchmark.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}