/**
 * UI-specific types for Rules View
 * Extends API types with UI state and computed properties
 */

import type { RuleMetadata } from './api';

// Predefined tag configurations matching TAG_FILTERING_FEATURE.md
export const PREDEFINED_TAGS = {
  // Performance tags
  slow: { color: 'red', description: 'P99 latency > 1ms' },
  'needs-optimization': { color: 'orange', description: 'Requires performance tuning' },
  vectorized: { color: 'blue', description: 'Uses SIMD vectorization' },

  // Match rate tags
  'high-match': { color: 'green', description: 'Match rate > 75%' },
  'low-match': { color: 'yellow', description: 'Match rate < 10%' },

  // Priority tags
  'production-critical': { color: 'purple', description: 'Critical production rule' },
  experimental: { color: 'cyan', description: 'Experimental/testing phase' },

  // Lifecycle tags
  deprecated: { color: 'gray', description: 'Scheduled for removal' },
  'needs-review': { color: 'orange', description: 'Requires manual review' },

  // Business tags
  seasonal: { color: 'pink', description: 'Seasonal campaign rule' }
} as const;

export type PredefinedTagKey = keyof typeof PREDEFINED_TAGS;
export type TagColor = 'red' | 'orange' | 'blue' | 'green' | 'yellow' | 'purple' | 'cyan' | 'gray' | 'pink' | 'indigo';

export interface TagConfig {
  color: TagColor;
  description: string;
}

// Extended Rule interface with UI-specific properties
export interface UIRule extends RuleMetadata {
  // Computed UI properties
  family?: string;
  status?: 'active' | 'inactive' | 'draft';
  lastModified?: string;

  // Performance stats (if available from monitoring)
  stats?: {
    evalsPerDay?: number;
    matchRate?: number;
    avgLatencyMs?: number;
    p99LatencyMs?: number;
    cacheHitRate?: number;
  };

  // Condition details for expanded view
  baseConditions?: Array<{
    attribute: string;
    operator: string;
    value: any;
  }>;

  vectorizedConditions?: Array<{
    attribute: string;
    operator: string;
    value: number;
  }>;

  // Actions
  actions?: Array<{
    type: string;
    params: any;
  }>;

  // Optimization metadata
  optimization?: {
    dedupGroupId?: string;
    dictionaryIds?: Record<string, number>;
    cacheKey?: string;
  };
}

// Rule family grouping
export interface RuleFamily {
  name: string;
  ruleCount: number;
  dedupRate?: number;
  avgPriority?: number;
}

// Filter state
export interface RuleFilters {
  searchQuery: string;
  selectedFamily: string;
  statusFilter: string[];
  selectedTags: string[];
  priorityRange?: [number, number];
  hasVectorized?: boolean;
  slowOnly?: boolean;
}

// View modes
export type ViewMode = 'list' | 'tree';
export type SortOption = 'match-rate' | 'priority' | 'modified' | 'latency' | 'name';

// Helper function to get tag style classes
export const getTagStyle = (tag: string): string => {
  const predefined = PREDEFINED_TAGS[tag as PredefinedTagKey];
  if (predefined) {
    const colorMap: Record<TagColor, string> = {
      red: 'bg-red-100 text-red-800 border-red-300',
      orange: 'bg-orange-100 text-orange-800 border-orange-300',
      blue: 'bg-blue-100 text-blue-800 border-blue-300',
      green: 'bg-green-100 text-green-800 border-green-300',
      yellow: 'bg-yellow-100 text-yellow-800 border-yellow-300',
      purple: 'bg-purple-100 text-purple-800 border-purple-300',
      cyan: 'bg-cyan-100 text-cyan-800 border-cyan-300',
      gray: 'bg-gray-100 text-gray-800 border-gray-300',
      pink: 'bg-pink-100 text-pink-800 border-pink-300',
      indigo: 'bg-indigo-100 text-indigo-800 border-indigo-300'
    };
    return colorMap[predefined.color];
  }
  return 'bg-indigo-100 text-indigo-800 border-indigo-300'; // Custom tags
};

