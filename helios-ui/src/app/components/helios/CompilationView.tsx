/**
 * Compilation View Component
 * Displays compilation pipeline statistics and real-time progress
 */

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Alert } from '../ui/alert';
import { get } from '../../../api/client';

interface CompilationStats {
  uniqueCombinations: number;
  totalPredicates: number;
  compilationTimeNanos: number;
  metadata: {
    logicalRules: number;
    totalExpandedCombinations: number;
    uniqueCombinations: number;
    deduplicationRatePercent: number | string;
  };
}

interface DictionaryInfo {
  type: string;
  size: number;
  description: string;
}

interface PredicateInfo {
  predicateCount: number;
  description: string;
}

interface DeduplicationInfo {
  logicalRules: number;
  uniqueCombinations: number;
  deduplicationRatePercent: number;
  savings: string;
}

export function CompilationView() {
  const [stats, setStats] = useState<CompilationStats | null>(null);
  const [fieldDict, setFieldDict] = useState<DictionaryInfo | null>(null);
  const [valueDict, setValueDict] = useState<DictionaryInfo | null>(null);
  const [predicates, setPredicates] = useState<PredicateInfo | null>(null);
  const [deduplication, setDeduplication] = useState<DeduplicationInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchStats = async () => {
    try {
      setLoading(true);
      setError(null);

      const [statsData, fieldData, valueData, predData, dedupData] = await Promise.all([
        get<CompilationStats>('/compilation/stats'),
        get<DictionaryInfo>('/compilation/dictionaries/fields'),
        get<DictionaryInfo>('/compilation/dictionaries/values'),
        get<PredicateInfo>('/compilation/predicates/count'),
        get<DeduplicationInfo>('/compilation/deduplication'),
      ]);

      setStats(statsData);
      setFieldDict(fieldData);
      setValueDict(valueData);
      setPredicates(predData);
      setDeduplication(dedupData);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch compilation stats');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStats();
  }, []);

  const formatTime = (nanos: number): string => {
    const ms = nanos / 1_000_000;
    if (ms < 1000) return `${ms.toFixed(2)} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
  };

  const formatNumber = (num: number): string => {
    return num.toLocaleString();
  };

  const getStageColor = (stageName: string): string => {
    const colors: Record<string, string> = {
      PARSING: 'bg-blue-500',
      VALIDATION: 'bg-green-500',
      FACTORIZATION: 'bg-purple-500',
      DICTIONARY_ENCODING: 'bg-yellow-500',
      SELECTIVITY_PROFILING: 'bg-pink-500',
      MODEL_BUILDING: 'bg-indigo-500',
      INDEX_BUILDING: 'bg-red-500',
    };
    return colors[stageName] || 'bg-gray-500';
  };

  const compilationStages = [
    { name: 'PARSING', description: 'Load and parse JSON rules' },
    { name: 'VALIDATION', description: 'Validate syntax and semantics' },
    { name: 'FACTORIZATION', description: 'Apply IS_ANY_OF factorization' },
    { name: 'DICTIONARY_ENCODING', description: 'Build field and value dictionaries' },
    { name: 'SELECTIVITY_PROFILING', description: 'Calculate predicate weights' },
    { name: 'MODEL_BUILDING', description: 'Build core model with deduplication' },
    { name: 'INDEX_BUILDING', description: 'Build inverted index' },
  ];

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-muted-foreground">Loading compilation stats...</div>
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
          <h2 className="text-2xl font-bold">Compilation Pipeline</h2>
          <p className="text-muted-foreground">
            View compilation statistics and pipeline stages
          </p>
        </div>
        <Button onClick={fetchStats}>Refresh Stats</Button>
      </div>

      {/* Summary Stats */}
      {stats && (
        <Card>
          <CardHeader>
            <CardTitle>Compilation Summary</CardTitle>
            <CardDescription>Overall compilation metrics</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Compilation Time</div>
                <div className="text-2xl font-bold">{formatTime(stats.compilationTimeNanos)}</div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Logical Rules</div>
                <div className="text-2xl font-bold">
                  {formatNumber(stats.metadata.logicalRules)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Unique Combinations</div>
                <div className="text-2xl font-bold">
                  {formatNumber(stats.metadata.uniqueCombinations)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Predicate Count</div>
                <div className="text-2xl font-bold">{formatNumber(stats.totalPredicates)}</div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Deduplication Stats */}
      {deduplication && (
        <Card>
          <CardHeader>
            <CardTitle>Deduplication Analysis</CardTitle>
            <CardDescription>Rule combination deduplication efficiency</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                <div className="space-y-1">
                  <div className="text-sm text-muted-foreground">Logical Rules</div>
                  <div className="text-xl font-semibold">
                    {formatNumber(deduplication.logicalRules)}
                  </div>
                </div>

                <div className="space-y-1">
                  <div className="text-sm text-muted-foreground">Physical Combinations</div>
                  <div className="text-xl font-semibold">
                    {formatNumber(deduplication.uniqueCombinations)}
                  </div>
                </div>

                <div className="space-y-1">
                  <div className="text-sm text-muted-foreground">Deduplication Rate</div>
                  <div className="text-xl font-semibold text-green-600">
                    {deduplication.deduplicationRatePercent.toFixed(1)}%
                  </div>
                </div>
              </div>

              {/* Visual Bar */}
              <div className="space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <span>Memory Savings</span>
                  <span className="text-green-600 font-medium">{deduplication.savings}</span>
                </div>
                <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-4">
                  <div
                    className="bg-green-500 h-4 rounded-full transition-all duration-500"
                    style={{ width: `${deduplication.deduplicationRatePercent}%` }}
                  />
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Dictionary Encoding */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {fieldDict && (
          <Card>
            <CardHeader>
              <CardTitle>Field Dictionary</CardTitle>
              <CardDescription>{fieldDict.description}</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Dictionary Size:</span>
                  <span className="text-2xl font-bold">{fieldDict.size}</span>
                </div>
                <div className="text-sm text-muted-foreground">
                  Each unique field name is encoded to an integer for faster lookups
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {valueDict && (
          <Card>
            <CardHeader>
              <CardTitle>Value Dictionary</CardTitle>
              <CardDescription>{valueDict.description}</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Dictionary Size:</span>
                  <span className="text-2xl font-bold">{valueDict.size}</span>
                </div>
                <div className="text-sm text-muted-foreground">
                  String values are encoded to integers to reduce memory usage
                </div>
              </div>
            </CardContent>
          </Card>
        )}
      </div>

      {/* Compilation Pipeline Stages */}
      <Card>
        <CardHeader>
          <CardTitle>Compilation Pipeline</CardTitle>
          <CardDescription>7-stage compilation process</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {compilationStages.map((stage, index) => (
              <div key={stage.name} className="flex items-center gap-4">
                <div className="flex items-center justify-center w-8 h-8 rounded-full bg-muted text-sm font-medium">
                  {index + 1}
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <div
                      className={`w-3 h-3 rounded-full ${getStageColor(stage.name)}`}
                    />
                    <span className="font-medium">{stage.name}</span>
                  </div>
                  <div className="text-sm text-muted-foreground mt-1">{stage.description}</div>
                </div>
              </div>
            ))}
          </div>

          <div className="mt-6 p-4 bg-blue-50 dark:bg-blue-950 rounded-md">
            <p className="text-sm text-blue-900 dark:text-blue-100">
              <strong>Note:</strong> Real-time compilation progress monitoring will be
              available in a future update. The current view shows post-compilation statistics.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* Performance Insights */}
      {stats && deduplication && (
        <Card>
          <CardHeader>
            <CardTitle>Performance Insights</CardTitle>
            <CardDescription>Optimization recommendations</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {deduplication.deduplicationRatePercent > 50 && (
                <div className="flex items-start gap-3 p-3 bg-green-50 dark:bg-green-950 rounded-md">
                  <div className="text-green-600 mt-0.5">✓</div>
                  <div>
                    <div className="font-medium text-green-900 dark:text-green-100">
                      Excellent Deduplication
                    </div>
                    <div className="text-sm text-green-700 dark:text-green-200">
                      {deduplication.deduplicationRatePercent.toFixed(1)}% deduplication rate indicates
                      effective rule factorization. The engine is sharing predicates efficiently.
                    </div>
                  </div>
                </div>
              )}

              {deduplication.deduplicationRatePercent < 20 && (
                <div className="flex items-start gap-3 p-3 bg-yellow-50 dark:bg-yellow-950 rounded-md">
                  <div className="text-yellow-600 mt-0.5">⚠</div>
                  <div>
                    <div className="font-medium text-yellow-900 dark:text-yellow-100">
                      Low Deduplication Rate
                    </div>
                    <div className="text-sm text-yellow-700 dark:text-yellow-200">
                      Consider refactoring rules to share common conditions. Rules with similar
                      IS_ANY_OF operators can be factorized for better memory efficiency.
                    </div>
                  </div>
                </div>
              )}

              {stats.compilationTimeNanos > 10_000_000_000 && (
                <div className="flex items-start gap-3 p-3 bg-orange-50 dark:bg-orange-950 rounded-md">
                  <div className="text-orange-600 mt-0.5">⚠</div>
                  <div>
                    <div className="font-medium text-orange-900 dark:text-orange-100">
                      Slow Compilation
                    </div>
                    <div className="text-sm text-orange-700 dark:text-orange-200">
                      Compilation took {formatTime(stats.compilationTimeNanos)}. Consider splitting
                      rules into smaller files or simplifying complex conditions.
                    </div>
                  </div>
                </div>
              )}

              {predicates && predicates.predicateCount > 10000 && (
                <div className="flex items-start gap-3 p-3 bg-blue-50 dark:bg-blue-950 rounded-md">
                  <div className="text-blue-600 mt-0.5">ℹ</div>
                  <div>
                    <div className="font-medium text-blue-900 dark:text-blue-100">
                      Large Predicate Count
                    </div>
                    <div className="text-sm text-blue-700 dark:text-blue-200">
                      {formatNumber(predicates.predicateCount)} unique predicates. Memory usage is
                      optimized through dictionary encoding, but consider caching evaluation results
                      for frequently accessed events.
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
