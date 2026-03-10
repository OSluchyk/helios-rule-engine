package com.helios.ruleengine.service.config;

import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.infra.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.ServiceLoader;

/**
 * CDI producers for rule engine components.
 * Creates singleton instances of core services.
 */
@ApplicationScoped
public class RuleEngineProducers {

    /**
     * Produces the TracingService singleton.
     */
    @Produces
    @ApplicationScoped
    public TracingService tracingService() {
        return TracingService.getInstance();
    }

    /**
     * Produces the OpenTelemetry Tracer.
     */
    @Produces
    @ApplicationScoped
    public Tracer tracer(TracingService tracingService) {
        return tracingService.getTracer();
    }

    /**
     * Produces the IRuleCompiler implementation using ServiceLoader.
     */
    @Produces
    @ApplicationScoped
    public IRuleCompiler ruleCompiler() {
        return ServiceLoader.load(IRuleCompiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No IRuleCompiler implementation found"));
    }

    /**
     * Produces the EngineModelManager in database mode.
     * Rules are loaded from the database on startup by RuleEngineLifecycle.
     */
    @Produces
    @ApplicationScoped
    public EngineModelManager engineModelManager(Tracer tracer, IRuleCompiler compiler) {
        return new EngineModelManager(tracer, compiler);
    }
}
