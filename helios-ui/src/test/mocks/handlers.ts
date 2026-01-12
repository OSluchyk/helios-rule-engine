/**
 * MSW Request Handlers
 * Mock API responses for testing
 */

import { http, HttpResponse } from 'msw';

// Mock data
export const mockRules = [
  {
    ruleCode: 'test.rule_001',
    description: 'Test rule 1 - High amount threshold',
    priority: 100,
    enabled: true,
    conditionCount: 1,
    tags: ['test', 'amount'],
    family: 'test',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    ruleCode: 'test.rule_002',
    description: 'Test rule 2 - Status check',
    priority: 90,
    enabled: true,
    conditionCount: 1,
    tags: ['test', 'status'],
    family: 'test',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    ruleCode: 'fraud.rule_001',
    description: 'Fraud detection rule',
    priority: 80,
    enabled: true,
    conditionCount: 2,
    tags: ['fraud', 'production'],
    family: 'fraud',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
];

export const mockMatchResult = {
  eventId: 'test-001',
  matchedRules: [
    {
      ruleCode: 'test.rule_001',
      priority: 100,
      description: 'Test rule 1 - High amount threshold',
    },
  ],
  evaluationTimeNanos: 150000,
  predicatesEvaluated: 5,
  rulesEvaluated: 3,
};

export const mockMatchResultNoMatches = {
  eventId: 'test-002',
  matchedRules: [],
  evaluationTimeNanos: 100000,
  predicatesEvaluated: 3,
  rulesEvaluated: 3,
};

/**
 * Mock for scenario where no rules match but predicates were still evaluated.
 * REGRESSION TEST DATA: Ensures predicatesEvaluated > 0 even when matchedRules is empty.
 */
export const mockEvaluationResultNoMatches = {
  match_result: {
    eventId: 'test-no-match',
    matchedRules: [],
    evaluationTimeNanos: 250000,
    predicatesEvaluated: 15,  // > 0 even though no matches
    rulesEvaluated: 96,
  },
  trace: {
    event_id: 'test-no-match',
    total_duration_nanos: 300000,
    dict_encoding_nanos: 10000,
    base_condition_nanos: 100000,
    predicate_eval_nanos: 150000,
    counter_update_nanos: 20000,
    match_detection_nanos: 20000,
    timingBreakdown: {
      dictEncodingPercent: 3.33,
      baseConditionPercent: 33.33,
      predicateEvalPercent: 50,
      counterUpdatePercent: 6.67,
      matchDetectionPercent: 6.67,
    },
    predicate_outcomes: [
      {
        predicate_id: 1,
        field_name: 'amount',
        operator: 'GREATER_THAN',
        expected_value: 10000,
        actual_value: 500,
        matched: false,
        evaluation_nanos: 5000,
      },
      {
        predicate_id: 2,
        field_name: 'country',
        operator: 'EQUAL_TO',
        expected_value: 'US',
        actual_value: 'XX',
        matched: false,
        evaluation_nanos: 3000,
      },
    ],
    rule_details: [],
    base_condition_cache_hit: true,
    eligible_rules_count: 0,
    matched_rule_codes: [],
  },
};

export const mockEvaluationResult = {
  matchResult: mockMatchResult,
  trace: {
    event_id: 'test-001',
    total_duration_nanos: 200000,
    dict_encoding_nanos: 10000,
    base_condition_nanos: 50000,
    predicate_eval_nanos: 100000,
    counter_update_nanos: 20000,
    match_detection_nanos: 20000,
    timingBreakdown: {
      dictEncodingPercent: 5,
      baseConditionPercent: 25,
      predicateEvalPercent: 50,
      counterUpdatePercent: 10,
      matchDetectionPercent: 10,
    },
    predicate_outcomes: [
      {
        predicate_id: 1,
        field_name: 'amount',
        operator: 'GREATER_THAN',
        expected_value: 5000,
        actual_value: 10000,
        matched: true,
        evaluation_nanos: 5000,
      },
      {
        predicate_id: 2,
        field_name: 'status',
        operator: 'EQUAL_TO',
        expected_value: 'ACTIVE',
        actual_value: 'PENDING',
        matched: false,
        evaluation_nanos: 3000,
      },
    ],
    rule_details: [
      {
        rule_code: 'test.rule_001',
        priority: 100,
        predicate_count: 1,
        matched: true,
      },
    ],
    base_condition_cache_hit: false,
    eligible_rules_count: 3,
    matched_rule_codes: ['test.rule_001'],
  },
};

export const mockExplanationResult = {
  rule_code: 'test.rule_001',
  matched: true,
  summary: 'Rule test.rule_001 matched (passed 1/1 conditions)',
  condition_explanations: [
    {
      field_name: 'amount',
      operator: 'GREATER_THAN',
      expected_value: 5000,
      actual_value: 10000,
      passed: true,
      reason: 'Value matches condition',
      evaluated: true,
    },
  ],
  evaluation_time_nanos: 50000,
  predicates_evaluated: 1,
  rules_evaluated: 1,
};

export const mockExplanationResultNotMatched = {
  rule_code: 'test.rule_001',
  matched: false,
  summary: 'Rule test.rule_001 did not match (failed 1/1 conditions)',
  condition_explanations: [
    {
      field_name: 'amount',
      operator: 'GREATER_THAN',
      expected_value: 5000,
      actual_value: 1000,
      passed: false,
      reason: 'Value mismatch',
      evaluated: true,
    },
  ],
  evaluation_time_nanos: 40000,
  predicates_evaluated: 1,
  rules_evaluated: 1,
};

export const mockBatchEvaluationResult = {
  results: [mockMatchResult, mockMatchResultNoMatches],
  stats: {
    totalEvents: 2,
    matchedEvents: 1,
    totalMatchedRules: 1,
    avgMatchedRulesPerEvent: 0.5,
    avgEvaluationTimeNanos: 125000,
    minEvaluationTimeNanos: 100000,
    maxEvaluationTimeNanos: 150000,
    matchRate: 50,
    p50LatencyNanos: 125000,
    p95LatencyNanos: 150000,
    p99LatencyNanos: 150000,
  },
};

export const mockCompilationStats = {
  uniqueCombinations: 10,
  totalPredicates: 25,
  compilationTimeNanos: 500000000,
  metadata: {
    logicalRules: 3,
    totalExpandedCombinations: 15,
    deduplicationRatePercent: 33.33,
  },
};

export const mockMetrics = {
  totalEvaluations: 1000,
  avgEvaluationTimeNanos: 150000,
  avgPredicatesEvaluated: 5,
  avgMatchesPerEvent: 0.3,
  cacheHitRate: 85.5,
  throughputPerSecond: 6666,
};

export const mockHealthStatus = {
  status: 'UP',
  checks: [
    { name: 'rule-engine', status: 'UP', data: { numRules: 3 } },
    { name: 'Database connections health check', status: 'UP', data: { default: 'UP' } },
  ],
};

// API Handlers
export const handlers = [
  // Rules endpoints
  http.get('/api/v1/rules', () => {
    return HttpResponse.json(mockRules);
  }),

  http.get('/api/v1/rules/:ruleCode', ({ params }) => {
    const rule = mockRules.find((r) => r.ruleCode === params.ruleCode);
    if (rule) {
      return HttpResponse.json({
        ...rule,
        conditions: [
          { field: 'amount', operator: 'GREATER_THAN', value: 5000 },
        ],
      });
    }
    return new HttpResponse(null, { status: 404 });
  }),

  http.delete('/api/v1/rules/:ruleCode', () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Evaluation endpoints
  http.post('/api/v1/evaluate', () => {
    return HttpResponse.json(mockMatchResult);
  }),

  http.post('/api/v1/evaluate/trace', () => {
    return HttpResponse.json(mockEvaluationResult);
  }),

  http.post('/api/v1/evaluate/explain/:ruleCode', ({ params }) => {
    if (params.ruleCode === 'test.rule_001') {
      return HttpResponse.json(mockExplanationResult);
    }
    return HttpResponse.json(mockExplanationResultNotMatched);
  }),

  http.post('/api/v1/evaluate/batch', () => {
    return HttpResponse.json(mockBatchEvaluationResult);
  }),

  // Compilation endpoints
  http.get('/api/v1/compilation/stats', () => {
    return HttpResponse.json(mockCompilationStats);
  }),

  http.get('/api/v1/compilation/dictionaries/fields', () => {
    return HttpResponse.json({
      size: 5,
      entries: {
        amount: 0,
        status: 1,
        country: 2,
        user_id: 3,
        transaction_id: 4,
      },
    });
  }),

  http.get('/api/v1/compilation/dictionaries/values', () => {
    return HttpResponse.json({
      size: 10,
      entries: {
        ACTIVE: 0,
        PENDING: 1,
        US: 2,
        CA: 3,
      },
    });
  }),

  http.get('/api/v1/compilation/predicates/count', () => {
    return HttpResponse.json({
      total: 25,
      byOperator: {
        GREATER_THAN: 10,
        EQUAL_TO: 8,
        LESS_THAN: 5,
        IS_ANY_OF: 2,
      },
      byField: {
        amount: 12,
        status: 8,
        country: 5,
      },
    });
  }),

  http.get('/api/v1/compilation/deduplication', () => {
    return HttpResponse.json({
      totalLogicalRules: 3,
      totalExpandedCombinations: 15,
      uniqueCombinations: 10,
      deduplicationRatePercent: 33.33,
      topSharedPredicates: [
        { predicateId: 1, shareCount: 5 },
        { predicateId: 2, shareCount: 3 },
      ],
    });
  }),

  // Monitoring endpoints
  http.get('/api/v1/monitoring/metrics', () => {
    return HttpResponse.json(mockMetrics);
  }),

  http.get('/health', () => {
    return HttpResponse.json(mockHealthStatus);
  }),
];
