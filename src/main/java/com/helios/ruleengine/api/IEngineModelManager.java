package com.helios.ruleengine.api;


import com.helios.ruleengine.core.model.EngineModel;

/**
 * Contract for managing engine model lifecycle (hot reload).
 */
public interface IEngineModelManager {

    /**
     * Starts watching for rule file changes.
     */
    void start();

    /**
     * Stops watching and releases resources.
     */
    void shutdown();

    /**
     * Gets the current active engine model.
     *
     * @return current engine model (thread-safe)
     */
    EngineModel getEngineModel();
}