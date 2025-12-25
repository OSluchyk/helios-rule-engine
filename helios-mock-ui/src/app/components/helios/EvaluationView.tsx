import { useState } from 'react';
import { mockEvaluationTrace } from './mock-data';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';
import { RadioGroup, RadioGroupItem } from '../ui/radio-group';
import { Label } from '../ui/label';
import { Progress } from '../ui/progress';
import { ScrollArea } from '../ui/scroll-area';
import { Play, Download, Save, FileJson, Clock, CheckCircle2, XCircle, Zap, TrendingDown, AlertCircle, Lightbulb } from 'lucide-react';

export function EvaluationView() {
  const [inputJson, setInputJson] = useState(`{
  "customer_id": "CUST_12345",
  "customer_segment": "premium",
  "region": "US",
  "lifetime_value": 15000.0,
  "days_since_last_purchase": 12,
  "account_status": "active",
  "purchase_frequency": 6
}`);
  const [evaluationMode, setEvaluationMode] = useState('full');
  const [hasEvaluated, setHasEvaluated] = useState(false);

  const trace = mockEvaluationTrace;

  return (
    <div className="grid grid-cols-2 gap-6 h-full">
      {/* Left Panel - Input */}
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>Rule Evaluation Console</CardTitle>
            <CardDescription>Test rules against sample events</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* Rule Set Selector */}
            <div className="space-y-2">
              <Label>Select Rule Set</Label>
              <Select defaultValue="customer-segmentation">
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="customer-segmentation">Customer Segmentation</SelectItem>
                  <SelectItem value="fraud-detection">Fraud Detection</SelectItem>
                  <SelectItem value="personalization">Personalization</SelectItem>
                  <SelectItem value="all">All Rules</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {/* Input Event */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label>Input Event (JSON)</Label>
                <Button size="sm" variant="ghost">
                  <FileJson className="size-4 mr-2" />
                  Load Sample
                </Button>
              </div>
              <Textarea
                value={inputJson}
                onChange={(e) => setInputJson(e.target.value)}
                className="font-mono text-sm h-64"
              />
            </div>

            {/* Evaluation Mode */}
            <div className="space-y-2">
              <Label>Evaluation Mode</Label>
              <RadioGroup value={evaluationMode} onValueChange={setEvaluationMode}>
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="full" id="full" />
                  <Label htmlFor="full" className="font-normal cursor-pointer">
                    Full (with tracing) - Detailed debugging
                  </Label>
                </div>
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="fast" id="fast" />
                  <Label htmlFor="fast" className="font-normal cursor-pointer">
                    Fast (prod simulation) - Performance test
                  </Label>
                </div>
              </RadioGroup>
            </div>

            {/* Action Buttons */}
            <div className="flex gap-2 pt-4">
              <Button className="flex-1" onClick={() => setHasEvaluated(true)}>
                <Play className="size-4 mr-2" />
                Evaluate
              </Button>
              <Button variant="outline">
                <Save className="size-4 mr-2" />
                Save Test Case
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* Dictionary Encoding Preview */}
        {hasEvaluated && (
          <Card>
            <CardHeader>
              <CardTitle>Dictionary Encoding</CardTitle>
              <CardDescription>String values mapped to integer IDs</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-2 text-sm font-mono">
                <div className="flex justify-between p-2 bg-gray-50 rounded">
                  <span className="text-gray-600">customer_segment: "premium"</span>
                  <span className="text-blue-600">→ ID 42</span>
                </div>
                <div className="flex justify-between p-2 bg-gray-50 rounded">
                  <span className="text-gray-600">region: "US"</span>
                  <span className="text-blue-600">→ ID 17</span>
                </div>
                <div className="flex justify-between p-2 bg-gray-50 rounded">
                  <span className="text-gray-600">account_status: "active"</span>
                  <span className="text-blue-600">→ ID 3</span>
                </div>
              </div>
            </CardContent>
          </Card>
        )}
      </div>

      {/* Right Panel - Results */}
      <div className="space-y-6 overflow-auto">
        {!hasEvaluated ? (
          <Card className="h-full flex items-center justify-center">
            <CardContent className="text-center text-gray-500">
              <Play className="size-12 mx-auto mb-4 text-gray-400" />
              <p>Click "Evaluate" to run the rule engine</p>
              <p className="text-sm mt-2">Results will appear here</p>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Performance Metrics */}
            <Card>
              <CardHeader>
                <CardTitle>Performance Metrics</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {/* Total Latency */}
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <Clock className="size-4 text-gray-600" />
                        <span className="font-medium">Total Latency</span>
                      </div>
                      <div className="flex items-baseline gap-2">
                        <span className="text-2xl font-semibold">{trace.timings.total}</span>
                        <span className="text-sm text-gray-500">ms</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 text-sm">
                      <Progress value={(trace.timings.total / 0.8) * 100} className="flex-1" />
                      <span className="text-green-600">✓ Under 0.8ms target</span>
                    </div>
                  </div>

                  {/* Timing Breakdown */}
                  <div className="space-y-2">
                    {[
                      { label: 'Candidate Filter', value: trace.timings.candidateFiltering, color: 'bg-blue-500' },
                      { label: 'Base Eval', value: trace.timings.baseEvaluation, color: 'bg-green-500' },
                      { label: 'Vector Eval', value: trace.timings.vectorEvaluation, color: 'bg-purple-500' },
                      { label: 'Action Exec', value: trace.timings.actionExecution, color: 'bg-orange-500' }
                    ].map(stage => (
                      <div key={stage.label} className="flex items-center gap-3 text-sm">
                        <div className="w-32 text-gray-600">{stage.label}</div>
                        <div className="flex-1">
                          <div className="h-6 bg-gray-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full ${stage.color}`}
                              style={{ width: `${(stage.value / trace.timings.total) * 100}%` }}
                            />
                          </div>
                        </div>
                        <div className="w-16 text-right font-mono">{stage.value}ms</div>
                      </div>
                    ))}
                  </div>

                  {/* Candidate Reduction */}
                  <div className="pt-4 border-t">
                    <div className="flex items-center gap-2 mb-2">
                      <TrendingDown className="size-4 text-green-600" />
                      <span className="font-medium">Candidate Reduction</span>
                    </div>
                    <div className="flex items-center gap-4 text-sm">
                      <span>{trace.candidateReduction.totalRules} rules</span>
                      <span className="text-gray-400">→</span>
                      <span className="text-green-600 font-semibold">{trace.candidateReduction.candidateRules} candidates</span>
                      <Badge variant="secondary">{trace.candidateReduction.reductionPercent}% reduction</Badge>
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Matching Rules */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <CheckCircle2 className="size-5 text-green-600" />
                  Matching Rules ({trace.matchedRules.length})
                </CardTitle>
              </CardHeader>
              <CardContent>
                <ScrollArea className="h-96">
                  <Accordion type="single" collapsible className="space-y-3">
                    {trace.matchedRules.map((match) => (
                      <AccordionItem
                        key={match.ruleId}
                        value={match.ruleId}
                        className="border rounded-lg px-4 bg-green-50"
                      >
                        <AccordionTrigger className="hover:no-underline">
                          <div className="flex items-center justify-between w-full">
                            <div className="flex items-center gap-3">
                              <CheckCircle2 className="size-4 text-green-600" />
                              <span className="font-semibold">{match.ruleName}</span>
                              <Badge>Priority: {match.priority}</Badge>
                            </div>
                            <div className="flex items-center gap-4 text-sm">
                              <span className="text-gray-600">{match.conditions.filter(c => c.passed).length}/{match.conditions.length} conditions met</span>
                              <span className="text-gray-600">{match.evalTimeMs}ms</span>
                              {match.cacheHit && <Badge variant="secondary">Cache Hit</Badge>}
                            </div>
                          </div>
                        </AccordionTrigger>
                        <AccordionContent>
                          <div className="space-y-2 pt-4">
                            {match.conditions.map((cond, idx) => (
                              <div
                                key={idx}
                                className={`flex items-center gap-3 p-2 rounded ${
                                  cond.passed ? 'bg-green-100' : 'bg-red-100'
                                }`}
                              >
                                {cond.passed ? (
                                  <CheckCircle2 className="size-4 text-green-600 shrink-0" />
                                ) : (
                                  <XCircle className="size-4 text-red-600 shrink-0" />
                                )}
                                <div className="flex-1 font-mono text-sm">
                                  <code className="bg-white px-2 py-1 rounded">{cond.attribute}</code>
                                  <span className="mx-2 text-gray-600">{cond.operator}</span>
                                  <code className="bg-white px-2 py-1 rounded">
                                    {typeof cond.expected === 'object' ? JSON.stringify(cond.expected) : cond.expected}
                                  </code>
                                </div>
                                <div className="text-sm text-gray-600">
                                  Actual: <code className="bg-white px-2 py-1 rounded">{cond.actual}</code>
                                </div>
                              </div>
                            ))}
                          </div>
                        </AccordionContent>
                      </AccordionItem>
                    ))}
                  </Accordion>
                </ScrollArea>
              </CardContent>
            </Card>

            {/* Non-Matching Rules with Explanation */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <XCircle className="size-5 text-gray-400" />
                  Non-Matching Rules ({trace.nonMatchedRules.length})
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {trace.nonMatchedRules.map((nonMatch) => (
                    <div key={nonMatch.ruleId} className="border rounded-lg p-4 bg-gray-50">
                      <div className="flex items-start gap-3 mb-3">
                        <XCircle className="size-5 text-red-600 mt-0.5" />
                        <div className="flex-1">
                          <h4 className="font-semibold mb-1">{nonMatch.ruleName}</h4>
                          <p className="text-sm text-gray-600">DID NOT MATCH</p>
                        </div>
                      </div>

                      <div className="ml-8 space-y-3">
                        <div className="bg-white rounded-lg p-3 border border-red-200">
                          <div className="flex items-center gap-2 mb-2">
                            <AlertCircle className="size-4 text-red-600" />
                            <span className="font-medium text-sm">Root Cause</span>
                          </div>
                          <div className="font-mono text-sm space-y-1">
                            <div className="flex items-center gap-2">
                              <XCircle className="size-4 text-red-600" />
                              <code>{nonMatch.failedCondition.attribute} &gt;= {nonMatch.failedCondition.expected}</code>
                              <span className="text-gray-600">(FAILED)</span>
                            </div>
                            <div className="pl-6 text-gray-600">
                              Input: <code className="bg-gray-100 px-2 py-0.5 rounded">{nonMatch.failedCondition.actual}</code>
                              {' < '}
                              Expected: <code className="bg-gray-100 px-2 py-0.5 rounded">{nonMatch.failedCondition.expected}</code>
                            </div>
                            <div className="pl-6 text-red-600">
                              Shortfall: <code className="bg-red-100 px-2 py-0.5 rounded">{nonMatch.failedCondition.delta}</code>
                              {' ('}
                              {((nonMatch.failedCondition.delta / nonMatch.failedCondition.expected) * 100).toFixed(0)}%)
                            </div>
                          </div>
                        </div>

                        <div className="bg-blue-50 rounded-lg p-3 border border-blue-200">
                          <div className="flex items-center gap-2 mb-2">
                            <Lightbulb className="size-4 text-blue-600" />
                            <span className="font-medium text-sm">Suggestion</span>
                          </div>
                          <p className="text-sm text-gray-700">
                            Reduce threshold to {nonMatch.failedCondition.actual} to match this customer profile
                          </p>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>

            {/* Execution Timeline */}
            <Card>
              <CardHeader>
                <CardTitle>Execution Timeline</CardTitle>
                <CardDescription>Visual breakdown of evaluation flow</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {[
                    { time: 0.00, duration: 0.02, label: 'Parse Event', color: 'bg-gray-400' },
                    { time: 0.02, duration: 0.06, label: 'Dict Encode', color: 'bg-purple-400' },
                    { time: 0.08, duration: 0.08, label: 'Inverted Index Lookup (91% reduction)', color: 'bg-green-400' },
                    { time: 0.16, duration: 0.12, label: 'Base Condition Eval (Cache Hit)', color: 'bg-blue-400' },
                    { time: 0.28, duration: 0.18, label: 'Vectorized Eval (SIMD 4x)', color: 'bg-orange-400' },
                    { time: 0.38, duration: 0.04, label: 'Action Execution', color: 'bg-red-400' }
                  ].map((stage, idx) => (
                    <div key={idx} className="flex items-center gap-4">
                      <div className="w-16 text-sm text-gray-600 font-mono">{stage.time.toFixed(2)}ms</div>
                      <div className="flex-1 h-8 bg-gray-100 rounded-lg overflow-hidden relative">
                        <div
                          className={`h-full ${stage.color} flex items-center px-3 text-white text-sm font-medium`}
                          style={{
                            marginLeft: `${(stage.time / trace.timings.total) * 100}%`,
                            width: `${(stage.duration / trace.timings.total) * 100}%`
                          }}
                        >
                          {stage.label}
                        </div>
                      </div>
                      <div className="w-16 text-sm text-gray-600 font-mono">{stage.duration.toFixed(2)}ms</div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>

            {/* Action Buttons */}
            <div className="flex gap-2">
              <Button variant="outline">
                <Download className="size-4 mr-2" />
                Export Report
              </Button>
              <Button variant="outline">
                <Save className="size-4 mr-2" />
                Add to Test Suite
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
