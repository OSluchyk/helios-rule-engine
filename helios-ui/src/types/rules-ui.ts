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
