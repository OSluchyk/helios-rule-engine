import { useState, useRef } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../ui/dialog';
import { Button } from '../ui/button';
import { Label } from '../ui/label';
import { Badge } from '../ui/badge';
import { Alert, AlertDescription } from '../ui/alert';
import { Progress } from '../ui/progress';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Card, CardContent } from '../ui/card';
import { ScrollArea } from '../ui/scroll-area';
import { Checkbox } from '../ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import {
  Upload,
  FileJson,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  AlertCircle,
  File,
  FileText,
  Zap,
  Loader2,
} from 'lucide-react';
import { toast } from 'sonner';
import * as importApi from '../../../api/import';
import { compileFromDatabase } from '../../../api/compilation';
import type {
  ImportedRuleStatus,
  ConflictResolution,
  RuleMetadata,
} from '../../../types/api';

// Supported operators that match the backend Predicate.Operator enum
const SUPPORTED_OPERATORS = new Set([
  'EQUAL_TO', 'NOT_EQUAL_TO', 'IS_ANY_OF', 'IS_NONE_OF',
  'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN', 'LESS_THAN_OR_EQUAL', 'BETWEEN',
  'CONTAINS', 'STARTS_WITH', 'ENDS_WITH', 'REGEX',
  'IS_NULL', 'IS_NOT_NULL'
]);

// Normalize operator to canonical form (matches rules-ui.ts normalizeOperator)
const normalizeOperator = (operator: string): string => {
  if (!operator) return '';
  const normalized = operator.toUpperCase().trim();

  switch (normalized) {
    case 'EQUALS': case 'EQ': case '==': case '=':
    case 'IS_EQUAL_TO': case 'IS_EQUAL':
      return 'EQUAL_TO';
    case 'NOT_EQUALS': case 'NE': case 'NEQ': case '!=': case '<>':
    case 'IS_NOT_EQUAL_TO': case 'IS_NOT_EQUAL':
      return 'NOT_EQUAL_TO';
    case 'GT': case '>': case 'IS_GREATER_THAN': case 'GREATER':
      return 'GREATER_THAN';
    case 'GTE': case 'GE': case '>=': case 'IS_GREATER_THAN_OR_EQUAL':
      return 'GREATER_THAN_OR_EQUAL';
    case 'LT': case '<': case 'IS_LESS_THAN': case 'LESS':
      return 'LESS_THAN';
    case 'LTE': case 'LE': case '<=': case 'IS_LESS_THAN_OR_EQUAL':
      return 'LESS_THAN_OR_EQUAL';
    case 'IN_RANGE': case 'RANGE':
      return 'BETWEEN';
    case 'IN': case 'ANY_OF': case 'ONE_OF':
      return 'IS_ANY_OF';
    case 'NOT_IN': case 'NONE_OF':
      return 'IS_NONE_OF';
    case 'HAS': case 'INCLUDES':
      return 'CONTAINS';
    case 'BEGINS_WITH': case 'PREFIX':
      return 'STARTS_WITH';
    case 'SUFFIX':
      return 'ENDS_WITH';
    case 'MATCHES': case 'REGEXP': case 'PATTERN':
      return 'REGEX';
    case 'NULL': case 'ISNULL':
      return 'IS_NULL';
    case 'NOT_NULL': case 'NOTNULL': case 'ISNOTNULL':
      return 'IS_NOT_NULL';
    default:
      return normalized;
  }
};

/**
 * Validate a rule for unsupported operators
 * Returns array of error messages for unsupported operators found
 */
const validateRuleOperators = (rule: RuleMetadata): string[] => {
  const errors: string[] = [];

  if (!rule.conditions || rule.conditions.length === 0) {
    return errors;
  }

  for (const condition of rule.conditions) {
    if (!condition.operator) continue;

    const normalizedOp = normalizeOperator(condition.operator);
    if (!SUPPORTED_OPERATORS.has(normalizedOp)) {
      errors.push(
        `Unsupported operator "${condition.operator}" on field "${condition.field}". ` +
        `Supported operators: ${Array.from(SUPPORTED_OPERATORS).join(', ')}`
      );
    }
  }

  return errors;
};

