import { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Badge } from '../ui/badge';
import { Checkbox } from '../ui/checkbox';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import { Separator } from '../ui/separator';
import { Plus, X, Zap, CheckCircle2, AlertCircle, Trash2, Save, Play } from 'lucide-react';

interface Condition {
  id: string;
  attribute: string;
  operator: string;
  value: string;
  type: 'base' | 'vectorized';
}

interface Action {
  id: string;
  type: string;
  params: string;
}

export function VisualRuleBuilder() {
  const [conditions, setConditions] = useState<Condition[]>([
    { id: '1', attribute: 'customer_segment', operator: 'EQUAL_TO', value: 'premium', type: 'base' },
    { id: '2', attribute: 'lifetime_value', operator: '>=', value: '10000', type: 'vectorized' }
  ]);

  const [actions, setActions] = useState<Action[]>([
    { id: '1', type: 'trigger_campaign', params: 'premium_upsell' },
    { id: '2', type: 'set_discount', params: '15' }
  ]);

  const [ruleName, setRuleName] = useState('New Premium Customer Rule');
  const [ruleDescription, setRuleDescription] = useState('');
  const [priority, setPriority] = useState('100');

  const addCondition = () => {
    setConditions([...conditions, {
      id: Date.now().toString(),
      attribute: '',
      operator: '=',
      value: '',
      type: 'base'
    }]);
  };

  const removeCondition = (id: string) => {
    setConditions(conditions.filter(c => c.id !== id));
  };

  const updateCondition = (id: string, field: keyof Condition, value: any) => {
    setConditions(conditions.map(c =>
      c.id === id ? { ...c, [field]: value } : c
    ));
  };

  const addAction = () => {
    setActions([...actions, {
      id: Date.now().toString(),
      type: '',
      params: ''
    }]);
  };

  const removeAction = (id: string) => {
    setActions(actions.filter(a => a.id !== id));
  };

  const updateAction = (id: string, field: keyof Action, value: string) => {
    setActions(actions.map(a =>
      a.id === id ? { ...a, [field]: value } : a
    ));
  };

  const attributes = [
    'customer_segment',
    'region',
    'lifetime_value',
    'account_status',
    'days_since_last_purchase',
    'purchase_frequency',
    'transaction_amount',
    'device_verified'
  ];

  const operators = {
    base: ['EQUAL_TO', 'NOT_EQUAL_TO', 'IS_ANY_OF', 'IS_NONE_OF'],
    vectorized: ['=', '!=', '>', '>=', '<', '<=']
  };

  const actionTypes = [
    'trigger_campaign',
    'set_discount',
    'send_notification',
    'flag_for_review',
    'log_event',
    'set_badge',
    'show_recommendations'
  ];

  // Calculate optimization preview
  const vectorizableCount = conditions.filter(c => c.type === 'vectorized').length;
  const estimatedLatency = 0.2 + (conditions.length * 0.05);
  const dedupPotential = 78; // Mock value

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Visual Rule Builder</CardTitle>
          <CardDescription>Create rules using an intuitive drag-and-drop interface</CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Basic Info */}
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>Rule Name</Label>
              <Input
                value={ruleName}
                onChange={(e) => setRuleName(e.target.value)}
                placeholder="Enter rule name..."
              />
            </div>

            <div className="space-y-2">
              <Label>Description</Label>
              <Textarea
                value={ruleDescription}
                onChange={(e) => setRuleDescription(e.target.value)}
                placeholder="Describe what this rule does..."
                rows={2}
              />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>Rule Family</Label>
                <Select defaultValue="customer-segmentation">
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="customer-segmentation">Customer Segmentation</SelectItem>
                    <SelectItem value="fraud-detection">Fraud Detection</SelectItem>
                    <SelectItem value="personalization">Personalization</SelectItem>
                    <SelectItem value="pricing">Pricing & Promotions</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label>Priority</Label>
                <Input
                  type="number"
                  value={priority}
                  onChange={(e) => setPriority(e.target.value)}
                  min="0"
                  max="200"
                />
              </div>
            </div>
          </div>

          <Separator />

          {/* Conditions */}
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold">Conditions</h3>
              <Button size="sm" variant="outline" onClick={addCondition}>
                <Plus className="size-4 mr-2" />
                Add Condition
              </Button>
            </div>

            <div className="space-y-3">
              {conditions.map((condition, idx) => (
                <div key={condition.id} className="flex items-start gap-3">
                  <div className="flex items-center justify-center size-8 rounded-full bg-blue-100 text-blue-600 font-semibold shrink-0 mt-2">
                    {idx === 0 ? 'IF' : 'AND'}
                  </div>

                  <Card className="flex-1">
                    <CardContent className="p-4">
                      <div className="grid grid-cols-[1fr,auto,1fr,auto,auto] gap-3 items-center">
                        {/* Attribute */}
                        <Select
                          value={condition.attribute}
                          onValueChange={(value) => updateCondition(condition.id, 'attribute', value)}
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="Select attribute" />
                          </SelectTrigger>
                          <SelectContent>
                            {attributes.map(attr => (
                              <SelectItem key={attr} value={attr}>{attr}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>

                        {/* Operator */}
                        <Select
                          value={condition.operator}
                          onValueChange={(value) => updateCondition(condition.id, 'operator', value)}
                        >
                          <SelectTrigger className="w-32">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {[...operators.base, ...operators.vectorized].map(op => (
                              <SelectItem key={op} value={op}>{op}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>

                        {/* Value */}
                        <Input
                          value={condition.value}
                          onChange={(e) => updateCondition(condition.id, 'value', e.target.value)}
                          placeholder="Value"
                        />

                        {/* Type Badge */}
                        <Badge variant={condition.type === 'vectorized' ? 'default' : 'secondary'}>
                          {condition.type === 'vectorized' ? (
                            <>
                              <Zap className="size-3 mr-1" />
                              SIMD
                            </>
                          ) : (
                            'Static'
                          )}
                        </Badge>

                        {/* Delete */}
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => removeCondition(condition.id)}
                        >
                          <X className="size-4" />
                        </Button>
                      </div>

                      {/* Type selector */}
                      <div className="mt-3 flex items-center gap-4 text-sm">
                        <span className="text-gray-600">Condition Type:</span>
                        <div className="flex gap-4">
                          <label className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="radio"
                              name={`type-${condition.id}`}
                              checked={condition.type === 'base'}
                              onChange={() => updateCondition(condition.id, 'type', 'base')}
                            />
                            <span>Base (Cached)</span>
                          </label>
                          <label className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="radio"
                              name={`type-${condition.id}`}
                              checked={condition.type === 'vectorized'}
                              onChange={() => updateCondition(condition.id, 'type', 'vectorized')}
                            />
                            <span>Vectorized (SIMD)</span>
                          </label>
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                </div>
              ))}
            </div>
          </div>

          <Separator />

          {/* Actions */}
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold">Then Execute Actions</h3>
              <Button size="sm" variant="outline" onClick={addAction}>
                <Plus className="size-4 mr-2" />
                Add Action
              </Button>
            </div>

            <div className="space-y-3">
              {actions.map((action) => (
                <div key={action.id} className="flex items-center gap-3">
                  <div className="flex items-center justify-center size-8 rounded-full bg-purple-100 text-purple-600 shrink-0">
                    →
                  </div>

                  <Card className="flex-1">
                    <CardContent className="p-4">
                      <div className="grid grid-cols-[1fr,1fr,auto] gap-3 items-center">
                        {/* Action Type */}
                        <Select
                          value={action.type}
                          onValueChange={(value) => updateAction(action.id, 'type', value)}
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="Select action" />
                          </SelectTrigger>
                          <SelectContent>
                            {actionTypes.map(type => (
                              <SelectItem key={type} value={type}>{type}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>

                        {/* Parameters */}
                        <Input
                          value={action.params}
                          onChange={(e) => updateAction(action.id, 'params', e.target.value)}
                          placeholder="Parameters"
                        />

                        {/* Delete */}
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => removeAction(action.id)}
                        >
                          <X className="size-4" />
                        </Button>
                      </div>
                    </CardContent>
                  </Card>
                </div>
              ))}
            </div>
          </div>

          <Separator />

          {/* Optimization Preview */}
          <div className="bg-gradient-to-r from-blue-50 to-purple-50 rounded-lg p-6 border border-blue-200">
            <div className="flex items-center gap-2 mb-4">
              <Zap className="size-5 text-blue-600" />
              <h3 className="font-semibold text-blue-900">Optimization Preview</h3>
            </div>

            <div className="grid grid-cols-3 gap-6">
              <div>
                <div className="flex items-center gap-2 mb-2">
                  {vectorizableCount > 0 ? (
                    <CheckCircle2 className="size-4 text-green-600" />
                  ) : (
                    <AlertCircle className="size-4 text-yellow-600" />
                  )}
                  <span className="text-sm font-medium">Vectorizable</span>
                </div>
                <p className="text-sm text-gray-700">
                  {vectorizableCount > 0 ? (
                    <>✓ {vectorizableCount} conditions will use SIMD</>
                  ) : (
                    <>! No SIMD optimization available</>
                  )}
                </p>
              </div>

              <div>
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle2 className="size-4 text-green-600" />
                  <span className="text-sm font-medium">Estimated Latency</span>
                </div>
                <p className="text-sm text-gray-700">
                  ~{estimatedLatency.toFixed(2)}ms per evaluation
                </p>
              </div>

              <div>
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle2 className="size-4 text-green-600" />
                  <span className="text-sm font-medium">Dedup Potential</span>
                </div>
                <p className="text-sm text-gray-700">
                  {dedupPotential}% condition overlap with existing rules
                </p>
              </div>
            </div>

            <div className="mt-4 p-3 bg-white rounded border border-blue-200">
              <p className="text-sm text-gray-700">
                <strong className="text-blue-900">Cache-friendly:</strong> ✓ Static predicates dominate, ensuring high cache hit rate
              </p>
            </div>
          </div>

          {/* Action Buttons */}
          <div className="flex gap-3 pt-4">
            <Button className="flex-1" size="lg">
              <Save className="size-4 mr-2" />
              Save as Draft
            </Button>
            <Button className="flex-1" variant="outline" size="lg">
              <Play className="size-4 mr-2" />
              Validate & Test
            </Button>
            <Button variant="outline" size="lg">
              Deploy to Production
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Generated Code Preview */}
      <Card>
        <CardHeader>
          <CardTitle>Generated Code Preview</CardTitle>
          <CardDescription>JSON representation of the rule</CardDescription>
        </CardHeader>
        <CardContent>
          <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg text-sm font-mono overflow-x-auto">
{JSON.stringify({
  name: ruleName,
  description: ruleDescription || 'No description provided',
  priority: parseInt(priority),
  conditions: conditions.map(c => ({
    attribute: c.attribute,
    operator: c.operator,
    value: c.value,
    type: c.type
  })),
  actions: actions.map(a => ({
    type: a.type,
    params: a.params
  }))
}, null, 2)}
          </pre>
        </CardContent>
      </Card>
    </div>
  );
}
