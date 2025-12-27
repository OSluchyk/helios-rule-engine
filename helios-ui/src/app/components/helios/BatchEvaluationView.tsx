/**
 * Batch Evaluation View Component
 * Allows users to evaluate multiple events and view aggregated statistics
 */

import { useState, useMemo } from 'react';
import { useEvaluateBatch } from '../../../hooks/useEvaluation';
import { useRules } from '../../../hooks/useRules';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Alert } from '../ui/alert';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Label } from '../ui/label';
import type { Event, BatchEvaluationResult, MatchResult } from '../../../types/api';

export function BatchEvaluationView() {
  const [eventsJson, setEventsJson] = useState('');
  const [result, setResult] = useState<BatchEvaluationResult | null>(null);
  const [filterStatus, setFilterStatus] = useState<'all' | 'matched' | 'unmatched'>('all');
  const [sortBy, setSortBy] = useState<'eventId' | 'time' | 'matches'>('eventId');
  const [savedSuites, setSavedSuites] = useState<Record<string, Event[]>>({});
  const [suiteName, setSuiteName] = useState('');
  const [selectedFamily, setSelectedFamily] = useState<string>('all');

  // Fetch all rules to get families
  const { data: allRules } = useRules();

  // Extract unique families
  const families = useMemo(() => {
    if (!allRules) return [];
    const familySet = new Set<string>();
    allRules.forEach(rule => {
      const family = rule.rule_code.split('.')[0];
      if (family) familySet.add(family);
    });
    return Array.from(familySet).sort();
  }, [allRules]);

  const evaluateBatch = useEvaluateBatch({
    onSuccess: (data) => {
      setResult(data);
    },
  });

  const handleEvaluate = () => {
    try {
      const events = JSON.parse(eventsJson) as Event[];

      if (!Array.isArray(events)) {
        throw new Error('Input must be an array of events');
      }

      evaluateBatch.mutate(events);
    } catch (error) {
      console.error('Failed to parse events JSON:', error);
    }
  };

  const handleLoadExample = () => {
    const exampleEvents = [
      {
        eventId: 'evt-001',
        timestamp: Date.now(),
        attributes: {
          user_age: 25,
          country: 'US',
          premium: true,
          login_count: 10,
        },
      },
      {
        eventId: 'evt-002',
        timestamp: Date.now(),
        attributes: {
          user_age: 30,
          country: 'UK',
          premium: false,
          login_count: 5,
        },
      },
      {
        eventId: 'evt-003',
        timestamp: Date.now(),
        attributes: {
          user_age: 35,
          country: 'US',
          premium: true,
          login_count: 15,
        },
      },
    ];

    setEventsJson(JSON.stringify(exampleEvents, null, 2));
  };

  const handleSaveSuite = () => {
    if (!suiteName.trim()) {
      alert('Please enter a suite name');
      return;
    }

    try {
      const events = JSON.parse(eventsJson) as Event[];
      setSavedSuites((prev) => ({
        ...prev,
        [suiteName]: events,
      }));
      setSuiteName('');
      alert(`Suite "${suiteName}" saved successfully!`);
    } catch (error) {
      alert('Failed to save suite: Invalid JSON');
    }
  };

  const handleLoadSuite = (name: string) => {
    const suite = savedSuites[name];
    if (suite) {
      setEventsJson(JSON.stringify(suite, null, 2));
    }
  };

  const handleDeleteSuite = (name: string) => {
    setSavedSuites((prev) => {
      const updated = { ...prev };
      delete updated[name];
      return updated;
    });
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const content = event.target?.result as string;
      setEventsJson(content);
    };
    reader.readAsText(file);
  };

  const getFilteredAndSortedResults = (): MatchResult[] => {
    if (!result) return [];

    let filtered = result.results;

    // Apply filter
    if (filterStatus === 'matched') {
      filtered = filtered.filter((r) => r.matchedRules.length > 0);
    } else if (filterStatus === 'unmatched') {
      filtered = filtered.filter((r) => r.matchedRules.length === 0);
    }

    // Apply sort
    const sorted = [...filtered].sort((a, b) => {
      switch (sortBy) {
        case 'eventId':
          return a.eventId.localeCompare(b.eventId);
        case 'time':
          return b.evaluationTimeNanos - a.evaluationTimeNanos;
        case 'matches':
          return b.matchedRules.length - a.matchedRules.length;
        default:
          return 0;
      }
    });

    return sorted;
  };

  const formatNanos = (nanos: number): string => {
    if (nanos < 1000) return `${nanos}ns`;
    if (nanos < 1000000) return `${(nanos / 1000).toFixed(2)}μs`;
    if (nanos < 1000000000) return `${(nanos / 1000000).toFixed(2)}ms`;
    return `${(nanos / 1000000000).toFixed(2)}s`;
  };

  const formatPercentage = (value: number): string => {
    return `${(value * 100).toFixed(1)}%`;
  };

  const handleExportResults = () => {
    if (!result) return;

    const exportData = {
      timestamp: new Date().toISOString(),
      statistics: result.stats,
      results: result.results,
    };

    const blob = new Blob([JSON.stringify(exportData, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `batch-evaluation-${Date.now()}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleExportCSV = () => {
    if (!result) return;

    const csvRows = [
      ['Event ID', 'Matched Rules', 'Evaluation Time (ns)', 'Predicates Evaluated', 'Rules Evaluated'],
      ...result.results.map((r) => [
        r.eventId,
        r.matchedRules.length.toString(),
        r.evaluationTimeNanos.toString(),
        r.predicatesEvaluated.toString(),
        r.rulesEvaluated.toString(),
      ]),
    ];

    const csvContent = csvRows.map((row) => row.join(',')).join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `batch-evaluation-${Date.now()}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-6">
      {/* Event Input Section */}
      <Card>
        <CardHeader>
          <CardTitle>Batch Event Input</CardTitle>
          <CardDescription>
            Enter multiple events as a JSON array or upload a JSON file
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Button onClick={handleLoadExample} variant="outline" size="sm">
              Load Example
            </Button>
            <div className="relative">
              <input
                type="file"
                accept=".json"
                onChange={handleFileUpload}
                className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
              />
              <Button variant="outline" size="sm">
                Upload JSON File
              </Button>
            </div>
          </div>

          <textarea
            value={eventsJson}
            onChange={(e) => setEventsJson(e.target.value)}
            placeholder='[{"eventId": "evt-001", "timestamp": 1234567890, "attributes": {...}}]'
            className="w-full h-64 p-3 font-mono text-sm border rounded-md bg-muted/50"
            spellCheck={false}
          />

          <div>
            <Label htmlFor="batch-family-filter" className="block text-sm font-medium mb-2">
              Rule Family Filter
            </Label>
            <Select value={selectedFamily} onValueChange={setSelectedFamily}>
              <SelectTrigger id="batch-family-filter">
                <SelectValue placeholder="All families" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Families</SelectItem>
                {families.map(family => (
                  <SelectItem key={family} value={family}>
                    {family}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground mt-1">
              Evaluate against specific rule family or all rules
            </p>
          </div>

          <div className="flex items-center gap-4">
            <Button
              onClick={handleEvaluate}
              disabled={!eventsJson.trim() || evaluateBatch.isPending}
            >
              {evaluateBatch.isPending ? 'Evaluating...' : 'Evaluate Batch'}
            </Button>

            <div className="flex-1 flex items-center gap-2">
              <input
                type="text"
                value={suiteName}
                onChange={(e) => setSuiteName(e.target.value)}
                placeholder="Suite name"
                className="px-3 py-2 border rounded-md text-sm"
              />
              <Button onClick={handleSaveSuite} variant="outline" size="sm">
                Save Suite
              </Button>
            </div>
          </div>

          {evaluateBatch.isError && (
            <Alert variant="destructive">
              <strong>Error:</strong> {evaluateBatch.error?.message || 'Failed to evaluate batch'}
            </Alert>
          )}
        </CardContent>
      </Card>

      {/* Saved Test Suites */}
      {Object.keys(savedSuites).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Saved Test Suites</CardTitle>
            <CardDescription>
              Quickly load previously saved event collections
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {Object.keys(savedSuites).map((name) => (
                <div
                  key={name}
                  className="inline-flex items-center gap-2 px-3 py-1 border rounded-md"
                >
                  <button
                    onClick={() => handleLoadSuite(name)}
                    className="text-sm hover:underline"
                  >
                    {name}
                  </button>
                  <span className="text-xs text-muted-foreground">
                    ({savedSuites[name].length} events)
                  </span>
                  <button
                    onClick={() => handleDeleteSuite(name)}
                    className="text-xs text-destructive hover:underline"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Batch Statistics */}
      {result && (
        <Card>
          <CardHeader>
            <div className="flex items-start justify-between">
              <div>
                <CardTitle>Batch Statistics</CardTitle>
                <CardDescription>
                  Aggregated metrics for {result.stats.totalEvents} events
                </CardDescription>
              </div>
              <div className="flex gap-2">
                <Button onClick={handleExportResults} variant="outline" size="sm">
                  Export JSON
                </Button>
                <Button onClick={handleExportCSV} variant="outline" size="sm">
                  Export CSV
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {/* Performance Distribution Chart */}
            <div className="mb-6 p-4 bg-muted/50 rounded-lg">
              <div className="text-sm font-medium mb-3">Evaluation Time Distribution</div>
              <div className="space-y-2">
                {result.results
                  .slice()
                  .sort((a, b) => b.evaluationTimeNanos - a.evaluationTimeNanos)
                  .slice(0, 10)
                  .map((r, idx) => {
                    const maxTime = Math.max(...result.results.map((x) => x.evaluationTimeNanos));
                    const widthPercent = (r.evaluationTimeNanos / maxTime) * 100;
                    return (
                      <div key={r.eventId} className="flex items-center gap-2">
                        <div className="w-24 text-xs font-mono truncate">{r.eventId}</div>
                        <div className="flex-1 bg-gray-200 dark:bg-gray-700 rounded-full h-4 relative">
                          <div
                            className={`h-4 rounded-full ${
                              idx === 0
                                ? 'bg-red-500'
                                : idx < 3
                                ? 'bg-orange-500'
                                : 'bg-blue-500'
                            }`}
                            style={{ width: `${widthPercent}%` }}
                          />
                        </div>
                        <div className="w-24 text-xs text-right font-mono">
                          {formatNanos(r.evaluationTimeNanos)}
                        </div>
                      </div>
                    );
                  })}
              </div>
              {result.results.length > 10 && (
                <div className="text-xs text-muted-foreground mt-2">
                  Showing top 10 slowest evaluations
                </div>
              )}
            </div>

            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Total Events</div>
                <div className="text-2xl font-bold">{result.stats.totalEvents}</div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Match Rate</div>
                <div className="text-2xl font-bold">
                  {formatPercentage(result.stats.matchRate)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Avg Time</div>
                <div className="text-2xl font-bold">
                  {formatNanos(result.stats.avgEvaluationTimeNanos)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Total Matches</div>
                <div className="text-2xl font-bold">{result.stats.totalMatchedRules}</div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Min Time</div>
                <div className="text-xl font-semibold">
                  {formatNanos(result.stats.minEvaluationTimeNanos)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Max Time</div>
                <div className="text-xl font-semibold">
                  {formatNanos(result.stats.maxEvaluationTimeNanos)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Avg Rules/Event</div>
                <div className="text-xl font-semibold">
                  {result.stats.avgRulesMatchedPerEvent.toFixed(2)}
                </div>
              </div>

              <div className="space-y-1">
                <div className="text-sm text-muted-foreground">Events with Matches</div>
                <div className="text-xl font-semibold">
                  {Math.round(result.stats.matchRate * result.stats.totalEvents)}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Individual Results */}
      {result && (
        <Card>
          <CardHeader>
            <CardTitle>Individual Results</CardTitle>
            <CardDescription>
              Detailed results for each event
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* Filters and Sort */}
            <div className="flex items-center gap-4 pb-4 border-b">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">Filter:</span>
                <div className="flex gap-1">
                  <Button
                    onClick={() => setFilterStatus('all')}
                    variant={filterStatus === 'all' ? 'default' : 'outline'}
                    size="sm"
                  >
                    All ({result.results.length})
                  </Button>
                  <Button
                    onClick={() => setFilterStatus('matched')}
                    variant={filterStatus === 'matched' ? 'default' : 'outline'}
                    size="sm"
                  >
                    Matched ({result.results.filter((r) => r.matchedRules.length > 0).length})
                  </Button>
                  <Button
                    onClick={() => setFilterStatus('unmatched')}
                    variant={filterStatus === 'unmatched' ? 'default' : 'outline'}
                    size="sm"
                  >
                    Unmatched ({result.results.filter((r) => r.matchedRules.length === 0).length})
                  </Button>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">Sort by:</span>
                <select
                  value={sortBy}
                  onChange={(e) => setSortBy(e.target.value as typeof sortBy)}
                  className="px-3 py-1 border rounded-md text-sm"
                >
                  <option value="eventId">Event ID</option>
                  <option value="time">Evaluation Time</option>
                  <option value="matches">Matched Rules</option>
                </select>
              </div>
            </div>

            {/* Results List */}
            <div className="space-y-3">
              {getFilteredAndSortedResults().map((matchResult) => (
                <div
                  key={matchResult.eventId}
                  className="p-4 border rounded-lg hover:bg-muted/50 transition-colors"
                >
                  <div className="flex items-start justify-between">
                    <div className="space-y-1">
                      <div className="font-mono font-semibold">{matchResult.eventId}</div>
                      <div className="text-sm text-muted-foreground">
                        Evaluation time: {formatNanos(matchResult.evaluationTimeNanos)}
                      </div>
                    </div>

                    <div className="text-right">
                      <div
                        className={`inline-flex items-center px-2 py-1 rounded text-xs font-medium ${
                          matchResult.matchedRules.length > 0
                            ? 'bg-green-100 text-green-800'
                            : 'bg-gray-100 text-gray-800'
                        }`}
                      >
                        {matchResult.matchedRules.length > 0
                          ? `${matchResult.matchedRules.length} rule${
                              matchResult.matchedRules.length > 1 ? 's' : ''
                            } matched`
                          : 'No matches'}
                      </div>
                    </div>
                  </div>

                  {matchResult.matchedRules.length > 0 && (
                    <div className="mt-3 pt-3 border-t space-y-2">
                      <div className="text-sm font-medium">Matched Rules:</div>
                      <div className="space-y-1">
                        {matchResult.matchedRules.map((rule) => (
                          <div
                            key={rule.ruleId}
                            className="flex items-center gap-3 text-sm bg-muted/50 p-2 rounded"
                          >
                            <span className="font-mono text-xs text-muted-foreground">
                              #{rule.ruleId}
                            </span>
                            <span className="font-medium">{rule.ruleCode}</span>
                            <span className="text-muted-foreground">
                              Priority: {rule.priority}
                            </span>
                            <span className="flex-1 text-muted-foreground text-xs">
                              {rule.description}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ))}

              {getFilteredAndSortedResults().length === 0 && (
                <div className="text-center py-8 text-muted-foreground">
                  No results match the current filter
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
