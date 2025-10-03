package os.toolset.ruleengine.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public class TracingService {

    private static final String INSTRUMENTATION_NAME = "os.toolset.rule-engine";
    private static final TracingService INSTANCE;

    static {
        TracingService result;
        if (Boolean.getBoolean("otel.disabled")) {
            result = new TracingService(OpenTelemetry.noop().getTracer(INSTRUMENTATION_NAME));
        } else {
            result = new TracingService(
                    OpenTelemetrySdk.builder()
                            .setTracerProvider(
                                    SdkTracerProvider.builder()
                                            .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                                            .build()
                            )
                            .buildAndRegisterGlobal()
                            .getTracer(INSTRUMENTATION_NAME)
            );
        }
        INSTANCE = result;
    }

    private final Tracer tracer;

    private TracingService(Tracer tracer) {
        this.tracer = tracer;
    }

    public static TracingService getInstance() {
        return INSTANCE;
    }

    public Tracer getTracer() {
        return tracer;
    }
}
