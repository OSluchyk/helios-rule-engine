// Mock data for Helios UI demonstration

export interface Rule {
  id: string;
  name: string;
  description: string;
  family: string;
  status: 'active' | 'inactive' | 'draft';
  priority: number;
  weight: number;
  createdAt: string;
  createdBy: string;
  lastModified: string;
  version: number;
  
  baseConditions: Array<{
    attribute: string;
    operator: string;
    value: any;
  }>;
  
  vectorizedConditions: Array<{
    attribute: string;
    operator: string;
    value: number;
  }>;
  
  actions: Array<{
    type: string;
    params: any;
  }>;
  
  stats: {
    evalsPerDay: number;
    matchRate: number;
    avgLatencyMs: number;
  };
  
  optimization: {
    dedupGroupId: string;
    cacheKey: string;
    dictionaryIds: Record<string, number>;
  };
}

export const mockRules: Rule[] = [
  {
    id: 'rule-12453',
    name: 'High-Value Customer Upsell',
    description: 'Target customers with >$10K lifetime value who viewed premium products in last 30 days',
    family: 'Customer Segmentation',
    status: 'active',
    priority: 100,
    weight: 1.0,
    createdAt: '2025-01-15',
    createdBy: 'user@company.com',
    lastModified: '2025-10-20',
    version: 3,
    baseConditions: [
      { attribute: 'customer_segment', operator: 'EQUAL_TO', value: 'premium' },
      { attribute: 'region', operator: 'IS_ANY_OF', value: ['US', 'EU', 'JP'] },
      { attribute: 'account_status', operator: 'EQUAL_TO', value: 'active' }
    ],
    vectorizedConditions: [
      { attribute: 'lifetime_value', operator: '>=', value: 10000.0 },
      { attribute: 'days_since_last_purchase', operator: '<=', value: 30 }
    ],
    actions: [
      { type: 'trigger_campaign', params: 'premium_upsell_2025' },
      { type: 'set_discount', params: 0.15 },
      { type: 'log_event', params: 'upsell_opportunity' }
    ],
    stats: {
      evalsPerDay: 1200000,
      matchRate: 0.85,
      avgLatencyMs: 0.28
    },
    optimization: {
      dedupGroupId: 'group-7',
      cacheKey: 'hash_3a4f9c2d',
      dictionaryIds: { customer_segment: 42, region: 17 }
    }
  },
  {
    id: 'rule-12458',
    name: 'Retention Campaign',
    description: 'Re-engage customers who haven\'t purchased in 60-90 days',
    family: 'Customer Segmentation',
    status: 'active',
    priority: 80,
    weight: 1.0,
    createdAt: '2025-02-10',
    createdBy: 'jane@company.com',
    lastModified: '2025-11-05',
    version: 2,
    baseConditions: [
      { attribute: 'customer_segment', operator: 'EQUAL_TO', value: 'premium' },
      { attribute: 'account_status', operator: 'EQUAL_TO', value: 'active' }
    ],
    vectorizedConditions: [
      { attribute: 'days_since_last_purchase', operator: '>=', value: 60 },
      { attribute: 'days_since_last_purchase', operator: '<=', value: 90 },
      { attribute: 'purchase_frequency', operator: '>=', value: 4 }
    ],
    actions: [
      { type: 'trigger_campaign', params: 'retention_2025' },
      { type: 'set_discount', params: 0.10 }
    ],
    stats: {
      evalsPerDay: 980000,
      matchRate: 0.42,
      avgLatencyMs: 0.31
    },
    optimization: {
      dedupGroupId: 'group-7',
      cacheKey: 'hash_7b2c1f3e',
      dictionaryIds: { customer_segment: 42 }
    }
  },
  {
    id: 'rule-14201',
    name: 'Fraud Alert - High Risk Transaction',
    description: 'Flag transactions with suspicious patterns',
    family: 'Fraud Detection',
    status: 'active',
    priority: 200,
    weight: 2.0,
    createdAt: '2025-03-01',
    createdBy: 'security@company.com',
    lastModified: '2025-12-15',
    version: 5,
    baseConditions: [
      { attribute: 'transaction_type', operator: 'EQUAL_TO', value: 'purchase' },
      { attribute: 'device_verified', operator: 'EQUAL_TO', value: false }
    ],
    vectorizedConditions: [
      { attribute: 'transaction_amount', operator: '>', value: 5000 },
      { attribute: 'velocity_score', operator: '>', value: 0.8 },
      { attribute: 'device_trust_score', operator: '<', value: 0.3 }
    ],
    actions: [
      { type: 'flag_for_review', params: 'high_risk' },
      { type: 'send_alert', params: 'fraud_team' },
      { type: 'require_2fa', params: true }
    ],
    stats: {
      evalsPerDay: 2500000,
      matchRate: 0.03,
      avgLatencyMs: 0.22
    },
    optimization: {
      dedupGroupId: 'group-12',
      cacheKey: 'hash_9d4e2a1b',
      dictionaryIds: { transaction_type: 8 }
    }
  },
  {
    id: 'rule-12455',
    name: 'Slow Performance Rule',
    description: 'Example of a rule with performance issues',
    family: 'Customer Segmentation',
    status: 'active',
    priority: 50,
    weight: 1.0,
    createdAt: '2025-05-20',
    createdBy: 'dev@company.com',
    lastModified: '2025-06-10',
    version: 1,
    baseConditions: [
      { attribute: 'customer_type', operator: 'EQUAL_TO', value: 'business' }
    ],
    vectorizedConditions: [
      { attribute: 'account_age_days', operator: '>', value: 365 }
    ],
    actions: [
      { type: 'send_survey', params: 'annual_feedback' }
    ],
    stats: {
      evalsPerDay: 450000,
      matchRate: 0.22,
      avgLatencyMs: 1.2 // Slow!
    },
    optimization: {
      dedupGroupId: 'group-3',
      cacheKey: 'hash_1f8c9b7a',
      dictionaryIds: { customer_type: 15 }
    }
  },
  {
    id: 'rule-15678',
    name: 'Premium Product Recommendation',
    description: 'Recommend premium products to high-value customers',
    family: 'Personalization',
    status: 'active',
    priority: 70,
    weight: 1.0,
    createdAt: '2025-07-12',
    createdBy: 'marketing@company.com',
    lastModified: '2025-11-20',
    version: 2,
    baseConditions: [
      { attribute: 'customer_segment', operator: 'EQUAL_TO', value: 'premium' }
    ],
    vectorizedConditions: [
      { attribute: 'lifetime_value', operator: '>=', value: 20000 },
      { attribute: 'avg_order_value', operator: '>=', value: 500 }
    ],
    actions: [
      { type: 'show_recommendations', params: 'premium_products' },
      { type: 'set_badge', params: 'vip_customer' }
    ],
    stats: {
      evalsPerDay: 750000,
      matchRate: 0.12,
      avgLatencyMs: 0.25
    },
    optimization: {
      dedupGroupId: 'group-7',
      cacheKey: 'hash_6e3d2c8b',
      dictionaryIds: { customer_segment: 42 }
    }
  }
];

