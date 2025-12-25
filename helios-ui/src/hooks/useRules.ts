/**
 * React Query hooks for Rule Management API
 * Provides type-safe, cached data fetching with loading and error states
 */

import { useQuery, UseQueryOptions } from '@tanstack/react-query';
import { rulesApi } from '../api/rules';
import type { RuleMetadata, RuleDetailResponse, RuleQueryParams, ApiError } from '../types/api';

/**
 * Hook to fetch all rules with optional filtering
 */
export const useRules = (
  params?: RuleQueryParams,
  options?: Omit<UseQueryOptions<RuleMetadata[], ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<RuleMetadata[], ApiError>({
    queryKey: rulesApi.queryKeys.list(params),
    queryFn: () => rulesApi.listRules(params),
    staleTime: 30000, // Consider data fresh for 30 seconds
    ...options,
  });
};

/**
 * Hook to fetch a single rule by rule code
 */
export const useRule = (
  ruleCode: string,
  options?: Omit<UseQueryOptions<RuleDetailResponse, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<RuleDetailResponse, ApiError>({
    queryKey: rulesApi.queryKeys.detail(ruleCode),
    queryFn: () => rulesApi.getRule(ruleCode),
    enabled: !!ruleCode, // Only fetch if ruleCode is provided
    staleTime: 60000, // Consider data fresh for 1 minute
    ...options,
  });
};

/**
 * Hook to fetch combination IDs for a rule
 */
export const useRuleCombinations = (
  ruleCode: string,
  options?: Omit<UseQueryOptions<number[], ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<number[], ApiError>({
    queryKey: rulesApi.queryKeys.combinations(ruleCode),
    queryFn: () => rulesApi.getRuleCombinations(ruleCode),
    enabled: !!ruleCode,
    staleTime: 60000,
    ...options,
  });
};

/**
 * Hook to fetch rules that use a specific predicate
 */
export const useRulesByPredicate = (
  predicateId: number,
  options?: Omit<UseQueryOptions<RuleMetadata[], ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<RuleMetadata[], ApiError>({
    queryKey: rulesApi.queryKeys.byPredicate(predicateId),
    queryFn: () => rulesApi.getRulesByPredicate(predicateId),
    enabled: predicateId >= 0,
    staleTime: 60000,
    ...options,
  });
};

/**
 * Hook to fetch rules with a specific tag
 */
export const useRulesByTag = (
  tag: string,
  options?: Omit<UseQueryOptions<RuleMetadata[], ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<RuleMetadata[], ApiError>({
    queryKey: rulesApi.queryKeys.byTag(tag),
    queryFn: () => rulesApi.getRulesByTag(tag),
    enabled: !!tag,
    staleTime: 60000,
    ...options,
  });
};
