-- Database schema for Helios Rule Engine
-- Compatible with H2 and PostgreSQL
--
-- Unified rules table: each row is a version of a rule.
-- The current/active version has is_current = true.
-- History is simply all rows for a rule_code ordered by version.

CREATE TABLE IF NOT EXISTS rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code VARCHAR(255) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(1000),
    conditions_json TEXT NOT NULL,
    priority INT,
    enabled BOOLEAN,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    last_modified_by VARCHAR(255),
    last_modified_at TIMESTAMP,
    change_type VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    change_summary VARCHAR(500),
    tags VARCHAR(1000),
    labels_json TEXT,
    combination_ids VARCHAR(5000),
    estimated_selectivity INT,
    is_vectorizable BOOLEAN,
    compilation_status VARCHAR(50),
    CONSTRAINT uk_rule_version UNIQUE (rule_code, version)
);

CREATE INDEX IF NOT EXISTS idx_rules_current ON rules(rule_code, is_current);
CREATE INDEX IF NOT EXISTS idx_enabled ON rules(enabled);
CREATE INDEX IF NOT EXISTS idx_priority ON rules(priority);
