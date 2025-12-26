/**
 * React Query hooks for evaluation operations
 */

import { useMutation, useQuery, UseQueryOptions, UseMutationOptions } from '@tanstack/react-query';
import evaluationApi from '../api/evaluation';
import type {
  Event,
  MatchResult,
  EvaluationResult,
  ExplanationResult,
  BatchEvaluationResult,
  TraceLevel,
  ApiError,
} from '../types/api';

/**
 * Hook for evaluating an event (mutation-based for on-demand evaluation)
 */
export const useEvaluate = (
  options?: UseMutationOptions<MatchResult, ApiError, Event>
) => {
  return useMutation<MatchResult, ApiError, Event>({
    mutationFn: (event: Event) => evaluationApi.evaluate(event),
    ...options,
  });
};

/**
 * Hook for evaluating with trace
 */
export const useEvaluateWithTrace = (
  options?: UseMutationOptions<
    EvaluationResult,
    ApiError,
    { event: Event; level?: TraceLevel; conditionalTracing?: boolean }
  >
) => {
  return useMutation<
    EvaluationResult,
    ApiError,
    { event: Event; level?: TraceLevel; conditionalTracing?: boolean }
  >({
    mutationFn: ({ event, level = 'FULL', conditionalTracing = false }) =>
      evaluationApi.evaluateWithTrace(event, level, conditionalTracing),
    ...options,
  });
};

/**
 * Hook for explaining a specific rule
 */
export const useExplainRule = (
  options?: UseMutationOptions<
    ExplanationResult,
    ApiError,
    { ruleCode: string; event: Event }
  >
) => {
  return useMutation<
    ExplanationResult,
    ApiError,
    { ruleCode: string; event: Event }
  >({
    mutationFn: ({ ruleCode, event }) => evaluationApi.explainRule(ruleCode, event),
    ...options,
  });
};

/**
 * Hook for batch evaluation with aggregated statistics
 */
export const useEvaluateBatch = (
  options?: UseMutationOptions<BatchEvaluationResult, ApiError, Event[]>
) => {
  return useMutation<BatchEvaluationResult, ApiError, Event[]>({
    mutationFn: (events: Event[]) => evaluationApi.evaluateBatch(events),
    ...options,
  });
};

/**
 * Hook to fetch a cached evaluation result (if using query-based approach)
 * Useful for viewing previously evaluated events
 */
export const useEvaluationResult = (
  eventId: string | null,
  options?: Omit<UseQueryOptions<EvaluationResult, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<EvaluationResult, ApiError>({
    queryKey: evaluationApi.queryKeys.evaluate(eventId || ''),
    queryFn: () => {
      throw new Error('Evaluation results are mutation-based, not query-based');
    },
    enabled: false, // Disabled by default since we use mutations
    ...options,
  });
};
