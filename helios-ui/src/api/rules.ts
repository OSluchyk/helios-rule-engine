/**
 * Rule Management API
 * Endpoints for listing, viewing, and managing rules
 */

import { get } from './client';
import type {
  RuleMetadata,
  RuleDetailResponse,
  RuleQueryParams,
} from '../types/api';

/**
 * List all rules with optional filtering
 */
export const listRules = async (params?: RuleQueryParams): Promise<RuleMetadata[]> => {
  return get<RuleMetadata[]>('/rules', params);
};

/**
 * Get detailed information about a specific rule
 */
export const getRule = async (ruleCode: string): Promise<RuleDetailResponse> => {
  return get<RuleDetailResponse>(`/rules/${encodeURIComponent(ruleCode)}`);
};

/**
 * Get all combination IDs for a specific rule
 */
export const getRuleCombinations = async (ruleCode: string): Promise<number[]> => {
  return get<number[]>(`/rules/${encodeURIComponent(ruleCode)}/combinations`);
};

/**
 * Get all rules that use a specific predicate
 */
export const getRulesByPredicate = async (predicateId: number): Promise<RuleMetadata[]> => {
  return get<RuleMetadata[]>(`/rules/by-predicate/${predicateId}`);
};

/**
 * Get all rules with a specific tag
 */
export const getRulesByTag = async (tag: string): Promise<RuleMetadata[]> => {
  return get<RuleMetadata[]>(`/rules/by-tag/${encodeURIComponent(tag)}`);
};

/**
 * React Query hooks for rules API
 */
export const rulesQueryKeys = {
  all: ['rules'] as const,
  lists: () => [...rulesQueryKeys.all, 'list'] as const,
  list: (params?: RuleQueryParams) => [...rulesQueryKeys.lists(), params] as const,
  details: () => [...rulesQueryKeys.all, 'detail'] as const,
  detail: (ruleCode: string) => [...rulesQueryKeys.details(), ruleCode] as const,
  combinations: (ruleCode: string) => [...rulesQueryKeys.detail(ruleCode), 'combinations'] as const,
  byPredicate: (predicateId: number) => [...rulesQueryKeys.all, 'by-predicate', predicateId] as const,
  byTag: (tag: string) => [...rulesQueryKeys.all, 'by-tag', tag] as const,
};

// Export all as a single object for convenience
export const rulesApi = {
  listRules,
  getRule,
  getRuleCombinations,
  getRulesByPredicate,
  getRulesByTag,
  queryKeys: rulesQueryKeys,
};

export default rulesApi;
