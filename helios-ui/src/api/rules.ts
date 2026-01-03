/**
 * Rule Management API
 * Endpoints for listing, viewing, and managing rules
 */

import { get, del, post } from './client';
import type {
  RuleMetadata,
  RuleDetailResponse,
  RuleQueryParams,
} from '../types/api';

/**
 * Response from delete operations
 */
export interface DeleteRuleResponse {
  ruleCode: string;
  message: string;
}

/**
 * Response from batch delete operations
 */
export interface BatchDeleteResponse {
  deleted: string[];
  failed: { ruleCode: string; error: string }[];
  totalDeleted: number;
  totalFailed: number;
}

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
 * Delete a single rule
 */
export const deleteRule = async (ruleCode: string): Promise<DeleteRuleResponse> => {
  return del<DeleteRuleResponse>(`/rules/${encodeURIComponent(ruleCode)}`);
};

/**
 * Delete multiple rules in batch using the backend batch delete endpoint.
 * This is much more efficient than deleting rules one by one.
 */
export const deleteRulesBatch = async (ruleCodes: string[]): Promise<BatchDeleteResponse> => {
  return post<BatchDeleteResponse>('/rules/batch-delete', { ruleCodes });
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
  deleteRule,
  deleteRulesBatch,
  queryKeys: rulesQueryKeys,
};

export default rulesApi;
