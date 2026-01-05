/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.repository;

import com.helios.ruleengine.api.model.RuleMetadata;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory implementation of RuleRepository.
 *
 * <p>This implementation stores rules in memory using a ConcurrentHashMap.
 * Rules are lost on application restart. For production use, consider
 * implementing a database-backed repository.
 *
 * <p><b>Thread Safety:</b> All operations are thread-safe.
 *
 * <p><b>Activation:</b> Disabled by default. Use JdbcRuleRepository instead.
 * To enable, set: repository.type=memory
 */
@jakarta.enterprise.inject.Alternative
@jakarta.annotation.Priority(1)
@ApplicationScoped
public class InMemoryRuleRepository implements RuleRepository {

    private final ConcurrentMap<String, RuleMetadata> rules = new ConcurrentHashMap<>();

    @Override
    public String save(RuleMetadata rule) {
        String ruleCode = rule.ruleCode();

        // Update timestamps
        RuleMetadata updatedRule;
        if (rules.containsKey(ruleCode)) {
            RuleMetadata existing = rules.get(ruleCode);

            // Check if conditions changed - only increment version if conditions changed
            boolean conditionsChanged = !java.util.Objects.equals(existing.conditions(), rule.conditions());
            int newVersion = conditionsChanged ? existing.version() + 1 : existing.version();

            // Update existing rule - increment version ONLY if conditions changed
            updatedRule = new RuleMetadata(
                rule.ruleCode(),
                rule.description(),
                rule.conditions(),
                rule.priority(),
                rule.enabled(),
                existing.createdBy(),
                existing.createdAt(), // Keep original creation time
                "system", // TODO: Get from security context
                Instant.now(),
                newVersion,
                rule.tags(),
                rule.labels(),
                rule.combinationIds(),
                rule.estimatedSelectivity(),
                rule.isVectorizable(),
                rule.compilationStatus()
            );
        } else {
            // New rule - set creation timestamp
            updatedRule = new RuleMetadata(
                rule.ruleCode(),
                rule.description(),
                rule.conditions(),
                rule.priority(),
                rule.enabled(),
                "system", // TODO: Get from security context
                Instant.now(),
                "system",
                Instant.now(),
                1, // Initial version
                rule.tags(),
                rule.labels(),
                null, // combinationIds - will be set after compilation
                null, // estimatedSelectivity
                null, // isVectorizable
                "PENDING" // compilationStatus
            );
        }

        rules.put(ruleCode, updatedRule);
        return ruleCode;
    }

    @Override
    public Optional<RuleMetadata> findByCode(String ruleCode) {
        return Optional.ofNullable(rules.get(ruleCode));
    }

    @Override
    public List<RuleMetadata> findAll() {
        return new ArrayList<>(rules.values());
    }

    @Override
    public boolean delete(String ruleCode) {
        return rules.remove(ruleCode) != null;
    }

    @Override
    public boolean exists(String ruleCode) {
        return rules.containsKey(ruleCode);
    }

    @Override
    public long count() {
        return rules.size();
    }

    /**
     * Load rules from a list (used for initial loading from rules.json).
     *
     * @param rulesList list of rules to load
     */
    public void loadRules(List<RuleMetadata> rulesList) {
        rules.clear();
        for (RuleMetadata rule : rulesList) {
            rules.put(rule.ruleCode(), rule);
        }
    }
}
