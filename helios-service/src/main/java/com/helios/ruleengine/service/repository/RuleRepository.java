/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.repository;

import com.helios.ruleengine.api.model.RuleMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for rule persistence.
 *
 * <p>This interface abstracts rule storage, allowing implementations to use:
 * <ul>
 *   <li>In-memory storage (default, for development)</li>
 *   <li>File-based storage (rules.json)</li>
 *   <li>Database storage (PostgreSQL, for production)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface RuleRepository {

    /**
     * Save a new rule or update an existing one.
     *
     * @param rule the rule metadata to save
     * @return the rule code of the saved rule
     */
    String save(RuleMetadata rule);

    /**
     * Find a rule by its code.
     *
     * @param ruleCode the rule code
     * @return the rule metadata, or empty if not found
     */
    Optional<RuleMetadata> findByCode(String ruleCode);

    /**
     * Get all rules.
     *
     * @return list of all rules
     */
    List<RuleMetadata> findAll();

    /**
     * Delete a rule by its code.
     *
     * @param ruleCode the rule code
     * @return true if the rule was deleted, false if it didn't exist
     */
    boolean delete(String ruleCode);

    /**
     * Check if a rule exists.
     *
     * @param ruleCode the rule code
     * @return true if the rule exists
     */
    boolean exists(String ruleCode);

    /**
     * Get the total number of rules.
     *
     * @return total rule count
     */
    long count();
}
