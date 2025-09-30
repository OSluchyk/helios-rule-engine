package os.toolset.ruleengine.core;

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
import java.util.logging.Level;
import java.util.logging.Logger;

public class EngineModelManager {
    private static final Logger logger = Logger.getLogger(EngineModelManager.class.getName());

    private final Path rulesPath;
    private final RuleCompiler compiler;
    private final AtomicReference<EngineModel> activeModel = new AtomicReference<>();
    private final ScheduledExecutorService monitoringExecutor;
    private final Tracer tracer;

    private long lastModifiedTime = -1;

    public EngineModelManager(Path rulesPath, Tracer tracer) throws RuleCompiler.CompilationException, IOException {
        this.rulesPath = rulesPath;
        this.tracer = tracer;
        this.compiler = new RuleCompiler(tracer); // Pass tracer to compiler
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Rule-File-Monitor");
            t.setDaemon(true);
            return t;
        });
        loadModel(); // Initial load
    }

    public EngineModel getEngineModel() {
        return activeModel.get();
    }

    public void start() {
        monitoringExecutor.scheduleAtFixedRate(this::checkForUpdates, 10, 10, TimeUnit.SECONDS);
    }

    public void shutdown() {
        monitoringExecutor.shutdown();
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
        Span span = tracer.spanBuilder("load-new-model").startSpan();
        try (Scope scope = span.makeCurrent()) {
            long modifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            EngineModel newModel = compiler.compile(rulesPath);
            activeModel.set(newModel);
            this.lastModifiedTime = modifiedTime;
            span.setAttribute("newModel.uniqueCombinations", newModel.getNumRules());
            logger.info("Successfully reloaded and swapped to new rule model.");
        } catch (IOException | RuleCompiler.CompilationException e) {
            span.recordException(e);
            logger.log(Level.SEVERE, "Failed to compile new rule model. Old model remains active.", e);
        } finally {
            span.end();
        }
    }
}