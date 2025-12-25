package com.helios.ruleengine.api;

import com.helios.ruleengine.runtime.model.EngineModel;

import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Path;

/**
 * Contract for compiling JSON rules into an executable engine model.
 */
public interface IRuleCompiler {

    /**
     * Compiles rules from a JSON file.
     *
     * @param rulesPath path to JSON rules file
     * @return compiled engine model
     * @throws Exception if compilation fails (e.g., IO or Validation)
     */
    EngineModel compile(Path rulesPath) throws Exception;

    /**
     * Sets the tracer for observability.
     *
     * @param tracer the OpenTelemetry tracer
     */
    default void setTracer(Tracer tracer) {
    }

    /**
     * Sets a compilation listener for tracking compilation progress.
     *
     * @param listener the compilation listener (null to disable)
     */
    default void setCompilationListener(CompilationListener listener) {
    }
}