interface RuleImportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function RuleImportDialog({ open, onOpenChange }: RuleImportDialogProps) {
  const queryClient = useQueryClient();
  const [step, setStep] = useState<'upload' | 'validate' | 'configure' | 'importing' | 'compiling' | 'complete'>('upload');
  const [importMethod, setImportMethod] = useState<'file' | 'paste'>('file');
  const [selectedFormat, setSelectedFormat] = useState<'json' | 'jsonl'>('json');
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [pastedContent, setPastedContent] = useState('');
  const [importedRules, setImportedRules] = useState<ImportedRuleStatus[]>([]);
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [conflictResolution, setConflictResolution] = useState<ConflictResolution>('SKIP');
  const [importProgress, setImportProgress] = useState(0);
  const [validationResults, setValidationResults] = useState({ valid: 0, warnings: 0, errors: 0 });
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'VALID' | 'WARNING' | 'ERROR'>('ALL');
  const [compileAfterImport, setCompileAfterImport] = useState(true);
  const [enableDisabledRules, setEnableDisabledRules] = useState(true);
  const [compileResult, setCompileResult] = useState<{ success: boolean; message: string } | null>(null);

  // Validation mutation
  const validateMutation = useMutation({
    mutationFn: importApi.validateImport,
    onSuccess: (response) => {
      setImportedRules(response.rules);
      setValidationResults(response.stats);

      // Auto-select valid and warning rules
      const autoSelect = new Set(
        response.rules
          .filter(r => r.status !== 'ERROR')
          .map(r => r.importId)
      );
      setSelectedRules(autoSelect);

      setStep('configure');
      toast.success('Validation complete');
    },
    onError: (error: any) => {
      toast.error(`Validation failed: ${error.message}`);
      setStep('upload');
    }
  });

  // Import mutation
  const importMutation = useMutation({
    mutationFn: importApi.executeImport,
    onSuccess: async (response) => {
      toast.success(`Successfully imported ${response.imported} rules`);
      if (response.skipped > 0) {
        toast.info(`Skipped ${response.skipped} rules`);
      }
      if (response.failed > 0) {
        toast.error(`Failed to import ${response.failed} rules`);
      }
      // Invalidate rules query to refresh the list
      queryClient.invalidateQueries({ queryKey: ['rules'] });

      // Compile rules if option is enabled
      if (compileAfterImport && response.imported > 0) {
        setStep('compiling');
        try {
          const result = await compileFromDatabase();
          setCompileResult({
            success: result.success,
            message: result.success
              ? `Compiled ${result.compiledRules} rules in ${result.compilationTimeMs}ms`
              : result.message || result.error || 'Compilation failed'
          });
          if (result.success) {
            toast.success('Rules compiled successfully');
          } else {
            toast.warning('Compilation completed with issues');
          }
          // Refresh rules list to get updated compilation status
          queryClient.invalidateQueries({ queryKey: ['rules'] });
        } catch (error) {
          setCompileResult({
            success: false,
            message: error instanceof Error ? error.message : 'Compilation failed'
          });
          toast.error('Failed to compile rules');
        }
      }
      setStep('complete');
    },
    onError: (error: any) => {
      toast.error(`Import failed: ${error.message}`);
      setStep('configure');
    }
  });

  const handleFileSelect = (file: File) => {
    setUploadedFile(file);

    // Detect format from file extension
    const ext = file.name.split('.').pop()?.toLowerCase();
    if (ext === 'jsonl') setSelectedFormat('jsonl');
    else if (ext === 'json') setSelectedFormat('json');

    toast.success(`File selected: ${file.name}`);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);

