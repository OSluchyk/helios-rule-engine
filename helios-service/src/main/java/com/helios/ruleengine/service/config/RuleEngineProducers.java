package com.helios.ruleengine.service.config;

import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.infra.telemetry.TracingService;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;

/**
 * CDI producers for rule engine components.
 * Creates singleton instances of core services.
 */
@ApplicationScoped
public class RuleEngineProducers {

    @ConfigProperty(name = "rules.file", defaultValue = "rules.json")
    String rulesFile;

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
     * Produces the EngineModelManager.
     * Performs initial compilation of rules during startup.
     */
    @Produces
    @ApplicationScoped
    public EngineModelManager engineModelManager(Tracer tracer, IRuleCompiler compiler) {
        Path rulesPath = Paths.get(rulesFile);
        try {
            return new EngineModelManager(rulesPath, tracer, compiler);
        } catch (CompilationException | IOException e) {
            throw new RuntimeException("Failed to initialize EngineModelManager with rules file: " + rulesFile, e);
        }
    }
}
