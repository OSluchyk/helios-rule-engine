import { useState, useRef } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Badge } from '../ui/badge';
import { Alert, AlertDescription } from '../ui/alert';
import { Progress } from '../ui/progress';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { ScrollArea } from '../ui/scroll-area';
import { Checkbox } from '../ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { 
  Upload, 
  FileJson, 
  FileText, 
  File, 
  CheckCircle2, 
  XCircle, 
  AlertTriangle, 
  AlertCircle,
  Download,
  RefreshCw,
  Copy,
  Trash2,
  ArrowRight,
  ArrowLeft,
  FileCode,
  FileSpreadsheet,
  Eye,
  Settings,
  Zap,
  X
} from 'lucide-react';
import { toast } from 'sonner';
import type { Rule } from './mock-data';

interface ImportedRule extends Partial<Rule> {
  _importId: string;
  _status: 'valid' | 'warning' | 'error';
  _issues: string[];
  _conflict?: {
    type: 'duplicate_id' | 'duplicate_name' | 'priority_conflict';
    existingRule?: string;
  };
}

interface RuleImportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function RuleImportDialog({ open, onOpenChange }: RuleImportDialogProps) {
  const [step, setStep] = useState<'upload' | 'validate' | 'configure' | 'preview' | 'importing' | 'complete'>('upload');
  const [importMethod, setImportMethod] = useState<'file' | 'paste'>('file');
  const [selectedFormat, setSelectedFormat] = useState<'json' | 'yaml' | 'csv'>('json');
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [pastedContent, setPastedContent] = useState('');
  const [importedRules, setImportedRules] = useState<ImportedRule[]>([]);
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [conflictResolution, setConflictResolution] = useState<'skip' | 'overwrite' | 'rename'>('skip');
  const [importProgress, setImportProgress] = useState(0);
  const [validationResults, setValidationResults] = useState({ valid: 0, warnings: 0, errors: 0 });
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = useState(false);

  const handleFileSelect = (file: File) => {
    setUploadedFile(file);
    
    // Detect format from file extension
    const ext = file.name.split('.').pop()?.toLowerCase();
    if (ext === 'json') setSelectedFormat('json');
    else if (ext === 'yaml' || ext === 'yml') setSelectedFormat('yaml');
    else if (ext === 'csv') setSelectedFormat('csv');
    
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
    
    // Simulate parsing and validation
    await new Promise(resolve => setTimeout(resolve, 1000));
    
    // Mock imported rules with various states
    const mockImportedRules: ImportedRule[] = [
      {
        _importId: 'import-1',
        id: 'rule-001', // Duplicate ID
        name: 'High-Value Customer Filter',
        family: 'customer_segmentation',
        priority: 100,
        status: 'active',
        _status: 'warning',
        _issues: [],
        _conflict: {
          type: 'duplicate_id',
          existingRule: 'High-Value Customer Filter (existing)'
        }
      },
      {
        _importId: 'import-2',
        id: 'rule-new-1',
        name: 'New Campaign Rule',
        family: 'marketing_automation',
        priority: 50,
        status: 'active',
        _status: 'valid',
        _issues: []
      },
      {
        _importId: 'import-3',
        id: 'rule-new-2',
        name: 'Invalid Rule Missing Fields',
        family: 'fraud_detection',
        _status: 'error',
        _issues: ['Missing required field: baseConditions', 'Missing required field: actions']
      },
      {
        _importId: 'import-4',
        id: 'rule-new-3',
        name: 'Rule with Warnings',
        family: 'customer_segmentation',
        priority: 999, // Very high priority - warning
        status: 'active',
        _status: 'warning',
        _issues: ['Priority value (999) is unusually high', 'No vectorized conditions - performance may be suboptimal']
      },
      {
        _importId: 'import-5',
        id: 'rule-new-4',
        name: 'Valid Rule with Tags',
        family: 'pricing_engine',
        priority: 75,
        status: 'active',
        tags: ['production-critical', 'vectorized'],
        _status: 'valid',
        _issues: []
      }
    ];
    
    setImportedRules(mockImportedRules);
    
    // Auto-select valid and warning rules
    const autoSelect = new Set(
      mockImportedRules
        .filter(r => r._status !== 'error')
        .map(r => r._importId)
    );
    setSelectedRules(autoSelect);
    
    // Calculate validation results
    setValidationResults({
      valid: mockImportedRules.filter(r => r._status === 'valid').length,
      warnings: mockImportedRules.filter(r => r._status === 'warning').length,
      errors: mockImportedRules.filter(r => r._status === 'error').length
    });
    
    setStep('configure');
  };