    const file = e.dataTransfer.files[0];
    if (file) {
      handleFileSelect(file);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const parseAndValidate = async () => {
    setStep('validate');

    try {
      let content = '';

      if (importMethod === 'file' && uploadedFile) {
        content = await uploadedFile.text();
      } else if (importMethod === 'paste') {
        content = pastedContent;
      }

      if (!content.trim()) {
        toast.error('No content to import');
        setStep('upload');
        return;
      }

      // Parse content based on format
      let rules: RuleMetadata[] = [];

      if (selectedFormat === 'json') {
        const parsed = JSON.parse(content);
        rules = Array.isArray(parsed) ? parsed : [parsed];
      } else if (selectedFormat === 'jsonl') {
        // Parse JSONL: each line is a separate JSON object
        const lines = content.split('\n').filter(line => line.trim());
        rules = lines.map((line, index) => {
          try {
            return JSON.parse(line);
          } catch (e) {
            throw new Error(`Invalid JSON on line ${index + 1}: ${(e as Error).message}`);
          }
        });
      }

      // Client-side validation for unsupported operators
      // This provides immediate feedback before hitting the backend
      const rulesWithOperatorErrors: string[] = [];
      for (const rule of rules) {
        const operatorErrors = validateRuleOperators(rule);
        if (operatorErrors.length > 0) {
          rulesWithOperatorErrors.push(
            `Rule "${rule.rule_code || 'unknown'}": ${operatorErrors.join('; ')}`
          );
        }
      }

      if (rulesWithOperatorErrors.length > 0) {
        // Show error toast with details about unsupported operators
        const errorCount = rulesWithOperatorErrors.length;
        toast.error(
          `${errorCount} rule${errorCount > 1 ? 's have' : ' has'} unsupported operators`,
          {
            description: rulesWithOperatorErrors.slice(0, 3).join('\n') +
              (rulesWithOperatorErrors.length > 3 ? `\n...and ${rulesWithOperatorErrors.length - 3} more` : ''),
            duration: 10000
          }
        );
        setStep('upload');
        return;
      }

      // Send to backend for validation
      validateMutation.mutate({
        format: selectedFormat,
        content,
        rules
      });

    } catch (error: any) {
      toast.error(`Failed to parse ${selectedFormat.toUpperCase()}: ${error.message}`);
      setStep('upload');
    }
  };

  const executeImport = async () => {
    setStep('importing');
    setImportProgress(0);

    const selectedRulesList = importedRules
      .filter(r => selectedRules.has(r.importId))
      .map(r => r.rule);

    const selectedIds = Array.from(selectedRules);

    // Simulate progress
    const progressInterval = setInterval(() => {
      setImportProgress(prev => Math.min(prev + 10, 90));
    }, 200);

    try {
      await importMutation.mutateAsync({
        importIds: selectedIds,
        rules: selectedRulesList,
        conflictResolution,
        enableDisabledRules
      });

      setImportProgress(100);
    } finally {
      clearInterval(progressInterval);
    }
  };

  const reset = () => {
    setStep('upload');
    setUploadedFile(null);
    setPastedContent('');
    setImportedRules([]);
    setSelectedRules(new Set());
    setImportProgress(0);
    setValidationResults({ valid: 0, warnings: 0, errors: 0 });
    setCompileAfterImport(true);
    setEnableDisabledRules(true);
    setCompileResult(null);
  };

  const toggleRuleSelection = (importId: string) => {
    const newSelection = new Set(selectedRules);
    if (newSelection.has(importId)) {
      newSelection.delete(importId);
    } else {
      newSelection.add(importId);
    }
    setSelectedRules(newSelection);
  };

  const getFormatIcon = (format: string) => {
    switch (format) {
      case 'json': return <FileJson className="size-4" />;
      case 'jsonl': return <FileText className="size-4" />;
      default: return <File className="size-4" />;
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'VALID': return <CheckCircle2 className="size-4 text-green-600" />;
      case 'WARNING': return <AlertTriangle className="size-4 text-yellow-600" />;
      case 'ERROR': return <XCircle className="size-4 text-red-600" />;
      default: return <AlertCircle className="size-4 text-gray-400" />;
    }
  };

  const getStatusBadgeVariant = (status: string): "default" | "secondary" | "destructive" | "outline" => {
    switch (status) {
      case 'VALID': return 'default';
      case 'WARNING': return 'secondary';
      case 'ERROR': return 'destructive';
      default: return 'outline';
    }
  };

  const getIssueExplanation = (issue: string): { title: string; explanation: string; severity: 'error' | 'warning' } => {
    if (issue.includes('Missing required field')) {
      return {
        title: 'Missing Required Field',
        explanation: 'This field is mandatory for all rules. The rule cannot be imported without it.',
        severity: 'error'
      };
    }
    if (issue.includes('Priority must be between 0 and 1000')) {
      return {
        title: 'Invalid Priority Range',
        explanation: 'Priority must be a number between 0 (lowest) and 1000 (highest). Adjust the priority value to continue.',
        severity: 'error'
      };
    }
    if (issue.includes('Unsupported operator')) {
      return {
        title: 'Unsupported Operator',
        explanation: 'This condition uses an operator that is not supported by the rule engine. Please use one of the supported operators: EQUAL_TO, NOT_EQUAL_TO, IS_ANY_OF, IS_NONE_OF, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, BETWEEN, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, IS_NULL, IS_NOT_NULL.',
        severity: 'error'
      };
    }
    if (issue.includes('No vectorized conditions')) {
      return {
        title: 'Performance Warning',
        explanation: 'This rule uses only non-vectorized operators (GREATER_THAN, LESS_THAN, etc.). For better performance, consider using vectorized operators like EQUAL_TO, IS_ANY_OF where possible.',
        severity: 'warning'
      };
    }
    if (issue.includes('unusually high')) {
      return {
        title: 'High Priority Warning',
        explanation: 'This rule has a very high priority (>900). High priority rules are evaluated first and may affect system performance. Verify this is intentional.',
        severity: 'warning'
      };
    }
    return {
      title: 'Validation Issue',
      explanation: issue,
      severity: issue.includes('Missing') ? 'error' : 'warning'
    };
  };

  // Check if any imported rule has enabled: false
  const hasDisabledRules = importedRules.some(r => r.rule.enabled === false);

  // Get list of disabled rules for display
  const disabledRules = importedRules.filter(r => r.rule.enabled === false);

  const filteredRules = importedRules.filter(rule => {
    if (statusFilter === 'ALL') return true;
    return rule.status === statusFilter;
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Upload className="size-5" />
            Import Rules
          </DialogTitle>
          <DialogDescription>
            Import rules from JSON or JSONL files
          </DialogDescription>
        </DialogHeader>

        {/* Progress Steps */}
        <div className="flex items-center justify-between px-4 py-3 bg-gray-50 rounded-lg">
          <div className={`flex items-center gap-2 ${step === 'upload' ? 'text-blue-600 font-medium' : 'text-green-600'}`}>
            {step !== 'upload' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Upload</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'validate' || step === 'configure' || step === 'importing' || step === 'compiling' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'importing' || step === 'compiling' || step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Validate</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'configure' || step === 'importing' || step === 'compiling' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'importing' || step === 'compiling' || step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Configure</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'importing' || step === 'compiling' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'compiling' || step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Import</span>
          </div>
          {compileAfterImport && (
            <>
              <div className="flex-1 h-px bg-gray-300 mx-2" />
              <div className={`flex items-center gap-2 ${step === 'compiling' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
                {step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
                <span className="text-sm">Compile</span>
              </div>
            </>
          )}
        </div>

        {/* Content Area */}
        <div className="flex-1 overflow-auto">
          {/* Step 1: Upload */}
          {step === 'upload' && (
            <div className="space-y-6 p-4">
              <Tabs value={importMethod} onValueChange={(v) => setImportMethod(v as 'file' | 'paste')}>
                <TabsList className="grid w-full grid-cols-2">
                  <TabsTrigger value="file">Upload File</TabsTrigger>
                  <TabsTrigger value="paste">Paste Content</TabsTrigger>
                </TabsList>

                <TabsContent value="file" className="space-y-4">
                  {/* Format Selection */}
                  <div className="space-y-2">
                    <Label>File Format</Label>
                    <div className="grid grid-cols-2 gap-3">
                      {(['json', 'jsonl'] as const).map(format => (
                        <button
                          key={format}
                          onClick={() => setSelectedFormat(format)}
                          className={`
                            p-4 border-2 rounded-lg transition-all
                            ${selectedFormat === format
                              ? 'border-blue-500 bg-blue-50'
                              : 'border-gray-200 hover:border-gray-300'
                            }
                          `}
                        >
                          <div className="flex flex-col items-center gap-2">
                            {getFormatIcon(format)}
                            <span className="font-medium uppercase text-sm">{format}</span>
                          </div>
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* File Upload Area */}
                  <div
                    onDrop={handleDrop}
                    onDragOver={handleDragOver}
                    onDragLeave={handleDragLeave}
                    className={`
                      border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-all
                      ${isDragging
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-gray-300 hover:border-gray-400'
                      }
                    `}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept=".json,.jsonl"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) handleFileSelect(file);
                      }}
                    />

                    {uploadedFile ? (
                      <div className="space-y-2">
                        <div className="flex items-center justify-center gap-2 text-green-600">
                          <CheckCircle2 className="size-5" />
                          <span className="font-medium">{uploadedFile.name}</span>
                        </div>
                        <p className="text-sm text-gray-500">
                          {(uploadedFile.size / 1024).toFixed(2)} KB
                        </p>
                      </div>
                    ) : (
                      <div className="space-y-2">
                        <Upload className="size-8 mx-auto text-gray-400" />
                        <p className="font-medium">Drop file here or click to browse</p>
                        <p className="text-sm text-gray-500">
                          Supports .json, .jsonl files
                        </p>
                      </div>
                    )}
                  </div>
                </TabsContent>

                <TabsContent value="paste" className="space-y-4">
                  <div className="space-y-2">
                    <Label>File Format</Label>
                    <Select value={selectedFormat} onValueChange={(v) => setSelectedFormat(v as 'json' | 'jsonl')}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="json">JSON</SelectItem>
                        <SelectItem value="jsonl">JSONL</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="space-y-2">
                    <Label>Paste Rule Content</Label>
                    <textarea
                      value={pastedContent}
                      onChange={(e) => setPastedContent(e.target.value)}
                      className="w-full h-64 p-3 border rounded-lg font-mono text-sm"
                      placeholder={`Paste your ${selectedFormat.toUpperCase()} content here...`}
                    />
                  </div>
                </TabsContent>
              </Tabs>
            </div>
          )}

          {/* Step 2: Validating */}
          {step === 'validate' && (
            <div className="flex items-center justify-center py-16">
              <div className="text-center space-y-4">
                <div className="animate-spin size-12 border-4 border-blue-500 border-t-transparent rounded-full mx-auto" />
                <p className="text-lg font-medium">Validating rules...</p>
                <p className="text-sm text-gray-500">Checking for errors and conflicts</p>
              </div>
            </div>
          )}

          {/* Step 3: Configure */}
          {step === 'configure' && (
            <div className="space-y-6 p-4">
              {/* Validation Summary */}
              <div className="grid grid-cols-3 gap-4">
                <Card>
                  <CardContent className="pt-6">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-2xl font-bold text-green-600">{validationResults.valid}</p>
                        <p className="text-sm text-gray-600">Valid</p>
                      </div>
                      <CheckCircle2 className="size-8 text-green-600" />
                    </div>
                  </CardContent>
                </Card>
                <Card>
                  <CardContent className="pt-6">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-2xl font-bold text-yellow-600">{validationResults.warnings}</p>
                        <p className="text-sm text-gray-600">Warnings</p>
                      </div>
                      <AlertTriangle className="size-8 text-yellow-600" />
                    </div>
                  </CardContent>
                </Card>
                <Card>
                  <CardContent className="pt-6">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-2xl font-bold text-red-600">{validationResults.errors}</p>
                        <p className="text-sm text-gray-600">Errors</p>
                      </div>
                      <XCircle className="size-8 text-red-600" />
                    </div>
                  </CardContent>
                </Card>
              </div>

              {/* Conflict Resolution */}
              <div className="space-y-2">
                <Label>Conflict Resolution Strategy</Label>
                <Select value={conflictResolution} onValueChange={(v) => setConflictResolution(v as ConflictResolution)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="SKIP">Skip - Keep existing rules (safest)</SelectItem>
                    <SelectItem value="OVERWRITE">Overwrite - Replace existing rules</SelectItem>
                    <SelectItem value="RENAME">Rename - Auto-rename imported rules</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* Option to enable disabled rules */}
              {hasDisabledRules && (
                <div className="flex items-start space-x-3 p-4 bg-orange-50 border border-orange-200 rounded-lg">
                  <Checkbox
                    id="enable-disabled-rules"
                    checked={enableDisabledRules}
                    onCheckedChange={(checked) => setEnableDisabledRules(checked === true)}
                  />
                  <div className="flex-1">
                    <label
                      htmlFor="enable-disabled-rules"
                      className="font-medium cursor-pointer flex items-center gap-2"
                    >
                      <AlertTriangle className="size-4 text-orange-600" />
                      Enable {disabledRules.length} disabled rule{disabledRules.length > 1 ? 's' : ''} during import
                    </label>
                    <p className="text-sm text-gray-600 mt-1">
                      {disabledRules.length} rule{disabledRules.length > 1 ? 's have' : ' has'} <code className="bg-orange-100 px-1 rounded text-xs">enabled: false</code> in the import file.
                      {enableDisabledRules
                        ? ' These rules will be automatically enabled so they can be compiled and evaluated.'
                        : ' These rules will remain disabled after import and won\'t be evaluated until manually activated.'}
                    </p>
                  </div>
                </div>
              )}

              {/* Compile After Import Option */}
              <div className="flex items-center space-x-3 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                <Checkbox
                  id="compile-after-import"
                  checked={compileAfterImport}
                  onCheckedChange={(checked) => setCompileAfterImport(checked === true)}
                />
                <div className="flex-1">
                  <label
                    htmlFor="compile-after-import"
                    className="font-medium cursor-pointer flex items-center gap-2"
                  >
                    <Zap className="size-4 text-blue-600" />
                    Compile rules after import
                  </label>
                  <p className="text-sm text-gray-600 mt-1">
                    Automatically compile all rules so they're immediately available for evaluation.
                    If disabled, rules will be saved but won't be active until manually compiled.
                  </p>
                </div>
              </div>

              {/* Filter and Selection Controls */}
              <div className="space-y-3">
                <div className="flex items-center justify-between gap-4">
                  <div className="flex-1">
                    <Label>Filter by Status</Label>
                    <div className="flex gap-2 mt-2">
                      <Button
                        size="sm"
                        variant={statusFilter === 'ALL' ? 'default' : 'outline'}
                        onClick={() => setStatusFilter('ALL')}
                      >
                        All ({importedRules.length})
                      </Button>
                      <Button
                        size="sm"
                        variant={statusFilter === 'VALID' ? 'default' : 'outline'}
                        onClick={() => setStatusFilter('VALID')}
                        className={statusFilter === 'VALID' ? 'bg-green-600 hover:bg-green-700' : ''}
                      >
                        <CheckCircle2 className="size-3 mr-1" />
                        Valid ({validationResults.valid})
                      </Button>
                      <Button
                        size="sm"
                        variant={statusFilter === 'WARNING' ? 'default' : 'outline'}
                        onClick={() => setStatusFilter('WARNING')}
                        className={statusFilter === 'WARNING' ? 'bg-yellow-600 hover:bg-yellow-700' : ''}
                      >
                        <AlertTriangle className="size-3 mr-1" />
                        Warnings ({validationResults.warnings})
                      </Button>
                      <Button
                        size="sm"
                        variant={statusFilter === 'ERROR' ? 'default' : 'outline'}
                        onClick={() => setStatusFilter('ERROR')}
                        className={statusFilter === 'ERROR' ? 'bg-red-600 hover:bg-red-700' : ''}
                      >
                        <XCircle className="size-3 mr-1" />
                        Errors ({validationResults.errors})
                      </Button>
                    </div>
                  </div>
                  <div className="text-right">
                    <Label>Selected</Label>
                    <p className="text-sm text-gray-500 mt-2">
                      {selectedRules.size} of {importedRules.length}
                    </p>
                  </div>
                </div>

                {/* Quick Actions */}
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      const validIds = filteredRules
                        .filter(r => r.status !== 'ERROR')
                        .map(r => r.importId);
                      setSelectedRules(new Set(validIds));
                    }}
                  >
                    Select All Valid
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setSelectedRules(new Set())}
                  >
                    Deselect All
                  </Button>
                </div>
              </div>

              {/* Rules List */}
              <div className="space-y-2">
                <ScrollArea className="h-96 border rounded-lg">
                  <div className="p-4 space-y-3">
                    {filteredRules.length === 0 ? (
                      <div className="text-center py-8 text-gray-500">
                        No rules match the selected filter
                      </div>
                    ) : (
                      filteredRules.map((rule) => (
                      <Card key={rule.importId} className={`
                        ${rule.status === 'ERROR' ? 'opacity-50' : ''}
                      `}>
                        <CardContent className="pt-4">
                          <div className="flex items-start gap-3">
                            <Checkbox
                              checked={selectedRules.has(rule.importId)}
                              onCheckedChange={() => toggleRuleSelection(rule.importId)}
                              disabled={rule.status === 'ERROR'}
                            />

                            <div className="flex-1 space-y-2">
                              <div className="flex items-start justify-between gap-4">
                                <div>
                                  <div className="flex items-center gap-2">
                                    {getStatusIcon(rule.status)}
                                    <span className="font-medium">{rule.rule.rule_code}</span>
                                    <Badge variant={getStatusBadgeVariant(rule.status)}>
                                      {rule.status}
                                    </Badge>
                                    {rule.rule.enabled === false && (
                                      <Badge
                                        variant="outline"
                                        className={enableDisabledRules
                                          ? "text-blue-600 border-blue-300 bg-blue-50"
                                          : "text-orange-600 border-orange-300 bg-orange-50"
                                        }
                                      >
                                        {enableDisabledRules ? 'Will be enabled' : 'Stays disabled'}
                                      </Badge>
                                    )}
                                  </div>
                                  <p className="text-sm text-gray-600 mt-1">
                                    {rule.rule.description}
                                  </p>
                                </div>
                                <span className="text-sm text-gray-500">
                                  Priority: {rule.rule.priority}
                                </span>
                              </div>

                              {/* Issues */}
                              {rule.issues.length > 0 && (
                                <div className="space-y-2">
                                  {rule.issues.map((issue, idx) => {
                                    const explanation = getIssueExplanation(issue);
                                    return (
                                      <Alert key={idx} variant={rule.status === 'ERROR' ? 'destructive' : 'default'}>
                                        <AlertDescription className="space-y-1">
                                          <div className="flex items-start gap-2">
                                            {explanation.severity === 'error' ? (
                                              <XCircle className="size-4 mt-0.5 flex-shrink-0" />
                                            ) : (
                                              <AlertTriangle className="size-4 mt-0.5 flex-shrink-0" />
                                            )}
                                            <div className="flex-1">
                                              <p className="font-medium text-sm">{explanation.title}</p>
                                              <p className="text-xs opacity-90 mt-1">{explanation.explanation}</p>
                                              <p className="text-xs opacity-70 mt-1 font-mono">{issue}</p>
                                            </div>
                                          </div>
                                        </AlertDescription>
                                      </Alert>
                                    );
                                  })}
                                </div>
                              )}

                              {/* Conflict */}
                              {rule.conflict && (
                                <Alert className="bg-yellow-50 border-yellow-200">
                                  <AlertDescription>
                                    <div className="flex items-start gap-2">
                                      <AlertCircle className="size-4 mt-0.5 flex-shrink-0 text-yellow-600" />
                                      <div className="flex-1">
                                        <p className="font-medium text-sm text-yellow-900">
                                          Duplicate Rule Code
                                        </p>
                                        <p className="text-xs text-yellow-800 mt-1">
                                          A rule with code <span className="font-mono font-semibold">{rule.conflict.existingRuleCode}</span> already exists.
                                          Choose a conflict resolution strategy above:
                                        </p>
                                        <ul className="text-xs text-yellow-800 mt-2 space-y-1 ml-4">
                                          <li>• <strong>SKIP:</strong> Keep existing rule, skip this import</li>
                                          <li>• <strong>OVERWRITE:</strong> Replace existing rule with this one</li>
                                          <li>• <strong>RENAME:</strong> Import with auto-renamed code ({rule.rule.rule_code}_imported_1)</li>
                                        </ul>
                                      </div>
                                    </div>
                                  </AlertDescription>
                                </Alert>
                              )}

                              {/* Tags */}
                              {rule.rule.tags && rule.rule.tags.length > 0 && (
                                <div className="flex gap-1 flex-wrap">
                                  {rule.rule.tags.map(tag => (
                                    <Badge key={tag} variant="outline" className="text-xs">
                                      {tag}
                                    </Badge>
                                  ))}
                                </div>
                              )}
                            </div>
                          </div>
                        </CardContent>
                      </Card>
                      ))
                    )}
                  </div>
                </ScrollArea>
              </div>
            </div>
          )}

          {/* Step 4: Importing */}
          {step === 'importing' && (
            <div className="flex items-center justify-center py-16">
              <div className="text-center space-y-4 w-full max-w-md px-4">
                <div className="size-12 border-4 border-blue-500 border-t-transparent rounded-full mx-auto animate-spin" />
                <p className="text-lg font-medium">Importing rules...</p>
                <Progress value={importProgress} className="w-full" />
                <p className="text-sm text-gray-500">{Math.round(importProgress)}% complete</p>
              </div>
            </div>
          )}

          {/* Step 4b: Compiling */}
          {step === 'compiling' && (
            <div className="flex items-center justify-center py-16">
              <div className="text-center space-y-4">
                <div className="relative">
                  <Zap className="size-12 text-blue-500 mx-auto" />
                  <Loader2 className="size-16 text-blue-500 mx-auto absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 animate-spin" />
                </div>
                <p className="text-lg font-medium">Compiling rules...</p>
                <p className="text-sm text-gray-500">Building optimized rule engine model</p>
              </div>
            </div>
          )}

          {/* Step 5: Complete */}
          {step === 'complete' && (
            <div className="flex items-center justify-center py-16">
              <div className="text-center space-y-4">
                <CheckCircle2 className="size-16 text-green-600 mx-auto" />
                <p className="text-xl font-semibold">Import Complete!</p>
                <p className="text-gray-600">
                  Successfully imported {selectedRules.size} rules
                </p>
                {compileResult && (
                  <div className={`mt-4 p-4 rounded-lg ${compileResult.success ? 'bg-green-50 border border-green-200' : 'bg-yellow-50 border border-yellow-200'}`}>
                    <div className="flex items-center justify-center gap-2">
                      {compileResult.success ? (
                        <Zap className="size-5 text-green-600" />
                      ) : (
                        <AlertTriangle className="size-5 text-yellow-600" />
                      )}
                      <span className={`font-medium ${compileResult.success ? 'text-green-800' : 'text-yellow-800'}`}>
                        {compileResult.success ? 'Compilation successful' : 'Compilation completed with issues'}
                      </span>
                    </div>
                    <p className={`text-sm mt-1 ${compileResult.success ? 'text-green-700' : 'text-yellow-700'}`}>
                      {compileResult.message}
                    </p>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div className="flex items-center justify-between pt-4 border-t">
          <div>
            {step !== 'upload' && step !== 'complete' && step !== 'compiling' && (
              <Button variant="outline" onClick={reset}>
                Cancel
              </Button>
            )}
          </div>

          <div className="flex gap-2">
            {step === 'upload' && (
              <Button
                onClick={parseAndValidate}
                disabled={!uploadedFile && !pastedContent.trim()}
              >
                Continue
              </Button>
            )}

            {step === 'configure' && (
              <Button
                onClick={executeImport}
                disabled={selectedRules.size === 0}
              >
                Import {selectedRules.size} Rules
              </Button>
            )}

            {step === 'complete' && (
              <Button onClick={() => {
                reset();
                onOpenChange(false);
              }}>
                Close
              </Button>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
