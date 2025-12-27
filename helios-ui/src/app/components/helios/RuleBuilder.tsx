/**
 * RuleBuilder Component - Production Implementation
 *
 * Visual rule builder with real API integration.
 * Features:
 * - Custom attribute input (not hardcoded dropdown)
 * - CSV to array conversion for IS_ANY_OF/IS_NONE_OF operators
 * - Automatic condition type detection (base vs vectorized)
 * - Tooltips explaining condition types
 * - Full API integration for create/update
 */

import { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Badge } from '../ui/badge';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import { Separator } from '../ui/separator';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';
import { Alert, AlertDescription } from '../ui/alert';
import {
  Plus,
  X,
  Zap,
  CheckCircle2,
  AlertCircle,
  Save,
  Play,
  Loader2,
  Info,
  AlertTriangle
} from 'lucide-react';
import { toast } from 'sonner';
import axios from 'axios';
import { useQueryClient } from '@tanstack/react-query';

interface Condition {
  id: string;
  field: string; // Custom input field name
  operator: string;
  value: string; // String input (will be converted for array operators)
  type: 'base' | 'vectorized' | 'auto';
}

interface RuleBuilderProps {
  onRuleCreated?: () => void;
}

export function RuleBuilder({ onRuleCreated }: RuleBuilderProps) {
  // React Query client for cache invalidation
  const queryClient = useQueryClient();

  // Form state
  const [family, setFamily] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState('100');
  const [enabled, setEnabled] = useState(true);
  const [tags, setTags] = useState('');

  // Conditions state
  const [conditions, setConditions] = useState<Condition[]>([
    {
      id: '1',
      field: '',
      operator: 'EQUAL_TO',
      value: '',
      type: 'auto'
    }
  ]);

  // UI state
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);

  // Computed rule code from family + name
  const ruleCode = family && ruleName ? `${family}.${ruleName}` : '';

  // Rule families
  const RULE_FAMILIES = [
    { value: 'fraud_detection', label: 'Fraud Detection' },
    { value: 'customer_segmentation', label: 'Customer Segmentation' },
    { value: 'personalization', label: 'Personalization' },
    { value: 'pricing', label: 'Pricing & Promotions' },
    { value: 'risk_assessment', label: 'Risk Assessment' },
    { value: 'compliance', label: 'Compliance' },
  ];

  // Operator definitions with metadata
  const OPERATORS = {
    // String/categorical operators (Base conditions - cached)
    EQUAL_TO: {
      label: 'EQUAL_TO',
      type: 'base',
      description: 'Exact string match (cached)',
      acceptsArray: false
    },
    NOT_EQUAL_TO: {
      label: 'NOT_EQUAL_TO',
      type: 'base',
      description: 'Not equal to value (cached)',
      acceptsArray: false
    },
    IS_ANY_OF: {
      label: 'IS_ANY_OF',
      type: 'base',
      description: 'Value is in list (cached) - enter CSV',
      acceptsArray: true
    },
    IS_NONE_OF: {
      label: 'IS_NONE_OF',
      type: 'base',
      description: 'Value not in list (cached) - enter CSV',
      acceptsArray: true
    },

    // Numeric operators (Vectorized - SIMD optimized)
    GREATER_THAN: {
      label: 'GREATER_THAN',
      type: 'vectorized',
      description: 'Greater than (SIMD optimized)',
      acceptsArray: false
    },
    GREATER_THAN_OR_EQUAL: {
      label: 'GREATER_THAN_OR_EQUAL',
      type: 'vectorized',
      description: 'Greater than or equal (SIMD optimized)',
      acceptsArray: false
    },
    LESS_THAN: {
      label: 'LESS_THAN',
      type: 'vectorized',
      description: 'Less than (SIMD optimized)',
      acceptsArray: false
    },
    LESS_THAN_OR_EQUAL: {
      label: 'LESS_THAN_OR_EQUAL',
      type: 'vectorized',
      description: 'Less than or equal (SIMD optimized)',
      acceptsArray: false
    }
  } as const;

  type OperatorKey = keyof typeof OPERATORS;

  // Auto-detect condition type based on operator
  const getConditionType = (operator: string): 'base' | 'vectorized' => {
    return OPERATORS[operator as OperatorKey]?.type || 'base';
  };

  // Check if operator accepts array values
  const operatorAcceptsArray = (operator: string): boolean => {
    return OPERATORS[operator as OperatorKey]?.acceptsArray || false;
  };

  // Add new condition
  const addCondition = () => {
    setConditions([
      ...conditions,
      {
        id: Date.now().toString(),
        field: '',
        operator: 'EQUAL_TO',
        value: '',
        type: 'auto'
      }
    ]);
  };

  // Remove condition
  const removeCondition = (id: string) => {
    if (conditions.length > 1) {
      setConditions(conditions.filter(c => c.id !== id));
    } else {
      toast.error('Rule must have at least one condition');
    }
  };

  // Update condition
  const updateCondition = (id: string, field: keyof Condition, value: any) => {
    setConditions(
      conditions.map(c => {
        if (c.id === id) {
          const updated = { ...c, [field]: value };
          // Auto-detect type when operator changes
          if (field === 'operator') {
            updated.type = 'auto';
          }
          return updated;
        }
        return c;
      })
    );
  };

  // Convert CSV string to array for array operators
  const parseValue = (value: string, operator: string): any => {
    if (operatorAcceptsArray(operator)) {
      // Split by comma, trim whitespace, filter empty strings
      return value
        .split(',')
        .map(v => v.trim())
        .filter(v => v.length > 0);
    }

    // Try to parse as number if it looks numeric
    const trimmed = value.trim();
    if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
      return parseFloat(trimmed);
    }

    return trimmed;
  };

  // Validate form
  const validate = (): boolean => {
    const errors: string[] = [];

    if (!family.trim()) {
      errors.push('Rule family is required');
    }

    if (!ruleName.trim()) {
      errors.push('Rule name is required');
    } else if (!/^[a-zA-Z0-9_-]+$/.test(ruleName)) {
      errors.push('Rule name must contain only letters, numbers, underscores, and hyphens');
    }

    if (!description.trim()) {
      errors.push('Description is required');
    }

    const priorityNum = parseInt(priority);
    if (isNaN(priorityNum) || priorityNum < 0 || priorityNum > 1000) {
      errors.push('Priority must be between 0 and 1000');
    }

    conditions.forEach((condition, idx) => {
      if (!condition.field.trim()) {
        errors.push(`Condition ${idx + 1}: Field name is required`);
      }
      if (!condition.value.trim()) {
        errors.push(`Condition ${idx + 1}: Value is required`);
      }
      if (operatorAcceptsArray(condition.operator)) {
        const arr = parseValue(condition.value, condition.operator);
        if (!Array.isArray(arr) || arr.length === 0) {
          errors.push(`Condition ${idx + 1}: Enter comma-separated values for ${condition.operator}`);
        }
      }
    });

    setValidationErrors(errors);
    return errors.length === 0;
  };

  // Build API payload
  const buildPayload = () => {
    return {
      rule_code: ruleCode,
      description: description,
      conditions: conditions.map(c => ({
        field: c.field.toUpperCase(),
        operator: c.operator,
        value: parseValue(c.value, c.operator)
      })),
      priority: parseInt(priority),
      enabled: enabled,
      tags: tags
        .split(',')
        .map(t => t.trim())
        .filter(t => t.length > 0)
    };
  };

  // Save rule
  const handleSave = async () => {
    if (!validate()) {
      toast.error('Please fix validation errors');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = buildPayload();
      const response = await axios.post('/api/v1/rules', payload, {
        headers: { 'Content-Type': 'application/json' }
      });

      toast.success(`Rule "${ruleCode}" created successfully!`);
      console.log('Created rule:', response.data);

      // Invalidate rules query to trigger refetch in RuleListView
      queryClient.invalidateQueries({ queryKey: ['rules'] });

      // Reset form
      setFamily('');
      setRuleName('');
      setDescription('');
      setPriority('100');
      setTags('');
      setConditions([
        {
          id: Date.now().toString(),
          field: '',
          operator: 'EQUAL_TO',
          value: '',
          type: 'auto'
        }
      ]);
      setValidationErrors([]);

      // Redirect to rules list
      if (onRuleCreated) {
        onRuleCreated();
      }
    } catch (error: any) {
      console.error('Error creating rule:', error);
      console.error('Error response:', error.response?.data);
      const errorMsg =
        error.response?.data?.error ||
        error.response?.data?.message ||
        error.message ||
        'Failed to create rule';
      toast.error(errorMsg);

      if (error.response?.data?.errors) {
        setValidationErrors(error.response.data.errors);
      } else if (error.response?.data?.message) {
        setValidationErrors([error.response.data.message]);
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // Save rule as draft (disabled state)
  const handleSaveAsDraft = async () => {
    if (!validate()) {
      toast.error('Please fix validation errors before saving as draft');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = buildPayload();
      // Override enabled status to false for draft
      payload.enabled = false;

      console.log('Sending draft payload:', JSON.stringify(payload, null, 2));

      const response = await axios.post(
        '/api/v1/rules',
        payload,
        {
          headers: { 'Content-Type': 'application/json' }
        }
      );

      console.log('Draft save response:', response.data);
      toast.success(`Rule "${ruleCode}" saved as draft successfully!`);

      // Invalidate rules query to trigger refetch in RuleListView
      queryClient.invalidateQueries({ queryKey: ['rules'] });

      // Reset form
      setFamily('');
      setRuleName('');
      setDescription('');
      setPriority('100');
      setEnabled(true);
      setTags('');
      setConditions([
        {
          id: Date.now().toString(),
          field: '',
          operator: 'EQUAL_TO',
          value: '',
          type: 'auto'
        }
      ]);
      setValidationErrors([]);

      // Redirect to rules list
      if (onRuleCreated) {
        onRuleCreated();
      }
    } catch (error: any) {
      console.error('Draft save error:', error);
      console.error('Error response:', error.response?.data);
      const errorMsg =
        error.response?.data?.message ||
        error.response?.data?.error ||
        'Failed to save rule as draft';
      toast.error(errorMsg);

      if (error.response?.data?.errors) {
        setValidationErrors(error.response.data.errors);
      } else if (error.response?.data?.message) {
        setValidationErrors([error.response.data.message]);
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // Validate rule (dry run)
  const handleValidate = async () => {
    if (!validate()) {
      toast.error('Please fix validation errors');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = buildPayload();
      const response = await axios.post(
        '/api/v1/rules/validate',
        payload,
        {
          headers: { 'Content-Type': 'application/json' }
        }
      );

      if (response.data.valid) {
        toast.success('✓ Rule is valid and ready to save');
      } else {
        toast.error('Validation failed');
        setValidationErrors(response.data.errors || []);
      }
    } catch (error: any) {
      console.error('Validation error:', error);
      toast.error('Validation request failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Helper function to check if a specific field has an error
  const hasFieldError = (fieldName: string): boolean => {
    return validationErrors.some(e => e.toLowerCase().includes(fieldName.toLowerCase()));
  };

  // Helper function to get field-specific error message
  const getFieldError = (fieldName: string): string | null => {
    const error = validationErrors.find(e => e.toLowerCase().includes(fieldName.toLowerCase()));
    return error || null;
  };

  // Calculate optimization metrics (only for filled conditions)
  const filledConditions = conditions.filter(c => c.field && c.value);
  const optimizationMetrics = {
    vectorizedCount: filledConditions.filter(
      c => getConditionType(c.operator) === 'vectorized'
    ).length,
    baseCount: filledConditions.filter(
      c => getConditionType(c.operator) === 'base'
    ).length,
    estimatedLatency: filledConditions.length > 0 ? 0.2 + filledConditions.length * 0.05 : 0
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Visual Rule Builder</CardTitle>
          <CardDescription>
            Create business rules with automatic optimization detection
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Validation Errors */}
          {validationErrors.length > 0 && (
            <Alert className="bg-red-50 border-red-200">
              <AlertTriangle className="size-4 text-red-600" />
              <AlertDescription>
                <p className="font-semibold text-red-800 mb-2">
                  Please fix the following errors:
                </p>
                <ul className="list-disc list-inside space-y-1 text-sm text-red-700">
                  {validationErrors.map((error, idx) => (
                    <li key={idx}>{error}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}

          {/* Basic Info */}
          <div className="space-y-4">
            {/* Family and Rule Name in Grid */}
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="family">
                  Rule Family <span className="text-red-500">*</span>
                </Label>
                <Select
                  value={family}
                  onValueChange={value => {
                    setFamily(value);
                    // Clear validation errors for this field when user selects
                    if (hasFieldError('family')) {
                      setValidationErrors(prev => prev.filter(e => !e.toLowerCase().includes('family')));
                    }
                  }}
                >
                  <SelectTrigger
                    id="family"
                    className={hasFieldError('family') ? 'border-red-300 focus:border-red-400' : ''}
                  >
                    <SelectValue placeholder="Select family..." />
                  </SelectTrigger>
                  <SelectContent>
                    {RULE_FAMILIES.map(fam => (
                      <SelectItem key={fam.value} value={fam.value}>
                        {fam.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {hasFieldError('family') ? (
                  <p className="text-xs text-red-600 flex items-center gap-1">
                    <AlertTriangle className="size-3" />
                    {getFieldError('family')}
                  </p>
                ) : (
                  <p className="text-xs text-gray-500">
                    Categorizes the rule
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="rule-name">
                  Rule Name <span className="text-red-500">*</span>
                </Label>
                <Input
                  id="rule-name"
                  value={ruleName}
                  onChange={e => {
                    setRuleName(e.target.value);
                    // Clear validation errors for this field when user starts typing
                    if (hasFieldError('rule name')) {
                      setValidationErrors(prev => prev.filter(e => !e.toLowerCase().includes('rule name')));
                    }
                  }}
                  placeholder="e.g., high_value"
                  className={hasFieldError('rule name') ? 'border-red-300 focus:border-red-400' : ''}
                />
                {hasFieldError('rule name') ? (
                  <p className="text-xs text-red-600 flex items-center gap-1">
                    <AlertTriangle className="size-3" />
                    {getFieldError('rule name')}
                  </p>
                ) : (
                  <p className="text-xs text-gray-500">
                    Specific rule identifier
                  </p>
                )}
              </div>
            </div>

            {/* Display computed rule code */}
            {ruleCode && (
              <div className="p-3 bg-blue-50 border border-blue-200 rounded-md">
                <p className="text-sm text-blue-900">
                  <strong>Rule Code:</strong> <code className="bg-white px-2 py-1 rounded font-mono">{ruleCode}</code>
                </p>
              </div>
            )}

            <div className="space-y-2">
              <Label htmlFor="description">
                Description <span className="text-red-500">*</span>
              </Label>
              <Textarea
                id="description"
                value={description}
                onChange={e => {
                  setDescription(e.target.value);
                  // Clear validation errors for this field when user starts typing
                  if (hasFieldError('description')) {
                    setValidationErrors(prev => prev.filter(e => !e.toLowerCase().includes('description')));
                  }
                }}
                placeholder="Describe what this rule does..."
                rows={2}
                className={hasFieldError('description') ? 'border-red-300 focus:border-red-400' : ''}
              />
              {hasFieldError('description') && (
                <p className="text-xs text-red-600 flex items-center gap-1">
                  <AlertTriangle className="size-3" />
                  {getFieldError('description')}
                </p>
              )}
            </div>

            <div className="grid grid-cols-3 gap-4">
              <div className="space-y-2">
                <Label htmlFor="priority">
                  Priority <span className="text-red-500">*</span>
                </Label>
                <Input
                  id="priority"
                  type="number"
                  value={priority}
                  onChange={e => {
                    setPriority(e.target.value);
                    // Clear validation errors for this field when user starts typing
                    if (hasFieldError('priority')) {
                      setValidationErrors(prev => prev.filter(e => !e.toLowerCase().includes('priority')));
                    }
                  }}
                  min="0"
                  max="1000"
                  className={hasFieldError('priority') ? 'border-red-300 focus:border-red-400' : ''}
                />
                {hasFieldError('priority') ? (
                  <p className="text-xs text-red-600 flex items-center gap-1">
                    <AlertTriangle className="size-3" />
                    {getFieldError('priority')}
                  </p>
                ) : (
                  <p className="text-xs text-gray-500">0-1000 (higher = evaluated first)</p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="tags">Tags (optional)</Label>
                <Input
                  id="tags"
                  value={tags}
                  onChange={e => setTags(e.target.value)}
                  placeholder="tag1, tag2, tag3"
                />
                <p className="text-xs text-gray-500">Comma-separated</p>
              </div>
            </div>
          </div>

          <Separator />

          {/* Conditions */}
          <div className="space-y-4">
            <div>
              <h3 className="font-semibold flex items-center gap-2">
                Conditions
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger>
                      <Info className="size-4 text-gray-400" />
                    </TooltipTrigger>
                    <TooltipContent className="max-w-xs">
                      <p className="text-sm">
                        All conditions must be true for the rule to match (AND logic).
                        Condition type is auto-detected based on operator.
                      </p>
                    </TooltipContent>
                  </Tooltip>
                </TooltipProvider>
              </h3>
              <p className="text-sm text-gray-600 mt-1">
                All conditions must be true (AND logic)
              </p>
            </div>

            <div className="space-y-3">
              {conditions.map((condition, idx) => {
                const detectedType = getConditionType(condition.operator);
                const acceptsArray = operatorAcceptsArray(condition.operator);
                const operatorInfo = OPERATORS[condition.operator as OperatorKey];

                return (
                  <div key={condition.id} className="flex items-start gap-3">
                    <div className="flex items-center justify-center size-8 rounded-full bg-blue-100 text-blue-600 font-semibold shrink-0 mt-2">
                      {idx === 0 ? 'IF' : 'AND'}
                    </div>

                    <Card className="flex-1">
                      <CardContent className="p-4">
                        <div className="grid grid-cols-[1fr,auto,1fr,auto] gap-3 items-start">
                          {/* Field Name (Custom Input) */}
                          <div>
                            <Input
                              value={condition.field}
                              onChange={e => {
                                updateCondition(condition.id, 'field', e.target.value);
                                // Clear validation errors for this field when user starts typing
                                const errorPrefix = `Condition ${idx + 1}: Field`;
                                if (validationErrors.some(e => e.includes(errorPrefix))) {
                                  setValidationErrors(prev => prev.filter(e => !e.includes(errorPrefix)));
                                }
                              }}
                              placeholder="Field name (e.g., CUSTOMER_TIER)"
                              className={
                                validationErrors.some(e => e.includes(`Condition ${idx + 1}: Field`))
                                  ? 'border-red-300 focus:border-red-400'
                                  : ''
                              }
                            />
                            {validationErrors.some(e => e.includes(`Condition ${idx + 1}: Field`)) ? (
                              <p className="text-xs text-red-600 flex items-center gap-1 mt-1">
                                <AlertTriangle className="size-3" />
                                Field name is required
                              </p>
                            ) : (
                              <p className="text-xs text-gray-500 mt-1">
                                Custom attribute name
                              </p>
                            )}
                          </div>

                          {/* Operator */}
                          <div>
                            <Select
                              value={condition.operator}
                              onValueChange={value =>
                                updateCondition(condition.id, 'operator', value)
                              }
                            >
                              <SelectTrigger className="w-48">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <div className="px-2 py-1 text-xs font-semibold text-gray-500 uppercase">
                                  String/Categorical (Cached)
                                </div>
                                {Object.entries(OPERATORS)
                                  .filter(([_, info]) => info.type === 'base')
                                  .map(([key, info]) => (
                                    <SelectItem key={key} value={key}>
                                      {info.label}
                                    </SelectItem>
                                  ))}
                                <Separator className="my-1" />
                                <div className="px-2 py-1 text-xs font-semibold text-gray-500 uppercase">
                                  Numeric (SIMD Optimized)
                                </div>
                                {Object.entries(OPERATORS)
                                  .filter(([_, info]) => info.type === 'vectorized')
                                  .map(([key, info]) => (
                                    <SelectItem key={key} value={key}>
                                      {info.label}
                                    </SelectItem>
                                  ))}
                              </SelectContent>
                            </Select>
                            {operatorInfo && (
                              <p className="text-xs text-gray-500 mt-1">
                                {operatorInfo.description}
                              </p>
                            )}
                          </div>

                          {/* Value */}
                          <div>
                            <Input
                              value={condition.value}
                              onChange={e => {
                                updateCondition(condition.id, 'value', e.target.value);
                                // Clear validation errors for this field when user starts typing
                                const errorPrefix = `Condition ${idx + 1}:`;
                                if (validationErrors.some(e => e.includes(errorPrefix) && e.toLowerCase().includes('value'))) {
                                  setValidationErrors(prev => prev.filter(e => !(e.includes(errorPrefix) && e.toLowerCase().includes('value'))));
                                }
                              }}
                              placeholder={
                                acceptsArray
                                  ? 'Enter CSV: value1, value2, value3'
                                  : 'Enter value'
                              }
                              className={
                                validationErrors.some(e => e.includes(`Condition ${idx + 1}:`) && (e.toLowerCase().includes('value') || e.toLowerCase().includes('comma')))
                                  ? 'border-red-300 focus:border-red-400'
                                  : ''
                              }
                            />
                            {validationErrors.some(e => e.includes(`Condition ${idx + 1}:`) && (e.toLowerCase().includes('value') || e.toLowerCase().includes('comma'))) ? (
                              <p className="text-xs text-red-600 flex items-center gap-1 mt-1">
                                <AlertTriangle className="size-3" />
                                {validationErrors.find(e => e.includes(`Condition ${idx + 1}:`) && (e.toLowerCase().includes('value') || e.toLowerCase().includes('comma')))}
                              </p>
                            ) : acceptsArray ? (
                              <p className="text-xs text-gray-500 mt-1">
                                Enter comma-separated values
                              </p>
                            ) : null}
                          </div>

                          {/* Delete */}
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => removeCondition(condition.id)}
                            className="mt-0"
                            disabled={conditions.length === 1}
                          >
                            <X className="size-4" />
                          </Button>
                        </div>

                        {/* Type Badge with Tooltip */}
                        <div className="mt-3 flex items-center gap-2">
                          <TooltipProvider>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Badge
                                  variant={detectedType === 'vectorized' ? 'default' : 'secondary'}
                                  className="cursor-help"
                                >
                                  {detectedType === 'vectorized' ? (
                                    <>
                                      <Zap className="size-3 mr-1" />
                                      Vectorized (SIMD)
                                    </>
                                  ) : (
                                    'Base (Cached)'
                                  )}
                                </Badge>
                              </TooltipTrigger>
                              <TooltipContent className="max-w-xs">
                                {detectedType === 'vectorized' ? (
                                  <div className="space-y-1">
                                    <p className="font-semibold">Vectorized (SIMD)</p>
                                    <p className="text-xs">
                                      Numeric comparisons using SIMD instructions for parallel
                                      processing. Best for numeric fields like amounts, counts,
                                      ratios. ~10-100x faster than regular comparisons.
                                    </p>
                                  </div>
                                ) : (
                                  <div className="space-y-1">
                                    <p className="font-semibold">Base (Cached)</p>
                                    <p className="text-xs">
                                      String/categorical comparisons with dictionary encoding and
                                      caching. Best for fields with limited distinct values like
                                      status, tier, category. High cache hit rate.
                                    </p>
                                  </div>
                                )}
                              </TooltipContent>
                            </Tooltip>
                          </TooltipProvider>
                          <span className="text-xs text-gray-500">
                            Auto-detected based on operator
                          </span>
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                );
              })}
            </div>

            {/* Add Condition Button */}
            <div className="flex justify-start">
              <Button size="sm" variant="outline" onClick={addCondition}>
                <Plus className="size-4 mr-2" />
                Add Condition
              </Button>
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
                  {optimizationMetrics.vectorizedCount > 0 ? (
                    <CheckCircle2 className="size-4 text-green-600" />
                  ) : (
                    <AlertCircle className="size-4 text-yellow-600" />
                  )}
                  <span className="text-sm font-medium">SIMD Optimization</span>
                </div>
                <p className="text-sm text-gray-700">
                  {optimizationMetrics.vectorizedCount > 0 ? (
                    <>✓ {optimizationMetrics.vectorizedCount} vectorized condition(s)</>
                  ) : (
                    <>! No SIMD conditions</>
                  )}
                </p>
              </div>

              <div>
                <div className="flex items-center gap-2 mb-2">
                  {optimizationMetrics.baseCount > 0 ? (
                    <CheckCircle2 className="size-4 text-green-600" />
                  ) : (
                    <AlertCircle className="size-4 text-yellow-600" />
                  )}
                  <span className="text-sm font-medium">Cached Conditions</span>
                </div>
                <p className="text-sm text-gray-700">
                  {optimizationMetrics.baseCount > 0 ? (
                    <>✓ {optimizationMetrics.baseCount} cached condition(s)</>
                  ) : (
                    <>! No cached conditions</>
                  )}
                </p>
              </div>

              <div>
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle2 className="size-4 text-green-600" />
                  <span className="text-sm font-medium">Estimated Latency</span>
                </div>
                <p className="text-sm text-gray-700">
                  ~{optimizationMetrics.estimatedLatency.toFixed(2)}ms per evaluation
                </p>
              </div>
            </div>

            {optimizationMetrics.vectorizedCount > 0 && optimizationMetrics.baseCount > 0 && (
              <div className="mt-4 p-3 bg-white rounded border border-blue-200">
                <p className="text-sm text-gray-700">
                  <strong className="text-blue-900">Hybrid optimization:</strong> ✓ Mix of
                  cached and SIMD conditions for optimal performance
                </p>
              </div>
            )}
          </div>

          {/* Action Buttons */}
          <div className="flex gap-3 pt-4">
            <Button
              className="flex-1"
              size="lg"
              onClick={handleSave}
              disabled={isSubmitting}
            >
              {isSubmitting ? (
                <>
                  <Loader2 className="size-4 mr-2 animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <CheckCircle2 className="size-4 mr-2" />
                  Save & Activate
                </>
              )}
            </Button>
            <Button
              className="flex-1"
              variant="outline"
              size="lg"
              onClick={handleSaveAsDraft}
              disabled={isSubmitting}
            >
              {isSubmitting ? (
                <>
                  <Loader2 className="size-4 mr-2 animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <Save className="size-4 mr-2" />
                  Save as Draft
                </>
              )}
            </Button>
            <Button
              variant="outline"
              size="lg"
              onClick={handleValidate}
              disabled={isSubmitting}
            >
              <Play className="size-4 mr-2" />
              Validate
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Generated Code Preview - Always Visible */}
      <Card>
        <CardHeader>
          <CardTitle>Generated JSON Preview</CardTitle>
          <CardDescription>API payload that will be sent</CardDescription>
        </CardHeader>
        <CardContent>
          <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg text-sm font-mono overflow-x-auto">
            {JSON.stringify(buildPayload(), null, 2)}
          </pre>
        </CardContent>
      </Card>
    </div>
  );
}

export default RuleBuilder;
