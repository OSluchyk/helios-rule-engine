/**
 * RuleListView Component - Production Implementation
 *
 * Refactored from helios-mock-ui with real API integration.
 * Features:
 * - 1/3 (filters) - 2/3 (content) responsive layout
 * - Tag-based filtering with predefined and custom tags
 * - Family grouping in tree view
 * - Collapsible rule cards with detailed metadata
 * - Real-time API data with loading/error states
 */

import { useState, useMemo, useEffect } from 'react';
import { useRules } from '../../../hooks/useRules';
import { getErrorMessage } from '../../../api/client';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';
import { Checkbox } from '../ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { ScrollArea } from '../ui/scroll-area';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';
import { Alert, AlertDescription } from '../ui/alert';
import {
  Search,
  Plus,
  Download,
  Upload,
  Edit,
  Copy,
  TestTube,
  History,
  Trash2,
  Play,
  Pause,
  CheckCircle2,
  XCircle,
  AlertCircle,
  AlertTriangle,
  Zap,
  Filter,
  ChevronRight,
  ChevronLeft,
  ChevronsLeft,
  ChevronsRight,
  Tag,
  X,
  Loader2
} from 'lucide-react';
import { toast } from 'sonner';
import { compileFromDatabase } from '../../../api/compilation';
import { deleteRule, deleteRulesBatch } from '../../../api/rules';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/dialog';
import {
  PREDEFINED_TAGS,
  getTagStyle,
  getAllTags,
  toUIRule,
  validateRule,
  type UIRule,
  type RuleFamily,
  type ViewMode,
  type SortOption,
  type PredefinedTagKey
} from '../../../types/rules-ui';
import { RuleImportDialog } from './RuleImportDialog';
import { RuleHistoryDialog } from './RuleHistoryDialog';
import type { RuleMetadata } from '../../../types/api';

interface RuleListViewProps {
  onNewRule?: () => void;
  onEditRule?: (rule: RuleMetadata) => void;
}

