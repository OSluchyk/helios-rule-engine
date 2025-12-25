/**
 * React Query hooks for Compilation API
 * Provides compilation statistics and model introspection
 */

import { useQuery, UseQueryOptions } from '@tanstack/react-query';
import { compilationApi } from '../api/compilation';
import type {
  CompilationStats,
  DictionaryInfo,
  PredicateCountInfo,
  DeduplicationAnalysis,
  ApiError,
} from '../types/api';

/**
 * Hook to fetch compilation statistics
 */
export const useCompilationStats = (
  options?: Omit<UseQueryOptions<CompilationStats, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<CompilationStats, ApiError>({
    queryKey: compilationApi.queryKeys.stats(),
    queryFn: () => compilationApi.getCompilationStats(),
    staleTime: 300000, // Consider data fresh for 5 minutes (compilation stats don't change often)
    ...options,
  });
};

/**
 * Hook to fetch field dictionary information
 */
export const useFieldDictionary = (
  options?: Omit<UseQueryOptions<DictionaryInfo, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<DictionaryInfo, ApiError>({
    queryKey: compilationApi.queryKeys.fieldDictionary(),
    queryFn: () => compilationApi.getFieldDictionary(),
    staleTime: 300000,
    ...options,
  });
};

/**
 * Hook to fetch value dictionary information
 */
export const useValueDictionary = (
  options?: Omit<UseQueryOptions<DictionaryInfo, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<DictionaryInfo, ApiError>({
    queryKey: compilationApi.queryKeys.valueDictionary(),
    queryFn: () => compilationApi.getValueDictionary(),
    staleTime: 300000,
    ...options,
  });
};

/**
 * Hook to fetch predicate count breakdown
 */
export const usePredicateCount = (
  options?: Omit<UseQueryOptions<PredicateCountInfo, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<PredicateCountInfo, ApiError>({
    queryKey: compilationApi.queryKeys.predicateCount(),
    queryFn: () => compilationApi.getPredicateCount(),
    staleTime: 300000,
    ...options,
  });
};

/**
 * Hook to fetch deduplication analysis
 */
export const useDeduplicationAnalysis = (
  options?: Omit<UseQueryOptions<DeduplicationAnalysis, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<DeduplicationAnalysis, ApiError>({
    queryKey: compilationApi.queryKeys.deduplication(),
    queryFn: () => compilationApi.getDeduplicationAnalysis(),
    staleTime: 300000,
    ...options,
  });
};
