import { mockCompilationMetrics } from './mock-data';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Progress } from '../ui/progress';
import { CheckCircle2, Clock, Database, Zap, Network, Code2, FileCode, Download, Play } from 'lucide-react';

export function CompilationView() {
  const metrics = mockCompilationMetrics;

  const stages = [
    { 
      name: 'Parsing & Validation', 
      icon: FileCode,
      data: metrics.stages.parsing,
      details: `${metrics.stages.parsing.rulesProcessed} rules parsed, ${metrics.stages.parsing.errors} errors`
    },
    { 
      name: 'Dictionary Encoding', 
      icon: Database,
      data: metrics.stages.dictionaryEncoding,
      details: `${metrics.stages.dictionaryEncoding.dictionariesCreated} unique dictionaries created`
    },
    { 
      name: 'Cross-Family Deduplication', 
      icon: Network,
      data: metrics.stages.deduplication,
      details: `${metrics.stages.deduplication.inputRules} → ${metrics.stages.deduplication.outputGroups} groups (${(metrics.stages.deduplication.dedupRate * 100).toFixed(0)}% dedup rate)`
    },
    { 
      name: 'Structure-of-Arrays Layout', 
      icon: Code2,
      data: metrics.stages.soaLayout,
      details: `${(metrics.stages.soaLayout.cacheLineUtilization * 100).toFixed(0)}% cache line utilization`
    },
    { 
      name: 'Inverted Index (RoaringBitmap)', 
      icon: Database,
      data: metrics.stages.invertedIndex,
      details: `Avg reduction: ${(metrics.stages.invertedIndex.averageReduction * 100).toFixed(0)}%`
    },
    { 
      name: 'SIMD Vectorization', 
      icon: Zap,
      data: metrics.stages.simdVectorization,
      details: `${metrics.stages.simdVectorization.vectorizedPredicates} vectorized, ${metrics.stages.simdVectorization.scalarPredicates} scalar`
    }
  ];

  return (
    <div className="space-y-6">
      {/* Summary Card */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>Compilation Pipeline</CardTitle>
              <CardDescription>Last compiled: Just now</CardDescription>
            </div>
            <div className="flex gap-2">
              <Button variant="outline">
                <Download className="size-4 mr-2" />
                Export Metrics
              </Button>
              <Button>
                <Play className="size-4 mr-2" />
                Recompile
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-4 gap-6">
            <div className="space-y-2">
              <div className="text-sm text-gray-600">Total Duration</div>
              <div className="flex items-baseline gap-2">
                <div className="text-2xl font-semibold">{metrics.totalDurationMs}</div>
                <div className="text-sm text-gray-500">ms</div>
              </div>
              <div className="flex items-center gap-1 text-sm text-green-600">
                <CheckCircle2 className="size-4" />
                <span>Complete</span>
              </div>
            </div>
            
            <div className="space-y-2">
              <div className="text-sm text-gray-600">Estimated Throughput</div>
              <div className="flex items-baseline gap-2">
                <div className="text-2xl font-semibold">{metrics.estimatedThroughput.toFixed(1)}</div>
                <div className="text-sm text-gray-500">M events/min</div>
              </div>
              <div className="text-sm text-gray-500">Target: 15M</div>
            </div>
            
            <div className="space-y-2">
              <div className="text-sm text-gray-600">Memory Footprint</div>
              <div className="flex items-baseline gap-2">
                <div className="text-2xl font-semibold">{metrics.memoryFootprintGB.toFixed(1)}</div>
                <div className="text-sm text-gray-500">GB</div>
              </div>
              <div className="text-sm text-gray-500">Target: &lt;6 GB</div>
            </div>
            
            <div className="space-y-2">
              <div className="text-sm text-gray-600">Dedup Rate</div>
              <div className="flex items-baseline gap-2">
                <div className="text-2xl font-semibold">{(metrics.stages.deduplication.dedupRate * 100).toFixed(0)}</div>
                <div className="text-sm text-gray-500">%</div>
              </div>
              <div className="text-sm text-gray-500">
                Saved {metrics.stages.deduplication.memorySavingsKB} KB
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Compilation Stages */}
      <Card>
        <CardHeader>
          <CardTitle>Compilation Stages</CardTitle>
          <CardDescription>Detailed breakdown of each optimization stage</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-6">
            {stages.map((stage, index) => {
              const Icon = stage.icon;
              const progress = ((stage.data.durationMs / metrics.totalDurationMs) * 100);
              
              return (
                <div key={stage.name}>
                  <div className="flex items-start gap-4 mb-3">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-blue-100 text-blue-600 shrink-0">
                      <Icon className="size-5" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-2">
                        <div>
                          <h4 className="font-medium">
                            {index + 1}. {stage.name}
                          </h4>
                          <p className="text-sm text-gray-600 mt-1">{stage.details}</p>
                        </div>
                        <div className="text-right shrink-0 ml-4">
                          <div className="flex items-center gap-1 text-sm text-gray-600">
                            <Clock className="size-4" />
                            <span>{stage.data.durationMs}ms</span>
                          </div>
                          <div className="text-xs text-gray-500 mt-1">
                            {progress.toFixed(1)}% of total
                          </div>
                        </div>
                      </div>
                      <Progress value={progress} className="h-2" />
                    </div>
                  </div>
                  
                  {/* Stage-specific details */}
                  {stage.name === 'Cross-Family Deduplication' && (
                    <div className="ml-14 mt-3 p-4 bg-green-50 rounded-lg border border-green-200">
                      <div className="flex items-center gap-2 mb-2">
                        <Zap className="size-4 text-green-600" />
                        <span className="font-medium text-green-900">Optimization Impact</span>
                      </div>
                      <div className="grid grid-cols-3 gap-4 text-sm">
                        <div>
                          <div className="text-gray-600">Input Rules</div>
                          <div className="font-semibold">{metrics.stages.deduplication.inputRules}</div>
                        </div>
                        <div>
                          <div className="text-gray-600">Output Groups</div>
                          <div className="font-semibold">{metrics.stages.deduplication.outputGroups}</div>
                        </div>
                        <div>
                          <div className="text-gray-600">Memory Savings</div>
                          <div className="font-semibold">{metrics.stages.deduplication.memorySavingsKB} KB</div>
                        </div>
                      </div>
                    </div>
                  )}
                  
                  {stage.name === 'SIMD Vectorization' && (
                    <div className="ml-14 mt-3 p-4 bg-blue-50 rounded-lg border border-blue-200">
                      <div className="flex items-center gap-2 mb-2">
                        <Zap className="size-4 text-blue-600" />
                        <span className="font-medium text-blue-900">Vectorization Stats</span>
                      </div>
                      <div className="grid grid-cols-3 gap-4 text-sm">
                        <div>
                          <div className="text-gray-600">Vectorized</div>
                          <div className="font-semibold">{metrics.stages.simdVectorization.vectorizedPredicates}</div>
                        </div>
                        <div>
                          <div className="text-gray-600">Scalar Fallback</div>
                          <div className="font-semibold">{metrics.stages.simdVectorization.scalarPredicates}</div>
                        </div>
                        <div>
                          <div className="text-gray-600">Vectorization Rate</div>
                          <div className="font-semibold">{(metrics.stages.simdVectorization.vectorizationRate * 100).toFixed(0)}%</div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {/* Compilation DAG Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Compilation Graph</CardTitle>
          <CardDescription>Interactive dependency graph showing optimization flow</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-center p-12 bg-gray-50 rounded-lg border-2 border-dashed">
            <div className="text-center space-y-4">
              <div className="space-y-8">
                {/* Simplified DAG visualization */}
                <div className="flex justify-center">
                  <div className="px-4 py-2 bg-blue-100 text-blue-900 rounded-lg font-medium">
                    Raw Rules (45)
                  </div>
                </div>
                
                <div className="flex items-center justify-center gap-1">
                  <div className="w-px h-8 bg-gray-300"></div>
                </div>
                
                <div className="flex justify-center gap-4">
                  <div className="px-3 py-2 bg-green-100 text-green-900 rounded text-sm">
                    Dedup Group #7<br/>(23 rules)
                  </div>
                  <div className="px-3 py-2 bg-green-100 text-green-900 rounded text-sm">
                    Dedup Group #3<br/>(8 rules)
                  </div>
                  <div className="px-3 py-2 bg-green-100 text-green-900 rounded text-sm">
                    Dedup Group #12<br/>(5 rules)
                  </div>
                </div>
                
                <div className="flex items-center justify-center gap-1">
                  <div className="w-px h-8 bg-gray-300"></div>
                </div>
                
                <div className="flex justify-center">
                  <div className="px-4 py-2 bg-purple-100 text-purple-900 rounded-lg">
                    Dictionary Encoding
                  </div>
                </div>
                
                <div className="flex items-center justify-center gap-1">
                  <div className="w-px h-8 bg-gray-300"></div>
                </div>
                
                <div className="flex justify-center">
                  <div className="px-4 py-2 bg-orange-100 text-orange-900 rounded-lg">
                    Inverted Index + SIMD Vectors
                  </div>
                </div>
              </div>
              
              <p className="text-sm text-gray-600 mt-8">
                Click nodes to inspect detailed metadata and optimization impact
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