export interface RuleFamily {
  name: string;
  ruleCount: number;
  dedupRate: number;
  color: string;
}

export const ruleFamilies: RuleFamily[] = [
  { name: 'Customer Segmentation', ruleCount: 45, dedupRate: 0.89, color: 'blue' },
  { name: 'Fraud Detection', ruleCount: 128, dedupRate: 0.92, color: 'red' },
  { name: 'Personalization', ruleCount: 89, dedupRate: 0.78, color: 'green' },
  { name: 'Pricing & Promotions', ruleCount: 67, dedupRate: 0.85, color: 'purple' },
  { name: 'Compliance', ruleCount: 34, dedupRate: 0.91, color: 'orange' }
];

export interface CompilationMetrics {
  stages: {
    parsing: { durationMs: number; rulesProcessed: number; errors: number };
    dictionaryEncoding: { durationMs: number; dictionariesCreated: number };
    deduplication: { 
      durationMs: number; 
      inputRules: number; 
      outputGroups: number; 
      dedupRate: number;
      memorySavingsKB: number;
    };
    soaLayout: { durationMs: number; cacheLineUtilization: number };
    invertedIndex: { durationMs: number; averageReduction: number };
    simdVectorization: { 
      durationMs: number; 
      vectorizedPredicates: number; 
      scalarPredicates: number;
      vectorizationRate: number;
    };
  };
  totalDurationMs: number;
  estimatedThroughput: number;
  memoryFootprintGB: number;
}

