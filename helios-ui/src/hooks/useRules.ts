/**
 * React Query hooks for Rule Management API
 * Provides type-safe, cached data fetching with loading and error states
 */

import { useQuery, useMutation, useQueryClient, UseQueryOptions, UseMutationOptions } from '@tanstack/react-query';
import { rulesApi, updateRule as updateRuleApi } from '../api/rules';
import { post } from '../api/client';
import type { RuleMetadata, RuleDetailResponse, RuleQueryParams, ApiError } from '../types/api';

// Response type for create/update operations
interface CreateRuleResponse {
  ruleCode: string;
  message?: string;
}

interface UpdateRuleResponse {
  ruleCode: string;
  message?: string;
}

// Request payload for creating rules
interface CreateRulePayload {
  rule_code: string;
  description?: string;
  priority?: number;
  enabled?: boolean;
  tags?: string[];
  conditions: Array<{
    field: string;
    operator: string;
    value: unknown;
  }>;
}

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

/**
 * Alias for useRule for compatibility with VisualRuleBuilder
 */
export const useRuleDetails = useRule;

/**
 * Hook to create a new rule
 */
export const useCreateRule = (
  options?: UseMutationOptions<CreateRuleResponse, ApiError, CreateRulePayload>
) => {
  const queryClient = useQueryClient();

  return useMutation<CreateRuleResponse, ApiError, CreateRulePayload>({
    mutationFn: async (payload) => {
      return post<CreateRuleResponse>('/rules', payload);
    },
    onSuccess: () => {
      // Invalidate rules list to refresh after creation
      queryClient.invalidateQueries({ queryKey: rulesApi.queryKeys.all });
    },
    ...options,
  });
};

/**
 * Hook to update an existing rule
 */
export const useUpdateRule = (
  options?: UseMutationOptions<UpdateRuleResponse, ApiError, { ruleCode: string; payload: CreateRulePayload }>
) => {
  const queryClient = useQueryClient();

  return useMutation<UpdateRuleResponse, ApiError, { ruleCode: string; payload: CreateRulePayload }>({
    mutationFn: async ({ ruleCode, payload }) => {
      const result = await updateRuleApi(ruleCode, payload);
      return { ruleCode: result.rule_code };
    },
    onSuccess: (_, variables) => {
      // Invalidate rules list and specific rule detail
      queryClient.invalidateQueries({ queryKey: rulesApi.queryKeys.all });
      queryClient.invalidateQueries({ queryKey: rulesApi.queryKeys.detail(variables.ruleCode) });
    },
    ...options,
  });
};