export function RuleListView({ onNewRule, onEditRule }: RuleListViewProps) {
  // API data fetching
  const { data: apiRules, isLoading, error, refetch } = useRules();

  // Filter state
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFamily, setSelectedFamily] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string[]>(['active', 'inactive', 'draft']);
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [customTagInput, setCustomTagInput] = useState('');
  const [compilationStatusFilter, setCompilationStatusFilter] = useState<string>('all');

  // UI state
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [sortBy, setSortBy] = useState<SortOption>('match-rate');
  const [showAdvancedFilters, setShowAdvancedFilters] = useState(false);
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [isCompiling, setIsCompiling] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ type: 'single' | 'batch'; ruleCode?: string } | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [historyDialogOpen, setHistoryDialogOpen] = useState(false);
  const [historyRule, setHistoryRule] = useState<RuleMetadata | null>(null);

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);

  // Convert API rules to UI rules with computed properties
  const uiRules = useMemo(() => {
    if (!apiRules) return [];
    return apiRules.map(toUIRule);
  }, [apiRules]);

  // Get all unique tags from current rules
  const allTags = useMemo(() => {
    if (!apiRules) return [];
    return getAllTags(apiRules);
  }, [apiRules]);

  // Get unique families for filter dropdown
  const families = useMemo((): RuleFamily[] => {
    const familyMap = new Map<string, RuleFamily>();

    uiRules.forEach(rule => {
      const family = rule.family || 'general';
      if (!familyMap.has(family)) {
        familyMap.set(family, {
          name: family,
          ruleCount: 0,
          dedupRate: 0,
          avgPriority: 0
        });
      }
      const fam = familyMap.get(family)!;
      fam.ruleCount++;
      fam.avgPriority = (fam.avgPriority || 0) + rule.priority;
    });

    // Calculate averages
    familyMap.forEach(fam => {
      fam.avgPriority = Math.round((fam.avgPriority || 0) / fam.ruleCount);
    });

    return Array.from(familyMap.values()).sort((a, b) => a.name.localeCompare(b.name));
  }, [uiRules]);

  // Apply filters
  const filteredRules = useMemo(() => {
    return uiRules.filter(rule => {
      // Search filter
      const matchesSearch =
        !searchQuery ||
        rule.rule_code.toLowerCase().includes(searchQuery.toLowerCase()) ||
        rule.description.toLowerCase().includes(searchQuery.toLowerCase());

      // Family filter
      const matchesFamily = selectedFamily === 'all' || rule.family === selectedFamily;

      // Status filter
      const matchesStatus = statusFilter.length === 0 || statusFilter.includes(rule.status || 'active');

      // Tag filter (AND logic - must have ALL selected tags)
      const matchesTags =
        selectedTags.length === 0 ||
        selectedTags.every(tag => rule.tags && rule.tags.includes(tag));

      // Compilation status filter
      const matchesCompilationStatus =
        compilationStatusFilter === 'all' ||
        (rule.compilation_status || 'PENDING') === compilationStatusFilter;

      return matchesSearch && matchesFamily && matchesStatus && matchesTags && matchesCompilationStatus;
    });
  }, [uiRules, searchQuery, selectedFamily, statusFilter, selectedTags, compilationStatusFilter]);

  // Group rules by family for tree view
  const groupedRules = useMemo(() => {
    const groups: Record<string, UIRule[]> = {};
    filteredRules.forEach(rule => {
      const family = rule.family || 'general';
      if (!groups[family]) groups[family] = [];
      groups[family].push(rule);
    });
    return groups;
  }, [filteredRules]);

  // Pagination calculations
  const totalPages = Math.ceil(filteredRules.length / pageSize);
  const paginatedRules = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize;
    return filteredRules.slice(startIndex, startIndex + pageSize);
  }, [filteredRules, currentPage, pageSize]);

  // Reset to first page when filters change
  useEffect(() => {
    setCurrentPage(1);
  }, [searchQuery, selectedFamily, statusFilter, selectedTags, compilationStatusFilter]);

  // Filter actions
  const toggleTagFilter = (tag: string) => {
    setSelectedTags(prev =>
      prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag]
    );
  };

  const addCustomTag = () => {
    const tag = customTagInput.trim().toLowerCase();
    if (tag && !selectedTags.includes(tag)) {
      setSelectedTags([...selectedTags, tag]);
      setCustomTagInput('');
      toast.success(`Added custom tag filter: ${tag}`);
    }
  };

  const clearAllFilters = () => {
    setSearchQuery('');
    setSelectedFamily('all');
    setStatusFilter(['active', 'inactive', 'draft']);
    setSelectedTags([]);
    setCompilationStatusFilter('all');
  };

  // Rule selection actions
  const toggleRuleSelection = (ruleCode: string) => {
    const newSelection = new Set(selectedRules);
    if (newSelection.has(ruleCode)) {
      newSelection.delete(ruleCode);
    } else {
      newSelection.add(ruleCode);
    }
    setSelectedRules(newSelection);
  };

  const selectAll = () => {
    setSelectedRules(new Set(filteredRules.map(r => r.rule_code)));
    toast.success(`Selected ${filteredRules.length} rules`);
  };

  const deselectAll = () => {
    setSelectedRules(new Set());
    toast.info('Selection cleared');
  };

  // Bulk actions
  const bulkAction = (action: string) => {
    if (selectedRules.size === 0) {
      toast.error('No rules selected');
      return;
    }
    toast.success(`${action} applied to ${selectedRules.size} rules`);
  };

  // Compile all rules from database
  const handleCompileAll = async () => {
    setIsCompiling(true);
    try {
      const result = await compileFromDatabase();
      if (result.success) {
        toast.success(result.message, {
          description: `Compiled ${result.compiledRules} rules in ${result.compilationTimeMs}ms`,
        });
        // Refetch rules to update compilation status
        refetch();
      } else {
        toast.error('Compilation failed', {
          description: result.message || result.error,
        });
      }
    } catch (error) {
      toast.error('Compilation failed', {
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setIsCompiling(false);
    }
  };

  // Open delete confirmation dialog for a single rule
  const confirmDeleteRule = (ruleCode: string) => {
    setDeleteTarget({ type: 'single', ruleCode });
    setDeleteDialogOpen(true);
  };

  // Open delete confirmation dialog for batch deletion
  const confirmBatchDelete = () => {
    if (selectedRules.size === 0) {
      toast.error('No rules selected');
      return;
    }
    setDeleteTarget({ type: 'batch' });
    setDeleteDialogOpen(true);
  };

  // Handle the actual deletion
  const handleDelete = async () => {
    if (!deleteTarget) return;

    setIsDeleting(true);
    try {
      if (deleteTarget.type === 'single' && deleteTarget.ruleCode) {
        await deleteRule(deleteTarget.ruleCode);
        toast.success(`Rule "${deleteTarget.ruleCode}" deleted successfully`);
      } else if (deleteTarget.type === 'batch') {
        const ruleCodesToDelete = Array.from(selectedRules);
        const result = await deleteRulesBatch(ruleCodesToDelete);

        if (result.totalDeleted > 0) {
          toast.success(`Deleted ${result.totalDeleted} rule${result.totalDeleted !== 1 ? 's' : ''}`);
        }
        if (result.totalFailed > 0) {
          toast.error(`Failed to delete ${result.totalFailed} rule${result.totalFailed !== 1 ? 's' : ''}`, {
            description: result.failed.map(f => f.ruleCode).join(', '),
          });
        }
        // Clear selection after batch delete
        setSelectedRules(new Set());
      }

      // Refetch rules to update the list
      refetch();
    } catch (error) {
      toast.error('Delete failed', {
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setIsDeleting(false);
      setDeleteDialogOpen(false);
      setDeleteTarget(null);
    }
  };

  // Open history dialog for a rule (only if there's more than one version)
  const openHistoryDialog = (ruleCode: string) => {
    const apiRule = apiRules?.find(r => r.rule_code === ruleCode);
    if (apiRule) {
      if (!apiRule.version || apiRule.version <= 1) {
        toast.info('No version history available', {
          description: 'This rule has not been modified since creation.',
        });
        return;
      }
      setHistoryRule(apiRule);
      setHistoryDialogOpen(true);
    }
  };

  // Toggle rule activation
  const toggleRuleActivation = async (ruleCode: string, currentlyEnabled: boolean) => {
    try {
      const action = currentlyEnabled ? 'deactivate' : 'activate';
      const newEnabledStatus = !currentlyEnabled;

      // Find the rule to get its full data
      const rule = apiRules?.find(r => r.rule_code === ruleCode);
      if (!rule) {
        toast.error('Rule not found');
        return;
      }

      // Update rule with new enabled status
      const response = await fetch(`/api/v1/rules/${encodeURIComponent(ruleCode)}`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          ...rule,
          enabled: newEnabledStatus
        }),
      });

      if (!response.ok) {
        throw new Error('Failed to update rule');
      }

      toast.success(`Rule ${action === 'activate' ? 'activated' : 'deactivated'} successfully`);

      // Refetch rules to update UI
      refetch();
    } catch (error) {
      toast.error(`Failed to ${currentlyEnabled ? 'deactivate' : 'activate'} rule`);
      console.error('Error toggling rule activation:', error);
    }
  };

  // UI helpers
  const getStatusIcon = (status?: string) => {
    switch (status) {
      case 'active':
        return <CheckCircle2 className="size-4 text-green-600" />;
      case 'inactive':
        return <Pause className="size-4 text-gray-400" />;
      case 'draft':
        return <AlertCircle className="size-4 text-yellow-600" />;
      default:
        return <XCircle className="size-4 text-red-600" />;
    }
  };

  const getRuleHealth = (rule: UIRule) => {
    if (rule.stats?.p99LatencyMs && rule.stats.p99LatencyMs > 1.0) return 'poor';
    if (rule.stats?.matchRate && rule.stats.matchRate < 0.1) return 'warning';
    return 'good';
  };

  const highlightText = (text: string, query: string) => {
    if (!query) return text;
    const parts = text.split(new RegExp(`(${query})`, 'gi'));
    return (
      <>
        {parts.map((part, i) =>
          part.toLowerCase() === query.toLowerCase() ? (
            <mark key={i} className="bg-yellow-200 text-yellow-900">
              {part}
            </mark>
          ) : (
            part
          )
        )}
      </>
    );
  };

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="text-center space-y-4">
          <div className="size-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin mx-auto" />
          <p className="text-gray-600">Loading rules...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="p-8">
        <Alert className="bg-red-50 border-red-200">
          <AlertTriangle className="size-4 text-red-600" />
          <AlertDescription>
            <h3 className="text-red-800 font-semibold mb-2">Error Loading Rules</h3>
            <p className="text-red-600 text-sm mb-4">{getErrorMessage(error)}</p>
            <Button onClick={() => refetch()} variant="outline" size="sm">
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-3 gap-6 h-full">
      {/* Left Panel - Filters (1/3 width) */}
      <div className="col-span-1 space-y-4">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle>Filters</CardTitle>
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setShowAdvancedFilters(!showAdvancedFilters)}
                    >
                      <Filter className="size-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>
                    {showAdvancedFilters ? 'Hide' : 'Show'} advanced filters
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            </div>
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
                  onChange={e => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
            </div>

            {/* Tag Filter */}
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <Tag className="size-4" />
                <label className="font-medium">Filter by Tags</label>
              </div>

              {/* Predefined Tags */}
              <div className="space-y-2">
                <div className="text-xs font-medium text-gray-500 uppercase">
                  Predefined Tags
                </div>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(PREDEFINED_TAGS).map(([tag, config]) => {
                    const count = (apiRules || []).filter(
                      r => r.tags && r.tags.includes(tag)
                    ).length;
                    if (count === 0) return null;

                    return (
                      <TooltipProvider key={tag}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <button
                              onClick={() => toggleTagFilter(tag)}
                              className={`
                                px-2 py-1 rounded-md text-xs font-medium border transition-all
                                ${
                                  selectedTags.includes(tag)
                                    ? `${getTagStyle(tag)} ring-2 ring-offset-1 ring-current`
                                    : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                                }
                              `}
                            >
                              {tag} ({count})
                            </button>
                          </TooltipTrigger>
                          <TooltipContent>{config.description}</TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    );
                  })}
                </div>
              </div>

              {/* Custom Tags */}
              {allTags.some(tag => !PREDEFINED_TAGS[tag as PredefinedTagKey]) && (
                <div className="space-y-2">
                  <div className="text-xs font-medium text-gray-500 uppercase">
                    Custom Tags
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {allTags
                      .filter(tag => !PREDEFINED_TAGS[tag as PredefinedTagKey])
                      .map(tag => {
                        const count = (apiRules || []).filter(
                          r => r.tags && r.tags.includes(tag)
                        ).length;
                        return (
                          <button
                            key={tag}
                            onClick={() => toggleTagFilter(tag)}
                            className={`
                              px-2 py-1 rounded-md text-xs font-medium border transition-all
                              ${
                                selectedTags.includes(tag)
                                  ? `${getTagStyle(tag)} ring-2 ring-offset-1 ring-current`
                                  : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                              }
                            `}
                          >
                            {tag} ({count})
                          </button>
                        );
                      })}
                  </div>
                </div>
              )}

              {/* Add Custom Tag Input */}
              <div className="space-y-2 pt-2 border-t">
                <div className="text-xs font-medium text-gray-500 uppercase">
                  Add Custom Tag Filter
                </div>
                <div className="flex gap-2">
                  <Input
                    placeholder="e.g., my-custom-tag"
                    value={customTagInput}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setCustomTagInput(e.target.value)}
                    onKeyDown={(e: React.KeyboardEvent<HTMLInputElement>) => e.key === 'Enter' && addCustomTag()}
                    className="text-sm"
                  />
                  <Button
                    size="sm"
                    onClick={addCustomTag}
                    disabled={!customTagInput.trim()}
                  >
                    <Plus className="size-4" />
                  </Button>
                </div>
                <p className="text-xs text-gray-500">Filter by any custom tag name</p>
              </div>

              {/* Active Tag Filters */}
              {selectedTags.length > 0 && (
                <div className="space-y-2 pt-2 border-t">
                  <div className="flex items-center justify-between">
                    <div className="text-xs font-medium text-gray-500 uppercase">
                      Active Tag Filters
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setSelectedTags([])}
                      className="h-6 text-xs"
                    >
                      Clear
                    </Button>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {selectedTags.map(tag => (
                      <Badge key={tag} className={`${getTagStyle(tag)} gap-1 pr-1`}>
                        {tag}
                        <button
                          onClick={() => toggleTagFilter(tag)}
                          className="hover:bg-black/10 rounded-full p-0.5"
                        >
                          <X className="size-3" />
                        </button>
                      </Badge>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* Family Filter */}
            <div className="space-y-2">
              <label className="font-medium">Rule Family</label>
              <Select value={selectedFamily} onValueChange={setSelectedFamily}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Families ({uiRules.length})</SelectItem>
                  {families.map(family => (
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
                      onCheckedChange={(checked: boolean) => {
                        setStatusFilter(prev =>
                          checked ? [...prev, status] : prev.filter(s => s !== status)
                        );
                      }}
                    />
                    <label
                      htmlFor={status}
                      className="capitalize cursor-pointer flex-1"
                    >
                      {status}
                    </label>
                    <Badge variant="outline" className="text-xs">
                      {uiRules.filter(r => r.status === status).length}
                    </Badge>
                  </div>
                ))}
              </div>
            </div>

            {/* Compilation Status Filter */}
            <div className="space-y-2">
              <label className="font-medium flex items-center gap-2">
                <Zap className="size-4" />
                Compilation Status
              </label>
              <Select value={compilationStatusFilter} onValueChange={setCompilationStatusFilter}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">
                    All ({uiRules.length})
                  </SelectItem>
                  <SelectItem value="OK">
                    <span className="flex items-center gap-2">
                      <CheckCircle2 className="size-3 text-green-600" />
                      Compiled ({uiRules.filter(r => r.compilation_status === 'OK').length})
                    </span>
                  </SelectItem>
                  <SelectItem value="PENDING">
                    <span className="flex items-center gap-2">
                      <AlertCircle className="size-3 text-yellow-600" />
                      Pending ({uiRules.filter(r => !r.compilation_status || r.compilation_status === 'PENDING').length})
                    </span>
                  </SelectItem>
                  <SelectItem value="ERROR">
                    <span className="flex items-center gap-2">
                      <XCircle className="size-3 text-red-600" />
                      Error ({uiRules.filter(r => r.compilation_status === 'ERROR').length})
                    </span>
                  </SelectItem>
                  <SelectItem value="WARNING">
                    <span className="flex items-center gap-2">
                      <AlertTriangle className="size-3 text-orange-600" />
                      Warning ({uiRules.filter(r => r.compilation_status === 'WARNING').length})
                    </span>
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>

            {/* Applied Filters Summary */}
            {(searchQuery ||
              selectedFamily !== 'all' ||
              statusFilter.length < 3 ||
              selectedTags.length > 0 ||
              compilationStatusFilter !== 'all') && (
              <div className="pt-4 border-t">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-medium">Active Filters</span>
                  <Button variant="ghost" size="sm" onClick={clearAllFilters}>
                    Clear All
                  </Button>
                </div>
                <div className="flex flex-wrap gap-2">
                  {searchQuery && (
                    <Badge variant="secondary" className="gap-1">
                      Search: "{searchQuery}"
                      <button onClick={() => setSearchQuery('')}>×</button>
                    </Badge>
                  )}
                  {selectedFamily !== 'all' && (
                    <Badge variant="secondary" className="gap-1">
                      {selectedFamily}
                      <button onClick={() => setSelectedFamily('all')}>×</button>
                    </Badge>
                  )}
                  {selectedTags.length > 0 && (
                    <Badge variant="secondary" className="gap-1">
                      <Tag className="size-3" />
                      {selectedTags.length} tag{selectedTags.length !== 1 ? 's' : ''}
                    </Badge>
                  )}
                  {compilationStatusFilter !== 'all' && (
                    <Badge variant="secondary" className="gap-1">
                      <Zap className="size-3" />
                      {compilationStatusFilter}
                      <button onClick={() => setCompilationStatusFilter('all')}>×</button>
                    </Badge>
                  )}
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Quick Actions */}
        <Card>
          <CardHeader>
            <CardTitle>Actions</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button
              className="w-full justify-start"
              onClick={() => onNewRule?.()}
            >
              <Plus className="size-4 mr-2" />
              New Rule
            </Button>
            <Button
              className="w-full justify-start"
              variant="outline"
              onClick={() => setImportDialogOpen(true)}
            >
              <Upload className="size-4 mr-2" />
              Import Rules
            </Button>
            <Button
              className="w-full justify-start"
              variant="outline"
              onClick={() => bulkAction('Export')}
            >
              <Download className="size-4 mr-2" />
              Export Selected
            </Button>
            <Button
              className="w-full justify-start"
              variant="outline"
              onClick={handleCompileAll}
              disabled={isCompiling}
            >
              {isCompiling ? (
                <Loader2 className="size-4 mr-2 animate-spin" />
              ) : (
                <Zap className="size-4 mr-2" />
              )}
              {isCompiling ? 'Compiling...' : 'Compile All Rules'}
            </Button>
          </CardContent>
        </Card>

        {/* Results Summary */}
        <Card>
          <CardContent className="pt-6">
            <div className="text-center space-y-2">
              <div className="text-3xl font-bold">{filteredRules.length}</div>
              <div className="text-sm text-gray-600">
                {filteredRules.length === 1 ? 'rule' : 'rules'} found
              </div>
              {selectedRules.size > 0 && (
                <Badge variant="secondary" className="mt-2">
                  {selectedRules.size} selected
                </Badge>
              )}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Right Panel - Rule List (2/3 width) */}
      <div className="col-span-2">
        <Card className="h-full flex flex-col">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle>Rules ({filteredRules.length})</CardTitle>
                <CardDescription>
                  Manage and monitor your rule configurations
                </CardDescription>
              </div>
              <div className="flex gap-2">
                {/* View Mode Toggle */}
                <div className="flex gap-1 border rounded-lg p-1">
                  <Button
                    size="sm"
                    variant={viewMode === 'tree' ? 'default' : 'ghost'}
                    onClick={() => setViewMode('tree')}
                  >
                    Tree
                  </Button>
                  <Button
                    size="sm"
                    variant={viewMode === 'list' ? 'default' : 'ghost'}
                    onClick={() => setViewMode('list')}
                  >
                    List
                  </Button>
                </div>

                {/* Sort Selector */}
                <Select value={sortBy} onValueChange={(value: string) => setSortBy(value as SortOption)}>
                  <SelectTrigger className="w-40">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="match-rate">Sort by Match Rate</SelectItem>
                    <SelectItem value="priority">Sort by Priority</SelectItem>
                    <SelectItem value="modified">Sort by Last Modified</SelectItem>
                    <SelectItem value="latency">Sort by Latency</SelectItem>
                    <SelectItem value="name">Sort by Name</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* Bulk Actions Bar */}
            {selectedRules.size > 0 && (
              <Alert className="mt-4 bg-blue-50 border-blue-200">
                <AlertDescription>
                  <div className="flex items-center justify-between">
                    <span className="font-medium">
                      {selectedRules.size} rule{selectedRules.size !== 1 ? 's' : ''}{' '}
                      selected
                    </span>
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => bulkAction('Enable')}
                      >
                        <Play className="size-4 mr-1" />
                        Enable
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => bulkAction('Disable')}
                      >
                        <Pause className="size-4 mr-1" />
                        Disable
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => bulkAction('Test')}
                      >
                        <TestTube className="size-4 mr-1" />
                        Test
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-red-600 hover:text-red-700 hover:bg-red-50"
                        onClick={confirmBatchDelete}
                      >
                        <Trash2 className="size-4 mr-1" />
                        Delete
                      </Button>
                      <Button size="sm" variant="outline" onClick={deselectAll}>
                        Clear
                      </Button>
                    </div>
                  </div>
                </AlertDescription>
              </Alert>
            )}

            {/* Select All */}
            <div className="flex items-center gap-2 text-sm mt-2">
              <Checkbox
                checked={
                  selectedRules.size === filteredRules.length && filteredRules.length > 0
                }
                onCheckedChange={(checked: boolean) => (checked ? selectAll() : deselectAll())}
              />
              <label
                className="cursor-pointer"
                onClick={() =>
                  selectedRules.size === filteredRules.length
                    ? deselectAll()
                    : selectAll()
                }
              >
                Select all {filteredRules.length} rules
              </label>
            </div>
          </CardHeader>

          <CardContent className="flex-1 overflow-hidden flex flex-col">
            {filteredRules.length === 0 ? (
              // Empty State
              <div className="flex flex-col items-center justify-center h-full text-center space-y-4">
                <div className="size-16 rounded-full bg-gray-100 flex items-center justify-center">
                  <Search className="size-8 text-gray-400" />
                </div>
                <div>
                  <h3 className="font-semibold mb-1">No rules found</h3>
                  <p className="text-sm text-gray-600">
                    Try adjusting your filters or create a new rule
                  </p>
                </div>
                <Button onClick={() => onNewRule?.()}>
                  <Plus className="size-4 mr-2" />
                  Create New Rule
                </Button>
              </div>
            ) : viewMode === 'tree' ? (
              // Tree View - Grouped by Family
              <ScrollArea className="flex-1 pr-4">
                <div className="space-y-6">
                  {Object.entries(groupedRules).map(([family, rules]) => {
                    const familyData = families.find(f => f.name === family);
                    return (
                      <div key={family} className="space-y-3">
                        <div className="flex items-center gap-3 sticky top-0 bg-white pb-2 border-b">
                          <ChevronRight className="size-4" />
                          <h3 className="font-semibold">{family}</h3>
                          <Badge variant="outline">{rules.length} rules</Badge>
                          {familyData && familyData.dedupRate && (
                            <Badge variant="secondary" className="gap-1">
                              <Zap className="size-3" />
                              {(familyData.dedupRate * 100).toFixed(0)}% dedup
                            </Badge>
                          )}
                        </div>

                        <Accordion type="single" collapsible className="space-y-3">
                          {rules.map(rule => {
                            const health = getRuleHealth(rule);
                            return (
                              <AccordionItem
                                key={rule.rule_code}
                                value={rule.rule_code}
                                className={`border rounded-lg px-4 shadow-sm ${
                                  selectedRules.has(rule.rule_code)
                                    ? 'bg-blue-50 border-blue-300'
                                    : 'bg-white'
                                }`}
                              >
                                <AccordionTrigger className="hover:no-underline">
                                  <div className="flex items-start gap-4 w-full text-left">
                                    <Checkbox
                                      checked={selectedRules.has(rule.rule_code)}
                                      onClick={(e: React.MouseEvent) => e.stopPropagation()}
                                      onCheckedChange={() =>
                                        toggleRuleSelection(rule.rule_code)
                                      }
                                      className="mt-1"
                                    />
                                    <div className="flex items-center gap-2 mt-1">
                                      {getStatusIcon(rule.status)}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <div className="flex items-center gap-3 mb-1 flex-wrap">
                                        <h3 className="font-semibold">
                                          {highlightText(rule.rule_code, searchQuery)}
                                        </h3>
                                        <Badge variant="outline">{rule.family}</Badge>
                                        <Badge variant="secondary">
                                          Priority: {rule.priority}
                                        </Badge>
                                        {rule.version && rule.version > 1 && (
                                          <Badge variant="outline" className="gap-1">
                                            <History className="size-3" />
                                            v{rule.version}
                                          </Badge>
                                        )}
                                        {health === 'poor' && (
                                          <TooltipProvider>
                                            <Tooltip>
                                              <TooltipTrigger>
                                                <Badge variant="destructive" className="gap-1">
                                                  <AlertTriangle className="size-3" />
                                                  Slow
                                                </Badge>
                                              </TooltipTrigger>
                                              <TooltipContent>
                                                P99 latency &gt; 1ms - needs optimization
                                              </TooltipContent>
                                            </Tooltip>
                                          </TooltipProvider>
                                        )}
                                        {/* Rule Tags */}
                                        {rule.tags &&
                                          rule.tags.map(tag => (
                                            <Badge
                                              key={tag}
                                              className={`${getTagStyle(tag)} text-xs gap-1`}
                                            >
                                              <Tag className="size-3" />
                                              {tag}
                                            </Badge>
                                          ))}
                                      </div>
                                      <p className="text-sm text-gray-600 mb-2">
                                        {highlightText(rule.description, searchQuery)}
                                      </p>
                                      <div className="flex gap-6 text-sm text-gray-500 flex-wrap">
                                        <span>
                                          {rule.stats?.evalsPerDay?.toLocaleString() || 0}{' '}
                                          evals/day
                                        </span>
                                        <span
                                          className={
                                            (rule.stats?.matchRate || 0) > 0.5
                                              ? 'text-green-600'
                                              : ''
                                          }
                                        >
                                          {((rule.stats?.matchRate || 0) * 100).toFixed(0)}%
                                          match rate
                                        </span>
                                        <span
                                          className={
                                            (rule.stats?.avgLatencyMs || 0) > 1
                                              ? 'text-red-600'
                                              : 'text-green-600'
                                          }
                                        >
                                          {(rule.stats?.avgLatencyMs || 0).toFixed(2)}ms avg
                                          latency
                                        </span>
                                      </div>
                                    </div>
                                  </div>
                                </AccordionTrigger>

                                <AccordionContent>
                                  <div className="pt-4 space-y-6">
                                    {/* Base Conditions */}
                                    <div>
                                      <h4 className="font-medium mb-2 flex items-center gap-2">
                                        Conditions
                                        <Badge variant="secondary" className="text-xs">
                                          {rule.conditions?.length || 0}
                                        </Badge>
                                      </h4>
                                      <div className="space-y-2">
                                        {rule.conditions &&
                                          rule.conditions.map((cond, idx) => (
                                            <div
                                              key={idx}
                                              className="flex items-center gap-2 text-sm pl-4 border-l-2 border-green-500 hover:bg-green-50 p-2 rounded-r transition-colors"
                                            >
                                              <CheckCircle2 className="size-4 text-green-600 shrink-0" />
                                              <code className="bg-gray-50 px-2 py-1 rounded font-mono text-xs">
                                                {cond.field}
                                              </code>
                                              <span className="text-gray-500">
                                                {cond.operator}
                                              </span>
                                              <code className="bg-gray-50 px-2 py-1 rounded font-mono text-xs">
                                                {Array.isArray(cond.value)
                                                  ? JSON.stringify(cond.value)
                                                  : `"${cond.value}"`}
                                              </code>
                                            </div>
                                          ))}
                                      </div>
                                    </div>

                                    {/* Metadata */}
                                    <div className="bg-gray-50 rounded-lg p-4">
                                      <h4 className="font-medium mb-3">Metadata</h4>
                                      <div className="grid grid-cols-2 gap-4 text-sm">
                                        <div>
                                          <span className="text-gray-600">Version:</span>
                                          <div className="mt-1 text-xs">
                                            v{rule.version || 1} • Last modified:{' '}
                                            {rule.lastModified}
                                          </div>
                                        </div>
                                        <div>
                                          <span className="text-gray-600">Compilation:</span>
                                          <div className="mt-1 text-xs">
                                            {rule.compilation_status || 'N/A'}
                                          </div>
                                        </div>
                                        {rule.combination_ids && (
                                          <div>
                                            <span className="text-gray-600">
                                              Combinations:
                                            </span>
                                            <div className="mt-1 text-xs">
                                              {rule.combination_ids.length}
                                            </div>
                                          </div>
                                        )}
                                      </div>
                                    </div>

                                    {/* Validation Issues */}
                                    {(() => {
                                      const issues = validateRule(rule);
                                      if (issues.length === 0) return null;
                                      const errors = issues.filter(i => i.type === 'error');
                                      const warnings = issues.filter(i => i.type === 'warning');
                                      return (
                                        <div className="space-y-3">
                                          {errors.length > 0 && (
                                            <Alert className="bg-red-50 border-red-200">
                                              <XCircle className="size-4 text-red-600" />
                                              <AlertDescription>
                                                <div className="font-medium text-red-800 mb-2">
                                                  {errors.length} Error{errors.length !== 1 ? 's' : ''}
                                                </div>
                                                <ul className="space-y-1 text-sm text-red-700">
                                                  {errors.map((issue, idx) => (
                                                    <li key={idx} className="flex items-start gap-2">
                                                      <span className="text-red-500 mt-0.5">•</span>
                                                      <span>
                                                        {issue.message}
                                                        {issue.field && (
                                                          <code className="ml-1 px-1 py-0.5 bg-red-100 rounded text-xs">
                                                            {issue.field}
                                                          </code>
                                                        )}
                                                      </span>
                                                    </li>
                                                  ))}
                                                </ul>
                                              </AlertDescription>
                                            </Alert>
                                          )}
                                          {warnings.length > 0 && (
                                            <Alert className="bg-yellow-50 border-yellow-200">
                                              <AlertTriangle className="size-4 text-yellow-600" />
                                              <AlertDescription>
                                                <div className="font-medium text-yellow-800 mb-2">
                                                  {warnings.length} Warning{warnings.length !== 1 ? 's' : ''}
                                                </div>
                                                <ul className="space-y-1 text-sm text-yellow-700">
                                                  {warnings.map((issue, idx) => (
                                                    <li key={idx} className="flex items-start gap-2">
                                                      <span className="text-yellow-500 mt-0.5">•</span>
                                                      <span>
                                                        {issue.message}
                                                        {issue.field && (
                                                          <code className="ml-1 px-1 py-0.5 bg-yellow-100 rounded text-xs">
                                                            {issue.field}
                                                          </code>
                                                        )}
                                                      </span>
                                                    </li>
                                                  ))}
                                                </ul>
                                              </AlertDescription>
                                            </Alert>
                                          )}
                                        </div>
                                      );
                                    })()}

                                    {/* Action Buttons */}
                                    <div className="flex gap-2 pt-4 border-t flex-wrap">
                                      <Button
                                        size="sm"
                                        variant={rule.status === 'active' ? 'outline' : 'default'}
                                        onClick={() =>
                                          toggleRuleActivation(rule.rule_code, rule.status === 'active')
                                        }
                                      >
                                        {rule.status === 'active' ? (
                                          <>
                                            <Pause className="size-4 mr-2" />
                                            Deactivate
                                          </>
                                        ) : (
                                          <>
                                            <Play className="size-4 mr-2" />
                                            Activate
                                          </>
                                        )}
                                      </Button>
                                      <Button
                                        size="sm"
                                        variant="outline"
                                        onClick={() => {
                                          const apiRule = apiRules?.find(r => r.rule_code === rule.rule_code);
                                          if (apiRule && onEditRule) {
                                            onEditRule(apiRule);
                                          }
                                        }}
                                      >
                                        <Edit className="size-4 mr-2" />
                                        Edit Rule
                                      </Button>
                                      <Button
                                        size="sm"
                                        variant="outline"
                                        onClick={() =>
                                          toast.success(`Cloned ${rule.rule_code}`)
                                        }
                                      >
                                        <Copy className="size-4 mr-2" />
                                        Clone
                                      </Button>
                                      <Button
                                        size="sm"
                                        variant="outline"
                                        onClick={() =>
                                          toast.info(`Testing ${rule.rule_code}`)
                                        }
                                      >
                                        <TestTube className="size-4 mr-2" />
                                        Test
                                      </Button>
                                      {rule.version && rule.version > 1 && (
                                        <Button
                                          size="sm"
                                          variant="outline"
                                          onClick={() => openHistoryDialog(rule.rule_code)}
                                        >
                                          <History className="size-4 mr-2" />
                                          History (v{rule.version})
                                        </Button>
                                      )}
                                      <Button
                                        size="sm"
                                        variant="outline"
                                        className="ml-auto text-red-600 hover:text-red-700 hover:bg-red-50"
                                        onClick={() => confirmDeleteRule(rule.rule_code)}
                                      >
                                        <Trash2 className="size-4 mr-2" />
                                        Delete
                                      </Button>
                                    </div>
                                  </div>
                                </AccordionContent>
                              </AccordionItem>
                            );
                          })}
                        </Accordion>
                      </div>
                    );
                  })}
                </div>
              </ScrollArea>
            ) : (
              // List View - No grouping with pagination
              <ScrollArea className="flex-1 pr-4">
                <Accordion type="single" collapsible className="space-y-4">
                  {paginatedRules.map(rule => {
                    const health = getRuleHealth(rule);
                    return (
                      <AccordionItem
                        key={rule.rule_code}
                        value={rule.rule_code}
                        className={`border rounded-lg px-4 shadow-sm ${
                          selectedRules.has(rule.rule_code)
                            ? 'bg-blue-50 border-blue-300'
                            : 'bg-white'
                        }`}
                      >
                        <AccordionTrigger className="hover:no-underline">
                          <div className="flex items-start gap-4 w-full text-left">
                            <Checkbox
                              checked={selectedRules.has(rule.rule_code)}
                              onClick={(e: React.MouseEvent) => e.stopPropagation()}
                              onCheckedChange={() => toggleRuleSelection(rule.rule_code)}
                              className="mt-1"
                            />
                            <div className="flex items-center gap-2 mt-1">
                              {getStatusIcon(rule.status)}
                            </div>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-3 mb-1 flex-wrap">
                                <h3 className="font-semibold">
                                  {highlightText(rule.rule_code, searchQuery)}
                                </h3>
                                <Badge variant="outline">{rule.family}</Badge>
                                <Badge variant="secondary">
                                  Priority: {rule.priority}
                                </Badge>
                                {health === 'poor' && (
                                  <Badge variant="destructive" className="gap-1">
                                    <AlertTriangle className="size-3" />
                                    Slow
                                  </Badge>
                                )}
                                {/* Rule Tags */}
                                {rule.tags &&
                                  rule.tags.map(tag => (
                                    <Badge
                                      key={tag}
                                      className={`${getTagStyle(tag)} text-xs gap-1`}
                                    >
                                      <Tag className="size-3" />
                                      {tag}
                                    </Badge>
                                  ))}
                              </div>
                              <p className="text-sm text-gray-600 mb-2">
                                {highlightText(rule.description, searchQuery)}
                              </p>
                              <div className="flex gap-6 text-sm text-gray-500 flex-wrap">
                                <span>
                                  {rule.stats?.evalsPerDay?.toLocaleString() || 0} evals/day
                                </span>
                                <span>
                                  {((rule.stats?.matchRate || 0) * 100).toFixed(0)}% match
                                  rate
                                </span>
                                <span
                                  className={
                                    (rule.stats?.avgLatencyMs || 0) > 1
                                      ? 'text-red-600'
                                      : ''
                                  }
                                >
                                  {(rule.stats?.avgLatencyMs || 0).toFixed(2)}ms avg latency
                                </span>
                              </div>
                            </div>
                          </div>
                        </AccordionTrigger>
                        <AccordionContent>
                          <div className="pt-4 space-y-6">
                            {/* Base Conditions */}
                            <div>
                              <h4 className="font-medium mb-2 flex items-center gap-2">
                                Conditions
                                <Badge variant="secondary" className="text-xs">
                                  {rule.conditions?.length || 0}
                                </Badge>
                              </h4>
                              <div className="space-y-2">
                                {rule.conditions &&
                                  rule.conditions.map((cond, idx) => (
                                    <div
                                      key={idx}
                                      className="flex items-center gap-2 text-sm pl-4 border-l-2 border-green-500 hover:bg-green-50 p-2 rounded-r transition-colors"
                                    >
                                      <CheckCircle2 className="size-4 text-green-600 shrink-0" />
                                      <code className="bg-gray-50 px-2 py-1 rounded font-mono text-xs">
                                        {cond.field}
                                      </code>
                                      <span className="text-gray-500">
                                        {cond.operator}
                                      </span>
                                      <code className="bg-gray-50 px-2 py-1 rounded font-mono text-xs">
                                        {Array.isArray(cond.value)
                                          ? JSON.stringify(cond.value)
                                          : `"${cond.value}"`}
                                      </code>
                                    </div>
                                  ))}
                              </div>
                            </div>

                            {/* Metadata */}
                            <div className="bg-gray-50 rounded-lg p-4">
                              <h4 className="font-medium mb-3">Metadata</h4>
                              <div className="grid grid-cols-2 gap-4 text-sm">
                                <div>
                                  <span className="text-gray-600">Version:</span>
                                  <div className="mt-1 text-xs">
                                    v{rule.version || 1} • Last modified:{' '}
                                    {rule.lastModified}
                                  </div>
                                </div>
                                <div>
                                  <span className="text-gray-600">Compilation:</span>
                                  <div className="mt-1 text-xs">
                                    {rule.compilation_status || 'N/A'}
                                  </div>
                                </div>
                                {rule.combination_ids && (
                                  <div>
                                    <span className="text-gray-600">
                                      Combinations:
                                    </span>
                                    <div className="mt-1 text-xs">
                                      {rule.combination_ids.length}
                                    </div>
                                  </div>
                                )}
                              </div>
                            </div>

                            {/* Validation Issues */}
                            {(() => {
                              const issues = validateRule(rule);
                              if (issues.length === 0) return null;
                              const errors = issues.filter(i => i.type === 'error');
                              const warnings = issues.filter(i => i.type === 'warning');
                              return (
                                <div className="space-y-3">
                                  {errors.length > 0 && (
                                    <Alert className="bg-red-50 border-red-200">
                                      <XCircle className="size-4 text-red-600" />
                                      <AlertDescription>
                                        <div className="font-medium text-red-800 mb-2">
                                          {errors.length} Error{errors.length !== 1 ? 's' : ''}
                                        </div>
                                        <ul className="space-y-1 text-sm text-red-700">
                                          {errors.map((issue, idx) => (
                                            <li key={idx} className="flex items-start gap-2">
                                              <span className="text-red-500 mt-0.5">•</span>
                                              <span>
                                                {issue.message}
                                                {issue.field && (
                                                  <code className="ml-1 px-1 py-0.5 bg-red-100 rounded text-xs">
                                                    {issue.field}
                                                  </code>
                                                )}
                                              </span>
                                            </li>
                                          ))}
                                        </ul>
                                      </AlertDescription>
                                    </Alert>
                                  )}
                                  {warnings.length > 0 && (
                                    <Alert className="bg-yellow-50 border-yellow-200">
                                      <AlertTriangle className="size-4 text-yellow-600" />
                                      <AlertDescription>
                                        <div className="font-medium text-yellow-800 mb-2">
                                          {warnings.length} Warning{warnings.length !== 1 ? 's' : ''}
                                        </div>
                                        <ul className="space-y-1 text-sm text-yellow-700">
                                          {warnings.map((issue, idx) => (
                                            <li key={idx} className="flex items-start gap-2">
                                              <span className="text-yellow-500 mt-0.5">•</span>
                                              <span>
                                                {issue.message}
                                                {issue.field && (
                                                  <code className="ml-1 px-1 py-0.5 bg-yellow-100 rounded text-xs">
                                                    {issue.field}
                                                  </code>
                                                )}
                                              </span>
                                            </li>
                                          ))}
                                        </ul>
                                      </AlertDescription>
                                    </Alert>
                                  )}
                                </div>
                              );
                            })()}

                            {/* Action Buttons */}
                            <div className="flex gap-2 pt-4 border-t flex-wrap">
                              <Button
                                size="sm"
                                variant={rule.status === 'active' ? 'outline' : 'default'}
                                onClick={() =>
                                  toggleRuleActivation(rule.rule_code, rule.status === 'active')
                                }
                              >
                                {rule.status === 'active' ? (
                                  <>
                                    <Pause className="size-4 mr-2" />
                                    Deactivate
                                  </>
                                ) : (
                                  <>
                                    <Play className="size-4 mr-2" />
                                    Activate
                                  </>
                                )}
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => {
                                  const apiRule = apiRules?.find(r => r.rule_code === rule.rule_code);
                                  if (apiRule && onEditRule) {
                                    onEditRule(apiRule);
                                  }
                                }}
                              >
                                <Edit className="size-4 mr-2" />
                                Edit Rule
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() =>
                                  toast.success(`Cloned ${rule.rule_code}`)
                                }
                              >
                                <Copy className="size-4 mr-2" />
                                Clone
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() =>
                                  toast.info(`Testing ${rule.rule_code}`)
                                }
                              >
                                <TestTube className="size-4 mr-2" />
                                Test
                              </Button>
                              {rule.version && rule.version > 1 && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => openHistoryDialog(rule.rule_code)}
                                >
                                  <History className="size-4 mr-2" />
                                  History (v{rule.version})
                                </Button>
                              )}
                              <Button
                                size="sm"
                                variant="outline"
                                className="ml-auto text-red-600 hover:text-red-700 hover:bg-red-50"
                                onClick={() => confirmDeleteRule(rule.rule_code)}
                              >
                                <Trash2 className="size-4 mr-2" />
                                Delete
                              </Button>
                            </div>
                          </div>
                        </AccordionContent>
                      </AccordionItem>
                    );
                  })}
                </Accordion>
              </ScrollArea>
            )}

            {/* Pagination Controls */}
            {filteredRules.length > 0 && viewMode === 'list' && (
              <div className="flex-shrink-0 flex items-center justify-between pt-4 border-t mt-4">
                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-600">Rows per page:</span>
                  <Select
                    value={pageSize.toString()}
                    onValueChange={(value) => {
                      setPageSize(Number(value));
                      setCurrentPage(1);
                    }}
                  >
                    <SelectTrigger className="w-20 h-8">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="10">10</SelectItem>
                      <SelectItem value="25">25</SelectItem>
                      <SelectItem value="50">50</SelectItem>
                      <SelectItem value="100">100</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-600">
                    {((currentPage - 1) * pageSize) + 1}-{Math.min(currentPage * pageSize, filteredRules.length)} of {filteredRules.length}
                  </span>

                  <div className="flex gap-1">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setCurrentPage(1)}
                      disabled={currentPage === 1}
                      className="h-8 w-8 p-0"
                    >
                      <ChevronsLeft className="size-4" />
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
                      disabled={currentPage === 1}
                      className="h-8 w-8 p-0"
                    >
                      <ChevronLeft className="size-4" />
                    </Button>

                    <div className="flex items-center gap-1 px-2">
                      <span className="text-sm">Page</span>
                      <Input
                        type="number"
                        min={1}
                        max={totalPages}
                        value={currentPage}
                        onChange={(e) => {
                          const page = parseInt(e.target.value);
                          if (page >= 1 && page <= totalPages) {
                            setCurrentPage(page);
                          }
                        }}
                        className="w-14 h-8 text-center"
                      />
                      <span className="text-sm">of {totalPages}</span>
                    </div>

                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))}
                      disabled={currentPage === totalPages}
                      className="h-8 w-8 p-0"
                    >
                      <ChevronRight className="size-4" />
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setCurrentPage(totalPages)}
                      disabled={currentPage === totalPages}
                      className="h-8 w-8 p-0"
                    >
                      <ChevronsRight className="size-4" />
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Import Dialog */}
      <RuleImportDialog
        open={importDialogOpen}
        onOpenChange={setImportDialogOpen}
      />

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="text-red-600">
              {deleteTarget?.type === 'batch'
                ? `Delete ${selectedRules.size} Rules`
                : 'Delete Rule'}
            </DialogTitle>
            <DialogDescription>
              {deleteTarget?.type === 'batch' ? (
                <>
                  Are you sure you want to delete{' '}
                  <span className="font-semibold">{selectedRules.size}</span>{' '}
                  selected rule{selectedRules.size !== 1 ? 's' : ''}? This action
                  cannot be undone.
                </>
              ) : (
                <>
                  Are you sure you want to delete the rule{' '}
                  <span className="font-semibold">{deleteTarget?.ruleCode}</span>?
                  This action cannot be undone.
                </>
              )}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              variant="outline"
              onClick={() => {
                setDeleteDialogOpen(false);
                setDeleteTarget(null);
              }}
              disabled={isDeleting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={isDeleting}
            >
              {isDeleting ? (
                <>
                  <Loader2 className="size-4 mr-2 animate-spin" />
                  Deleting...
                </>
              ) : (
                <>
                  <Trash2 className="size-4 mr-2" />
                  Delete
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Rule History Dialog */}
      <RuleHistoryDialog
        open={historyDialogOpen}
        onOpenChange={setHistoryDialogOpen}
        rule={historyRule}
        onRollbackSuccess={() => refetch()}
      />
    </div>
  );
}

export default RuleListView;
