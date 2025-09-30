package os.toolset.ruleengine.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public final class TracingService {

    private static final String INSTRUMENTATION_NAME = "os.toolset.rule-engine";
    private static final TracingService INSTANCE = new TracingService();

    private final Tracer tracer;

    private TracingService() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public static TracingService getInstance() {
        return INSTANCE;
    }

    public Tracer getTracer() {
        return tracer;
    }
}