  const executeImport = async () => {
    setStep('importing');
    
    const selectedRulesList = importedRules.filter(r => selectedRules.has(r._importId));
    
    for (let i = 0; i < selectedRulesList.length; i++) {
      await new Promise(resolve => setTimeout(resolve, 300));
      setImportProgress(((i + 1) / selectedRulesList.length) * 100);
    }
    
    setStep('complete');
    toast.success(`Successfully imported ${selectedRulesList.length} rules`);
  };

  const reset = () => {
    setStep('upload');
    setUploadedFile(null);
    setPastedContent('');
    setImportedRules([]);
    setSelectedRules(new Set());
    setImportProgress(0);
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
      case 'yaml': return <FileCode className="size-4" />;
      case 'csv': return <FileSpreadsheet className="size-4" />;
      default: return <File className="size-4" />;
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'valid': return <CheckCircle2 className="size-4 text-green-600" />;
      case 'warning': return <AlertTriangle className="size-4 text-yellow-600" />;
      case 'error': return <XCircle className="size-4 text-red-600" />;
      default: return <AlertCircle className="size-4 text-gray-400" />;
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Upload className="size-5" />
            Import Rules
          </DialogTitle>
          <DialogDescription>
            Import rules from JSON, YAML, or CSV files
          </DialogDescription>
        </DialogHeader>

        {/* Progress Steps */}
        <div className="flex items-center justify-between px-4 py-3 bg-gray-50 rounded-lg">
          <div className={`flex items-center gap-2 ${step === 'upload' ? 'text-blue-600 font-medium' : step !== 'upload' ? 'text-green-600' : 'text-gray-400'}`}>
            {step !== 'upload' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Upload</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'validate' || step === 'configure' || step === 'preview' || step === 'importing' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'importing' || step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Validate</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'configure' || step === 'preview' || step === 'importing' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'importing' || step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Configure</span>
          </div>
          <div className="flex-1 h-px bg-gray-300 mx-2" />
          <div className={`flex items-center gap-2 ${step === 'importing' || step === 'complete' ? 'text-blue-600 font-medium' : 'text-gray-400'}`}>
            {step === 'complete' ? <CheckCircle2 className="size-4" /> : <div className="size-4 rounded-full border-2 border-current" />}
            <span className="text-sm">Import</span>
          </div>
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
                    <div className="grid grid-cols-3 gap-3">
                      {(['json', 'yaml', 'csv'] as const).map(format => (
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

                  {/* Drag & Drop Area */}
                  <div
                    onDrop={handleDrop}
                    onDragOver={handleDragOver}
                    onDragLeave={handleDragLeave}
                    className={`
                      border-2 border-dashed rounded-lg p-12 text-center transition-all cursor-pointer
                      ${isDragging 
                        ? 'border-blue-500 bg-blue-50' 
                        : uploadedFile 
                          ? 'border-green-500 bg-green-50'
                          : 'border-gray-300 hover:border-gray-400'
                      }
                    `}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept=".json,.yaml,.yml,.csv"
                      onChange={(e) => e.target.files?.[0] && handleFileSelect(e.target.files[0])}
                      className="hidden"
                    />
                    
                    {uploadedFile ? (
                      <div className="space-y-3">
                        <div className="size-16 rounded-full bg-green-100 flex items-center justify-center mx-auto">
                          <CheckCircle2 className="size-8 text-green-600" />
                        </div>
                        <div>
                          <p className="font-medium text-green-900">{uploadedFile.name}</p>
                          <p className="text-sm text-green-700 mt-1">
                            {(uploadedFile.size / 1024).toFixed(2)} KB
                          </p>
                        </div>
                        <Button 
                          variant="outline" 
                          size="sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            setUploadedFile(null);
                          }}
                        >
                          <X className="size-4 mr-2" />
                          Remove
                        </Button>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="size-16 rounded-full bg-gray-100 flex items-center justify-center mx-auto">
                          <Upload className="size-8 text-gray-400" />
                        </div>
                        <div>
                          <p className="font-medium">Drop your file here, or click to browse</p>
                          <p className="text-sm text-gray-600 mt-1">
                            Supports .json, .yaml, and .csv files up to 10MB
                          </p>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Example Downloads */}
                  <Alert>
                    <Download className="size-4" />
                    <AlertDescription>
                      <div className="flex items-center justify-between">
                        <span>Need a template?</span>
                        <div className="flex gap-2">
                          <Button variant="link" size="sm" onClick={() => toast.info('Downloading JSON template...')}>
                            JSON Template
                          </Button>
                          <Button variant="link" size="sm" onClick={() => toast.info('Downloading YAML template...')}>
                            YAML Template
                          </Button>
                          <Button variant="link" size="sm" onClick={() => toast.info('Downloading CSV template...')}>
                            CSV Template
                          </Button>
                        </div>
                      </div>
                    </AlertDescription>
                  </Alert>
                </TabsContent>

                <TabsContent value="paste" className="space-y-4">
                  <div className="space-y-2">
                    <Label>Paste Rule Content</Label>
                    <textarea
                      value={pastedContent}
                      onChange={(e) => setPastedContent(e.target.value)}
                      placeholder={`Paste your ${selectedFormat.toUpperCase()} content here...`}
                      className="w-full h-64 p-4 font-mono text-sm border rounded-lg resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                    <p className="text-sm text-gray-600">
                      {pastedContent.length} characters
                    </p>
                  </div>
                </TabsContent>
              </Tabs>
            </div>
          )}

          {/* Step 2: Validation (loading state) */}
          {step === 'validate' && (
            <div className="flex flex-col items-center justify-center h-64 space-y-4">
              <RefreshCw className="size-12 text-blue-600 animate-spin" />
              <div className="text-center">
                <p className="font-medium">Parsing and validating rules...</p>
                <p className="text-sm text-gray-600 mt-1">This may take a moment</p>
              </div>
            </div>
          )}

          {/* Step 3: Configure */}
          {step === 'configure' && (
            <div className="space-y-6 p-4">
              {/* Validation Summary */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Validation Results</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-3 gap-4">
                    <div className="flex items-center gap-3 p-3 bg-green-50 rounded-lg">
                      <CheckCircle2 className="size-8 text-green-600" />
                      <div>
                        <div className="text-2xl font-bold text-green-900">{validationResults.valid}</div>
                        <div className="text-sm text-green-700">Valid Rules</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 p-3 bg-yellow-50 rounded-lg">
                      <AlertTriangle className="size-8 text-yellow-600" />
                      <div>
                        <div className="text-2xl font-bold text-yellow-900">{validationResults.warnings}</div>
                        <div className="text-sm text-yellow-700">Warnings</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 p-3 bg-red-50 rounded-lg">
                      <XCircle className="size-8 text-red-600" />
                      <div>
                        <div className="text-2xl font-bold text-red-900">{validationResults.errors}</div>
                        <div className="text-sm text-red-700">Errors</div>
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* Conflict Resolution */}
              {importedRules.some(r => r._conflict) && (
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base flex items-center gap-2">
                      <Settings className="size-4" />
                      Conflict Resolution
                    </CardTitle>
                    <CardDescription>
                      {importedRules.filter(r => r._conflict).length} rule(s) conflict with existing rules
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      <Label>How should conflicts be handled?</Label>
                      <Select value={conflictResolution} onValueChange={(v) => setConflictResolution(v as any)}>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="skip">
                            Skip conflicting rules (keep existing)
                          </SelectItem>
                          <SelectItem value="overwrite">
                            Overwrite existing rules
                          </SelectItem>
                          <SelectItem value="rename">
                            Auto-rename imported rules
                          </SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                  </CardContent>
                </Card>
              )}

              {/* Rule Selection */}
              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <div>
                      <CardTitle className="text-base">Select Rules to Import</CardTitle>
                      <CardDescription>
                        {selectedRules.size} of {importedRules.length} selected
                      </CardDescription>
                    </div>
                    <div className="flex gap-2">
                      <Button 
                        variant="outline" 
                        size="sm"
                        onClick={() => setSelectedRules(new Set(importedRules.filter(r => r._status !== 'error').map(r => r._importId)))}
                      >
                        Select All Valid
                      </Button>
                      <Button 
                        variant="outline" 
                        size="sm"
                        onClick={() => setSelectedRules(new Set())}
                      >
                        Deselect All
                      </Button>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  <ScrollArea className="h-64">
                    <div className="space-y-3">
                      {importedRules.map(rule => (
                        <div 
                          key={rule._importId}
                          className={`
                            p-4 border rounded-lg transition-all
                            ${selectedRules.has(rule._importId) ? 'border-blue-300 bg-blue-50' : 'border-gray-200'}
                            ${rule._status === 'error' ? 'opacity-50' : ''}
                          `}
                        >
                          <div className="flex items-start gap-3">
                            <Checkbox
                              checked={selectedRules.has(rule._importId)}
                              onCheckedChange={() => toggleRuleSelection(rule._importId)}
                              disabled={rule._status === 'error'}
                              className="mt-1"
                            />
                            
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-1">
                                {getStatusIcon(rule._status)}
                                <h4 className="font-medium">{rule.name || 'Unnamed Rule'}</h4>
                                {rule._conflict && (
                                  <Badge variant="outline" className="text-xs">
                                    Conflict
                                  </Badge>
                                )}
                              </div>
                              
                              {rule.family && (
                                <p className="text-sm text-gray-600 mb-2">
                                  Family: {rule.family} • Priority: {rule.priority || 'N/A'}
                                </p>
                              )}
                              
                              {/* Issues */}
                              {rule._issues.length > 0 && (
                                <div className="space-y-1 mt-2">
                                  {rule._issues.map((issue, idx) => (
                                    <div key={idx} className="flex items-start gap-2 text-sm">
                                      <AlertCircle className="size-3 text-yellow-600 mt-0.5 shrink-0" />
                                      <span className="text-yellow-700">{issue}</span>
                                    </div>
                                  ))}
                                </div>
                              )}
                              
                              {/* Conflict Info */}
                              {rule._conflict && (
                                <Alert className="mt-2">
                                  <AlertTriangle className="size-4" />
                                  <AlertDescription className="text-sm">
                                    {rule._conflict.type === 'duplicate_id' && 'Rule ID already exists'}
                                    {rule._conflict.type === 'duplicate_name' && 'Rule name already exists'}
                                    {rule._conflict.existingRule && ` (${rule._conflict.existingRule})`}
                                  </AlertDescription>
                                </Alert>
                              )}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </ScrollArea>
                </CardContent>
              </Card>
            </div>
          )}

          {/* Step 4: Importing (progress) */}
          {step === 'importing' && (
            <div className="flex flex-col items-center justify-center h-64 space-y-6 p-4">
              <div className="size-20 rounded-full bg-blue-100 flex items-center justify-center">
                <Zap className="size-10 text-blue-600 animate-pulse" />
              </div>
              <div className="w-full max-w-md space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="font-medium">Importing rules...</span>
                  <span className="text-gray-600">{Math.round(importProgress)}%</span>
                </div>
                <Progress value={importProgress} className="h-2" />
                <p className="text-sm text-gray-600 text-center">
                  {Math.round(importProgress / 100 * selectedRules.size)} of {selectedRules.size} rules imported
                </p>
              </div>
            </div>
          )}

          {/* Step 5: Complete */}
          {step === 'complete' && (
            <div className="flex flex-col items-center justify-center h-64 space-y-6 p-4 text-center">
              <div className="size-20 rounded-full bg-green-100 flex items-center justify-center">
                <CheckCircle2 className="size-10 text-green-600" />
              </div>
              <div>
                <h3 className="text-xl font-semibold mb-2">Import Complete!</h3>
                <p className="text-gray-600">
                  Successfully imported {selectedRules.size} rule{selectedRules.size !== 1 ? 's' : ''}
                </p>
              </div>
              <div className="flex gap-3">
                <Button onClick={() => {
                  onOpenChange(false);
                  setTimeout(reset, 300);
                }}>
                  Close
                </Button>
                <Button variant="outline" onClick={reset}>
                  Import More Rules
                </Button>
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        {step !== 'importing' && step !== 'complete' && (
          <DialogFooter className="border-t pt-4">
            <div className="flex justify-between w-full">
              <Button
                variant="outline"
                onClick={() => {
                  if (step === 'upload') {
                    onOpenChange(false);
                    setTimeout(reset, 300);
                  } else if (step === 'configure') {
                    setStep('upload');
                  }
                }}
              >
                {step === 'upload' ? (
                  'Cancel'
                ) : (
                  <>
                    <ArrowLeft className="size-4 mr-2" />
                    Back
                  </>
                )}
              </Button>

              <div className="flex gap-2">
                {step === 'upload' && (
                  <Button
                    onClick={parseAndValidate}
                    disabled={!uploadedFile && !pastedContent}
                  >
                    Continue
                    <ArrowRight className="size-4 ml-2" />
                  </Button>
                )}

                {step === 'configure' && (
                  <>
                    <Button
                      variant="outline"
                      onClick={() => setStep('preview')}
                      disabled={selectedRules.size === 0}
                    >
                      <Eye className="size-4 mr-2" />
                      Preview
                    </Button>
                    <Button
                      onClick={executeImport}
                      disabled={selectedRules.size === 0}
                    >
                      Import {selectedRules.size} Rule{selectedRules.size !== 1 ? 's' : ''}
                      <ArrowRight className="size-4 ml-2" />
                    </Button>
                  </>
                )}
              </div>
            </div>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  );
}