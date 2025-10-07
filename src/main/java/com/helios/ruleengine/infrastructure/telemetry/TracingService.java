package com.helios.ruleengine.infrastructure.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production-grade OpenTelemetry tracing service.
 *
 * Performance characteristics:
 * - Async batch export (non-blocking hot path)
 * - Configurable sampling (default: 10% at scale)
 * - Graceful shutdown with 30s drain timeout
 * - ~200 bytes per span in memory
 *
 * Configuration via environment variables:
 * - OTEL_DISABLED: Disable tracing entirely (default: false)
 * - OTEL_EXPORTER_TYPE: otlp|logging|jaeger (default: otlp)
 * - OTEL_EXPORTER_ENDPOINT: OTLP endpoint (default: http://localhost:4317)
 * - OTEL_TRACE_SAMPLING_RATIO: 0.0-1.0 (default: 0.1 for production)
 * - SERVICE_NAME: Service identifier (default: rule-engine)
 * - SERVICE_VERSION: Deployment version (default: unknown)
 * - DEPLOYMENT_ENVIRONMENT: prod|staging|dev (default: dev)
 */
public class TracingService {
    private static final Logger logger = Logger.getLogger(TracingService.class.getName());

    private static final String INSTRUMENTATION_NAME = "com.helios.rule-engine";
    private static final String DEFAULT_SERVICE_NAME = "rule-engine";

    // Semantic Convention Attribute Keys (using string literals instead of semconv dependency)
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey("service.version");
    private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT = AttributeKey.stringKey("deployment.environment");
    private static final AttributeKey<String> TELEMETRY_SDK_NAME = AttributeKey.stringKey("telemetry.sdk.name");
    private static final AttributeKey<String> TELEMETRY_SDK_LANGUAGE = AttributeKey.stringKey("telemetry.sdk.language");
    private static final AttributeKey<String> TELEMETRY_SDK_VERSION = AttributeKey.stringKey("telemetry.sdk.version");

    // Singleton with lazy initialization for thread-safety
    private static volatile TracingService INSTANCE;
    private static final Object LOCK = new Object();

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final SdkTracerProvider tracerProvider;
    private final boolean isNoop;

    /**
     * Private constructor - use getInstance()
     */
    private TracingService(OpenTelemetry openTelemetry, Tracer tracer,
                           SdkTracerProvider tracerProvider, boolean isNoop) {
        this.openTelemetry = openTelemetry;
        this.tracer = tracer;
        this.tracerProvider = tracerProvider;
        this.isNoop = isNoop;

        if (!isNoop) {
            registerShutdownHook();
        }
    }

    /**
     * Get singleton instance with double-checked locking.
     */
    public static TracingService getInstance() {
        TracingService instance = INSTANCE;
        if (instance == null) {
            synchronized (LOCK) {
                instance = INSTANCE;
                if (instance == null) {
                    instance = initialize();
                    INSTANCE = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Initialize OpenTelemetry based on configuration.
     */
    private static TracingService initialize() {
        try {
            // Check if tracing is disabled
            if (isTracingDisabled()) {
                logger.info("OpenTelemetry tracing is DISABLED (OTEL_DISABLED=true)");
                return createNoopInstance();
            }

            logger.info("Initializing OpenTelemetry tracing...");

            // Build resource with service attributes
            Resource resource = buildResource();

            // Configure sampler
            Sampler sampler = configureSampler();

            // Configure exporter
            SpanExporter spanExporter = configureExporter();

            // Build tracer provider with batch processor
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .setSampler(sampler)
                    .addSpanProcessor(
                            BatchSpanProcessor.builder(spanExporter)
                                    // Tuned for high throughput
                                    .setMaxQueueSize(8192)           // Buffer 8K spans
                                    .setMaxExportBatchSize(512)      // Export in batches of 512
                                    .setScheduleDelay(Duration.ofSeconds(5))  // Export every 5s
                                    .setExporterTimeout(Duration.ofSeconds(30))  // 30s timeout
                                    .build()
                    )
                    .build();

            // Build OpenTelemetry SDK with context propagation
            OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.create(
                            W3CTraceContextPropagator.getInstance()
                    ))
                    .buildAndRegisterGlobal();

            Tracer tracer = openTelemetrySdk.getTracer(INSTRUMENTATION_NAME);

            logger.info(String.format(
                    "OpenTelemetry initialized: service=%s, version=%s, env=%s, sampler=%s",
                    getServiceName(), getServiceVersion(), getEnvironment(),
                    sampler.getDescription()
            ));

            return new TracingService(openTelemetrySdk, tracer, tracerProvider, false);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize OpenTelemetry - falling back to noop", e);
            return createNoopInstance();
        }
    }

    /**
     * Create noop instance (no tracing overhead).
     */
    private static TracingService createNoopInstance() {
        OpenTelemetry noop = OpenTelemetry.noop();
        return new TracingService(
                noop,
                noop.getTracer(INSTRUMENTATION_NAME),
                null,
                true
        );
    }

    /**
     * Build resource with service identity attributes.
     */
    private static Resource buildResource() {
        return Resource.getDefault().merge(
                Resource.create(
                        Attributes.builder()
                                .put(SERVICE_NAME, getServiceName())
                                .put(SERVICE_VERSION, getServiceVersion())
                                .put(DEPLOYMENT_ENVIRONMENT, getEnvironment())
                                .put(TELEMETRY_SDK_NAME, "opentelemetry")
                                .put(TELEMETRY_SDK_LANGUAGE, "java")
                                .put(TELEMETRY_SDK_VERSION, "1.34.1")
                                .build()
                )
        );
    }

    /**
     * Configure sampler based on environment.
     */
    private static Sampler configureSampler() {
        String samplingRatioStr = getEnvOrProperty("OTEL_TRACE_SAMPLING_RATIO",
                getDefaultSamplingRatio());

        double samplingRatio;
        try {
            samplingRatio = Double.parseDouble(samplingRatioStr);
            samplingRatio = Math.max(0.0, Math.min(1.0, samplingRatio));
        } catch (NumberFormatException e) {
            logger.warning("Invalid OTEL_TRACE_SAMPLING_RATIO, using default");
            samplingRatio = Double.parseDouble(getDefaultSamplingRatio());
        }

        // Parent-based sampling with ratio fallback
        return Sampler.parentBasedBuilder(Sampler.traceIdRatioBased(samplingRatio))
                .build();
    }

    /**
     * Get default sampling ratio based on environment.
     */
    private static String getDefaultSamplingRatio() {
        String env = getEnvironment().toLowerCase();
        return switch (env) {
            case "prod", "production" -> "0.1";   // 10% in production
            case "staging" -> "0.5";              // 50% in staging
            default -> "1.0";                     // 100% in dev
        };
    }

    /**
     * Configure span exporter based on type.
     */
    private static SpanExporter configureExporter() {
        String exporterType = getEnvOrProperty("OTEL_EXPORTER_TYPE", "logging").toLowerCase();

        return switch (exporterType) {
            case "otlp" -> {
                String endpoint = getEnvOrProperty("OTEL_EXPORTER_OTLP_ENDPOINT",
                        "http://localhost:4317");
                logger.info("Using OTLP exporter: " + endpoint);
                yield OtlpGrpcSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .setTimeout(30, TimeUnit.SECONDS)
                        .build();
            }
            case "jaeger" -> {
                // If Jaeger exporter is available on classpath
                logger.warning("Jaeger exporter requires additional dependency - falling back to logging");
                yield LoggingSpanExporter.create();
            }
            case "logging" -> {
                logger.info("Using Logging exporter (dev/debug mode)");
                yield LoggingSpanExporter.create();
            }
            default -> {
                logger.warning("Unknown exporter type: " + exporterType + ", using logging");
                yield LoggingSpanExporter.create();
            }
        };
    }

    /**
     * Register JVM shutdown hook for graceful cleanup.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down OpenTelemetry...");
            shutdown();
        }, "otel-shutdown-hook"));
    }

    /**
     * Graceful shutdown with timeout.
     */
    public void shutdown() {
        if (isNoop || tracerProvider == null) {
            return;
        }

        try {
            logger.info("Draining span buffer...");
            tracerProvider.shutdown().join(30, TimeUnit.SECONDS);
            logger.info("OpenTelemetry shutdown complete");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during OpenTelemetry shutdown", e);
        }
    }

    /**
     * Force flush all pending spans.
     */
    public void flush() {
        if (isNoop || tracerProvider == null) {
            return;
        }

        try {
            tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error flushing spans", e);
        }
    }

    // ==================== Public API ====================

    public Tracer getTracer() {
        return tracer;
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public boolean isEnabled() {
        return !isNoop;
    }

    // ==================== Configuration Helpers ====================

    private static boolean isTracingDisabled() {
        return Boolean.parseBoolean(getEnvOrProperty("OTEL_DISABLED", "false"));
    }

    private static String getServiceName() {
        return getEnvOrProperty("SERVICE_NAME",
                getEnvOrProperty("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME));
    }

    private static String getServiceVersion() {
        return getEnvOrProperty("SERVICE_VERSION",
                getEnvOrProperty("OTEL_SERVICE_VERSION", "unknown"));
    }

    private static String getEnvironment() {
        return getEnvOrProperty("DEPLOYMENT_ENVIRONMENT",
                getEnvOrProperty("OTEL_RESOURCE_ATTRIBUTES_DEPLOYMENT_ENVIRONMENT", "dev"));
    }

    /**
     * Get value from environment variable, falling back to system property.
     */
    private static String getEnvOrProperty(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            value = System.getProperty(key, defaultValue);
        }
        return value;
    }
}