export const mockCompilationMetrics: CompilationMetrics = {
  stages: {
    parsing: { durationMs: 12, rulesProcessed: 45, errors: 0 },
    dictionaryEncoding: { durationMs: 18, dictionariesCreated: 8 },
    deduplication: { 
      durationMs: 23, 
      inputRules: 45, 
      outputGroups: 5, 
      dedupRate: 0.89,
      memorySavingsKB: 3720
    },
    soaLayout: { durationMs: 15, cacheLineUtilization: 0.87 },
    invertedIndex: { durationMs: 31, averageReduction: 0.92 },
    simdVectorization: { 
      durationMs: 28, 
      vectorizedPredicates: 89, 
      scalarPredicates: 67,
      vectorizationRate: 0.57
    }
  },
  totalDurationMs: 127,
  estimatedThroughput: 18.5,
  memoryFootprintGB: 4.8
};

export interface EvaluationTrace {
  eventId: string;
  timestamp: string;
  
  timings: {
    parsing: number;
    dictionaryEncoding: number;
    candidateFiltering: number;
    baseEvaluation: number;
    vectorEvaluation: number;
    actionExecution: number;
    total: number;
  };
  
  candidateReduction: {
    totalRules: number;
    candidateRules: number;
    reductionPercent: number;
  };
  
  matchedRules: Array<{
    ruleId: string;
    ruleName: string;
    priority: number;
    conditions: Array<{
      attribute: string;
      operator: string;
      expected: any;
      actual: any;
      passed: boolean;
    }>;
    evalTimeMs: number;
    cacheHit: boolean;
  }>;
  
  nonMatchedRules: Array<{
    ruleId: string;
    ruleName: string;
    failedCondition: {
      attribute: string;
      expected: any;
      actual: any;
      delta: any;
    };
  }>;
}

export const mockEvaluationTrace: EvaluationTrace = {
  eventId: 'evt_abc123',
  timestamp: '2025-12-25T14:32:15Z',
  
  timings: {
    parsing: 0.02,
    dictionaryEncoding: 0.06,
    candidateFiltering: 0.08,
    baseEvaluation: 0.12,
    vectorEvaluation: 0.18,
    actionExecution: 0.04,
    total: 0.42
  },
  
  candidateReduction: {
    totalRules: 45,
    candidateRules: 4,
    reductionPercent: 91
  },
  
  matchedRules: [
    {
      ruleId: 'rule-12453',
      ruleName: 'High-Value Customer Upsell',
      priority: 100,
      conditions: [
        { attribute: 'customer_segment', operator: '=', expected: 'premium', actual: 'premium', passed: true },
        { attribute: 'region', operator: 'IN', expected: ['US', 'EU', 'JP'], actual: 'US', passed: true },
        { attribute: 'account_status', operator: '=', expected: 'active', actual: 'active', passed: true },
        { attribute: 'lifetime_value', operator: '>=', expected: 10000, actual: 15000, passed: true },
        { attribute: 'days_since_last_purchase', operator: '<=', expected: 30, actual: 12, passed: true }
      ],
      evalTimeMs: 0.28,
      cacheHit: true
    },
    {
      ruleId: 'rule-12458',
      ruleName: 'Retention Campaign',
      priority: 80,
      conditions: [
        { attribute: 'customer_segment', operator: '=', expected: 'premium', actual: 'premium', passed: true },
        { attribute: 'account_status', operator: '=', expected: 'active', actual: 'active', passed: true },
        { attribute: 'days_since_last_purchase', operator: '>=', expected: 60, actual: 12, passed: false },
        { attribute: 'purchase_frequency', operator: '>=', expected: 4, actual: 6, passed: true }
      ],
      evalTimeMs: 0.14,
      cacheHit: true
    }
  ],
  
  nonMatchedRules: [
    {
      ruleId: 'rule-15678',
      ruleName: 'Premium Product Recommendation',
      failedCondition: {
        attribute: 'lifetime_value',
        expected: 20000,
        actual: 15000,
        delta: -5000
      }
    }
  ]
};

export interface SystemMetrics {
  throughput: number; // events/min
  latency: {
    p50: number;
    p95: number;
    p99: number;
    p999: number;
  };
  memory: {
    used: number;
    total: number;
  };
  cache: {
    baseConditionHitRate: number;
    dictionaryHitRate: number;
  };
}

export const mockSystemMetrics: SystemMetrics = {
  throughput: 18200000,
  latency: {
    p50: 0.28,
    p95: 0.52,
    p99: 0.74,
    p999: 1.2
  },
  memory: {
    used: 4.2,
    total: 6.0
  },
  cache: {
    baseConditionHitRate: 0.94,
    dictionaryHitRate: 0.998
  }
};
