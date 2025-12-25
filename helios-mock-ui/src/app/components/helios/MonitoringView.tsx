import { mockSystemMetrics, mockRules } from './mock-data';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Progress } from '../ui/progress';
import { ScrollArea } from '../ui/scroll-area';
import { Activity, TrendingUp, Database, Zap, AlertTriangle, CheckCircle2, Clock, Cpu } from 'lucide-react';

export function MonitoringView() {
  const metrics = mockSystemMetrics;

  const hotRules = [
    { ruleId: 'rule-12453', name: 'High-Value Customer Upsell', evalsPerMin: 4200000, matchRate: 0.82 },
    { ruleId: 'rule-12458', name: 'Retention Campaign', evalsPerMin: 3800000, matchRate: 0.45 },
    { ruleId: 'rule-14201', name: 'Fraud Alert - High Risk', evalsPerMin: 2900000, matchRate: 0.91 }
  ];

  const slowRules = [
    { ruleId: 'rule-12455', name: 'Slow Performance Rule', p99Latency: 1.2, reason: 'Complex nested conditions' },
    { ruleId: 'rule-12499', name: 'Complex String Matching', p99Latency: 1.4, reason: 'Missing index on purchase_history' }
  ];

  const alerts = [
    { id: 1, severity: 'warning', message: 'P99.9 latency spike at 14:23 (+40% from baseline)', time: '5 min ago', suspected: 'GC pause (Old Gen collection)' },
    { id: 2, severity: 'info', message: 'Rule #12499 match rate dropped 30% (anomaly)', time: '12 min ago', suspected: 'Data distribution change' }
  ];

  return (
    <div className="space-y-6">
      {/* Key Metrics Row */}
      <div className="grid grid-cols-4 gap-6">
        <Card>
          <CardHeader className="pb-3">
            <CardDescription className="flex items-center gap-2">
              <Activity className="size-4" />
              Throughput
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-baseline gap-2">
              <div className="text-3xl font-semibold">{(metrics.throughput / 1000000).toFixed(1)}</div>
              <div className="text-sm text-gray-500">M events/min</div>
            </div>
            <Progress value={(metrics.throughput / 20000000) * 100} className="mt-3" />
            <div className="flex justify-between text-xs text-gray-500 mt-2">
              <span>Target: 15M</span>
              <span className="text-green-600">✓ Above target</span>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardDescription className="flex items-center gap-2">
              <Clock className="size-4" />
              P99 Latency
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-baseline gap-2">
              <div className="text-3xl font-semibold">{metrics.latency.p99}</div>
              <div className="text-sm text-gray-500">ms</div>
            </div>
            <Progress value={(metrics.latency.p99 / 0.8) * 100} className="mt-3" />
            <div className="flex justify-between text-xs text-gray-500 mt-2">
              <span>Target: &lt;0.8ms</span>
              <span className="text-green-600">✓ Under target</span>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardDescription className="flex items-center gap-2">
              <Database className="size-4" />
              Memory Usage
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-baseline gap-2">
              <div className="text-3xl font-semibold">{metrics.memory.used.toFixed(1)}</div>
              <div className="text-sm text-gray-500">/ {metrics.memory.total} GB</div>
            </div>
            <Progress value={(metrics.memory.used / metrics.memory.total) * 100} className="mt-3" />
            <div className="flex justify-between text-xs text-gray-500 mt-2">
              <span>{((metrics.memory.used / metrics.memory.total) * 100).toFixed(0)}% utilized</span>
              <span className="text-green-600">✓ Healthy</span>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardDescription className="flex items-center gap-2">
              <Zap className="size-4" />
              Cache Hit Rate
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-baseline gap-2">
              <div className="text-3xl font-semibold">{(metrics.cache.baseConditionHitRate * 100).toFixed(0)}</div>
              <div className="text-sm text-gray-500">%</div>
            </div>
            <Progress value={metrics.cache.baseConditionHitRate * 100} className="mt-3" />
            <div className="flex justify-between text-xs text-gray-500 mt-2">
              <span>Base conditions</span>
              <span className="text-green-600">⚡ Excellent</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Latency Distribution */}
      <Card>
        <CardHeader>
          <CardTitle>Latency Distribution (Last 5 Minutes)</CardTitle>
          <CardDescription>Percentile breakdown of evaluation latency</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {[
              { label: 'P50', value: metrics.latency.p50, target: 0.5, color: 'bg-green-500' },
              { label: 'P95', value: metrics.latency.p95, target: 0.6, color: 'bg-blue-500' },
              { label: 'P99', value: metrics.latency.p99, target: 0.8, color: 'bg-yellow-500' },
              { label: 'P99.9', value: metrics.latency.p999, target: 1.5, color: 'bg-red-500' }
            ].map(percentile => {
              const isAboveTarget = percentile.value > percentile.target;
              return (
                <div key={percentile.label} className="flex items-center gap-4">
                  <div className="w-16 font-semibold">{percentile.label}</div>
                  <div className="flex-1">
                    <div className="h-8 bg-gray-100 rounded-lg overflow-hidden relative">
                      <div
                        className={`h-full ${percentile.color} flex items-center px-3 text-white font-medium`}
                        style={{ width: `${(percentile.value / 2.0) * 100}%` }}
                      >
                        {percentile.value}ms
                      </div>
                    </div>
                  </div>
                  <div className="w-32 text-sm text-gray-600">
                    Target: &lt;{percentile.target}ms
                  </div>
                  <div className="w-20">
                    {isAboveTarget ? (
                      <Badge variant="destructive">⚠️ Spike</Badge>
                    ) : (
                      <Badge variant="secondary" className="text-green-600">✓ Good</Badge>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {/* Two Column Layout */}
      <div className="grid grid-cols-2 gap-6">
        {/* Hot Rules */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <TrendingUp className="size-5 text-orange-600" />
              Hot Rules (Most Evaluated)
            </CardTitle>
            <CardDescription>Rules with highest evaluation volume</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {hotRules.map((rule, idx) => (
                <div key={rule.ruleId} className="space-y-2">
                  <div className="flex items-start justify-between">
                    <div className="flex items-start gap-3">
                      <div className="flex items-center justify-center size-8 rounded-full bg-orange-100 text-orange-600 font-semibold shrink-0">
                        {idx + 1}
                      </div>
                      <div>
                        <div className="font-medium">{rule.name}</div>
                        <div className="text-sm text-gray-600">{rule.ruleId}</div>
                      </div>
                    </div>
                  </div>
                  <div className="ml-11 space-y-1">
                    <div className="flex justify-between text-sm">
                      <span className="text-gray-600">Evaluations/min</span>
                      <span className="font-semibold">{(rule.evalsPerMin / 1000000).toFixed(1)}M</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-gray-600">Match rate</span>
                      <span className="font-semibold">{(rule.matchRate * 100).toFixed(0)}%</span>
                    </div>
                    <Progress value={rule.matchRate * 100} className="h-1.5 mt-2" />
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Slow Rules */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <AlertTriangle className="size-5 text-red-600" />
              Slow Rules (P99 &gt; 1ms)
            </CardTitle>
            <CardDescription>Rules requiring optimization</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {slowRules.map((rule) => (
                <div key={rule.ruleId} className="p-4 bg-red-50 rounded-lg border border-red-200">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <div className="font-medium text-red-900">{rule.name}</div>
                      <div className="text-sm text-red-700">{rule.ruleId}</div>
                    </div>
                    <Badge variant="destructive">{rule.p99Latency}ms</Badge>
                  </div>
                  <div className="space-y-2">
                    <div className="text-sm">
                      <span className="text-red-700">Issue: </span>
                      <span className="text-red-900">{rule.reason}</span>
                    </div>
                    <Button size="sm" variant="outline" className="w-full">
                      View Optimization Suggestions
                    </Button>
                  </div>
                </div>
              ))}
              
              <div className="p-4 bg-blue-50 rounded-lg border border-blue-200">
                <div className="flex items-center gap-2 mb-2 text-blue-900">
                  <Zap className="size-4" />
                  <span className="font-medium">Auto-Optimization Available</span>
                </div>
                <p className="text-sm text-blue-800 mb-3">
                  12 rules can be refactored for better performance
                </p>
                <Button size="sm" className="w-full">
                  Run Auto-Optimizer
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Alerts */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <AlertTriangle className="size-5 text-yellow-600" />
            Active Alerts ({alerts.length})
          </CardTitle>
          <CardDescription>System anomalies and performance issues</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {alerts.map((alert) => (
              <div
                key={alert.id}
                className={`p-4 rounded-lg border ${
                  alert.severity === 'warning'
                    ? 'bg-yellow-50 border-yellow-200'
                    : 'bg-blue-50 border-blue-200'
                }`}
              >
                <div className="flex items-start gap-3">
                  <AlertTriangle
                    className={`size-5 shrink-0 mt-0.5 ${
                      alert.severity === 'warning' ? 'text-yellow-600' : 'text-blue-600'
                    }`}
                  />
                  <div className="flex-1">
                    <div className="flex items-start justify-between mb-2">
                      <p className="font-medium">{alert.message}</p>
                      <span className="text-sm text-gray-600">{alert.time}</span>
                    </div>
                    <div className="text-sm text-gray-700 mb-3">
                      <span className="font-medium">Suspected cause: </span>
                      {alert.suspected}
                    </div>
                    <div className="flex gap-2">
                      <Button size="sm" variant="outline">
                        View Details
                      </Button>
                      <Button size="sm" variant="outline">
                        Acknowledge
                      </Button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Cache Effectiveness */}
      <Card>
        <CardHeader>
          <CardTitle>Cache Effectiveness</CardTitle>
          <CardDescription>Real-time cache performance metrics</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-3 gap-6">
            <div>
              <div className="flex items-center gap-2 mb-3">
                <Zap className="size-4 text-blue-600" />
                <span className="font-medium">Base Condition Cache</span>
              </div>
              <div className="text-3xl font-semibold mb-2">
                {(metrics.cache.baseConditionHitRate * 100).toFixed(1)}%
              </div>
              <Progress value={metrics.cache.baseConditionHitRate * 100} className="mb-2" />
              <p className="text-sm text-gray-600">
                ⚡ Excellent hit rate
              </p>
            </div>

            <div>
              <div className="flex items-center gap-2 mb-3">
                <Database className="size-4 text-purple-600" />
                <span className="font-medium">Dictionary Lookup</span>
              </div>
              <div className="text-3xl font-semibold mb-2">
                {(metrics.cache.dictionaryHitRate * 100).toFixed(1)}%
              </div>
              <Progress value={metrics.cache.dictionaryHitRate * 100} className="mb-2" />
              <p className="text-sm text-gray-600">
                ⚡ Near-perfect caching
              </p>
            </div>

            <div>
              <div className="flex items-center gap-2 mb-3">
                <Cpu className="size-4 text-green-600" />
                <span className="font-medium">Dedup Effectiveness</span>
              </div>
              <div className="text-3xl font-semibold mb-2">89%</div>
              <Progress value={89} className="mb-2" />
              <p className="text-sm text-gray-600">
                40 rules share 5 base condition sets
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
