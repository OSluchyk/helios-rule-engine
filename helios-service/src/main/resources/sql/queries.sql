-- SQL Queries for Rule Repository
-- Query names are prefixed with -- @name: <query_name>

-- @name: insert_rule
INSERT INTO rules (
    rule_code, description, conditions_json, priority, enabled,
    created_by, created_at, last_modified_by, last_modified_at, version,
    tags, labels_json, combination_ids, estimated_selectivity,
    is_vectorizable, compilation_status
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- @name: update_rule
UPDATE rules SET
    description = ?,
    conditions_json = ?,
    priority = ?,
    enabled = ?,
    last_modified_by = ?,
    last_modified_at = ?,
    version = ?,
    tags = ?,
    labels_json = ?,
    combination_ids = ?,
    estimated_selectivity = ?,
    is_vectorizable = ?,
    compilation_status = ?
WHERE rule_code = ?;

-- @name: select_by_code
SELECT * FROM rules WHERE rule_code = ?;

-- @name: select_all
SELECT * FROM rules ORDER BY priority DESC, rule_code;

-- @name: delete_by_code
DELETE FROM rules WHERE rule_code = ?;

-- @name: count_by_code
SELECT COUNT(*) FROM rules WHERE rule_code = ?;

-- @name: count_all
SELECT COUNT(*) FROM rules;

-- @name: delete_all
DELETE FROM rules;
