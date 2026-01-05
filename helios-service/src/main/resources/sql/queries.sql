-- SQL Queries for Rule Repository
-- Query names are prefixed with -- @name: <query_name>
--
-- Unified model: rules table contains all versions.
-- Current version has is_current = true.

-- @name: insert_rule
INSERT INTO rules (
    rule_code, version, is_current, description, conditions_json, priority, enabled,
    created_by, created_at, last_modified_by, last_modified_at,
    change_type, change_summary,
    tags, labels_json, combination_ids, estimated_selectivity,
    is_vectorizable, compilation_status
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- @name: mark_not_current
UPDATE rules SET is_current = FALSE WHERE rule_code = ? AND is_current = TRUE;

-- @name: select_by_code
SELECT * FROM rules WHERE rule_code = ? AND is_current = TRUE;

-- @name: select_all
SELECT * FROM rules WHERE is_current = TRUE ORDER BY priority DESC, rule_code;

-- @name: delete_by_code
DELETE FROM rules WHERE rule_code = ?;

-- @name: count_by_code
SELECT COUNT(*) FROM rules WHERE rule_code = ? AND is_current = TRUE;

-- @name: count_all
SELECT COUNT(*) FROM rules WHERE is_current = TRUE;

-- @name: delete_all
DELETE FROM rules;

-- ========================================
-- Rule Version History Queries
-- ========================================

-- @name: select_versions_by_rule_code
SELECT * FROM rules WHERE rule_code = ? ORDER BY version DESC;

-- @name: select_version_by_rule_code_and_version
SELECT * FROM rules WHERE rule_code = ? AND version = ?;

-- @name: select_latest_version_number
SELECT MAX(version) FROM rules WHERE rule_code = ?;

-- @name: count_versions_by_rule_code
SELECT COUNT(*) FROM rules WHERE rule_code = ?;

-- @name: update_compilation_metadata
UPDATE rules SET combination_ids = ?, estimated_selectivity = ?, is_vectorizable = ?, compilation_status = ?
WHERE rule_code = ? AND is_current = TRUE;

-- @name: update_rule_in_place
-- Update rule metadata without creating a new version (when conditions haven't changed)
UPDATE rules SET
    description = ?,
    priority = ?,
    enabled = ?,
    last_modified_by = ?,
    last_modified_at = ?,
    tags = ?,
    labels_json = ?
WHERE rule_code = ? AND is_current = TRUE;
