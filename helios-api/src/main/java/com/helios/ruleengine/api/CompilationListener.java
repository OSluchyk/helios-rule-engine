/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.api;

import java.util.Map;

/**
 * Callback interface for compilation stage events.
 * Allows UI and monitoring systems to track compilation progress in real-time.
 *
 * <p>The compilation pipeline consists of 7 stages:
 * <ol>
 *   <li>PARSING - Load and parse JSON rules</li>
 *   <li>VALIDATION - Validate syntax and semantics</li>
 *   <li>FACTORIZATION - Apply IS_ANY_OF factorization</li>
 *   <li>DICTIONARY_ENCODING - Build field and value dictionaries</li>
 *   <li>SELECTIVITY_PROFILING - Calculate predicate weights</li>
 *   <li>MODEL_BUILDING - Build core model with deduplication</li>
 *   <li>INDEX_BUILDING - Build inverted index</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>
 * CompilationListener listener = new CompilationListener() {
 *     {@literal @}Override
 *     public void onStageStart(String stageName, int stageNumber, int totalStages) {
 *         System.out.printf("Starting %s (%d/%d)%n", stageName, stageNumber, totalStages);
 *     }
 *
 *     {@literal @}Override
 *     public void onStageComplete(String stageName, StageResult result) {
 *         System.out.printf("Completed %s in %d ms%n",
 *             stageName, result.durationNanos() / 1_000_000);
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String stageName, Exception error) {
 *         System.err.printf("Error in %s: %s%n", stageName, error.getMessage());
 *     }
 * };
 *
 * IRuleCompiler compiler = new RuleCompiler();
 * compiler.setCompilationListener(listener);
 * EngineModel model = compiler.compile(rulesPath);
 * </pre>
 */
public interface CompilationListener {

    /**
     * Called when a compilation stage starts.
     *
     * @param stageName Name of the stage (e.g., "PARSING", "VALIDATION")
     * @param stageNumber Current stage number (1-based)
     * @param totalStages Total number of stages
     */
    void onStageStart(String stageName, int stageNumber, int totalStages);

    /**
     * Called when a compilation stage completes successfully.
     *
     * @param stageName Name of the stage
     * @param result Result containing duration and stage-specific metrics
     */
    void onStageComplete(String stageName, StageResult result);

    /**
     * Called when a compilation stage encounters an error.
     *
     * @param stageName Name of the stage that failed
     * @param error The exception that occurred
     */
    void onError(String stageName, Exception error);

    /**
     * Result of a single compilation stage.
     *
     * @param stageName Name of the stage
     * @param durationNanos Duration in nanoseconds
     * @param metrics Stage-specific metrics (e.g., "ruleCount", "deduplicationRate")
     */
    record StageResult(
        String stageName,
        long durationNanos,
        Map<String, Object> metrics
    ) {
        /**
         * Returns the duration in milliseconds.
         */
        public long durationMillis() {
            return durationNanos / 1_000_000;
        }

        /**
         * Returns the duration in microseconds.
         */
        public long durationMicros() {
            return durationNanos / 1_000;
        }
    }
}
