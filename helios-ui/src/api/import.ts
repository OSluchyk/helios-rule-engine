/**
 * Rule Import API Client
 * Provides methods for validating and importing rules from files
 */

import { post } from './client';
import type {
  ImportValidationRequest,
  ImportValidationResponse,
  ImportExecutionRequest,
  ImportExecutionResponse,
} from '../types/api';

/**
 * Validate rules before import
 *
 * @param request - Validation request containing format and rules
 * @returns Validation results with status for each rule
 */
export const validateImport = async (
  request: ImportValidationRequest
): Promise<ImportValidationResponse> => {
  return post<ImportValidationResponse>('/rules/import/validate', request);
};

/**
 * Execute rule import with conflict resolution
 *
 * @param request - Import execution request with selected rules and conflict resolution strategy
 * @returns Import results with counts and per-rule status
 */
export const executeImport = async (
  request: ImportExecutionRequest
): Promise<ImportExecutionResponse> => {
  return post<ImportExecutionResponse>('/rules/import/execute', request);
};

/**
 * React Query keys for cache management
 */
export const importQueryKeys = {
  all: ['import'] as const,
  validate: (format: string) => [...importQueryKeys.all, 'validate', format] as const,
  execute: () => [...importQueryKeys.all, 'execute'] as const,
};

export default {
  validateImport,
  executeImport,
  queryKeys: importQueryKeys,
};
