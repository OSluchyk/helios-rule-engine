package com.helios.ruleengine.api.model;

/**
 * Defines the detail level for trace collection.
 */
public enum TraceLevel {
    /**
     * No tracing. Minimal overhead (0%).
     */
    NONE,

    /**
     * Basic tracing. Captures which rules matched/failed, but no predicate details.
     * Low overhead (~5%).
     */
    BASIC,

    /**
     * Standard tracing. Captures matched/failed rules and predicate outcomes.
     * Does NOT capture field values.
     * Medium overhead (~30%).
     */
    STANDARD,

    /**
     * Full tracing. Captures all details including field values for every
     * predicate.
     * High overhead (~60-100%).
     */
    FULL
}
