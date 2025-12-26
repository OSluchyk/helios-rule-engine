/**
 * Monitoring View Component
 * Displays real-time rule evaluation metrics and system performance
 */

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Alert } from '../ui/alert';
import { Badge } from '../ui/badge';
import { get } from '../../../api/client';

interface MetricsSummary {
  totalEvaluations: number;
  totalMatches: number;
  overallMatchRate: number;
  uniqueRulesEvaluated: number;
  avgEventsPerMinute: number;
  cacheHitRate: number;
}

interface HotRule {
  ruleCode: string;
  evaluationCount: number;
  matchCount: number;
  matchRate: number;
}

interface SlowRule {
  ruleCode: string;
  p99Nanos: number;
  avgNanos: number;
  maxNanos: number;
  evaluationCount: number;
}

interface LatencySample {
  timestamp: string;
  durationNanos: number;
}

interface ThroughputSample {
  timestamp: string;
  eventsProcessed: number;
}

export function MonitoringView() {
  const [summary, setSummary] = useState<MetricsSummary | null>(null);
  const [hotRules, setHotRules] = useState<HotRule[]>([]);
  const [slowRules, setSlowRules] = useState<SlowRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const fetchMetrics = async () => {
    try {
      setError(null);

      const [summaryData, hotRulesData, slowRulesData] = await Promise.all([
        get<MetricsSummary>('/monitoring/summary'),
        get<{ topN: number; rules: HotRule[]; total: number }>('/monitoring/hot-rules?topN=10'),
        get<{ thresholdMs: number; rules: SlowRule[]; total: number }>('/monitoring/slow-rules?thresholdMs=100'),
      ]);

      setSummary(summaryData);
      setHotRules(hotRulesData.rules);
      setSlowRules(slowRulesData.rules);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch monitoring metrics');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();

    // Auto-refresh every 5 seconds
    if (autoRefresh) {
      const interval = setInterval(fetchMetrics, 5000);
      return () => clearInterval(interval);
    }
  }, [autoRefresh]);

  const formatNumber = (num: number): string => {
    return num.toLocaleString();
  };

  const formatNanos = (nanos: number): string => {
    const ms = nanos / 1_000_000;
    if (ms < 1) return `${(nanos / 1_000).toFixed(0)} μs`;
    if (ms < 1000) return `${ms.toFixed(2)} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-muted-foreground">Loading monitoring metrics...</div>
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <strong>Error:</strong> {error}
      </Alert>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">Performance Monitoring</h2>
          <p className="text-muted-foreground">Real-time rule evaluation metrics and system health</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant={autoRefresh ? 'default' : 'outline'}
            size="sm"
            onClick={() => setAutoRefresh(!autoRefresh)}
          >
            {autoRefresh ? '⏸ Pause' : '▶ Resume'} Auto-Refresh
          </Button>
          <Button onClick={fetchMetrics} size="sm">
            Refresh Now
          </Button>
        </div>
      </div>

      {/* Key Metrics */}
      {summary && (
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Total Evaluations</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatNumber(summary.totalEvaluations)}</div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Total Matches</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatNumber(summary.totalMatches)}</div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Match Rate</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{(summary.overallMatchRate * 100).toFixed(1)}%</div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Active Rules</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatNumber(summary.uniqueRulesEvaluated)}</div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Throughput</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatNumber(summary.avgEventsPerMinute)}</div>
              <div className="text-xs text-muted-foreground">events/min</div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardDescription className="text-xs">Cache Hit Rate</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{(summary.cacheHitRate * 100).toFixed(1)}%</div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Hot Rules */}
      <Card>
        <CardHeader>
          <CardTitle>Hot Rules (Top 10)</CardTitle>
          <CardDescription>Most frequently evaluated rules</CardDescription>
        </CardHeader>
        <CardContent>
          {hotRules.length === 0 ? (
            <div className="text-center text-muted-foreground py-8">
              No hot rules data available. Start evaluating events to see metrics.
            </div>
          ) : (
            <div className="space-y-3">
              {hotRules.map((rule, index) => (
                <div
                  key={rule.ruleCode}
                  className="flex items-center gap-4 p-3 bg-muted rounded-md"
                >
                  <div className="flex items-center justify-center w-8 h-8 rounded-full bg-primary text-primary-foreground font-bold text-sm">
                    {index + 1}
                  </div>
                  <div className="flex-1">
                    <div className="font-medium">{rule.ruleCode}</div>
                    <div className="text-sm text-muted-foreground">
                      {formatNumber(rule.evaluationCount)} evaluations •{' '}
                      {formatNumber(rule.matchCount)} matches
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-sm font-semibold">
                      {(rule.matchRate * 100).toFixed(1)}%
                    </div>
                    <div className="text-xs text-muted-foreground">match rate</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Slow Rules */}
      <Card>
        <CardHeader>
          <CardTitle>Slow Rules</CardTitle>
          <CardDescription>Rules with P99 latency above 100ms threshold</CardDescription>
        </CardHeader>
        <CardContent>
          {slowRules.length === 0 ? (
            <div className="text-center text-muted-foreground py-8">
              No slow rules detected. All rules are performing well!
            </div>
          ) : (
            <div className="space-y-3">
              {slowRules.map((rule) => (
                <div key={rule.ruleCode} className="p-4 border border-yellow-500/50 bg-yellow-50 dark:bg-yellow-950 rounded-md">
                  <div className="flex items-start justify-between">
                    <div>
                      <div className="font-medium">{rule.ruleCode}</div>
                      <div className="text-sm text-muted-foreground mt-1">
                        {formatNumber(rule.evaluationCount)} evaluations
                      </div>
                    </div>
                    <Badge variant="destructive">Slow</Badge>
                  </div>
                  <div className="grid grid-cols-3 gap-4 mt-3">
                    <div>
                      <div className="text-xs text-muted-foreground">P99 Latency</div>
                      <div className="text-sm font-semibold text-red-600">
                        {formatNanos(rule.p99Nanos)}
                      </div>
                    </div>
                    <div>
                      <div className="text-xs text-muted-foreground">Avg Latency</div>
                      <div className="text-sm font-semibold">{formatNanos(rule.avgNanos)}</div>
                    </div>
                    <div>
                      <div className="text-xs text-muted-foreground">Max Latency</div>
                      <div className="text-sm font-semibold">{formatNanos(rule.maxNanos)}</div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Performance Recommendations */}
      {summary && (
        <Card>
          <CardHeader>
            <CardTitle>Performance Insights</CardTitle>
            <CardDescription>Recommendations based on current metrics</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {summary.cacheHitRate > 0.8 && (
                <div className="flex items-start gap-3 p-3 bg-green-50 dark:bg-green-950 rounded-md">
                  <div className="text-green-600 mt-0.5">✓</div>
                  <div>
                    <div className="font-medium text-green-900 dark:text-green-100">
                      Excellent Cache Performance
                    </div>
                    <div className="text-sm text-green-700 dark:text-green-200">
                      Cache hit rate is {(summary.cacheHitRate * 100).toFixed(1)}%. BaseCondition
                      cache is working efficiently.
                    </div>
                  </div>
                </div>
              )}

              {summary.cacheHitRate < 0.5 && (
                <div className="flex items-start gap-3 p-3 bg-yellow-50 dark:bg-yellow-950 rounded-md">
                  <div className="text-yellow-600 mt-0.5">⚠</div>
                  <div>
                    <div className="font-medium text-yellow-900 dark:text-yellow-100">
                      Low Cache Hit Rate
                    </div>
                    <div className="text-sm text-yellow-700 dark:text-yellow-200">
                      Cache hit rate is only {(summary.cacheHitRate * 100).toFixed(1)}%. Consider
                      reviewing event patterns or increasing cache size.
                    </div>
                  </div>
                </div>
              )}

              {slowRules.length > 5 && (
                <div className="flex items-start gap-3 p-3 bg-orange-50 dark:bg-orange-950 rounded-md">
                  <div className="text-orange-600 mt-0.5">⚠</div>
                  <div>
                    <div className="font-medium text-orange-900 dark:text-orange-100">
                      Multiple Slow Rules Detected
                    </div>
                    <div className="text-sm text-orange-700 dark:text-orange-200">
                      {slowRules.length} rules have P99 latency above threshold. Review rule
                      complexity and consider optimization.
                    </div>
                  </div>
                </div>
              )}

              {summary.avgEventsPerMinute > 100000 && (
                <div className="flex items-start gap-3 p-3 bg-blue-50 dark:bg-blue-950 rounded-md">
                  <div className="text-blue-600 mt-0.5">ℹ</div>
                  <div>
                    <div className="font-medium text-blue-900 dark:text-blue-100">
                      High Throughput
                    </div>
                    <div className="text-sm text-blue-700 dark:text-blue-200">
                      Processing {formatNumber(summary.avgEventsPerMinute)} events per minute.
                      System is handling high load efficiently.
                    </div>
                  </div>
                </div>
              )}

              {summary.totalEvaluations === 0 && (
                <div className="flex items-start gap-3 p-3 bg-gray-50 dark:bg-gray-900 rounded-md">
                  <div className="text-gray-600 mt-0.5">ℹ</div>
                  <div>
                    <div className="font-medium text-gray-900 dark:text-gray-100">
                      No Metrics Yet
                    </div>
                    <div className="text-sm text-gray-700 dark:text-gray-200">
                      Start evaluating events to see performance metrics. Use the Evaluation or
                      Batch Testing tabs to generate data.
                    </div>
                  </div>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
