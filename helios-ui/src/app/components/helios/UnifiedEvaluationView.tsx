/**
 * Unified Evaluation View - Single event and batch testing in one interface
 */

import { useState, useMemo, useEffect } from 'react';
import { useEvaluateWithTrace, useExplainRule, useEvaluateBatch } from '../../../hooks/useEvaluation';
import { useRules } from '../../../hooks/useRules';
import type { Event, TraceLevel, BatchEvaluationResult, MatchResult } from '../../../types/api';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Button } from '../ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Alert, AlertDescription } from '../ui/alert';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Label } from '../ui/label';
import { SearchableSelect, SearchableSelectOption } from '../ui/searchable-select';

export function UnifiedEvaluationView() {
  // Mode toggle
  const [mode, setMode] = useState<'single' | 'batch'>('single');

  // Single event state
  const [eventJson, setEventJson] = useState(JSON.stringify({
    eventId: 'test-001',
    timestamp: Date.now(),
    attributes: {
      amount: 1500.0,
      transaction_count: 12,
      time_window: 45,
      total_spend: 15000.0
    }
  }, null, 2));

  // Batch events state
  const [eventsJson, setEventsJson] = useState('');
  const [batchResult, setBatchResult] = useState<BatchEvaluationResult | null>(null);
  const [filterStatus, setFilterStatus] = useState<'all' | 'matched' | 'unmatched'>('all');
  const [sortBy, setSortBy] = useState<'eventId' | 'time' | 'matches'>('eventId');

  // Shared state
  const [traceLevel, setTraceLevel] = useState<TraceLevel>('FULL');
  const [selectedFamily, setSelectedFamily] = useState<string>('all');
  const [selectedRuleForExplanation, setSelectedRuleForExplanation] = useState('');

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

  // Convert rules to searchable select options
  const ruleOptions = useMemo<SearchableSelectOption[]>(() => {
    if (!allRules) return [];
    return allRules.map(rule => ({
      value: rule.rule_code,
      label: rule.rule_code,
      description: rule.description
    }));
  }, [allRules]);

  // Get selected rule data
  const selectedRule = useMemo(() => {
    if (!allRules || !selectedRuleForExplanation) return null;
    return allRules.find(rule => rule.rule_code === selectedRuleForExplanation);
  }, [allRules, selectedRuleForExplanation]);

  const evaluateMutation = useEvaluateWithTrace();
  const explainMutation = useExplainRule();
  const evaluateBatch = useEvaluateBatch({
    onSuccess: (data) => {
      setBatchResult(data);
    },
  });

  // Auto-trigger explain when rule selection changes
  useEffect(() => {
    if (selectedRuleForExplanation && eventJson) {
      try {
        const event: Event = JSON.parse(eventJson);
        explainMutation.mutate({ ruleCode: selectedRuleForExplanation, event });
      } catch (error) {
        console.error('Invalid JSON:', error);
      }
    }
  }, [selectedRuleForExplanation]);

  const handleEvaluateSingle = () => {
    try {
      const event: Event = JSON.parse(eventJson);
      evaluateMutation.mutate({ event, level: traceLevel });
    } catch (error) {
      console.error('Invalid JSON:', error);
    }
  };

  const handleEvaluateBatch = () => {
    try {
      const events = JSON.parse(eventsJson) as Event[];
      if (!Array.isArray(events)) {
        throw new Error('Input must be an array of events');
      }
      evaluateBatch.mutate({ events, level: traceLevel });
    } catch (error) {
      console.error('Failed to parse events JSON:', error);
    }
  };

  const handleLoadBatchExample = () => {
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
    if (!batchResult) return [];

    let filtered = batchResult.results;

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
    if (!batchResult) return;

    const exportData = {
      timestamp: new Date().toISOString(),
      statistics: batchResult.stats,
      results: batchResult.results,
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
    if (!batchResult) return;

    const csvRows = [
      ['Event ID', 'Matched Rules', 'Evaluation Time (ns)', 'Predicates Evaluated', 'Rules Evaluated'],
      ...batchResult.results.map((r) => [
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
      <div>
        <h2 className="text-2xl font-bold">Rule Evaluation & Testing</h2>
        <p className="text-muted-foreground">
          Test rules with single events or run batch evaluations
        </p>
      </div>

      {/* Mode Toggle */}
      <Card>
        <CardContent className="pt-6">
          <div className="flex items-center gap-4">
            <Label>Evaluation Mode:</Label>
            <div className="flex gap-2">
              <Button
                onClick={() => setMode('single')}
                variant={mode === 'single' ? 'default' : 'outline'}
                size="sm"
              >
                Single Event
              </Button>
              <Button
                onClick={() => setMode('batch')}
                variant={mode === 'batch' ? 'default' : 'outline'}
                size="sm"
              >
                Batch Testing
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Single Event Mode */}
      {mode === 'single' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Left Panel: Event Input */}
          <Card>
            <CardHeader>
              <CardTitle>Event Input</CardTitle>
              <CardDescription>
                Enter event JSON to evaluate against rules
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-2">
                  Event JSON
                </label>
                <textarea
                  value={eventJson}
                  onChange={(e) => setEventJson(e.target.value)}
                  className="w-full h-64 p-3 border rounded-md font-mono text-sm bg-muted/50"
                  placeholder="Enter event JSON..."
                  spellCheck={false}
                />
              </div>

              <div>
                <Label htmlFor="trace-level" className="block text-sm font-medium mb-2">
                  Trace Level
                </Label>
                <select
                  id="trace-level"
                  value={traceLevel}
                  onChange={(e) => setTraceLevel(e.target.value as TraceLevel)}
                  className="w-full px-3 py-2 border rounded-md text-sm"
                >
                  <option value="NONE">NONE - No tracing (fastest)</option>
                  <option value="BASIC">BASIC - Rule matches only (~34% overhead)</option>
                  <option value="STANDARD">STANDARD - + Predicate outcomes (~51% overhead)</option>
                  <option value="FULL">FULL - + Field values (~53% overhead)</option>
                </select>
              </div>

              <div>
                <Label htmlFor="family-filter" className="block text-sm font-medium mb-2">
                  Rule Family Filter
                </Label>
                <Select value={selectedFamily} onValueChange={setSelectedFamily}>
                  <SelectTrigger id="family-filter">
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

              <Button
                onClick={handleEvaluateSingle}
                disabled={evaluateMutation.isPending}
                className="w-full"
              >
                {evaluateMutation.isPending ? 'Evaluating...' : 'Evaluate Event'}
              </Button>
            </CardContent>
          </Card>

          {/* Right Panel: Results (same as original) */}
          <Card>
            <CardHeader>
              <CardTitle>Evaluation Results</CardTitle>
              <CardDescription>
                Rule matches and execution traces
              </CardDescription>
            </CardHeader>
            <CardContent>
              {evaluateMutation.isSuccess && evaluateMutation.data && (
                <Tabs defaultValue="matches">
                  <TabsList>
                    <TabsTrigger value="matches">Matches</TabsTrigger>
                    <TabsTrigger value="trace">Execution Trace</TabsTrigger>
                    <TabsTrigger value="explain">Explain Rule</TabsTrigger>
                  </TabsList>

                  <TabsContent value="matches" className="space-y-4">
                    <div className="p-4 border rounded-lg bg-muted/50">
                      <h3 className="font-semibold mb-2">Summary</h3>
                      <div className="grid grid-cols-2 gap-2 text-sm">
                        <div>Matched Rules:</div>
                        <div className="font-mono">{evaluateMutation.data.match_result.matchedRules.length}</div>
                        <div>Evaluation Time:</div>
                        <div className="font-mono">{formatNanos(evaluateMutation.data.match_result.evaluationTimeNanos)}</div>
                        <div>Predicates Evaluated:</div>
                        <div className="font-mono">{evaluateMutation.data.match_result.predicatesEvaluated}</div>
                        <div>Rules Evaluated:</div>
                        <div className="font-mono">{evaluateMutation.data.match_result.rulesEvaluated}</div>
                      </div>
                    </div>

                    {evaluateMutation.data.match_result.matchedRules.length > 0 ? (
                      <div className="space-y-2">
                        <h3 className="font-semibold">Matched Rules:</h3>
                        {evaluateMutation.data.match_result.matchedRules.map((rule) => (
                          <div key={rule.ruleId} className="p-3 border rounded-lg">
                            <div className="flex items-start justify-between">
                              <div>
                                <div className="font-mono font-semibold">{rule.ruleCode}</div>
                                <div className="text-sm text-muted-foreground">{rule.description}</div>
                              </div>
                              <div className="text-sm px-2 py-1 bg-blue-100 text-blue-800 rounded">
                                Priority: {rule.priority}
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <Alert>
                        <AlertDescription>
                          No rules matched this event.
                        </AlertDescription>
                      </Alert>
                    )}
                  </TabsContent>

                  <TabsContent value="trace" className="space-y-4">
                    {evaluateMutation.data.trace && traceLevel !== 'NONE' ? (
                      <div className="space-y-4">
                        <div className="p-4 border rounded-lg bg-muted/50">
                          <h3 className="font-semibold mb-2">Performance Breakdown</h3>
                          <div className="space-y-2 text-sm">
                            <div className="flex justify-between">
                              <span>Dictionary Encoding:</span>
                              <span className="font-mono">
                                {formatNanos(evaluateMutation.data.trace.dict_encoding_nanos)}
                                ({formatPercentage(evaluateMutation.data.trace.timingBreakdown.dictEncodingPercent)})
                              </span>
                            </div>
                            <div className="flex justify-between">
                              <span>Base Condition:</span>
                              <span className="font-mono">
                                {formatNanos(evaluateMutation.data.trace.base_condition_nanos)}
                                ({formatPercentage(evaluateMutation.data.trace.timingBreakdown.baseConditionPercent)})
                              </span>
                            </div>
                            <div className="flex justify-between">
                              <span>Predicate Evaluation:</span>
                              <span className="font-mono">
                                {formatNanos(evaluateMutation.data.trace.predicate_eval_nanos)}
                                ({formatPercentage(evaluateMutation.data.trace.timingBreakdown.predicateEvalPercent)})
                              </span>
                            </div>
                          </div>
                        </div>

                        {evaluateMutation.data.trace.predicate_outcomes && (
                          <div>
                            <h3 className="font-semibold mb-2">Predicate Outcomes</h3>
                            <div className="space-y-1 max-h-96 overflow-y-auto">
                              {evaluateMutation.data.trace.predicate_outcomes.map((pred, idx) => (
                                <div
                                  key={idx}
                                  className={`p-2 border rounded text-sm ${pred.matched ? 'bg-green-50' : 'bg-red-50'}`}
                                >
                                  <div className="flex items-center justify-between">
                                    <span className="font-mono text-xs">{pred.field_name} {pred.operator}</span>
                                    <span className={pred.matched ? 'text-green-700' : 'text-red-700'}>
                                      {pred.matched ? '✓ Matched' : '✗ Failed'}
                                    </span>
                                  </div>
                                  {traceLevel === 'FULL' && pred.actual_value !== undefined && (
                                    <div className="text-xs text-muted-foreground mt-1">
                                      Expected: {JSON.stringify(pred.expected_value)} |
                                      Actual: {JSON.stringify(pred.actual_value)}
                                    </div>
                                  )}
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    ) : (
                      <Alert>
                        <AlertDescription>
                          Set trace level to BASIC, STANDARD, or FULL to see execution traces.
                        </AlertDescription>
                      </Alert>
                    )}
                  </TabsContent>

                  <TabsContent value="explain" className="space-y-4">
                    <div className="space-y-2">
                      <Label>Select Rule to Explain</Label>
                      <SearchableSelect
                        options={ruleOptions}
                        value={selectedRuleForExplanation}
                        onChange={setSelectedRuleForExplanation}
                        placeholder="Search and select a rule..."
                        searchPlaceholder="Type to search by code or description..."
                        emptyMessage="No rules found"
                      />
                    </div>

                    {explainMutation.isSuccess && explainMutation.data && selectedRule && (
                      <div className="space-y-4">
                        {/* Selected Rule Info */}
                        <div className="p-3 bg-gray-50 border rounded-lg">
                          <div className="flex items-start justify-between gap-2">
                            <div className="flex-1 min-w-0">
                              <div className="text-xs text-muted-foreground mb-1">Rule Being Explained</div>
                              <div className="font-mono text-sm font-semibold">{selectedRule.rule_code}</div>
                              {selectedRule.description && (
                                <div className="text-sm text-muted-foreground mt-1">{selectedRule.description}</div>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Overall Match Status */}
                        <div className={`p-4 border rounded-lg ${explainMutation.data.matched ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
                          <div className="flex items-center justify-between mb-3">
                            <h3 className="font-semibold text-lg">
                              {explainMutation.data.matched ? '✓ Rule Matched' : '✗ Rule Did Not Match'}
                            </h3>
                            <span className={`px-3 py-1 rounded-full text-sm font-medium ${
                              explainMutation.data.matched
                                ? 'bg-green-100 text-green-800'
                                : 'bg-red-100 text-red-800'
                            }`}>
                              {explainMutation.data.matched ? 'PASS' : 'FAIL'}
                            </span>
                          </div>
                          {/* Evaluation Metrics */}
                          <div className="grid grid-cols-3 gap-3 text-sm">
                            <div>
                              <div className="text-muted-foreground">Evaluation Time</div>
                              <div className="font-mono font-semibold">{formatNanos(explainMutation.data.evaluation_time_nanos)}</div>
                            </div>
                            <div>
                              <div className="text-muted-foreground">Predicates Evaluated</div>
                              <div className="font-mono font-semibold">{explainMutation.data.predicates_evaluated}</div>
                            </div>
                            <div>
                              <div className="text-muted-foreground">Rules Evaluated</div>
                              <div className="font-mono font-semibold">{explainMutation.data.rules_evaluated}</div>
                            </div>
                          </div>
                        </div>

                        {/* Condition Evaluation Results */}
                        <div className="space-y-3">
                          <h3 className="font-semibold text-base">
                            Condition Evaluation Results
                            <span className="text-sm font-normal text-muted-foreground ml-2">
                              ({explainMutation.data.condition_explanations.length} {explainMutation.data.condition_explanations.length === 1 ? 'condition' : 'conditions'})
                            </span>
                          </h3>
                          <div className="space-y-2">
                            {explainMutation.data.condition_explanations.map((cond, idx) => (
                              <div
                                key={idx}
                                className={`p-3 border rounded-lg ${
                                  !cond.evaluated
                                    ? 'bg-gray-50 border-gray-300 opacity-60'
                                    : cond.passed
                                    ? 'bg-green-50 border-green-200'
                                    : 'bg-red-50 border-red-200'
                                }`}
                              >
                                <div className="flex items-start justify-between gap-3">
                                  <div className="flex-1 min-w-0">
                                    {/* Condition */}
                                    <div className="font-mono text-sm font-medium mb-1">
                                      {cond.field_name} {cond.operator} {JSON.stringify(cond.expected_value)}
                                    </div>

                                    {/* Actual value from event (only if evaluated) */}
                                    {cond.evaluated && (
                                      <div className="text-sm text-muted-foreground">
                                        Actual: <span className="font-mono">{JSON.stringify(cond.actual_value)}</span>
                                      </div>
                                    )}

                                    {/* Result reason */}
                                    <div className={`text-sm mt-1 ${
                                      !cond.evaluated
                                        ? 'text-gray-600 italic'
                                        : cond.passed ? 'text-green-700' : 'text-red-700'
                                    }`}>
                                      {!cond.evaluated ? '⊘ ' : cond.passed ? '✓ ' : '✗ '}{cond.reason}
                                    </div>
                                  </div>

                                  {/* Pass/Fail/Skipped badge */}
                                  <div className={`flex-shrink-0 px-2 py-1 rounded text-xs font-semibold ${
                                    !cond.evaluated
                                      ? 'bg-gray-200 text-gray-700'
                                      : cond.passed
                                      ? 'bg-green-100 text-green-800'
                                      : 'bg-red-100 text-red-800'
                                  }`}>
                                    {!cond.evaluated ? 'SKIPPED' : cond.passed ? 'PASS' : 'FAIL'}
                                  </div>
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>
                      </div>
                    )}
                  </TabsContent>
                </Tabs>
              )}

              {evaluateMutation.isError && (
                <Alert variant="destructive">
                  <AlertDescription>
                    {evaluateMutation.error?.message || 'Failed to evaluate event'}
                  </AlertDescription>
                </Alert>
              )}

              {!evaluateMutation.isSuccess && !evaluateMutation.isError && (
                <div className="text-center py-8 text-muted-foreground">
                  Enter an event and click "Evaluate Event" to see results
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* Batch Mode */}
      {mode === 'batch' && (
        <div className="space-y-6">
          {/* Batch Event Input */}
          <Card>
            <CardHeader>
              <CardTitle>Batch Event Input</CardTitle>
              <CardDescription>
                Enter multiple events as a JSON array or upload a JSON file
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex gap-2">
                <Button onClick={handleLoadBatchExample} variant="outline" size="sm">
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
                <Label htmlFor="batch-trace-level" className="block text-sm font-medium mb-2">
                  Trace Level
                </Label>
                <select
                  id="batch-trace-level"
                  value={traceLevel}
                  onChange={(e) => setTraceLevel(e.target.value as TraceLevel)}
                  className="w-full px-3 py-2 border rounded-md text-sm"
                >
                  <option value="NONE">NONE - No tracing (fastest)</option>
                  <option value="BASIC">BASIC - Rule matches only (~34% overhead)</option>
                  <option value="STANDARD">STANDARD - + Predicate outcomes (~51% overhead)</option>
                  <option value="FULL">FULL - + Field values (~53% overhead)</option>
                </select>
                <p className="text-xs text-muted-foreground mt-1">
                  Control trace detail level for batch evaluation
                </p>
              </div>

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

              <Button
                onClick={handleEvaluateBatch}
                disabled={!eventsJson.trim() || evaluateBatch.isPending}
                className="w-full"
              >
                {evaluateBatch.isPending ? 'Evaluating...' : 'Evaluate Batch'}
              </Button>

              {evaluateBatch.isError && (
                <Alert variant="destructive">
                  <AlertDescription>
                    {evaluateBatch.error?.message || 'Failed to evaluate batch'}
                  </AlertDescription>
                </Alert>
              )}
            </CardContent>
          </Card>

          {/* Batch Statistics */}
          {batchResult && (
            <Card>
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div>
                    <CardTitle>Batch Statistics</CardTitle>
                    <CardDescription>
                      Aggregated metrics for {batchResult.stats.totalEvents} events
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
                    {batchResult.results
                      .slice()
                      .sort((a, b) => b.evaluationTimeNanos - a.evaluationTimeNanos)
                      .slice(0, 10)
                      .map((r, idx) => {
                        const maxTime = Math.max(...batchResult.results.map((x) => x.evaluationTimeNanos));
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
                  {batchResult.results.length > 10 && (
                    <div className="text-xs text-muted-foreground mt-2">
                      Showing top 10 slowest evaluations
                    </div>
                  )}
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  <div className="space-y-1">
                    <div className="text-sm text-muted-foreground">Total Events</div>
                    <div className="text-2xl font-bold">{batchResult.stats.totalEvents}</div>
                  </div>
                  <div className="space-y-1">
                    <div className="text-sm text-muted-foreground">Match Rate</div>
                    <div className="text-2xl font-bold">
                      {formatPercentage(batchResult.stats.matchRate)}
                    </div>
                  </div>
                  <div className="space-y-1">
                    <div className="text-sm text-muted-foreground">Avg Time</div>
                    <div className="text-2xl font-bold">
                      {formatNanos(batchResult.stats.avgEvaluationTimeNanos)}
                    </div>
                  </div>
                  <div className="space-y-1">
                    <div className="text-sm text-muted-foreground">Total Matches</div>
                    <div className="text-2xl font-bold">{batchResult.stats.totalMatchedRules}</div>
                  </div>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Individual Results */}
          {batchResult && (
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
                        All ({batchResult.results.length})
                      </Button>
                      <Button
                        onClick={() => setFilterStatus('matched')}
                        variant={filterStatus === 'matched' ? 'default' : 'outline'}
                        size="sm"
                      >
                        Matched ({batchResult.results.filter((r) => r.matchedRules.length > 0).length})
                      </Button>
                      <Button
                        onClick={() => setFilterStatus('unmatched')}
                        variant={filterStatus === 'unmatched' ? 'default' : 'outline'}
                        size="sm"
                      >
                        Unmatched ({batchResult.results.filter((r) => r.matchedRules.length === 0).length})
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
      )}
    </div>
  );
}

export default UnifiedEvaluationView;
