/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.repository;

import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.RuleMetadata;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC-based implementation of RuleRepository using H2 or PostgreSQL.
 *
 * <p>This implementation uses raw JDBC for maximum flexibility and
 * minimal dependencies. It supports:
 * <ul>
 *   <li>H2 in-memory database (development)</li>
 *   <li>H2 file-based database (development)</li>
 *   <li>PostgreSQL (production)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All operations are thread-safe through database ACID properties.
 */
@ApplicationScoped
@Startup
public class JdbcRuleRepository implements RuleRepository {

    private static final Logger logger = Logger.getLogger(JdbcRuleRepository.class.getName());

    @Inject
    DataSource dataSource;

    /**
     * Initialize database schema on startup.
     */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        logger.info("Initializing rule repository database schema...");
        try {
            createSchemaIfNotExists();
            logger.info("Rule repository schema initialized successfully");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to initialize database schema", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Create database schema if it doesn't exist.
     */
    private void createSchemaIfNotExists() throws SQLException {
        String createTable = """
            CREATE TABLE IF NOT EXISTS rules (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                rule_code VARCHAR(255) NOT NULL UNIQUE,
                description VARCHAR(1000),
                conditions_json TEXT NOT NULL,
                priority INT,
                enabled BOOLEAN,
                created_by VARCHAR(255),
                created_at TIMESTAMP NOT NULL,
                last_modified_by VARCHAR(255),
                last_modified_at TIMESTAMP,
                version INT,
                tags VARCHAR(1000),
                labels_json TEXT,
                combination_ids VARCHAR(5000),
                estimated_selectivity INT,
                is_vectorizable BOOLEAN,
                compilation_status VARCHAR(50)
            )
            """;

        String createIndex1 = "CREATE INDEX IF NOT EXISTS idx_enabled ON rules(enabled)";
        String createIndex2 = "CREATE INDEX IF NOT EXISTS idx_priority ON rules(priority)";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(createIndex1);
            stmt.execute(createIndex2);
        }
    }

