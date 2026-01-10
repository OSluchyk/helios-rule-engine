/**
 * Rule Version History API
 * Endpoints for viewing rule version history, comparing versions, and rollback
 */

import { get, post } from './client';
import type {
  RuleVersion,
  RuleVersionsResponse,
  RollbackResponse,
  CompareVersionsResponse,
} from '../types/api';

/**
 * Get all versions for a specific rule
 */
export const getVersions = async (ruleCode: string): Promise<RuleVersionsResponse> => {
  return get<RuleVersionsResponse>(`/rules/${encodeURIComponent(ruleCode)}/versions`);
};

/**
 * Get a specific version of a rule
 */
export const getVersion = async (ruleCode: string, version: number): Promise<RuleVersion> => {
  return get<RuleVersion>(`/rules/${encodeURIComponent(ruleCode)}/versions/${version}`);
};

/**
 * Compare two versions of a rule
 */
export const compareVersions = async (
  ruleCode: string,
  v1: number,
  v2: number
): Promise<CompareVersionsResponse> => {
  return get<CompareVersionsResponse>(
    `/rules/${encodeURIComponent(ruleCode)}/versions/compare`,
    { v1, v2 }
  );
};

/**
 * Rollback a rule to a specific version
 */
export const rollback = async (
  ruleCode: string,
  version: number,
  author: string
): Promise<RollbackResponse> => {
  return post<RollbackResponse>(
    `/rules/${encodeURIComponent(ruleCode)}/versions/${version}/rollback`,
    { author }
  );
};

/**
 * React Query hooks for rule history API
 */
export const ruleHistoryQueryKeys = {
  all: ['ruleHistory'] as const,
  versions: (ruleCode: string) => [...ruleHistoryQueryKeys.all, 'versions', ruleCode] as const,
  version: (ruleCode: string, version: number) =>
    [...ruleHistoryQueryKeys.versions(ruleCode), version] as const,
  compare: (ruleCode: string, v1: number, v2: number) =>
    [...ruleHistoryQueryKeys.all, 'compare', ruleCode, v1, v2] as const,
};

// Export all as a single object for convenience
export const ruleHistoryApi = {
  getVersions,
  getVersion,
  compareVersions,
  rollback,
  queryKeys: ruleHistoryQueryKeys,
};

export default ruleHistoryApi;