// Helper to get all unique tags from rules
export const getAllTags = (rules: RuleMetadata[]): string[] => {
  const tagSet = new Set<string>();
  rules.forEach(rule => {
    if (rule.tags) {
      rule.tags.forEach(tag => tagSet.add(tag));
    }
  });
  return Array.from(tagSet).sort();
};

// Helper to convert API RuleMetadata to UIRule
export const toUIRule = (apiRule: RuleMetadata): UIRule => {
  return {
    ...apiRule,
    // Map compilation_status to UI status
    status: apiRule.enabled ? 'active' : 'inactive',
    // Extract family from rule_code or tags
    family: extractFamily(apiRule),
    lastModified: apiRule.last_modified_at || 'Unknown',
    // Stats would come from monitoring API - placeholder for now
    stats: {
      evalsPerDay: 0,
      matchRate: 0,
      avgLatencyMs: 0,
      p99LatencyMs: 0,
      cacheHitRate: 0
    }
  };
};

// Extract family from rule code or tags
const extractFamily = (rule: RuleMetadata): string => {
  // Try to extract from rule_code pattern (e.g., "customer_segmentation.high_value")
  const parts = rule.rule_code.split('.');
  if (parts.length > 1) {
    return parts[0];
  }

  // Try to extract from tags
  if (rule.tags) {
    const familyTag = rule.tags.find(tag => tag.includes('family:'));
    if (familyTag) {
      return familyTag.replace('family:', '');
    }
  }

  // Default to 'general'
  return 'general';
};

// Validation issue types
export interface RuleValidationIssue {
  type: 'error' | 'warning';
  message: string;
  field?: string;
}

// Supported operators (canonical form)
const SUPPORTED_OPERATORS = [
  'EQUAL_TO', 'NOT_EQUAL_TO', 'IS_ANY_OF', 'IS_NONE_OF',
  'GREATER_THAN', 'LESS_THAN', 'BETWEEN',
  'CONTAINS', 'STARTS_WITH', 'ENDS_WITH', 'REGEX',
  'IS_NULL', 'IS_NOT_NULL'
];

// Normalize operator to canonical form
const normalizeOperator = (operator: string): string => {
  if (!operator) return '';
  const normalized = operator.toUpperCase().trim();

  // Map aliases to canonical operators
  switch (normalized) {
    case 'EQUALS': case 'EQ': case '==': case '=':
    case 'IS_EQUAL_TO': case 'IS_EQUAL':
      return 'EQUAL_TO';
    case 'NOT_EQUALS': case 'NE': case 'NEQ': case '!=': case '<>':
    case 'IS_NOT_EQUAL_TO': case 'IS_NOT_EQUAL':
      return 'NOT_EQUAL_TO';
    case 'GT': case '>': case 'IS_GREATER_THAN': case 'GREATER':
    case 'GTE': case 'GE': case '>=': case 'IS_GREATER_THAN_OR_EQUAL':
    case 'GREATER_THAN_OR_EQUAL':
      return 'GREATER_THAN';
    case 'LT': case '<': case 'IS_LESS_THAN': case 'LESS':
    case 'LTE': case 'LE': case '<=': case 'IS_LESS_THAN_OR_EQUAL':
    case 'LESS_THAN_OR_EQUAL':
      return 'LESS_THAN';
    case 'IN_RANGE': case 'RANGE':
      return 'BETWEEN';
    case 'IN': case 'ANY_OF': case 'ONE_OF':
      return 'IS_ANY_OF';
    case 'NOT_IN': case 'NONE_OF':
      return 'IS_NONE_OF';
    case 'HAS': case 'INCLUDES':
      return 'CONTAINS';
    case 'BEGINS_WITH': case 'PREFIX':
      return 'STARTS_WITH';
    case 'SUFFIX':
      return 'ENDS_WITH';
    case 'MATCHES': case 'REGEXP': case 'PATTERN':
      return 'REGEX';
    case 'NULL': case 'ISNULL':
      return 'IS_NULL';
    case 'NOT_NULL': case 'NOTNULL': case 'ISNOTNULL':
      return 'IS_NOT_NULL';
    default:
      return normalized;
  }
};