    @Override
    public String save(RuleMetadata rule) {
        String ruleCode = rule.ruleCode();

        try (Connection conn = dataSource.getConnection()) {
            // Check if exists
            boolean exists = exists(ruleCode);

            if (exists) {
                return update(conn, rule);
            } else {
                return insert(conn, rule);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to save rule: " + ruleCode, e);
            throw new RuntimeException("Failed to save rule", e);
        }
    }

    private String insert(Connection conn, RuleMetadata rule) throws SQLException {
        String sql = """
            INSERT INTO rules (
                rule_code, description, conditions_json, priority, enabled,
                created_by, created_at, last_modified_by, last_modified_at, version,
                tags, labels_json, combination_ids, estimated_selectivity,
                is_vectorizable, compilation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, rule.ruleCode());
            stmt.setString(idx++, rule.description());
            stmt.setString(idx++, serializeConditions(rule.conditions()));
            stmt.setInt(idx++, rule.priority() != null ? rule.priority() : 0);
            stmt.setBoolean(idx++, rule.enabled() != null ? rule.enabled() : true);
            stmt.setString(idx++, rule.createdBy() != null ? rule.createdBy() : "system");
            stmt.setTimestamp(idx++, Timestamp.from(rule.createdAt() != null ? rule.createdAt() : Instant.now()));
            stmt.setString(idx++, rule.lastModifiedBy());
            stmt.setTimestamp(idx++, rule.lastModifiedAt() != null ? Timestamp.from(rule.lastModifiedAt()) : null);
            stmt.setInt(idx++, rule.version() != null ? rule.version() : 1);
            stmt.setString(idx++, serializeTags(rule.tags()));
            stmt.setString(idx++, serializeLabels(rule.labels()));
            stmt.setString(idx++, serializeCombinationIds(rule.combinationIds()));
            setIntegerOrNull(stmt, idx++, rule.estimatedSelectivity());
            setBooleanOrNull(stmt, idx++, rule.isVectorizable());
            stmt.setString(idx++, rule.compilationStatus() != null ? rule.compilationStatus() : "PENDING");

            stmt.executeUpdate();
            return rule.ruleCode();
        }
    }

    private String update(Connection conn, RuleMetadata rule) throws SQLException {
        // First, get the existing rule to preserve createdAt and increment version
        RuleMetadata existing = findByCode(rule.ruleCode()).orElse(null);
        if (existing == null) {
            throw new SQLException("Rule not found for update: " + rule.ruleCode());
        }

        String sql = """
            UPDATE rules SET
                description = ?, conditions_json = ?, priority = ?, enabled = ?,
                last_modified_by = ?, last_modified_at = ?, version = ?,
                tags = ?, labels_json = ?, combination_ids = ?,
                estimated_selectivity = ?, is_vectorizable = ?, compilation_status = ?
            WHERE rule_code = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, rule.description());
            stmt.setString(idx++, serializeConditions(rule.conditions()));
            stmt.setInt(idx++, rule.priority() != null ? rule.priority() : 0);
            stmt.setBoolean(idx++, rule.enabled() != null ? rule.enabled() : true);
            stmt.setString(idx++, rule.lastModifiedBy() != null ? rule.lastModifiedBy() : "system");
            stmt.setTimestamp(idx++, Timestamp.from(Instant.now()));
            stmt.setInt(idx++, existing.version() + 1);
            stmt.setString(idx++, serializeTags(rule.tags()));
            stmt.setString(idx++, serializeLabels(rule.labels()));
            stmt.setString(idx++, serializeCombinationIds(rule.combinationIds()));
            setIntegerOrNull(stmt, idx++, rule.estimatedSelectivity());
            setBooleanOrNull(stmt, idx++, rule.isVectorizable());
            stmt.setString(idx++, rule.compilationStatus() != null ? rule.compilationStatus() : existing.compilationStatus());
            stmt.setString(idx++, rule.ruleCode());

            stmt.executeUpdate();
            return rule.ruleCode();
        }
    }

    @Override
    public Optional<RuleMetadata> findByCode(String ruleCode) {
        String sql = "SELECT * FROM rules WHERE rule_code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ruleCode);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to find rule: " + ruleCode, e);
        }

        return Optional.empty();
    }

    @Override
    public List<RuleMetadata> findAll() {
        String sql = "SELECT * FROM rules ORDER BY priority DESC, rule_code";
        List<RuleMetadata> rules = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                rules.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to find all rules", e);
        }

        return rules;
    }

    @Override
    public boolean delete(String ruleCode) {
        String sql = "DELETE FROM rules WHERE rule_code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ruleCode);
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to delete rule: " + ruleCode, e);
            return false;
        }
    }

    @Override
    public boolean exists(String ruleCode) {
        String sql = "SELECT COUNT(*) FROM rules WHERE rule_code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, ruleCode);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to check if rule exists: " + ruleCode, e);
        }

        return false;
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM rules";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to count rules", e);
        }

        return 0;
    }

    /**
     * Load rules from a list (used for initial loading from rules.json).
     */
    public void loadRules(List<RuleMetadata> rulesList) {
        try (Connection conn = dataSource.getConnection()) {
            // Clear existing rules
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM rules");
            }

            // Insert all rules
            for (RuleMetadata rule : rulesList) {
                insert(conn, rule);
            }

            logger.info("Loaded " + rulesList.size() + " rules into database");

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load rules", e);
            throw new RuntimeException("Failed to load rules", e);
        }
    }

    // Mapping and serialization helpers
    private RuleMetadata mapResultSet(ResultSet rs) throws SQLException {
        return new RuleMetadata(
            rs.getString("rule_code"),
            rs.getString("description"),
            parseConditions(rs.getString("conditions_json")),
            rs.getInt("priority"),
            rs.getBoolean("enabled"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("last_modified_by"),
            rs.getTimestamp("last_modified_at") != null ? rs.getTimestamp("last_modified_at").toInstant() : null,
            rs.getInt("version"),
            parseTags(rs.getString("tags")),
            parseLabels(rs.getString("labels_json")),
            parseCombinationIds(rs.getString("combination_ids")),
            getIntegerOrNull(rs, "estimated_selectivity"),
            getBooleanOrNull(rs, "is_vectorizable"),
            rs.getString("compilation_status")
        );
    }

    private void setIntegerOrNull(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.INTEGER);
        } else {
            stmt.setInt(index, value);
        }
    }

    private void setBooleanOrNull(PreparedStatement stmt, int index, Boolean value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.BOOLEAN);
        } else {
            stmt.setBoolean(index, value);
        }
    }

    private Integer getIntegerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Boolean getBooleanOrNull(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    // JSON serialization helpers (simple implementation - production would use Jackson)
    private String serializeConditions(List<RuleDefinition.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sb.append(",");
            RuleDefinition.Condition c = conditions.get(i);
            sb.append("{\"field\":\"").append(escape(c.field()))
                .append("\",\"operator\":\"").append(c.operator())
                .append("\",\"value\":").append(serializeValue(c.value()))
                .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<RuleDefinition.Condition> parseConditions(String json) {
        // Simplified JSON parser - production would use Jackson
        if (json == null || json.equals("[]")) {
            return List.of();
        }

        List<RuleDefinition.Condition> conditions = new ArrayList<>();
        json = json.trim();

        // Extract individual condition objects
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            }
            if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    String conditionJson = json.substring(start, i + 1);
                    conditions.add(parseCondition(conditionJson));
                    start = -1;
                }
            }
        }

        return conditions;
    }

    private RuleDefinition.Condition parseCondition(String json) {
        String field = extractStringValue(json, "field");
        String operator = extractStringValue(json, "operator");
        Object value = extractValue(json, "value");

        return new RuleDefinition.Condition(field, operator, value);
    }

    private String extractStringValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return unescape(json.substring(start, end));
    }

    private Object extractValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();

        int end = start;
        boolean inString = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && (end == start || json.charAt(end - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && (c == ',' || c == '}')) {
                break;
            }
            end++;
        }

        String valueStr = json.substring(start, end).trim();

        if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
            return unescape(valueStr.substring(1, valueStr.length() - 1));
        } else if (valueStr.equals("true") || valueStr.equals("false")) {
            return Boolean.parseBoolean(valueStr);
        } else if (valueStr.contains(".")) {
            return Double.parseDouble(valueStr);
        } else {
            return Integer.parseInt(valueStr);
        }
    }

    private String serializeValue(Object value) {
        if (value instanceof String) {
            return "\"" + escape((String) value) + "\"";
        } else {
            return value.toString();
        }
    }

    private String serializeTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return String.join(",", tags);
    }

    private Set<String> parseTags(String tags) {
        if (tags == null || tags.isEmpty()) return Set.of();
        return new HashSet<>(Arrays.asList(tags.split(",")));
    }

    private String serializeLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(entry.getKey())).append("\":\"")
                .append(escape(entry.getValue())).append("\"");
            i++;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> parseLabels(String json) {
        if (json == null || json.equals("{}")) return Map.of();

        Map<String, String> labels = new HashMap<>();
        String content = json.trim().substring(1, json.length() - 1).trim();
        if (content.isEmpty()) return Map.of();

        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String value = kv[1].trim().replaceAll("^\"|\"$", "");
                labels.put(unescape(key), unescape(value));
            }
        }

        return labels;
    }

    private String serializeCombinationIds(Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }

    private Set<Integer> parseCombinationIds(String ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<Integer> result = new HashSet<>();
        for (String id : ids.split(",")) {
            result.add(Integer.parseInt(id.trim()));
        }
        return result;
    }

    private String escape(String str) {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String unescape(String str) {
        return str.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }
}
