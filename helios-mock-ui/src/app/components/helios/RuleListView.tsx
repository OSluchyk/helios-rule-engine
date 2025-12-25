import { useState } from 'react';
import { mockRules, ruleFamilies, type Rule } from './mock-data';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';
import { Checkbox } from '../ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Slider } from '../ui/slider';
import { ScrollArea } from '../ui/scroll-area';
import { Search, Plus, Download, Upload, Edit, Copy, TestTube, History, Trash2, Play, Pause, CheckCircle2, XCircle, AlertCircle } from 'lucide-react';

export function RuleListView() {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFamily, setSelectedFamily] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string[]>(['active']);
  const [selectedRule, setSelectedRule] = useState<string | null>(null);

  const filteredRules = mockRules.filter(rule => {
    const matchesSearch = rule.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
                         rule.description.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesFamily = selectedFamily === 'all' || rule.family === selectedFamily;
    const matchesStatus = statusFilter.length === 0 || statusFilter.includes(rule.status);
    return matchesSearch && matchesFamily && matchesStatus;
  });

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'active': return <CheckCircle2 className="size-4 text-green-600" />;
      case 'inactive': return <Pause className="size-4 text-gray-400" />;
      case 'draft': return <AlertCircle className="size-4 text-yellow-600" />;
      default: return <XCircle className="size-4 text-red-600" />;
    }
  };

  return (
    <div className="flex gap-6 h-full">
      {/* Left Sidebar - Filters */}
      <div className="w-80 shrink-0">
        <Card>
          <CardHeader>
            <CardTitle>Filters</CardTitle>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* Search */}
            <div className="space-y-2">
              <label className="font-medium">Search</label>
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-gray-400" />
                <Input
                  placeholder="Search rules..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
            </div>

            {/* Family Filter */}
            <div className="space-y-2">
              <label className="font-medium">Rule Family</label>
              <Select value={selectedFamily} onValueChange={setSelectedFamily}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Families</SelectItem>
                  {ruleFamilies.map(family => (
                    <SelectItem key={family.name} value={family.name}>
                      {family.name} ({family.ruleCount})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Status Filter */}
            <div className="space-y-2">
              <label className="font-medium">Status</label>
              <div className="space-y-2">
                {['active', 'inactive', 'draft'].map(status => (
                  <div key={status} className="flex items-center gap-2">
                    <Checkbox
                      id={status}
                      checked={statusFilter.includes(status)}
                      onCheckedChange={(checked) => {
                        setStatusFilter(prev =>
                          checked
                            ? [...prev, status]
                            : prev.filter(s => s !== status)
                        );
                      }}
                    />
                    <label htmlFor={status} className="capitalize cursor-pointer">
                      {status}
                    </label>
                  </div>
                ))}
              </div>
            </div>

            {/* Priority Range */}
            <div className="space-y-2">
              <label className="font-medium">Priority Range</label>
              <Slider defaultValue={[0]} max={200} step={10} />
              <div className="flex justify-between text-sm text-gray-500">
                <span>0</span>
                <span>200</span>
              </div>
            </div>

            {/* Advanced Filters */}
            <div className="space-y-2">
              <label className="font-medium">Advanced</label>
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <Checkbox id="vectorized" />
                  <label htmlFor="vectorized" className="cursor-pointer">Has Vectorized Conditions</label>
                </div>
                <div className="flex items-center gap-2">
                  <Checkbox id="low-cache" />
                  <label htmlFor="low-cache" className="cursor-pointer">Cache Hit Rate &lt; 50%</label>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Quick Actions */}
        <Card className="mt-4">
          <CardHeader>
            <CardTitle>Actions</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button className="w-full justify-start" variant="outline">
              <Plus className="size-4 mr-2" />
              New Rule
            </Button>
            <Button className="w-full justify-start" variant="outline">
              <Upload className="size-4 mr-2" />
              Import Rules
            </Button>
            <Button className="w-full justify-start" variant="outline">
              <Download className="size-4 mr-2" />
              Export Selected
            </Button>
          </CardContent>
        </Card>
      </div>

      {/* Main Content - Rule List */}
      <div className="flex-1 min-w-0">
        <Card className="h-full flex flex-col">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle>Rules ({filteredRules.length})</CardTitle>
                <CardDescription>Manage and monitor your rule configurations</CardDescription>
              </div>
              <div className="flex gap-2">
                <Select defaultValue="match-rate">
                  <SelectTrigger className="w-40">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="match-rate">Sort by Match Rate</SelectItem>
                    <SelectItem value="priority">Sort by Priority</SelectItem>
                    <SelectItem value="modified">Sort by Last Modified</SelectItem>
                    <SelectItem value="latency">Sort by Latency</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </CardHeader>
          
          <CardContent className="flex-1 overflow-hidden">
            <ScrollArea className="h-full pr-4">
              <Accordion type="single" collapsible className="space-y-4">
                {filteredRules.map((rule) => (
                  <AccordionItem
                    key={rule.id}
                    value={rule.id}
                    className="border rounded-lg px-4 bg-white shadow-sm"
                  >
                    <AccordionTrigger className="hover:no-underline">
                      <div className="flex items-start gap-4 w-full text-left">
                        <div className="flex items-center gap-2 mt-1">
                          {getStatusIcon(rule.status)}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-3 mb-1">
                            <h3 className="font-semibold">{rule.name}</h3>
                            <Badge variant="outline">{rule.family}</Badge>
                            <Badge variant="secondary">Priority: {rule.priority}</Badge>
                          </div>
                          <p className="text-sm text-gray-600 mb-2">{rule.description}</p>
                          <div className="flex gap-6 text-sm text-gray-500">
                            <span>{rule.stats.evalsPerDay.toLocaleString()} evals/day</span>
                            <span>{(rule.stats.matchRate * 100).toFixed(0)}% match rate</span>
                            <span>{rule.stats.avgLatencyMs.toFixed(2)}ms avg latency</span>
                          </div>
                        </div>
                      </div>
                    </AccordionTrigger>
                    
                    <AccordionContent>
                      <div className="pt-4 space-y-6">
                        {/* Base Conditions */}
                        <div>
                          <h4 className="font-medium mb-2">Base Conditions (Static - Cached)</h4>
                          <div className="space-y-2">
                            {rule.baseConditions.map((cond, idx) => (
                              <div key={idx} className="flex items-center gap-2 text-sm pl-4 border-l-2 border-green-500">
                                <CheckCircle2 className="size-4 text-green-600" />
                                <code className="bg-gray-50 px-2 py-1 rounded">
                                  {cond.attribute}
                                </code>
                                <span className="text-gray-500">{cond.operator}</span>
                                <code className="bg-gray-50 px-2 py-1 rounded">
                                  {Array.isArray(cond.value) ? JSON.stringify(cond.value) : `"${cond.value}"`}
                                </code>
                              </div>
                            ))}
                          </div>
                        </div>

                        {/* Vectorized Conditions */}
                        {rule.vectorizedConditions.length > 0 && (
                          <div>
                            <h4 className="font-medium mb-2">Vectorized Conditions (Numeric - SIMD)</h4>
                            <div className="space-y-2">
                              {rule.vectorizedConditions.map((cond, idx) => (
                                <div key={idx} className="flex items-center gap-2 text-sm pl-4 border-l-2 border-blue-500">
                                  <span className="text-blue-600">⚡</span>
                                  <code className="bg-gray-50 px-2 py-1 rounded">
                                    {cond.attribute}
                                  </code>
                                  <span className="text-gray-500">{cond.operator}</span>
                                  <code className="bg-gray-50 px-2 py-1 rounded">
                                    {cond.value.toLocaleString()}
                                  </code>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Actions */}
                        <div>
                          <h4 className="font-medium mb-2">Actions</h4>
                          <div className="space-y-2">
                            {rule.actions.map((action, idx) => (
                              <div key={idx} className="flex items-center gap-2 text-sm pl-4 border-l-2 border-purple-500">
                                <span>•</span>
                                <code className="bg-gray-50 px-2 py-1 rounded">
                                  {action.type}({typeof action.params === 'string' ? `"${action.params}"` : action.params})
                                </code>
                              </div>
                            ))}
                          </div>
                        </div>

                        {/* Optimization Metadata */}
                        <div className="bg-gray-50 rounded-lg p-4">
                          <h4 className="font-medium mb-3">Optimization Metadata</h4>
                          <div className="grid grid-cols-2 gap-4 text-sm">
                            <div>
                              <span className="text-gray-600">Dictionary IDs:</span>
                              <div className="mt-1">
                                {Object.entries(rule.optimization.dictionaryIds).map(([key, val]) => (
                                  <div key={key} className="text-xs">
                                    {key}={val}
                                  </div>
                                ))}
                              </div>
                            </div>
                            <div>
                              <span className="text-gray-600">Dedup Group:</span>
                              <div className="mt-1 text-xs">{rule.optimization.dedupGroupId}</div>
                            </div>
                            <div>
                              <span className="text-gray-600">Cache Key:</span>
                              <div className="mt-1 text-xs">{rule.optimization.cacheKey}</div>
                            </div>
                            <div>
                              <span className="text-gray-600">Version:</span>
                              <div className="mt-1 text-xs">v{rule.version}</div>
                            </div>
                          </div>
                        </div>

                        {/* Action Buttons */}
                        <div className="flex gap-2 pt-4 border-t">
                          <Button size="sm" variant="outline">
                            <Edit className="size-4 mr-2" />
                            Edit Rule
                          </Button>
                          <Button size="sm" variant="outline">
                            <Copy className="size-4 mr-2" />
                            Clone
                          </Button>
                          <Button size="sm" variant="outline">
                            <TestTube className="size-4 mr-2" />
                            Test
                          </Button>
                          <Button size="sm" variant="outline">
                            <History className="size-4 mr-2" />
                            History
                          </Button>
                          <Button size="sm" variant="outline" className="ml-auto text-red-600 hover:text-red-700">
                            <Trash2 className="size-4 mr-2" />
                            Delete
                          </Button>
                        </div>
                      </div>
                    </AccordionContent>
                  </AccordionItem>
                ))}
              </Accordion>
            </ScrollArea>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
