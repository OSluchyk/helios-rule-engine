/**
 * RuleHistoryDialog Component - Production Implementation
 *
 * Displays rule version history with timeline, diff comparison, and rollback functionality.
 * Based on approved mock-ui design from helios-mock-ui.
 *
 * Features:
 * - Three-tab interface: Timeline, Compare Versions, Version Details
 * - Visual timeline with connecting line and clickable version cards
 * - Side-by-side raw JSON diff viewer
 * - Rollback functionality with confirmation dialog
 * - Export version capability
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { ScrollArea } from '../ui/scroll-area';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Alert, AlertDescription } from '../ui/alert';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/dialog';
import {
  History,
  Clock,
  User,
  GitBranch,
  FileText,
  RotateCcw,
  Download,
  ArrowRight,
  Plus,
  Minus,
  AlertTriangle,
  CheckCircle2,
  Info,
  Settings,
  Loader2,
  Zap
} from 'lucide-react';
import { toast } from 'sonner';
import { Differ, Viewer } from 'json-diff-kit';
import { ruleHistoryApi } from '../../../api/ruleHistory';
import type { RuleMetadata, RuleVersion, RuleCondition } from '../../../types/api';

type ViewMode = 'timeline' | 'diff' | 'details';

interface LocalRuleVersion {
  version: number;
  timestamp: string;
  author: string;
  changeType: 'created' | 'updated' | 'rollback';
  changeSummary: string;
  changeDetails: {
    conditionsChanged: number;
    actionsChanged: number;
    metadataChanged: boolean;
  };
  conditions: RuleCondition[];
  priority: number | null;
  enabled: boolean | null;
  description: string;
  tags: string[];
  labels: Record<string, string>;
  // Store raw API data for JSON view
  rawData?: RuleVersion;
}

interface RuleHistoryDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  rule: RuleMetadata | null;
  onRollbackSuccess?: () => void;
}

// Convert API version to local format
function convertApiVersion(apiVersion: RuleVersion): LocalRuleVersion {
  return {
    version: apiVersion.version,
    timestamp: apiVersion.timestamp,
    author: apiVersion.author || 'unknown',
    changeType: apiVersion.change_type.toLowerCase() as 'created' | 'updated' | 'rollback',
    changeSummary: apiVersion.change_summary || 'No summary',
    changeDetails: {
      conditionsChanged: apiVersion.conditions?.length || 0,
      actionsChanged: 0,
      metadataChanged: false
    },
    conditions: apiVersion.conditions || [],
    priority: apiVersion.priority,
    enabled: apiVersion.enabled,
    description: apiVersion.description || '',
    tags: apiVersion.tags || [],
    labels: apiVersion.labels || {},
    rawData: apiVersion
  };
}

export function RuleHistoryDialog({ open, onOpenChange, rule, onRollbackSuccess }: RuleHistoryDialogProps) {
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [compareFromVersion, setCompareFromVersion] = useState<number | null>(null);
  const [compareToVersion, setCompareToVersion] = useState<number | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('timeline');
  const [showRollbackConfirm, setShowRollbackConfirm] = useState(false);
  const [versions, setVersions] = useState<LocalRuleVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [rollbackLoading, setRollbackLoading] = useState(false);

  const fetchVersions = useCallback(async () => {
    if (!rule) return;

    setLoading(true);

    // Helper to set versions and selection
    const applyVersions = (versionList: LocalRuleVersion[]) => {
      setVersions(versionList);
      if (versionList.length > 0) {
        setSelectedVersion(versionList[0].version);
        if (versionList.length >= 2) {
          setCompareFromVersion(versionList[0].version);
          setCompareToVersion(versionList[1].version);
        }
      }
    };

    try {
      const response = await ruleHistoryApi.getVersions(rule.rule_code);
      const convertedVersions = response.versions.map(v => convertApiVersion(v));
      // Sort by version descending (newest first)
      convertedVersions.sort((a, b) => b.version - a.version);
      applyVersions(convertedVersions);
    } catch (err) {
      console.error('Failed to fetch versions:', err);
      toast.error('Failed to load version history');
    } finally {
      setLoading(false);
    }
  }, [rule]);

  // Fetch versions when dialog opens
  useEffect(() => {
    if (open && rule) {
      fetchVersions();
    }
  }, [open, rule?.rule_code, fetchVersions]);

  // Reset state when dialog closes
  useEffect(() => {
    if (!open) {
      setSelectedVersion(null);
      setCompareFromVersion(null);
      setCompareToVersion(null);
      setViewMode('timeline');
      setShowRollbackConfirm(false);
      setVersions([]);
    }
  }, [open]);

  const currentVersion = selectedVersion !== null
    ? versions.find(v => v.version === selectedVersion)
    : null;

  const compareFrom = compareFromVersion !== null
    ? versions.find(v => v.version === compareFromVersion)
    : null;

  const compareTo = compareToVersion !== null
    ? versions.find(v => v.version === compareToVersion)
    : null;

  // Compute diff using json-diff-kit - MUST be before early return to follow Rules of Hooks
  const diffResult = useMemo(() => {
    if (!compareFrom || !compareTo) return null;

    try {
      // Create differ instance
      const differ = new Differ({
        detectCircular: true,
        maxDepth: Infinity,
        showModifications: true,
        arrayDiffMethod: 'lcs', // Use LCS for better array diffing
        recursiveEqual: true,
        preserveKeyOrder: 'before', // Keep key order from 'before' version
      });

      // Prepare version data for JSON diff comparison
      const prepareVersionForDiff = (version: LocalRuleVersion) => ({
        priority: version.priority,
        enabled: version.enabled,
        description: version.description,
        conditions: version.conditions.map(c => ({
          field: c.field,
          operator: c.operator,
          value: c.value
        })),
        tags: version.tags || [],
        labels: version.labels || {}
      });

      const beforeData = prepareVersionForDiff(compareTo); // older version
      const afterData = prepareVersionForDiff(compareFrom); // newer version
      return differ.diff(beforeData, afterData);
    } catch (error) {
      console.error('Error computing diff:', error);
      return null;
    }
  }, [compareFrom, compareTo]);

  if (!open || !rule) return null;

  const latestVersion = rule.version || 1;

  const handleRollback = async (version: number) => {
    setRollbackLoading(true);
    try {
      await ruleHistoryApi.rollback(rule.rule_code, version, 'current-user');
      toast.success(`Successfully rolled back to version ${version}`, {
        description: `A new version (v${latestVersion + 1}) has been created with the restored settings.`
      });
      setShowRollbackConfirm(false);
      onOpenChange(false);
      // Notify parent to refresh rule list
      onRollbackSuccess?.();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to rollback');
    } finally {
      setRollbackLoading(false);
    }
  };

  // Normalize field name for case-insensitive comparison
  const normalizeField = (field: string) => field.toUpperCase();

  // Compare conditions by field name (case-insensitive) and find differences
  const compareConditions = (
    conditions1: RuleCondition[],
    conditions2: RuleCondition[]
  ): {
    added: RuleCondition[];
    removed: RuleCondition[];
    modified: { old: RuleCondition; new: RuleCondition }[];
    unchanged: RuleCondition[];
  } => {
    const map1 = new Map(conditions1.map(c => [normalizeField(c.field), c]));
    const map2 = new Map(conditions2.map(c => [normalizeField(c.field), c]));

    const added: RuleCondition[] = [];
    const removed: RuleCondition[] = [];
    const modified: { old: RuleCondition; new: RuleCondition }[] = [];
    const unchanged: RuleCondition[] = [];

    // Find added and modified (in conditions1 but not in conditions2, or changed)
    for (const [field, cond1] of map1) {
      const cond2 = map2.get(field);
      if (!cond2) {
        added.push(cond1);
      } else {
        // Compare value and operator
        const val1 = JSON.stringify(cond1.value);
        const val2 = JSON.stringify(cond2.value);
        if (val1 !== val2 || cond1.operator !== cond2.operator) {
          modified.push({ old: cond2, new: cond1 });
        } else {
          unchanged.push(cond1);
        }
      }
    }

    // Find removed (in conditions2 but not in conditions1)
    for (const [field, cond2] of map2) {
      if (!map1.has(field)) {
        removed.push(cond2);
      }
    }

    return { added, removed, modified, unchanged };
  };

  // Get diff stats by comparing versions properly
  const getDiffStats = (newer: LocalRuleVersion, older: LocalRuleVersion) => {
    const condDiff = compareConditions(newer.conditions || [], older.conditions || []);

    let additions = condDiff.added.length;
    let deletions = condDiff.removed.length;
    let modifications = condDiff.modified.length;

    // Check other fields
    if (newer.priority !== older.priority) modifications++;
    if (newer.enabled !== older.enabled) modifications++;
    if (newer.description !== older.description) modifications++;

    // Tags comparison
    const newerTags = new Set(newer.tags || []);
    const olderTags = new Set(older.tags || []);
    for (const tag of newerTags) {
      if (!olderTags.has(tag)) additions++;
    }
    for (const tag of olderTags) {
      if (!newerTags.has(tag)) deletions++;
    }

    return { additions, deletions, modifications };
  };

  const getChangeTypeIcon = (type: string) => {
    switch (type) {
      case 'created': return <Plus className="size-4 text-green-600" />;
      case 'updated': return <Settings className="size-4 text-blue-600" />;
      case 'rollback': return <RotateCcw className="size-4 text-orange-600" />;
      default: return <FileText className="size-4" />;
    }
  };

  const getChangeTypeBadge = (type: string) => {
    switch (type) {
      case 'created': return <Badge className="bg-green-100 text-green-800 border-green-200">Created</Badge>;
      case 'updated': return <Badge className="bg-blue-100 text-blue-800 border-blue-200">Updated</Badge>;
      case 'rollback': return <Badge className="bg-orange-100 text-orange-800 border-orange-200">Rollback</Badge>;
      default: return <Badge>Unknown</Badge>;
    }
  };

  const formatTimestamp = (timestamp: string) => {
    try {
      return new Date(timestamp).toLocaleString();
    } catch {
      return timestamp;
    }
  };

  const handleExportVersion = (version: LocalRuleVersion) => {
    const exportData = version.rawData || {
      rule_code: rule.rule_code,
      version: version.version,
      description: version.description,
      conditions: version.conditions,
      priority: version.priority,
      enabled: version.enabled,
      author: version.author,
      timestamp: version.timestamp,
      change_type: version.changeType.toUpperCase(),
      change_summary: version.changeSummary,
      tags: version.tags,
      labels: version.labels
    };

    const data = JSON.stringify(exportData, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${rule.rule_code}_v${version.version}.json`;
    a.click();
    URL.revokeObjectURL(url);
    toast.success(`Exported version ${version.version}`, {
      description: `Saved as ${rule.rule_code}_v${version.version}.json`
    });
  };

  // Categorize conditions by type (base vs vectorized based on operator patterns)
  const categorizeConditions = (conditions: RuleCondition[]) => {
    const baseConditions: RuleCondition[] = [];
    const vectorizedConditions: RuleCondition[] = [];

    conditions.forEach(cond => {
      // Conditions that are likely vectorizable (numeric comparisons on hot paths)
      const isVectorizable = ['>', '<', '>=', '<=', '=='].includes(cond.operator) &&
        typeof cond.value === 'number' &&
        cond.value > 1000; // Heuristic: large numeric thresholds are often vectorized

      if (isVectorizable) {
        vectorizedConditions.push(cond);
      } else {
        baseConditions.push(cond);
      }
    });

    return { baseConditions, vectorizedConditions };
  };

  return (
    <>
      {/* Main History Dialog */}
      <Dialog open={open && !showRollbackConfirm} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-[95vw] w-[1400px] max-h-[95vh] h-[900px] flex flex-col p-0">
          {/* Header */}
          <DialogHeader className="border-b px-6 py-4 shrink-0">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-blue-100 rounded-lg">
                <History className="size-5 text-blue-600" />
              </div>
              <div className="flex-1">
                <DialogTitle className="text-xl flex items-center gap-3">
                  Rule History: {rule.rule_code}
                  {rule.tags && rule.tags.length > 0 && (
                    <Badge variant="outline" className="font-normal">
                      {rule.tags[0]}
                    </Badge>
                  )}
                </DialogTitle>
                <DialogDescription className="mt-1">
                  {loading ? (
                    <span className="flex items-center gap-2">
                      <Loader2 className="size-3 animate-spin" />
                      Loading version history...
                    </span>
                  ) : (
                    `${versions.length} version${versions.length !== 1 ? 's' : ''} • Current: v${latestVersion}`
                  )}
                </DialogDescription>
              </div>
            </div>
          </DialogHeader>

          {/* Content */}
          <div className="flex-1 overflow-hidden p-6">
            {loading ? (
              <div className="flex items-center justify-center h-full">
                <div className="flex flex-col items-center gap-4">
                  <Loader2 className="size-8 animate-spin text-blue-600" />
                  <p className="text-gray-600">Loading version history...</p>
                </div>
              </div>
            ) : versions.length === 0 ? (
              <div className="flex items-center justify-center h-full">
                <div className="text-center space-y-4">
                  <History className="size-16 text-gray-300 mx-auto" />
                  <div>
                    <h3 className="font-medium text-gray-900">No version history available</h3>
                    <p className="text-sm text-gray-500 mt-1">
                      This rule has not been modified since creation.
                    </p>
                  </div>
                </div>
              </div>
            ) : (
              <Tabs value={viewMode} onValueChange={(v) => setViewMode(v as ViewMode)} className="h-full flex flex-col">
                <TabsList className="grid w-full grid-cols-3 mb-6 shrink-0">
                  <TabsTrigger value="timeline" className="gap-2">
                    <Clock className="size-4" />
                    Timeline
                  </TabsTrigger>
                  <TabsTrigger value="diff" className="gap-2">
                    <GitBranch className="size-4" />
                    Compare Versions
                  </TabsTrigger>
                  <TabsTrigger value="details" className="gap-2">
                    <FileText className="size-4" />
                    Version Details
                  </TabsTrigger>
                </TabsList>


                {/* Timeline View */}
                <TabsContent value="timeline" className="flex-1 overflow-hidden m-0">
                  <div className="flex gap-6 h-full">
                    {/* Version List - Left Panel */}
                    <div className="w-96 shrink-0">
                      <Card className="h-full flex flex-col">
                        <CardHeader className="shrink-0">
                          <CardTitle className="text-base">Version History</CardTitle>
                          <CardDescription>Click a version to view details</CardDescription>
                        </CardHeader>
                        <CardContent className="flex-1 overflow-hidden p-0">
                          <ScrollArea className="h-full px-6 pb-6">
                            <div className="relative space-y-4">
                              {/* Timeline line */}
                              <div className="absolute left-[22px] top-8 bottom-8 w-0.5 bg-gradient-to-b from-blue-400 via-gray-300 to-gray-200" />

                              {versions.map((version) => (
                                <button
                                  key={version.version}
                                  onClick={() => setSelectedVersion(version.version)}
                                  className={`
                                    relative w-full text-left p-4 rounded-lg border-2 transition-all
                                    ${selectedVersion === version.version
                                      ? 'border-blue-500 bg-blue-50 shadow-md'
                                      : 'border-gray-200 hover:border-gray-300 hover:shadow-sm bg-white'
                                    }
                                  `}
                                >
                                  {/* Timeline dot */}
                                  <div className={`
                                    absolute -left-[18px] top-6 size-5 rounded-full border-3 bg-white shadow-sm
                                    flex items-center justify-center
                                    ${selectedVersion === version.version ? 'border-blue-500' : 'border-gray-300'}
                                  `}>
                                    <div className={`size-2 rounded-full ${
                                      selectedVersion === version.version ? 'bg-blue-500' : 'bg-gray-300'
                                    }`} />
                                  </div>

                                  <div className="space-y-2 ml-2">
                                    <div className="flex items-center gap-2 flex-wrap">
                                      <span className="font-semibold text-base">v{version.version}</span>
                                      {version.version === latestVersion && (
                                        <Badge variant="default" className="text-xs">Current</Badge>
                                      )}
                                      {getChangeTypeBadge(version.changeType)}
                                    </div>

                                    <p className="text-sm text-gray-600 line-clamp-2">
                                      {version.changeSummary}
                                    </p>

                                    <div className="flex items-center gap-3 text-xs text-gray-500 flex-wrap">
                                      <div className="flex items-center gap-1">
                                        <Clock className="size-3" />
                                        {formatTimestamp(version.timestamp)}
                                      </div>
                                      <div className="flex items-center gap-1">
                                        <User className="size-3" />
                                        {version.author.split('@')[0]}
                                      </div>
                                    </div>

                                    {/* Change indicators */}
                                    <div className="flex gap-2 pt-2 flex-wrap">
                                      {version.conditions.length > 0 && (
                                        <Badge variant="outline" className="text-xs gap-1">
                                          <CheckCircle2 className="size-3" />
                                          {version.conditions.length} condition{version.conditions.length !== 1 ? 's' : ''}
                                        </Badge>
                                      )}
                                      {version.changeDetails.metadataChanged && (
                                        <Badge variant="outline" className="text-xs gap-1">
                                          <Settings className="size-3" />
                                          metadata
                                        </Badge>
                                      )}
                                    </div>
                                  </div>
                                </button>
                              ))}
                            </div>
                          </ScrollArea>
                        </CardContent>
                      </Card>
                    </div>

                    {/* Version Details - Right Panel */}
                    <div className="flex-1 min-w-0">
                      <Card className="h-full flex flex-col">
                        <CardHeader className="shrink-0">
                          <div className="flex items-center justify-between">
                            <div className="flex-1">
                              <CardTitle className="text-base flex items-center gap-2">
                                Version {selectedVersion} Details
                                {selectedVersion === latestVersion && (
                                  <Badge variant="default" className="text-xs">Current</Badge>
                                )}
                              </CardTitle>
                              <CardDescription className="mt-1">
                                {currentVersion?.changeSummary || 'Select a version to view details'}
                              </CardDescription>
                            </div>
                            <div className="flex gap-2">
                              <TooltipProvider>
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <Button
                                      variant="outline"
                                      size="sm"
                                      onClick={() => currentVersion && handleExportVersion(currentVersion)}
                                      disabled={!currentVersion}
                                    >
                                      <Download className="size-4" />
                                    </Button>
                                  </TooltipTrigger>
                                  <TooltipContent>Export this version as JSON</TooltipContent>
                                </Tooltip>
                              </TooltipProvider>

                              {selectedVersion !== null && selectedVersion !== latestVersion && (
                                <TooltipProvider>
                                  <Tooltip>
                                    <TooltipTrigger asChild>
                                      <Button
                                        size="sm"
                                        onClick={() => setShowRollbackConfirm(true)}
                                      >
                                        <RotateCcw className="size-4 mr-2" />
                                        Restore
                                      </Button>
                                    </TooltipTrigger>
                                    <TooltipContent>Restore this version as the current configuration</TooltipContent>
                                  </Tooltip>
                                </TooltipProvider>
                              )}
                            </div>
                          </div>
                        </CardHeader>

                        <CardContent className="flex-1 overflow-hidden p-0">
                          <ScrollArea className="h-full px-6 pb-6">
                            {currentVersion ? (
                              <div className="space-y-6">
                                {/* Metadata Grid */}
                                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 p-4 bg-gray-50 rounded-lg">
                                  <div>
                                    <div className="text-sm font-medium text-gray-500 flex items-center gap-1">
                                      <User className="size-3" />
                                      Author
                                    </div>
                                    <div className="mt-1 text-sm font-medium truncate">{currentVersion.author}</div>
                                  </div>
                                  <div>
                                    <div className="text-sm font-medium text-gray-500 flex items-center gap-1">
                                      <Clock className="size-3" />
                                      Timestamp
                                    </div>
                                    <div className="mt-1 text-sm">{formatTimestamp(currentVersion.timestamp)}</div>
                                  </div>
                                  <div>
                                    <div className="text-sm font-medium text-gray-500 flex items-center gap-1">
                                      Change Type
                                    </div>
                                    <div className="mt-1 flex items-center gap-2">
                                      {getChangeTypeIcon(currentVersion.changeType)}
                                      <span className="text-sm capitalize">{currentVersion.changeType}</span>
                                    </div>
                                  </div>
                                  <div>
                                    <div className="text-sm font-medium text-gray-500">Priority</div>
                                    <div className="mt-1 text-sm font-medium">{currentVersion.priority ?? 'N/A'}</div>
                                  </div>
                                </div>

                                {/* Conditions */}
                                {(() => {
                                  const { baseConditions, vectorizedConditions } = categorizeConditions(currentVersion.conditions);
                                  return (
                                    <>
                                      {/* Base Conditions */}
                                      {baseConditions.length > 0 && (
                                        <div>
                                          <h4 className="font-medium mb-3 flex items-center gap-2">
                                            <CheckCircle2 className="size-4 text-green-600" />
                                            Base Conditions
                                            <Badge variant="secondary" className="text-xs">
                                              {baseConditions.length}
                                            </Badge>
                                          </h4>
                                          <div className="space-y-2">
                                            {baseConditions.map((cond, idx) => (
                                              <div key={idx} className="flex items-center gap-2 text-sm p-3 bg-green-50 border border-green-200 rounded-lg flex-wrap">
                                                <CheckCircle2 className="size-4 text-green-600 shrink-0" />
                                                <code className="bg-white px-2 py-1 rounded text-xs font-mono border">
                                                  {cond.field}
                                                </code>
                                                <span className="text-gray-500 font-medium">{cond.operator}</span>
                                                <code className="bg-white px-2 py-1 rounded text-xs font-mono border">
                                                  {Array.isArray(cond.value) ? JSON.stringify(cond.value) : String(cond.value)}
                                                </code>
                                              </div>
                                            ))}
                                          </div>
                                        </div>
                                      )}

                                      {/* Vectorized Conditions */}
                                      {vectorizedConditions.length > 0 && (
                                        <div>
                                          <h4 className="font-medium mb-3 flex items-center gap-2">
                                            <Zap className="size-4 text-blue-600" />
                                            Vectorized Conditions
                                            <Badge className="text-xs bg-blue-100 text-blue-800">
                                              {vectorizedConditions.length}
                                            </Badge>
                                          </h4>
                                          <div className="space-y-2">
                                            {vectorizedConditions.map((cond, idx) => (
                                              <div key={idx} className="flex items-center gap-2 text-sm p-3 bg-blue-50 border border-blue-200 rounded-lg flex-wrap">
                                                <Zap className="size-4 text-blue-600 shrink-0" />
                                                <code className="bg-white px-2 py-1 rounded text-xs font-mono border">
                                                  {cond.field}
                                                </code>
                                                <span className="text-gray-500 font-medium">{cond.operator}</span>
                                                <code className="bg-white px-2 py-1 rounded text-xs font-mono border">
                                                  {typeof cond.value === 'number' ? cond.value.toLocaleString() : String(cond.value)}
                                                </code>
                                              </div>
                                            ))}
                                          </div>
                                        </div>
                                      )}

                                      {/* Empty state for conditions */}
                                      {baseConditions.length === 0 && vectorizedConditions.length === 0 && (
                                        <div className="text-center py-8 text-gray-500">
                                          <FileText className="size-8 mx-auto mb-2 text-gray-300" />
                                          <p className="text-sm">No conditions defined in this version</p>
                                        </div>
                                      )}
                                    </>
                                  );
                                })()}

                                {/* Tags */}
                                {currentVersion.tags && currentVersion.tags.length > 0 && (
                                  <div>
                                    <h4 className="font-medium mb-3">Tags</h4>
                                    <div className="flex flex-wrap gap-2">
                                      {currentVersion.tags.map(tag => (
                                        <Badge key={tag} variant="secondary">{tag}</Badge>
                                      ))}
                                    </div>
                                  </div>
                                )}

                                {/* Labels */}
                                {currentVersion.labels && Object.keys(currentVersion.labels).length > 0 && (
                                  <div>
                                    <h4 className="font-medium mb-3">Labels</h4>
                                    <div className="flex flex-wrap gap-2">
                                      {Object.entries(currentVersion.labels).map(([key, value]) => (
                                        <Badge key={key} variant="outline" className="gap-1">
                                          <span className="text-gray-500">{key}:</span>
                                          <span>{value}</span>
                                        </Badge>
                                      ))}
                                    </div>
                                  </div>
                                )}
                              </div>
                            ) : (
                              <div className="flex items-center justify-center h-full text-center">
                                <div className="space-y-4">
                                  <FileText className="size-12 text-gray-300 mx-auto" />
                                  <div>
                                    <p className="font-medium text-gray-600">Select a version</p>
                                    <p className="text-sm text-gray-500 mt-1">
                                      Click on a version in the timeline to view its details
                                    </p>
                                  </div>
                                </div>
                              </div>
                            )}
                          </ScrollArea>
                        </CardContent>
                      </Card>
                    </div>
                  </div>
                </TabsContent>

                {/* Compare Versions View */}
                <TabsContent value="diff" className="flex-1 overflow-hidden m-0">
                  <ScrollArea className="h-full">
                    <div className="space-y-6 pr-4">
                      {/* Version Selectors */}
                      <Card>
                        <CardContent className="pt-6">
                          <div className="flex items-center gap-4">
                            <div className="flex-1">
                              <label className="text-sm font-medium mb-2 block">Compare From</label>
                              <Select
                                value={compareFromVersion?.toString() || ''}
                                onValueChange={(v) => setCompareFromVersion(parseInt(v))}
                              >
                                <SelectTrigger>
                                  <SelectValue placeholder="Select version" />
                                </SelectTrigger>
                                <SelectContent>
                                  {versions.map(v => (
                                    <SelectItem key={v.version} value={v.version.toString()}>
                                      <span className="flex items-center gap-2">
                                        v{v.version}
                                        {v.version === latestVersion && (
                                          <Badge variant="default" className="text-xs">Current</Badge>
                                        )}
                                        <span className="text-gray-500 truncate max-w-[200px]">
                                          - {v.changeSummary}
                                        </span>
                                      </span>
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </div>

                            <div className="pt-6">
                              <ArrowRight className="size-6 text-gray-400" />
                            </div>

                            <div className="flex-1">
                              <label className="text-sm font-medium mb-2 block">Compare To</label>
                              <Select
                                value={compareToVersion?.toString() || ''}
                                onValueChange={(v) => setCompareToVersion(parseInt(v))}
                              >
                                <SelectTrigger>
                                  <SelectValue placeholder="Select version to compare" />
                                </SelectTrigger>
                                <SelectContent>
                                  {versions
                                    .filter(v => v.version !== compareFromVersion)
                                    .map(v => (
                                      <SelectItem key={v.version} value={v.version.toString()}>
                                        <span className="flex items-center gap-2">
                                          v{v.version}
                                          {v.version === latestVersion && (
                                            <Badge variant="default" className="text-xs">Current</Badge>
                                          )}
                                          <span className="text-gray-500 truncate max-w-[200px]">
                                            - {v.changeSummary}
                                          </span>
                                        </span>
                                      </SelectItem>
                                    ))}
                                </SelectContent>
                              </Select>
                            </div>
                          </div>

                          {/* Diff Statistics */}
                          {compareFrom && compareTo && (
                            <Alert className="mt-4 bg-blue-50 border-blue-200">
                              <Info className="size-4 text-blue-600" />
                              <AlertDescription>
                                <div className="flex items-center gap-6 text-sm flex-wrap">
                                  <div className="flex items-center gap-2">
                                    <div className="size-6 rounded bg-green-100 flex items-center justify-center">
                                      <Plus className="size-4 text-green-600" />
                                    </div>
                                    <span className="font-medium text-green-700">
                                      {getDiffStats(compareFrom, compareTo).additions} additions
                                    </span>
                                  </div>
                                  <div className="flex items-center gap-2">
                                    <div className="size-6 rounded bg-red-100 flex items-center justify-center">
                                      <Minus className="size-4 text-red-600" />
                                    </div>
                                    <span className="font-medium text-red-700">
                                      {getDiffStats(compareFrom, compareTo).deletions} deletions
                                    </span>
                                  </div>
                                  <div className="flex items-center gap-2">
                                    <div className="size-6 rounded bg-blue-100 flex items-center justify-center">
                                      <Settings className="size-4 text-blue-600" />
                                    </div>
                                    <span className="font-medium text-blue-700">
                                      {getDiffStats(compareFrom, compareTo).modifications} modifications
                                    </span>
                                  </div>
                                </div>
                              </AlertDescription>
                            </Alert>
                          )}
                        </CardContent>
                      </Card>

                      {/* Diff Display */}
                      {compareFrom && compareTo ? (
                        <Card>
                          <CardHeader>
                            <CardTitle className="text-base">Changes</CardTitle>
                            <CardDescription>
                              Comparing version {compareFromVersion} with version {compareToVersion}
                            </CardDescription>
                          </CardHeader>
                          <CardContent>
                            <div className="space-y-6">
                              {/* Side-by-side JSON Diff View using json-diff-kit */}
                              <div>
                                <h4 className="font-medium mb-3 flex items-center gap-2">
                                  <FileText className="size-4" />
                                  JSON Comparison
                                </h4>
                                <div className="flex items-center gap-4 mb-3">
                                  <div className="flex items-center gap-2">
                                    <Badge variant="outline">v{compareToVersion}</Badge>
                                    <span className="text-sm text-gray-500">Older (left)</span>
                                  </div>
                                  <ArrowRight className="size-4 text-gray-400" />
                                  <div className="flex items-center gap-2">
                                    <Badge variant="outline">v{compareFromVersion}</Badge>
                                    <span className="text-sm text-gray-500">Newer (right)</span>
                                  </div>
                                </div>
                                <div className="border rounded-lg overflow-hidden max-h-[500px] overflow-y-auto">
                                  {diffResult ? (
                                    <Viewer
                                      diff={diffResult}
                                      indent={4}
                                      lineNumbers={true}
                                      highlightInlineDiff={true}
                                      hideUnchangedLines={true}
                                      inlineDiffOptions={{
                                        mode: 'word',
                                        wordSeparator: ' ',
                                      }}
                                    />
                                  ) : (
                                    <div className="p-4 text-gray-500 text-center">
                                      Loading diff...
                                    </div>
                                  )}
                                </div>
                              </div>

                              {/* Detailed Changes List */}
                              <div>
                                <h4 className="font-medium mb-3 flex items-center gap-2">
                                  <Settings className="size-4" />
                                  Detailed Changes
                                </h4>

                                {/* Priority Change */}
                                {compareFrom.priority !== compareTo.priority && (
                                  <div className="mb-4 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
                                    <div className="flex items-center gap-2">
                                      <Settings className="size-4 text-yellow-600" />
                                      <span className="font-medium text-yellow-800">Priority Modified</span>
                                    </div>
                                    <div className="mt-2 flex items-center gap-2 text-sm">
                                      <code className="bg-red-100 text-red-800 px-2 py-1 rounded line-through">
                                        {compareTo.priority}
                                      </code>
                                      <ArrowRight className="size-4 text-gray-400" />
                                      <code className="bg-green-100 text-green-800 px-2 py-1 rounded">
                                        {compareFrom.priority}
                                      </code>
                                    </div>
                                  </div>
                                )}

                                {/* Status Change */}
                                {compareFrom.enabled !== compareTo.enabled && (
                                  <div className="mb-4 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
                                    <div className="flex items-center gap-2">
                                      <Settings className="size-4 text-yellow-600" />
                                      <span className="font-medium text-yellow-800">Status Modified</span>
                                    </div>
                                    <div className="mt-2 flex items-center gap-2 text-sm">
                                      <code className="bg-red-100 text-red-800 px-2 py-1 rounded line-through">
                                        enabled: {String(compareTo.enabled)}
                                      </code>
                                      <ArrowRight className="size-4 text-gray-400" />
                                      <code className="bg-green-100 text-green-800 px-2 py-1 rounded">
                                        enabled: {String(compareFrom.enabled)}
                                      </code>
                                    </div>
                                  </div>
                                )}

                                {/* Conditions Changes */}
                                <div className="space-y-2">
                                  <h5 className="text-sm font-medium text-gray-700">Conditions</h5>

                                  {/* Added conditions (in compareFrom but not in compareTo) - case-insensitive */}
                                  {compareFrom.conditions
                                    .filter(cond => !compareTo.conditions.some(c => normalizeField(c.field) === normalizeField(cond.field)))
                                    .map((cond, idx) => (
                                      <div
                                        key={`added-${idx}`}
                                        className="flex items-start gap-2 p-3 bg-green-50 border border-green-200 rounded-lg"
                                      >
                                        <Plus className="size-4 text-green-600 mt-0.5 shrink-0" />
                                        <div className="flex-1">
                                          <span className="text-xs font-medium text-green-600 uppercase">Added</span>
                                          <code className="block text-sm font-mono text-green-800 mt-1">
                                            {cond.field} {cond.operator} {JSON.stringify(cond.value)}
                                          </code>
                                        </div>
                                      </div>
                                    ))}

                                  {/* Modified conditions - case-insensitive */}
                                  {compareFrom.conditions
                                    .filter(cond => {
                                      const other = compareTo.conditions.find(c => normalizeField(c.field) === normalizeField(cond.field));
                                      return other && (JSON.stringify(cond.value) !== JSON.stringify(other.value) || cond.operator !== other.operator);
                                    })
                                    .map((cond, idx) => {
                                      const other = compareTo.conditions.find(c => normalizeField(c.field) === normalizeField(cond.field))!;
                                      return (
                                        <div
                                          key={`modified-${idx}`}
                                          className="flex items-start gap-2 p-3 bg-yellow-50 border border-yellow-200 rounded-lg"
                                        >
                                          <Settings className="size-4 text-yellow-600 mt-0.5 shrink-0" />
                                          <div className="flex-1">
                                            <span className="text-xs font-medium text-yellow-600 uppercase">Modified</span>
                                            <div className="mt-1 space-y-1">
                                              <div className="flex items-center gap-2">
                                                <code className="text-sm font-mono text-gray-700">{cond.field} {cond.operator}</code>
                                              </div>
                                              <div className="flex items-center gap-2 text-sm">
                                                <code className="bg-red-100 text-red-800 px-2 py-1 rounded line-through">
                                                  {JSON.stringify(other.value)}
                                                </code>
                                                <ArrowRight className="size-4 text-gray-400" />
                                                <code className="bg-green-100 text-green-800 px-2 py-1 rounded">
                                                  {JSON.stringify(cond.value)}
                                                </code>
                                              </div>
                                            </div>
                                          </div>
                                        </div>
                                      );
                                    })}

                                  {/* Removed conditions (in compareTo but not in compareFrom) - case-insensitive */}
                                  {compareTo.conditions
                                    .filter(cond => !compareFrom.conditions.some(c => normalizeField(c.field) === normalizeField(cond.field)))
                                    .map((cond, idx) => (
                                      <div
                                        key={`removed-${idx}`}
                                        className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg"
                                      >
                                        <Minus className="size-4 text-red-600 mt-0.5 shrink-0" />
                                        <div className="flex-1">
                                          <span className="text-xs font-medium text-red-600 uppercase">Removed</span>
                                          <code className="block text-sm font-mono text-red-800 mt-1 line-through">
                                            {cond.field} {cond.operator} {JSON.stringify(cond.value)}
                                          </code>
                                        </div>
                                      </div>
                                    ))}

                                  {/* No condition changes - case-insensitive */}
                                  {compareFrom.conditions.filter(cond => !compareTo.conditions.some(c => normalizeField(c.field) === normalizeField(cond.field))).length === 0 &&
                                   compareTo.conditions.filter(cond => !compareFrom.conditions.some(c => normalizeField(c.field) === normalizeField(cond.field))).length === 0 &&
                                   compareFrom.conditions.filter(cond => {
                                     const other = compareTo.conditions.find(c => normalizeField(c.field) === normalizeField(cond.field));
                                     return other && (JSON.stringify(cond.value) !== JSON.stringify(other.value) || cond.operator !== other.operator);
                                   }).length === 0 && (
                                    <div className="text-sm text-gray-500 italic p-3 bg-gray-50 rounded-lg">
                                      No condition changes between versions
                                    </div>
                                  )}
                                </div>

                                {/* Tags Changes */}
                                {(compareFrom.tags.some(t => !compareTo.tags.includes(t)) ||
                                  compareTo.tags.some(t => !compareFrom.tags.includes(t))) && (
                                  <div className="mt-4">
                                    <h5 className="text-sm font-medium text-gray-700 mb-2">Tags</h5>
                                    <div className="flex flex-wrap gap-2">
                                      {compareFrom.tags
                                        .filter(tag => !compareTo.tags.includes(tag))
                                        .map(tag => (
                                          <Badge key={tag} className="bg-green-100 text-green-800 border-green-200 gap-1">
                                            <Plus className="size-3" />
                                            {tag}
                                          </Badge>
                                        ))}
                                      {compareTo.tags
                                        .filter(tag => !compareFrom.tags.includes(tag))
                                        .map(tag => (
                                          <Badge key={tag} className="bg-red-100 text-red-800 border-red-200 gap-1">
                                            <Minus className="size-3" />
                                            {tag}
                                          </Badge>
                                        ))}
                                    </div>
                                  </div>
                                )}
                              </div>
                            </div>
                          </CardContent>
                        </Card>
                      ) : (
                        <Card>
                          <CardContent className="py-16">
                            <div className="flex flex-col items-center justify-center text-center space-y-4">
                              <GitBranch className="size-16 text-gray-300" />
                              <div>
                                <p className="font-medium text-gray-600">Select two versions to compare</p>
                                <p className="text-sm text-gray-500 mt-1">
                                  Choose versions from the dropdowns above to see the differences
                                </p>
                              </div>
                            </div>
                          </CardContent>
                        </Card>
                      )}
                    </div>
                  </ScrollArea>
                </TabsContent>

                {/* Version Details / Raw JSON View */}
                <TabsContent value="details" className="flex-1 overflow-hidden m-0">
                  <Card className="h-full flex flex-col">
                    <CardHeader className="shrink-0">
                      <div className="flex items-center justify-between">
                        <div>
                          <CardTitle className="text-base">Raw Version Data</CardTitle>
                          <CardDescription>
                            JSON representation of version {selectedVersion}
                          </CardDescription>
                        </div>
                        <div className="flex items-center gap-2">
                          <Select
                            value={selectedVersion?.toString() || ''}
                            onValueChange={(v) => setSelectedVersion(parseInt(v))}
                          >
                            <SelectTrigger className="w-48">
                              <SelectValue placeholder="Select version" />
                            </SelectTrigger>
                            <SelectContent>
                              {versions.map(v => (
                                <SelectItem key={v.version} value={v.version.toString()}>
                                  <span className="flex items-center gap-2">
                                    v{v.version}
                                    {v.version === latestVersion && (
                                      <Badge variant="default" className="text-xs">Current</Badge>
                                    )}
                                  </span>
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => currentVersion && handleExportVersion(currentVersion)}
                            disabled={!currentVersion}
                          >
                            <Download className="size-4 mr-2" />
                            Export
                          </Button>
                        </div>
                      </div>
                    </CardHeader>
                    <CardContent className="flex-1 overflow-hidden p-0">
                      <ScrollArea className="h-full">
                        <div className="px-6 pb-6">
                          {currentVersion ? (
                            <pre className="text-sm font-mono bg-gray-900 text-gray-100 p-6 rounded-lg overflow-x-auto">
                              {JSON.stringify(
                                currentVersion.rawData || {
                                  rule_code: rule.rule_code,
                                  version: currentVersion.version,
                                  description: currentVersion.description,
                                  conditions: currentVersion.conditions,
                                  priority: currentVersion.priority,
                                  enabled: currentVersion.enabled,
                                  author: currentVersion.author,
                                  timestamp: currentVersion.timestamp,
                                  change_type: currentVersion.changeType.toUpperCase(),
                                  change_summary: currentVersion.changeSummary,
                                  tags: currentVersion.tags,
                                  labels: currentVersion.labels
                                },
                                null,
                                2
                              )}
                            </pre>
                          ) : (
                            <div className="flex items-center justify-center h-64 text-gray-500">
                              Select a version to view its raw data
                            </div>
                          )}
                        </div>
                      </ScrollArea>
                    </CardContent>
                  </Card>
                </TabsContent>
              </Tabs>
            )}
          </div>

          {/* Footer */}
          <DialogFooter className="border-t px-6 py-4 shrink-0">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Rollback Confirmation Dialog */}
      <Dialog open={showRollbackConfirm} onOpenChange={setShowRollbackConfirm}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-orange-600">
              <AlertTriangle className="size-5" />
              Confirm Rollback
            </DialogTitle>
            <DialogDescription>
              Are you sure you want to restore version {selectedVersion}?
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-4">
            <Alert className="bg-blue-50 border-blue-200">
              <Info className="size-4 text-blue-600" />
              <AlertDescription className="text-blue-800">
                <div className="space-y-2 text-sm">
                  <p className="font-medium">This will:</p>
                  <ul className="list-disc list-inside space-y-1 ml-2">
                    <li>Create a new version (v{latestVersion + 1})</li>
                    <li>Restore all settings from version {selectedVersion}</li>
                    <li>Mark the change as a rollback in history</li>
                    <li>Preserve the current version in history</li>
                  </ul>
                </div>
              </AlertDescription>
            </Alert>

            <Alert className="bg-yellow-50 border-yellow-200">
              <AlertTriangle className="size-4 text-yellow-600" />
              <AlertDescription className="text-yellow-800">
                <p className="text-sm">
                  <strong>Warning:</strong> This action will immediately affect the rule in production.
                </p>
              </AlertDescription>
            </Alert>
          </div>

          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              variant="outline"
              onClick={() => setShowRollbackConfirm(false)}
              disabled={rollbackLoading}
            >
              Cancel
            </Button>
            <Button
              onClick={() => selectedVersion && handleRollback(selectedVersion)}
              disabled={rollbackLoading}
              className="bg-orange-600 hover:bg-orange-700"
            >
              {rollbackLoading ? (
                <>
                  <Loader2 className="size-4 mr-2 animate-spin" />
                  Rolling back...
                </>
              ) : (
                <>
                  <RotateCcw className="size-4 mr-2" />
                  Confirm Rollback
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default RuleHistoryDialog;
