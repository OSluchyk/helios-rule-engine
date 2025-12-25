/**
 * Compilation and Model Introspection API
 * Endpoints for compilation statistics, dictionaries, and model analysis
 */

import { get } from './client';
import type {
  CompilationStats,
  DictionaryInfo,
  PredicateCountInfo,
  DeduplicationAnalysis,
} from '../types/api';

/**
 * Get compilation statistics and metrics
 */
export const getCompilationStats = async (): Promise<CompilationStats> => {
  return get<CompilationStats>('/compilation/stats');
};

/**
 * Get field dictionary information
 */
export const getFieldDictionary = async (): Promise<DictionaryInfo> => {
  return get<DictionaryInfo>('/compilation/dictionaries/fields');
};

/**
 * Get value dictionary information
 */
export const getValueDictionary = async (): Promise<DictionaryInfo> => {
  return get<DictionaryInfo>('/compilation/dictionaries/values');
};

/**
 * Get predicate count breakdown
 */
export const getPredicateCount = async (): Promise<PredicateCountInfo> => {
  return get<PredicateCountInfo>('/compilation/predicates/count');
};

/**
 * Get deduplication analysis
 */
export const getDeduplicationAnalysis = async (): Promise<DeduplicationAnalysis> => {
  return get<DeduplicationAnalysis>('/compilation/deduplication');
};

/**
 * React Query hooks for compilation API
 */
export const compilationQueryKeys = {
  all: ['compilation'] as const,
  stats: () => [...compilationQueryKeys.all, 'stats'] as const,
  dictionaries: () => [...compilationQueryKeys.all, 'dictionaries'] as const,
  fieldDictionary: () => [...compilationQueryKeys.dictionaries(), 'fields'] as const,
  valueDictionary: () => [...compilationQueryKeys.dictionaries(), 'values'] as const,
  predicates: () => [...compilationQueryKeys.all, 'predicates'] as const,
  predicateCount: () => [...compilationQueryKeys.predicates(), 'count'] as const,
  deduplication: () => [...compilationQueryKeys.all, 'deduplication'] as const,
};

// Export all as a single object for convenience
export const compilationApi = {
  getCompilationStats,
  getFieldDictionary,
  getValueDictionary,
  getPredicateCount,
  getDeduplicationAnalysis,
  queryKeys: compilationQueryKeys,
};

export default compilationApi;
