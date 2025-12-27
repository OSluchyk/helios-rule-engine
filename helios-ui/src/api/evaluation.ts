/**
 * Evaluation API Client
 * Provides methods for evaluating events with tracing and explanations
 */

import { post } from './client';
import type {
  Event,
  MatchResult,
  EvaluationResult,
  ExplanationResult,
  BatchEvaluationResult,
  TraceLevel,
} from '../types/api';

/**
 * Evaluate an event against all rules (no tracing)
 */
export const evaluate = async (event: Event): Promise<MatchResult> => {
  return post<MatchResult>('/evaluate', event);
};

/**
 * Evaluate an event with configurable tracing
 *
 * @param event - The event to evaluate
 * @param level - Trace detail level (BASIC, STANDARD, FULL)
 * @param conditionalTracing - Only generate trace if rules match
 */
export const evaluateWithTrace = async (
  event: Event,
  level: TraceLevel = 'FULL',
  conditionalTracing: boolean = false
): Promise<EvaluationResult> => {
  return post<EvaluationResult>(
    `/evaluate/trace?level=${level}&conditionalTracing=${conditionalTracing}`,
    event
  );
};

/**
 * Explain why a specific rule matched or didn't match
 *
 * @param ruleCode - The rule to explain
 * @param event - The event to evaluate against
 */
export const explainRule = async (
  ruleCode: string,
  event: Event
): Promise<ExplanationResult> => {
  return post<ExplanationResult>(`/evaluate/explain/${ruleCode}`, event);
};

/**
 * Evaluate multiple events in batch with aggregated statistics
 *
 * @param events - Array of events to evaluate
 * @param level - Trace detail level (default: NONE for performance)
 */
export const evaluateBatch = async (
  events: Event[],
  level: TraceLevel = 'NONE'
): Promise<BatchEvaluationResult> => {
  return post<BatchEvaluationResult>(`/evaluate/batch?level=${level}`, events);
};

/**
 * React Query keys for cache management
 */
export const evaluationQueryKeys = {
  all: ['evaluation'] as const,
  evaluate: (eventId: string) => [...evaluationQueryKeys.all, 'evaluate', eventId] as const,
  trace: (eventId: string, level: TraceLevel) =>
    [...evaluationQueryKeys.all, 'trace', eventId, level] as const,
  explain: (ruleCode: string, eventId: string) =>
    [...evaluationQueryKeys.all, 'explain', ruleCode, eventId] as const,
};

export default {
  evaluate,
  evaluateWithTrace,
  explainRule,
  evaluateBatch,
  queryKeys: evaluationQueryKeys,
};
