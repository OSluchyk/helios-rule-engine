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
export interface EvaluationRequest {
  trace_level?: 'NONE' | 'BASIC' | 'STANDARD' | 'FULL';
  context: Record<string, any>;
}

export interface EvaluationResult {
  matched_rules: string[];
  execution_time_ms: number;
  trace?: EvaluationTrace;
}

export interface EvaluationTrace {
  trace_level: string;
  matched_rule_ids: string[];
  evaluated_predicate_count: number;
  total_predicate_count: number;
  execution_time_ns: number;
  predicate_results?: PredicateResult[];
}

export interface PredicateResult {
  predicate_id: number;
  field: string;
  operator: string;
  value: any;
  matched: boolean;
  evaluation_time_ns: number;
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
