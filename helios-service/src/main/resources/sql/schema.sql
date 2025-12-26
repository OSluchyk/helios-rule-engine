-- Database schema for Helios Rule Engine
-- Compatible with H2 and PostgreSQL

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
);

CREATE INDEX IF NOT EXISTS idx_enabled ON rules(enabled);
CREATE INDEX IF NOT EXISTS idx_priority ON rules(priority);
