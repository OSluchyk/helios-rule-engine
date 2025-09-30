package os.toolset.ruleengine.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of the EngineModel, providing hot-reloading capabilities
 * for zero-downtime rule updates.
 */
public class EngineModelManager {
    private static final Logger logger = Logger.getLogger(EngineModelManager.class.getName());

    private final Path rulesPath;
    private final RuleCompiler compiler;
    private final AtomicReference<EngineModel> activeModel = new AtomicReference<>();
    private final ScheduledExecutorService monitoringExecutor;

    private long lastModifiedTime = -1;

    public EngineModelManager(Path rulesPath) throws RuleCompiler.CompilationException, IOException {
        this.rulesPath = rulesPath;
        this.compiler = new RuleCompiler();
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Rule-File-Monitor");
            t.setDaemon(true);
            return t;
        });

        // Initial compilation
        logger.info("Performing initial rule compilation...");
        loadModel();
    }

    /**
     * Gets the currently active EngineModel. This is thread-safe.
     *
     * @return The active EngineModel.
     */
    public EngineModel getEngineModel() {
        return activeModel.get();
    }

    /**
     * Starts the background thread to monitor for rule file changes.
     */
    public void start() {
        logger.info("Starting rule file monitor for: " + rulesPath);
        // Check for changes every 10 seconds
        monitoringExecutor.scheduleAtFixedRate(this::checkForUpdates, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the monitoring thread.
     */
    public void shutdown() {
        logger.info("Shutting down rule file monitor.");
        monitoringExecutor.shutdown();
        try {
            if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitoringExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void checkForUpdates() {
        try {
            long currentModifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            if (currentModifiedTime > lastModifiedTime) {
                logger.info("Change detected in rule file. Attempting to reload...");
                loadModel();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not check rule file for modifications.", e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "An unexpected error occurred during rule reload check.", e);
        }
    }

    private void loadModel() {
        try {
            long modifiedTime = Files.getLastModifiedTime(rulesPath).toMillis();
            EngineModel newModel = compiler.compile(rulesPath);

            // Atomically swap to the new model
            activeModel.set(newModel);
            this.lastModifiedTime = modifiedTime;

            logger.info(String.format("Successfully reloaded and swapped to new rule model. Unique combinations: %d", newModel.getNumRules()));

        } catch (IOException | RuleCompiler.CompilationException e) {
            logger.log(Level.SEVERE, "Failed to compile new rule model. The old model will remain active.", e);
        }
    }
}