/**
 * Validate a rule and return any issues found.
 * This mirrors the backend validation logic for consistent UX.
 */
export const validateRule = (rule: RuleMetadata): RuleValidationIssue[] => {
  const issues: RuleValidationIssue[] = [];

  // Check required fields
  if (!rule.rule_code || rule.rule_code.trim() === '') {
    issues.push({ type: 'error', message: 'Missing required field: rule_code' });
  }
  if (!rule.description || rule.description.trim() === '') {
    issues.push({ type: 'error', message: 'Missing required field: description' });
  }
  if (!rule.conditions || rule.conditions.length === 0) {
    issues.push({ type: 'error', message: 'Missing required field: conditions' });
  }

  // Validate priority range
  if (rule.priority < 0 || rule.priority > 1000) {
    issues.push({ type: 'error', message: 'Priority must be between 0 and 1000' });
  } else if (rule.priority > 900) {
    issues.push({ type: 'warning', message: `Priority value (${rule.priority}) is unusually high` });
  }

  // Validate conditions
  if (rule.conditions) {
    const fieldOperatorCounts: Record<string, number> = {};

    for (const condition of rule.conditions) {
      const normalizedOp = normalizeOperator(condition.operator);

      // Check for unsupported operators
      if (!SUPPORTED_OPERATORS.includes(normalizedOp)) {
        issues.push({
          type: 'error',
          message: `Unsupported operator: ${condition.operator}`,
          field: condition.field
        });
      }

      // Check for null value with EQUAL_TO (suggest IS_NULL)
      if (normalizedOp === 'EQUAL_TO' && condition.value === null) {
        issues.push({
          type: 'warning',
          message: `Consider using IS_NULL instead of EQUAL_TO for null checks`,
          field: condition.field
        });
      }

      // Check for null value with NOT_EQUAL_TO (suggest IS_NOT_NULL)
      if (normalizedOp === 'NOT_EQUAL_TO' && condition.value === null) {
        issues.push({
          type: 'warning',
          message: `Consider using IS_NOT_NULL instead of NOT_EQUAL_TO for null checks`,
          field: condition.field
        });
      }

      // Track EQUAL_TO conditions per field
      if (normalizedOp === 'EQUAL_TO') {
        const key = condition.field;
        fieldOperatorCounts[key] = (fieldOperatorCounts[key] || 0) + 1;
      }
    }

    // Check for multiple EQUAL_TO on same field (suggest IS_ANY_OF)
    for (const [field, count] of Object.entries(fieldOperatorCounts)) {
      if (count > 1) {
        issues.push({
          type: 'warning',
          message: `Multiple EQUAL_TO conditions on field '${field}' - consider combining into IS_ANY_OF for better performance`,
          field
        });
      }
    }
  }

  // Check compilation status for issues
  if (rule.compilation_status === 'ERROR') {
    issues.push({ type: 'error', message: 'Rule failed to compile - check conditions for errors' });
  } else if (rule.compilation_status === 'WARNING') {
    issues.push({ type: 'warning', message: 'Rule compiled with warnings' });
  } else if (!rule.compilation_status || rule.compilation_status === 'PENDING') {
    issues.push({ type: 'warning', message: 'Rule has not been compiled yet - it won\'t be active until compiled' });
  }

  return issues;
};

/**
 * Get a summary of validation issues
 */
export const getValidationSummary = (issues: RuleValidationIssue[]): { errors: number; warnings: number } => {
  return {
    errors: issues.filter(i => i.type === 'error').length,
    warnings: issues.filter(i => i.type === 'warning').length
  };
};

/**
 * Check if a rule has any validation issues
 */
export const hasValidationIssues = (rule: RuleMetadata): boolean => {
  return validateRule(rule).length > 0;
};

/**
 * Check if a rule has errors (not just warnings)
 */
export const hasValidationErrors = (rule: RuleMetadata): boolean => {
  return validateRule(rule).some(i => i.type === 'error');
};
