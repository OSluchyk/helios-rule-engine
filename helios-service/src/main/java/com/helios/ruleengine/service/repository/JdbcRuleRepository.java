/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.repository;

import com.helios.ruleengine.api.model.RuleDefinition;
import com.helios.ruleengine.api.model.RuleMetadata;
import com.helios.ruleengine.api.model.RuleVersion;
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
 * JDBC-based implementation of RuleRepository using unified versioning model.
 *
 * <p>This implementation stores all rule versions in a single table.
 * The current/active version is marked with is_current = true.
 * History is simply all rows for a rule_code ordered by version.
 *
 * <p>When a rule is deleted, ALL its versions are deleted - ensuring
 * clean slate on re-import.
 *
 * <p><b>Thread Safety:</b> All operations are thread-safe through database ACID properties.
 */
@ApplicationScoped
@Startup
@jakarta.annotation.Priority(100)
public class JdbcRuleRepository implements RuleRepository {

    private static final Logger logger = Logger.getLogger(JdbcRuleRepository.class.getName());

    private static final Map<String, String> SQL = SqlLoader.loadQueries("sql/queries.sql");
    private static final String SCHEMA_SQL = SqlLoader.loadSchema("sql/schema.sql");

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

    private void createSchemaIfNotExists() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : SCHEMA_SQL.split(";")) {
                sql = sql.trim();
                if (!sql.isEmpty() && !sql.startsWith("--")) {
                    stmt.execute(sql);
                }
            }
        }
    }

    @Override
    public String save(RuleMetadata rule) {
        String ruleCode = rule.ruleCode();

        try (Connection conn = dataSource.getConnection()) {
            boolean exists = exists(ruleCode);

            if (exists) {
                return update(conn, rule, null, null);
            } else {
                return insert(conn, rule);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to save rule: " + ruleCode, e);
            throw new RuntimeException("Failed to save rule", e);
        }
    }

    private String insert(Connection conn, RuleMetadata rule) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(SQL.get("insert_rule"))) {
            int idx = 1;
            stmt.setString(idx++, rule.ruleCode());
            stmt.setInt(idx++, 1); // version = 1 for new rules
            stmt.setBoolean(idx++, true); // is_current = true
            stmt.setString(idx++, rule.description());
            stmt.setString(idx++, serializeConditions(rule.conditions()));
            stmt.setInt(idx++, rule.priority() != null ? rule.priority() : 0);
            stmt.setBoolean(idx++, rule.enabled() != null ? rule.enabled() : true);
            stmt.setString(idx++, rule.createdBy() != null ? rule.createdBy() : "system");
            stmt.setTimestamp(idx++, Timestamp.from(rule.createdAt() != null ? rule.createdAt() : Instant.now()));
            stmt.setString(idx++, rule.lastModifiedBy());
            stmt.setTimestamp(idx++, rule.lastModifiedAt() != null ? Timestamp.from(rule.lastModifiedAt()) : null);
            stmt.setString(idx++, "CREATED"); // change_type
            stmt.setString(idx++, "Initial rule creation"); // change_summary
            stmt.setString(idx++, serializeTags(rule.tags()));
            stmt.setString(idx++, serializeLabels(rule.labels()));
            stmt.setString(idx++, serializeCombinationIds(rule.combinationIds()));
            setIntegerOrNull(stmt, idx++, rule.estimatedSelectivity());
            setBooleanOrNull(stmt, idx++, rule.isVectorizable());
            stmt.setString(idx++, rule.compilationStatus() != null ? rule.compilationStatus() : "PENDING");

            stmt.executeUpdate();
            logger.info("Inserted new rule: " + rule.ruleCode() + " v1");
            return rule.ruleCode();
        }
    }

    private String update(Connection conn, RuleMetadata rule, RuleVersion.ChangeType changeType, String changeSummary) throws SQLException {
        // Get existing rule to calculate new version
        RuleMetadata existing = findByCode(rule.ruleCode()).orElse(null);
        if (existing == null) {
            throw new SQLException("Rule not found for update: " + rule.ruleCode());
        }

        int newVersion = existing.version() + 1;
        String type = changeType != null ? changeType.name() : "UPDATED";
        String summary = changeSummary != null ? changeSummary : generateChangeSummary(existing, rule);

        // Mark current version as not current
        try (PreparedStatement markStmt = conn.prepareStatement(SQL.get("mark_not_current"))) {
            markStmt.setString(1, rule.ruleCode());
            markStmt.executeUpdate();
        }

        // Insert new version as current
        try (PreparedStatement stmt = conn.prepareStatement(SQL.get("insert_rule"))) {
            int idx = 1;
            stmt.setString(idx++, rule.ruleCode());
            stmt.setInt(idx++, newVersion);
            stmt.setBoolean(idx++, true); // is_current = true
            stmt.setString(idx++, rule.description());
            stmt.setString(idx++, serializeConditions(rule.conditions()));
            stmt.setInt(idx++, rule.priority() != null ? rule.priority() : 0);
            stmt.setBoolean(idx++, rule.enabled() != null ? rule.enabled() : true);
            stmt.setString(idx++, existing.createdBy()); // preserve original creator
            stmt.setTimestamp(idx++, Timestamp.from(existing.createdAt())); // preserve original creation time
            stmt.setString(idx++, rule.lastModifiedBy() != null ? rule.lastModifiedBy() : "system");
            stmt.setTimestamp(idx++, Timestamp.from(Instant.now()));
            stmt.setString(idx++, type);
            stmt.setString(idx++, summary);
            stmt.setString(idx++, serializeTags(rule.tags()));
            stmt.setString(idx++, serializeLabels(rule.labels()));
            stmt.setString(idx++, serializeCombinationIds(rule.combinationIds()));
            setIntegerOrNull(stmt, idx++, rule.estimatedSelectivity());
            setBooleanOrNull(stmt, idx++, rule.isVectorizable());
            stmt.setString(idx++, rule.compilationStatus() != null ? rule.compilationStatus() : existing.compilationStatus());

            stmt.executeUpdate();
            logger.info("Updated rule: " + rule.ruleCode() + " v" + newVersion);
            return rule.ruleCode();
        }
    }

    private String generateChangeSummary(RuleMetadata existing, RuleMetadata updated) {
        List<String> changes = new ArrayList<>();

        if (!Objects.equals(existing.priority(), updated.priority())) {
            changes.add("priority changed");
        }
        if (!Objects.equals(existing.enabled(), updated.enabled())) {
            changes.add(updated.enabled() ? "enabled" : "disabled");
        }
        if (!Objects.equals(existing.conditions(), updated.conditions())) {
            changes.add("conditions modified");
        }
        if (!Objects.equals(existing.tags(), updated.tags())) {
            changes.add("tags updated");
        }
        if (!Objects.equals(existing.description(), updated.description())) {
            changes.add("description updated");
        }

        if (changes.isEmpty()) {
            return "Rule updated";
        }
        return String.join(", ", changes);
    }

    /**
     * Save a rule as a rollback operation.
     */
    public String saveAsRollback(RuleMetadata rule, int restoredFromVersion) {
        try (Connection conn = dataSource.getConnection()) {
            return update(conn, rule, RuleVersion.ChangeType.ROLLBACK,
                "Rolled back to version " + restoredFromVersion);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to save rollback for rule: " + rule.ruleCode(), e);
            throw new RuntimeException("Failed to save rule rollback", e);
        }
    }

    /**
     * Update compilation metadata for a rule without creating a new version.
     * This is used after compilation to store combination IDs and other computed metadata.
     */
    public void updateCompilationMetadata(String ruleCode, Set<Integer> combinationIds,
                                          Integer estimatedSelectivity, Boolean isVectorizable,
                                          String compilationStatus) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("update_compilation_metadata"))) {

            stmt.setString(1, serializeCombinationIds(combinationIds));
            setIntegerOrNull(stmt, 2, estimatedSelectivity);
            setBooleanOrNull(stmt, 3, isVectorizable);
            stmt.setString(4, compilationStatus);
            stmt.setString(5, ruleCode);

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected > 0) {
                logger.fine("Updated compilation metadata for rule: " + ruleCode);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to update compilation metadata for rule: " + ruleCode, e);
            throw new RuntimeException("Failed to update compilation metadata", e);
        }
    }

    @Override
    public Optional<RuleMetadata> findByCode(String ruleCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("select_by_code"))) {

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
        List<RuleMetadata> rules = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("select_all"));
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("delete_by_code"))) {

            stmt.setString(1, ruleCode);
            int rowsAffected = stmt.executeUpdate();
            logger.info("Deleted rule and all versions: " + ruleCode + " (" + rowsAffected + " rows)");
            return rowsAffected > 0;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to delete rule: " + ruleCode, e);
            return false;
        }
    }

    @Override
    public boolean exists(String ruleCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("count_by_code"))) {

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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("count_all"));
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
            try (PreparedStatement stmt = conn.prepareStatement(SQL.get("delete_all"))) {
                stmt.execute();
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

    // ========================================
    // Version History Methods
    // ========================================

    /**
     * Get all versions for a rule.
     */
    public List<RuleVersion> getVersions(String ruleCode) {
        List<RuleVersion> versions = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("select_versions_by_rule_code"))) {

            stmt.setString(1, ruleCode);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    versions.add(mapVersionResultSet(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to get versions for rule: " + ruleCode, e);
        }

        return versions;
    }

    /**
     * Get a specific version of a rule.
     */
    public Optional<RuleVersion> getVersion(String ruleCode, int version) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("select_version_by_rule_code_and_version"))) {

            stmt.setString(1, ruleCode);
            stmt.setInt(2, version);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVersionResultSet(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to get version " + version + " for rule: " + ruleCode, e);
        }

        return Optional.empty();
    }

    /**
     * Get the count of versions for a rule.
     */
    public int getVersionCount(String ruleCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL.get("count_versions_by_rule_code"))) {

            stmt.setString(1, ruleCode);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to count versions for rule: " + ruleCode, e);
        }

        return 0;
    }

    // ========================================
    // Mapping helpers
    // ========================================

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

    private RuleVersion mapVersionResultSet(ResultSet rs) throws SQLException {
        String changeTypeStr = rs.getString("change_type");
        RuleVersion.ChangeType changeType;
        try {
            changeType = RuleVersion.ChangeType.valueOf(changeTypeStr);
        } catch (Exception e) {
            changeType = RuleVersion.ChangeType.UPDATED;
        }

        return new RuleVersion(
            rs.getString("rule_code"),
            rs.getInt("version"),
            rs.getString("description"),
            parseConditions(rs.getString("conditions_json")),
            rs.getInt("priority"),
            rs.getBoolean("enabled"),
            rs.getString("last_modified_by") != null ? rs.getString("last_modified_by") : rs.getString("created_by"),
            rs.getTimestamp("last_modified_at") != null ? rs.getTimestamp("last_modified_at").toInstant() : rs.getTimestamp("created_at").toInstant(),
            changeType,
            rs.getString("change_summary"),
            parseTags(rs.getString("tags")),
            parseLabels(rs.getString("labels_json"))
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

    // JSON serialization helpers
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
        if (json == null || json.equals("[]")) {
            return List.of();
        }

        List<RuleDefinition.Condition> conditions = new ArrayList<>();
        json = json.trim();

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
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escape(key)).append("\":\"")
                    .append(escape(value != null ? value : "")).append("\"");
                i++;
            }
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
                if (key != null && !key.isEmpty()) {
                    labels.put(unescape(key), unescape(value != null ? value : ""));
                }
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
