/**
 * TypeScript type definitions for Helios Rule Engine API
 * These types match the backend API DTOs and responses
 */

// Rule Management Types
export interface RuleCondition {
  field: string;
  operator: string;
  value: any;
}

export interface RuleMetadata {
  rule_code: string;
  description: string;
  conditions: RuleCondition[];
  priority: number;
  enabled: boolean;
  created_by?: string;
  created_at?: string;
  last_modified_by?: string;
  last_modified_at?: string;
  version?: number;
  tags?: string[];
  labels?: Record<string, string>;
  combination_ids?: number[];
  estimated_selectivity?: number;
  is_vectorizable?: boolean;
  compilation_status?: string;
}

export interface RuleListResponse {
  rules: RuleMetadata[];
  total: number;
}

export interface RuleDetailResponse extends RuleMetadata {
  combinations: number[];
  predicates: PredicateInfo[];
}

export interface PredicateInfo {
  id: number;
  field: string;
  operator: string;
  value: any;
  weight: number;
  selectivity: number;
}

// Compilation Types
export interface CompilationStats {
  logical_rules: number;
  total_expanded_combinations: number;
  unique_combinations: number;
  deduplication_rate_percent: number;
  predicate_count: number;
  compilation_time_ms: number;
  field_dictionary_size: number;
  value_dictionary_size: number;
}

export interface DictionaryInfo {
  size: number;
  entries: Record<string, number>;
}

export interface PredicateCountInfo {
  total: number;
  by_operator: Record<string, number>;
}

export interface DeduplicationAnalysis {
  total_logical_rules: number;
  total_expanded_combinations: number;
  unique_physical_combinations: number;
  deduplication_rate: number;
  average_combinations_per_rule: number;
  max_combinations_for_single_rule: number;
}

// Monitoring Types
export interface MonitoringMetrics {
  timestamp: number;
  evaluations_total: number;
  evaluations_per_second: number;
  average_latency_ms: number;
  p50_latency_ms: number;
  p95_latency_ms: number;
  p99_latency_ms: number;
  cache_hit_rate: number;
  cache_size: number;
  active_rules: number;
  error_count: number;
  memory_used_mb: number;
  cpu_usage_percent: number;
}

export interface HealthStatus {
  status: 'UP' | 'DOWN' | 'DEGRADED';
  timestamp: number;
  checks?: HealthCheck[];
}

export interface HealthCheck {
  name: string;
  status: 'UP' | 'DOWN';
  details?: Record<string, any>;
}

// Evaluation Types
export type TraceLevel = 'NONE' | 'BASIC' | 'STANDARD' | 'FULL';

export interface Event {
  eventId: string;
  timestamp: number;
  attributes: Record<string, any>;
}

export interface MatchedRule {
  ruleId: number;
  ruleCode: string;
  priority: number;
  description: string;
}

export interface MatchResult {
  eventId: string;
  matchedRules: MatchedRule[];
  evaluationTimeNanos: number;
  predicatesEvaluated: number;
  rulesEvaluated: number;
}

export interface TimingBreakdown {
  dictEncodingPercent: number;
  baseConditionPercent: number;
  predicateEvalPercent: number;
  counterUpdatePercent: number;
  matchDetectionPercent: number;
}

export interface PredicateOutcome {
  predicate_id: number;
  field_name: string;
  operator: string;
  expected_value: any;
  actual_value?: any; // Only present in FULL trace level
  matched: boolean;
  evaluation_nanos: number;
}

export interface RuleDetail {
  combination_id: number;
  rule_code: string;
  priority: number;
  predicates_matched: number;
  predicates_required: number;
  final_match: boolean;
  failed_predicates: string[];
}

export interface EvaluationTrace {
  event_id: string;
  total_duration_nanos: number;
  dict_encoding_nanos: number;
  base_condition_nanos: number;
  predicate_eval_nanos: number;
  counter_update_nanos: number;
  match_detection_nanos: number;
  timingBreakdown: TimingBreakdown;
  predicate_outcomes: PredicateOutcome[];
  rule_details: RuleDetail[];
}

export interface EvaluationResult {
  match_result: MatchResult;
  trace?: EvaluationTrace;
}

export interface ConditionExplanation {
  field_name: string;
  operator: string;
  expected_value: any;
  actual_value: any;
  passed: boolean;
  reason: string;
  closeness?: number; // 0-100, how close the value was to passing
}

export interface ExplanationResult {
  rule_code: string;
  matched: boolean;
  summary: string;
  condition_explanations: ConditionExplanation[];
}

// Batch Evaluation Types
export interface BatchStats {
  totalEvents: number;
  avgEvaluationTimeNanos: number;
  matchRate: number;
  minEvaluationTimeNanos: number;
  maxEvaluationTimeNanos: number;
  totalMatchedRules: number;
  avgRulesMatchedPerEvent: number;
}

export interface BatchEvaluationResult {
  results: MatchResult[];
  stats: BatchStats;
}

// API Error Types
export interface ApiError {
  error: string;
  message: string;
  status?: number;
  details?: any;
}

// Query Parameters
export interface RuleQueryParams {
  tag?: string;
  enabled?: boolean;
  search?: string;
  limit?: number;
  offset?: number;
}
