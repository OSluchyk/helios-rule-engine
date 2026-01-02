/**
 * Compilation and Model Introspection API
 * Endpoints for compilation statistics, dictionaries, and model analysis
 */

import { get, post } from './client';
import type {
  CompilationStats,
  DictionaryInfo,
  PredicateCountInfo,
  DeduplicationAnalysis,
} from '../types/api';

/**
 * Result of compiling rules from the database
 */
export interface CompileFromDbResult {
  success: boolean;
  message: string;
  totalRulesInDb?: number;
  enabledRules?: number;
  compiledRules?: number;
  compilationTimeMs?: number;
  uniqueCombinations?: number;
  totalPredicates?: number;
  error?: string;
}

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
 * Compile all enabled rules from the database into the active engine model.
 * This updates compilation_status to "OK" for successfully compiled rules.
 */
export const compileFromDatabase = async (): Promise<CompileFromDbResult> => {
  return post<CompileFromDbResult>('/compilation/compile-from-db');
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
  compileFromDatabase,
  queryKeys: compilationQueryKeys,
};

export default compilationApi;
