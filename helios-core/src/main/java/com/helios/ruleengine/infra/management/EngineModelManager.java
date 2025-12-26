package com.helios.ruleengine.infra.management;

import com.helios.ruleengine.api.CompilationListener;
import com.helios.ruleengine.api.IEngineModelManager;
import com.helios.ruleengine.api.exceptions.CompilationException;
import com.helios.ruleengine.api.IRuleCompiler;
import com.helios.ruleengine.runtime.model.EngineModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EngineModelManager implements IEngineModelManager {
    private static final Logger logger = Logger.getLogger(EngineModelManager.class.getName());

    private final Path rulesPath;
    private final IRuleCompiler compiler;

    /**
     * Holds the currently active engine model.
     * <p>
     * <b>Performance Note:</b>
     * This reference is updated atomically. Readers (evaluators) always see a
     * consistent snapshot
     * of the model without needing locks, ensuring zero overhead for reads even
     * during updates.
     */
    private final AtomicReference<EngineModel> activeModel = new AtomicReference<>();
    private final ScheduledExecutorService monitoringExecutor;
    private final Tracer tracer;

    private long lastModifiedTime = -1;

    /**
     * Optional callback to trigger cache warming after model reload.
     * This helps address COLD cache P99 latency spikes by pre-populating caches.
     */
    private Consumer<EngineModel> cacheWarmupCallback = null;

    public EngineModelManager(Path rulesPath, Tracer tracer, IRuleCompiler compiler)
            throws CompilationException, IOException {
        this.rulesPath = rulesPath;
        this.tracer = tracer;
        this.compiler = compiler;
        this.compiler.setTracer(tracer);
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Rule-File-Monitor");
            t.setDaemon(true);
            return t;
        });

        reloadModelInternal(); // Initial load, fail fast

    }

    public EngineModel getEngineModel() {
        return activeModel.get();
    }

    /**
     * Sets a callback to be invoked after model reload for cache warming.
     * <p>
     * <b>Purpose:</b> Address COLD cache P99 latency spikes by pre-populating caches
     * with representative data immediately after model reload.
     * <p>
     * <b>Usage:</b>
     * <pre>{@code
     * manager.setCacheWarmupCallback(model -> {
     *     // Evaluate representative events to warm up caches
     *     Event[] warmupEvents = loadWarmupEvents();
     *     RuleEvaluator evaluator = new RuleEvaluator(model, tracer, true);
     *     for (Event event : warmupEvents) {
     *         evaluator.evaluate(event);
     *     }
     *     logger.info("Cache warmup completed");
     * });
     * }</pre>
     *
     * @param callback function to execute after model reload (receives the new model)
     */
    public void setCacheWarmupCallback(Consumer<EngineModel> callback) {
        this.cacheWarmupCallback = callback;
    }

    public void start() {
        monitoringExecutor.scheduleAtFixedRate(this::checkForUpdates, 10, 10, TimeUnit.SECONDS);
    }

    public void shutdown() {
        monitoringExecutor.shutdown();
    }

    /**
     * Manually trigger a recompilation with a compilation listener.
     * Used for monitoring compilation progress in real-time.
     *
     * @param listener compilation listener to receive stage events
     * @throws CompilationException if compilation fails
     * @throws IOException if rules file cannot be read
     */
    public void recompile(CompilationListener listener) throws CompilationException, IOException {
        Span span = tracer.spanBuilder("manual-recompile").startSpan();
        try (Scope scope = span.makeCurrent()) {
            // Set the listener on the compiler
            compiler.setCompilationListener(listener);

            // Trigger recompilation
            long modifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            EngineModel newModel = compiler.compile(rulesPath);
            activeModel.set(newModel);
            this.lastModifiedTime = modifiedTime;
            span.setAttribute("newModel.uniqueCombinations", newModel.getNumRules());
            logger.info("Successfully recompiled and swapped to new rule model.");

            // Clear the listener after compilation
            compiler.setCompilationListener(null);

            // Trigger cache warmup if callback is configured
            if (cacheWarmupCallback != null) {
                Span warmupSpan = tracer.spanBuilder("cache-warmup").startSpan();
                try (Scope warmupScope = warmupSpan.makeCurrent()) {
                    logger.info("Starting cache warmup...");
                    long warmupStart = System.nanoTime();
                    cacheWarmupCallback.accept(newModel);
                    long warmupDuration = System.nanoTime() - warmupStart;
                    warmupSpan.setAttribute("warmupDurationMs", warmupDuration / 1_000_000.0);
                    logger.info(String.format("Cache warmup completed in %.2f ms", warmupDuration / 1_000_000.0));
                } catch (Exception e) {
                    warmupSpan.recordException(e);
                    logger.log(Level.WARNING, "Cache warmup failed, continuing with cold cache", e);
                } finally {
                    warmupSpan.end();
                }
            }
        } catch (IOException | RuntimeException e) {
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.recordException(e);
            throw new RuntimeException("Unexpected error during manual recompile", e);
        } finally {
            span.end();
        }
    }

    private void checkForUpdates() {
        Span span = tracer.spanBuilder("check-for-rule-updates").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("ruleFile", rulesPath.toString());
            long currentModifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            if (currentModifiedTime > lastModifiedTime) {
                span.addEvent("Change detected. Triggering reload.");
                logger.info("Change detected in rule file. Attempting to reload...");
                loadModel();
            }
        } catch (IOException e) {
            span.recordException(e);
            logger.log(Level.WARNING, "Could not check rule file for modifications.", e);
        } catch (Exception e) {
            span.recordException(e);
            logger.log(Level.SEVERE, "An unexpected error occurred during rule reload check.", e);
        } finally {
            span.end();
        }
    }

    private void loadModel() {
        try {
            reloadModelInternal();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to compile new rule model. Old model remains active.", e);
        }
    }

    private void reloadModelInternal() throws CompilationException, IOException {
        Span span = tracer.spanBuilder("load-new-model").startSpan();
        try (Scope scope = span.makeCurrent()) {
            long modifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            EngineModel newModel = compiler.compile(rulesPath);
            activeModel.set(newModel);
            this.lastModifiedTime = modifiedTime;
            span.setAttribute("newModel.uniqueCombinations", newModel.getNumRules());
            logger.info("Successfully reloaded and swapped to new rule model.");

            // Trigger cache warmup if callback is configured
            if (cacheWarmupCallback != null) {
                Span warmupSpan = tracer.spanBuilder("cache-warmup").startSpan();
                try (Scope warmupScope = warmupSpan.makeCurrent()) {
                    logger.info("Starting cache warmup...");
                    long warmupStart = System.nanoTime();
                    cacheWarmupCallback.accept(newModel);
                    long warmupDuration = System.nanoTime() - warmupStart;
                    warmupSpan.setAttribute("warmupDurationMs", warmupDuration / 1_000_000.0);
                    logger.info(String.format("Cache warmup completed in %.2f ms", warmupDuration / 1_000_000.0));
                } catch (Exception e) {
                    warmupSpan.recordException(e);
                    logger.log(Level.WARNING, "Cache warmup failed, continuing with cold cache", e);
                } finally {
                    warmupSpan.end();
                }
            }
        } catch (IOException | RuntimeException e) {
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.recordException(e);
            throw new RuntimeException("Unexpected error during rule reload", e);
        } finally {
            span.end();
        }
    }
}