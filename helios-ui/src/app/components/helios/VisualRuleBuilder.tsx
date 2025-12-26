/**
 * Visual Rule Builder Component
 * Intuitive interface for creating and editing rules without writing code
 */

import { useState, useEffect } from 'react';
import { Card } from '../ui/card';
import { Badge } from '../ui/badge';
import { useRuleDetails, useCreateRule, useUpdateRule } from '../../../hooks/useRules';

interface Toast {
  id: number;
  message: string;
  type: 'success' | 'error';
}

interface Condition {
  id: string;
  field: string;
  operator: string;
  value: string;
  type: 'base' | 'vectorized';
}

interface RuleForm {
  ruleCode: string;
  description: string;
  priority: number;
  enabled: boolean;
  tags: string[];
}

interface VisualRuleBuilderProps {
  editingRuleCode?: string | null;
  onRuleCreated?: () => void;
  onCancel?: () => void;
}

export const VisualRuleBuilder = ({ editingRuleCode, onRuleCreated, onCancel }: VisualRuleBuilderProps) => {
  const [ruleForm, setRuleForm] = useState<RuleForm>({
    ruleCode: '',
    description: '',
    priority: 100,
    enabled: true,
    tags: [],
  });

  const [conditions, setConditions] = useState<Condition[]>([
    { id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }
  ]);

  const [tagInput, setTagInput] = useState('');
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [fieldErrors, setFieldErrors] = useState<{
    ruleCode?: string;
    conditions?: { [id: string]: { field?: string; operator?: string; value?: string } };
  }>({});

  // Fetch rule details when editing
  const { data: ruleDetails } = useRuleDetails(editingRuleCode || '', {
    enabled: !!editingRuleCode,
  });

  // Mutation hooks for create and update
  const createRuleMutation = useCreateRule({
    onSuccess: (data) => {
      showToast(`Rule created successfully! Rule Code: ${data.ruleCode}`, 'success');
      // Reset form
      setRuleForm({
        ruleCode: '',
        description: '',
        priority: 100,
        enabled: true,
        tags: [],
      });
      setConditions([{ id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }]);

      // Call the callback to redirect to rules tab
      if (onRuleCreated) {
        setTimeout(() => onRuleCreated(), 1500); // Small delay to show success toast
      }
    },
    onError: (error: any) => {
      const errorMessage = error.message || error.error || 'Unknown error';
      showToast(`Failed to create rule: ${errorMessage}`, 'error');
    },
  });

  const updateRuleMutation = useUpdateRule({
    onSuccess: (data) => {
      showToast(`Rule updated successfully! Rule Code: ${data.ruleCode}`, 'success');
      // Reset form
      setRuleForm({
        ruleCode: '',
        description: '',
        priority: 100,
        enabled: true,
        tags: [],
      });
      setConditions([{ id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }]);

      // Call the callback to redirect to rules tab
      if (onRuleCreated) {
        setTimeout(() => onRuleCreated(), 1500); // Small delay to show success toast
      }
    },
    onError: (error: any) => {
      const errorMessage = error.message || error.error || 'Unknown error';
      showToast(`Failed to update rule: ${errorMessage}`, 'error');
    },
  });

  // Load rule data into form when editing
  useEffect(() => {
    if (editingRuleCode && ruleDetails) {
      const metadata = ruleDetails.rule_metadata;

      setRuleForm({
        ruleCode: metadata.rule_code,
        description: metadata.description || '',
        priority: metadata.priority || 100,
        enabled: metadata.enabled !== undefined ? metadata.enabled : true,
        tags: Array.isArray(metadata.tags) ? metadata.tags : [],
      });

      // Convert backend conditions to UI format
      const uiConditions = metadata.conditions.map((cond, idx) => {
        let value = cond.value;

        // Convert array values to comma-separated string for IS_ANY_OF/IS_NONE_OF
        if (Array.isArray(value)) {
          value = value.join(', ');
        } else if (typeof value === 'number') {
          value = String(value);
        }

        return {
          id: String(idx + 1),
          field: cond.field,
          operator: cond.operator,
          value: String(value),
          type: 'base' as const, // Default to base, can be enhanced later
        };
      });

      setConditions(uiConditions.length > 0 ? uiConditions : [
        { id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }
      ]);
    } else if (!editingRuleCode) {
      // Reset form when not editing
      setRuleForm({
        ruleCode: '',
        description: '',
        priority: 100,
        enabled: true,
        tags: [],
      });
      setConditions([{ id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }]);
    }
  }, [editingRuleCode, ruleDetails]);

  const showToast = (message: string, type: 'success' | 'error') => {
    const id = Date.now();
    setToasts(prev => [...prev, { id, message, type }]);

    // Auto-remove after 30 seconds
    setTimeout(() => {
      setToasts(prev => prev.filter(toast => toast.id !== id));
    }, 30000);
  };

  const removeToast = (id: number) => {
    setToasts(prev => prev.filter(toast => toast.id !== id));
  };

  // Available attributes for conditions
  const attributes = [
    'CUSTOMER_SEGMENT',
    'REGION',
    'LIFETIME_VALUE',
    'ACCOUNT_STATUS',
    'DAYS_SINCE_LAST_PURCHASE',
    'PURCHASE_FREQUENCY',
    'TRANSACTION_AMOUNT',
    'DEVICE_VERIFIED',
    'USER_AGE',
    'PREMIUM_MEMBER'
  ];

  // Operators categorized by type
  const operators = {
    base: ['EQUAL_TO', 'NOT_EQUAL_TO', 'IS_ANY_OF', 'IS_NONE_OF'],
    vectorized: ['GREATER_THAN', 'LESS_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN_OR_EQUAL']
  };

  const addCondition = () => {
    setConditions([...conditions, {
      id: Date.now().toString(),
      field: '',
      operator: 'EQUAL_TO',
      value: '',
      type: 'base'
    }]);
  };

  const removeCondition = (id: string) => {
    if (conditions.length > 1) {
      setConditions(conditions.filter(c => c.id !== id));
    }
  };

  const updateCondition = (id: string, field: keyof Condition, value: any) => {
    setConditions(conditions.map(c =>
      c.id === id ? { ...c, [field]: value } : c
    ));
  };

  const addTag = () => {
    if (tagInput.trim() && !ruleForm.tags.includes(tagInput.trim())) {
      setRuleForm({ ...ruleForm, tags: [...ruleForm.tags, tagInput.trim()] });
      setTagInput('');
    }
  };

  const removeTag = (tag: string) => {
    setRuleForm({ ...ruleForm, tags: ruleForm.tags.filter(t => t !== tag) });
  };

  const handleSave = () => {
    // Clear previous errors
    setFieldErrors({});

    // Validate form
    const errors: typeof fieldErrors = {};

    if (!ruleForm.ruleCode.trim()) {
      errors.ruleCode = 'Rule code is required';
    }

    // Validate conditions
    const conditionErrors: { [id: string]: { field?: string; operator?: string; value?: string } } = {};
    conditions.forEach(c => {
      const err: { field?: string; operator?: string; value?: string } = {};
      if (!c.field) err.field = 'Field is required';
      if (!c.value) err.value = 'Value is required';
      if (Object.keys(err).length > 0) {
        conditionErrors[c.id] = err;
      }
    });

    if (Object.keys(conditionErrors).length > 0) {
      errors.conditions = conditionErrors;
    }

    // If there are validation errors, show them and return
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);

      const errorMessages = [];
      if (errors.ruleCode) errorMessages.push(errors.ruleCode);
      if (errors.conditions) {
        const condCount = Object.keys(errors.conditions).length;
        errorMessages.push(`${condCount} condition${condCount > 1 ? 's have' : ' has'} validation errors`);
      }

      showToast(`Validation failed: ${errorMessages.join(', ')}`, 'error');
      return;
    }

    // Convert to backend format
    const ruleData = {
      rule_code: ruleForm.ruleCode.toUpperCase().replace(/\s+/g, '_'),
      description: ruleForm.description,
      conditions: conditions.map(c => {
        let value: any = c.value;

        // For IS_ANY_OF and IS_NONE_OF, convert comma-separated string to array
        if (c.operator === 'IS_ANY_OF' || c.operator === 'IS_NONE_OF') {
          value = c.value.split(',').map(v => v.trim()).filter(v => v.length > 0);
        } else if (!isNaN(Number(c.value)) && c.value.trim() !== '') {
          // Convert to number if it's a valid number
          value = Number(c.value);
        }

        return {
          field: c.field,
          operator: c.operator,
          value: value
        };
      }),
      priority: ruleForm.priority,
      enabled: ruleForm.enabled,
      tags: ruleForm.tags,
      created_by: 'user',
      labels: {}
    };

    // Use mutation hooks to create or update
    if (editingRuleCode) {
      updateRuleMutation.mutate({ ruleCode: editingRuleCode, ruleData });
    } else {
      createRuleMutation.mutate(ruleData);
    }
  };

  const generateCodePreview = () => {
    return {
      rule_code: ruleForm.ruleCode.toUpperCase().replace(/\s+/g, '_'),
      description: ruleForm.description || 'No description provided',
      priority: ruleForm.priority,
      enabled: ruleForm.enabled,
      tags: ruleForm.tags,
      conditions: conditions.map(c => {
        let value: any = c.value;

        // For IS_ANY_OF and IS_NONE_OF, convert comma-separated string to array
        if (c.operator === 'IS_ANY_OF' || c.operator === 'IS_NONE_OF') {
          value = c.value ? c.value.split(',').map(v => v.trim()).filter(v => v.length > 0) : [];
        } else if (c.value && !isNaN(Number(c.value))) {
          // Convert to number if it's a valid number
          value = Number(c.value);
        }

        return {
          field: c.field,
          operator: c.operator,
          value: value
        };
      })
    };
  };

  // Calculate optimization metrics
  const vectorizableCount = conditions.filter(c => c.type === 'vectorized').length;
  const estimatedLatency = (0.2 + (conditions.length * 0.05)).toFixed(2);

  return (
    <div className="space-y-6 p-6">
      <Card className="p-6">
        <div className="mb-6">
          <h2 className="text-2xl font-bold mb-2">
            {editingRuleCode ? 'Edit Rule' : 'Visual Rule Builder'}
          </h2>
          <p className="text-gray-600">
            {editingRuleCode
              ? `Editing rule: ${editingRuleCode}`
              : 'Create rules using an intuitive form-based interface'}
          </p>
        </div>

        {/* Basic Information */}
        <div className="space-y-4 mb-6">
          <h3 className="text-lg font-semibold">Basic Information</h3>

          <div>
            <label className="block text-sm font-medium mb-2">Rule Code *</label>
            <input
              type="text"
              className={`w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 ${
                fieldErrors.ruleCode
                  ? 'border-red-500 focus:ring-red-500 bg-red-50'
                  : editingRuleCode
                  ? 'border-gray-300 bg-gray-100 cursor-not-allowed'
                  : 'border-gray-300 focus:ring-blue-500'
              }`}
              value={ruleForm.ruleCode}
              onChange={(e) => {
                setRuleForm({ ...ruleForm, ruleCode: e.target.value });
                if (fieldErrors.ruleCode) {
                  setFieldErrors({ ...fieldErrors, ruleCode: undefined });
                }
              }}
              placeholder="e.g., PREMIUM_UPSELL"
              disabled={!!editingRuleCode}
            />
            {fieldErrors.ruleCode ? (
              <p className="text-xs text-red-600 mt-1">{fieldErrors.ruleCode}</p>
            ) : (
              <p className="text-xs text-gray-500 mt-1">
                {editingRuleCode ? 'Rule code cannot be changed when editing' : 'Unique identifier for this rule'}
              </p>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Description</label>
            <textarea
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={ruleForm.description}
              onChange={(e) => setRuleForm({ ...ruleForm, description: e.target.value })}
              placeholder="Describe what this rule does..."
              rows={3}
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium mb-2">Priority</label>
              <input
                type="number"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                value={ruleForm.priority}
                onChange={(e) => setRuleForm({ ...ruleForm, priority: parseInt(e.target.value) || 0 })}
                min="0"
                max="200"
              />
              <p className="text-xs text-gray-500 mt-1">Higher priority rules execute first</p>
            </div>

            <div>
              <label className="block text-sm font-medium mb-2">Status</label>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  className="w-4 h-4"
                  checked={ruleForm.enabled}
                  onChange={(e) => setRuleForm({ ...ruleForm, enabled: e.target.checked })}
                />
                <span className="text-sm">Enabled</span>
              </label>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-2">Tags</label>
            <div className="flex gap-2">
              <input
                type="text"
                className="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && (e.preventDefault(), addTag())}
                placeholder="Add tag..."
              />
              <button
                onClick={addTag}
                className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300"
              >
                Add
              </button>
            </div>
            <div className="flex flex-wrap gap-2 mt-2">
              {ruleForm.tags.map(tag => (
                <Badge key={tag} className="flex items-center gap-1">
                  {tag}
                  <button
                    onClick={() => removeTag(tag)}
                    className="ml-1 text-xs hover:text-red-600"
                  >
                    ×
                  </button>
                </Badge>
              ))}
            </div>
          </div>
        </div>

        <hr className="my-6" />

        {/* Conditions */}
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold">Conditions</h3>
            <button
              onClick={addCondition}
              className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 text-sm"
            >
              + Add Condition
            </button>
          </div>

          <div className="space-y-3">
            {conditions.map((condition, idx) => {
              const conditionError = fieldErrors.conditions?.[condition.id];
              const hasError = !!conditionError;

              return (
                <div key={condition.id} className="flex gap-3">
                  <div className={`flex items-center justify-center w-12 h-12 rounded-full font-semibold shrink-0 ${
                    hasError ? 'bg-red-100 text-red-600' : 'bg-blue-100 text-blue-600'
                  }`}>
                    {idx === 0 ? 'IF' : 'AND'}
                  </div>

                  <Card className={`flex-1 p-4 ${hasError ? 'border-2 border-red-500' : ''}`}>
                    <div className="grid grid-cols-3 gap-3 mb-3">
                      {/* Field */}
                      <div>
                        <label className="block text-xs font-medium mb-1">Attribute</label>
                        <select
                          className={`w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 text-sm ${
                            conditionError?.field
                              ? 'border-red-500 focus:ring-red-500 bg-red-50'
                              : 'border-gray-300 focus:ring-blue-500'
                          }`}
                          value={condition.field}
                          onChange={(e) => {
                            updateCondition(condition.id, 'field', e.target.value);
                            if (conditionError?.field) {
                              const newErrors = { ...fieldErrors };
                              if (newErrors.conditions?.[condition.id]) {
                                delete newErrors.conditions[condition.id].field;
                                if (Object.keys(newErrors.conditions[condition.id]).length === 0) {
                                  delete newErrors.conditions[condition.id];
                                }
                              }
                              setFieldErrors(newErrors);
                            }
                          }}
                        >
                          <option value="">Select attribute</option>
                          {attributes.map(attr => (
                            <option key={attr} value={attr}>{attr}</option>
                          ))}
                        </select>
                        {conditionError?.field && (
                          <p className="text-xs text-red-600 mt-1">{conditionError.field}</p>
                        )}
                      </div>

                      {/* Operator */}
                      <div>
                        <label className="block text-xs font-medium mb-1">Operator</label>
                        <select
                          className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
                          value={condition.operator}
                          onChange={(e) => updateCondition(condition.id, 'operator', e.target.value)}
                        >
                          <optgroup label="Base Operators">
                            {operators.base.map(op => (
                              <option key={op} value={op}>{op}</option>
                            ))}
                          </optgroup>
                          <optgroup label="Vectorized Operators">
                            {operators.vectorized.map(op => (
                              <option key={op} value={op}>{op}</option>
                            ))}
                          </optgroup>
                        </select>
                      </div>

                      {/* Value */}
                      <div>
                        <label className="block text-xs font-medium mb-1">Value</label>
                        <input
                          type="text"
                          className={`w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 text-sm ${
                            conditionError?.value
                              ? 'border-red-500 focus:ring-red-500 bg-red-50'
                              : 'border-gray-300 focus:ring-blue-500'
                          }`}
                          value={condition.value}
                          onChange={(e) => {
                            updateCondition(condition.id, 'value', e.target.value);
                            if (conditionError?.value) {
                              const newErrors = { ...fieldErrors };
                              if (newErrors.conditions?.[condition.id]) {
                                delete newErrors.conditions[condition.id].value;
                                if (Object.keys(newErrors.conditions[condition.id]).length === 0) {
                                  delete newErrors.conditions[condition.id];
                                }
                              }
                              setFieldErrors(newErrors);
                            }
                          }}
                          placeholder={
                            condition.operator === 'IS_ANY_OF' || condition.operator === 'IS_NONE_OF'
                              ? 'US,CA,EU (comma-separated)'
                              : 'Enter value'
                          }
                        />
                        {conditionError?.value && (
                          <p className="text-xs text-red-600 mt-1">{conditionError.value}</p>
                        )}
                      </div>
                    </div>

                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-4 text-sm">
                      <span className="text-gray-600">Type:</span>
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

                    <button
                      onClick={() => removeCondition(condition.id)}
                      className="px-3 py-1 text-sm text-red-600 hover:bg-red-50 rounded"
                      disabled={conditions.length === 1}
                    >
                      Remove
                    </button>
                  </div>
                </Card>
              </div>
              );
            })}
          </div>
        </div>

        <hr className="my-6" />

        {/* Optimization Preview */}
        <div className="bg-gradient-to-r from-blue-50 to-purple-50 rounded-lg p-6 border border-blue-200">
          <h3 className="font-semibold text-blue-900 mb-4 flex items-center gap-2">
            <span className="text-lg">⚡</span>
            Optimization Preview
          </h3>

          <div className="grid grid-cols-3 gap-6">
            <div>
              <div className="flex items-center gap-2 mb-2">
                <span className={`text-sm ${vectorizableCount > 0 ? 'text-green-600' : 'text-yellow-600'}`}>
                  {vectorizableCount > 0 ? '✓' : '⚠'}
                </span>
                <span className="text-sm font-medium">Vectorizable</span>
              </div>
              <p className="text-sm text-gray-700">
                {vectorizableCount > 0 ? (
                  <>✓ {vectorizableCount} condition{vectorizableCount !== 1 ? 's' : ''} will use SIMD</>
                ) : (
                  <>⚠ No SIMD optimization available</>
                )}
              </p>
            </div>

            <div>
              <div className="flex items-center gap-2 mb-2">
                <span className="text-sm text-green-600">✓</span>
                <span className="text-sm font-medium">Estimated Latency</span>
              </div>
              <p className="text-sm text-gray-700">
                ~{estimatedLatency}ms per evaluation
              </p>
            </div>

            <div>
              <div className="flex items-center gap-2 mb-2">
                <span className="text-sm text-blue-600">ℹ</span>
                <span className="text-sm font-medium">Total Conditions</span>
              </div>
              <p className="text-sm text-gray-700">
                {conditions.length} condition{conditions.length !== 1 ? 's' : ''}
              </p>
            </div>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex gap-3 mt-6">
          <button
            onClick={handleSave}
            className="flex-1 px-6 py-3 bg-blue-600 text-white rounded-md hover:bg-blue-700 font-semibold"
          >
            💾 {editingRuleCode ? 'Update Rule' : 'Create Rule'}
          </button>
          {editingRuleCode ? (
            <button
              className="flex-1 px-6 py-3 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300 font-semibold"
              onClick={() => {
                if (onCancel) {
                  onCancel();
                }
              }}
            >
              ✕ Cancel
            </button>
          ) : (
            <button
              className="flex-1 px-6 py-3 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300 font-semibold"
              onClick={() => {
                if (confirm('Reset form and start over?')) {
                  setRuleForm({
                    ruleCode: '',
                    description: '',
                    priority: 100,
                    enabled: true,
                    tags: [],
                  });
                  setConditions([{ id: '1', field: '', operator: 'EQUAL_TO', value: '', type: 'base' }]);
                }
              }}
            >
              🔄 Reset
            </button>
          )}
        </div>
      </Card>

      {/* Generated Code Preview */}
      <Card className="p-6">
        <h3 className="text-lg font-semibold mb-4">Generated Code Preview</h3>
        <p className="text-sm text-gray-600 mb-4">JSON representation that will be sent to the backend</p>
        <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg text-sm font-mono overflow-x-auto">
          {JSON.stringify(generateCodePreview(), null, 2)}
        </pre>
      </Card>

      {/* Toast Notifications */}
      <div className="fixed bottom-4 right-4 z-50 space-y-2">
        {toasts.map(toast => (
          <div
            key={toast.id}
            className={`flex items-center gap-3 px-6 py-4 rounded-lg shadow-lg border-l-4 min-w-[300px] max-w-[500px] animate-slide-in ${
              toast.type === 'success'
                ? 'bg-green-50 border-green-500 text-green-900'
                : 'bg-red-50 border-red-500 text-red-900'
            }`}
          >
            <div className="flex-shrink-0 text-2xl">
              {toast.type === 'success' ? '✓' : '✕'}
            </div>
            <div className="flex-1 text-sm font-medium break-words">
              {toast.message}
            </div>
            <button
              onClick={() => removeToast(toast.id)}
              className="flex-shrink-0 text-gray-500 hover:text-gray-700 text-xl font-bold"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default VisualRuleBuilder;
