package com.helios.ruleengine.api.model;

import java.util.List;

/**
 * Request to validate rules before import
 */
public record ImportValidationRequest(
    String format,  // "json", "yaml", "csv"
    String content, // The raw content to parse
    List<RuleMetadata> rules  // Or parsed rules if format is JSON
) {}
