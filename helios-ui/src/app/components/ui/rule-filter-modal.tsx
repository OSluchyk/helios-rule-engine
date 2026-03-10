/**
 * Rule Filter Modal Component
 * A modal-based multi-select for filtering rules during evaluation.
 * Designed for large rulesets with search, family grouping, and bulk actions.
 */

import * as React from "react";
import { Check, Search, X, Filter } from "lucide-react";
import { cn } from "./utils";
import { Button } from "./button";
import { Input } from "./input";
import { Badge } from "./badge";
import { Checkbox } from "./checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "./dialog";

export interface RuleFilterOption {
  ruleCode: string;
  description?: string;
  family: string;
  enabled?: boolean;
}

interface RuleFilterModalProps {
  rules: RuleFilterOption[];
  selectedRuleCodes: Set<string>;
  onSelectionChange: (selected: Set<string>) => void;
  familyFilter: string;
  className?: string;
}

export function RuleFilterModal({
  rules,
  selectedRuleCodes,
  onSelectionChange,
  familyFilter,
  className,
}: RuleFilterModalProps) {
  const [open, setOpen] = React.useState(false);
  const [search, setSearch] = React.useState("");
  // Local selection state (applied only on confirm)
  const [localSelection, setLocalSelection] = React.useState<Set<string>>(new Set());

  // Rules visible based on family filter
  const visibleRules = React.useMemo(() => {
    if (familyFilter === "all") return rules;
    return rules.filter((r) => r.family === familyFilter);
  }, [rules, familyFilter]);

  // Filtered by search within visible rules
  const filteredRules = React.useMemo(() => {
    const query = search.toLowerCase().trim();
    if (!query) return visibleRules;
    return visibleRules.filter(
      (r) =>
        r.ruleCode.toLowerCase().includes(query) ||
        r.description?.toLowerCase().includes(query) ||
        r.family.toLowerCase().includes(query)
    );
  }, [visibleRules, search]);

  // Group filtered rules by family
  const groupedRules = React.useMemo(() => {
    const groups = new Map<string, RuleFilterOption[]>();
    filteredRules.forEach((rule) => {
      const group = groups.get(rule.family) || [];
      group.push(rule);
      groups.set(rule.family, group);
    });
    return Array.from(groups.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [filteredRules]);

  const handleOpen = () => {
    setLocalSelection(new Set(selectedRuleCodes));
    setSearch("");
    setOpen(true);
  };

  const handleConfirm = () => {
    onSelectionChange(new Set(localSelection));
    setOpen(false);
  };

  const handleCancel = () => {
    setOpen(false);
  };

  const toggleRule = (ruleCode: string) => {
    setLocalSelection((prev) => {
      const next = new Set(prev);
      if (next.has(ruleCode)) {
        next.delete(ruleCode);
      } else {
        next.add(ruleCode);
      }
      return next;
    });
  };

  const selectAllVisible = () => {
    setLocalSelection((prev) => {
      const next = new Set(prev);
      filteredRules.forEach((r) => next.add(r.ruleCode));
      return next;
    });
  };

  const deselectAllVisible = () => {
    setLocalSelection((prev) => {
      const next = new Set(prev);
      filteredRules.forEach((r) => next.delete(r.ruleCode));
      return next;
    });
  };

  const toggleFamily = (_family: string, rules: RuleFilterOption[]) => {
    const allSelected = rules.every((r) => localSelection.has(r.ruleCode));
    setLocalSelection((prev) => {
      const next = new Set(prev);
      rules.forEach((r) => {
        if (allSelected) {
          next.delete(r.ruleCode);
        } else {
          next.add(r.ruleCode);
        }
      });
      return next;
    });
  };

  const removeSelectedRule = (ruleCode: string) => {
    const next = new Set(selectedRuleCodes);
    next.delete(ruleCode);
    onSelectionChange(next);
  };

  const selectedCount = selectedRuleCodes.size;
  const totalVisible = visibleRules.length;
  const allSelected = totalVisible > 0 && selectedCount === totalVisible &&
    visibleRules.every((r) => selectedRuleCodes.has(r.ruleCode));

  // Selected rules that are in visible set (for chip display)
  const selectedVisibleRules = visibleRules.filter((r) => selectedRuleCodes.has(r.ruleCode));
  const showChips = !allSelected && selectedVisibleRules.length > 0 && selectedVisibleRules.length <= 5;

  return (
    <div className={cn("space-y-2", className)}>
      {/* Trigger */}
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          className="w-full justify-between font-normal"
          onClick={handleOpen}
        >
          <div className="flex items-center gap-2">
            <Filter className="size-4 text-muted-foreground" />
            <span>
              {allSelected
                ? `All ${totalVisible} rules selected`
                : `${selectedCount} of ${totalVisible} rules selected`}
            </span>
          </div>
          <Badge variant="secondary" className="ml-2">
            {selectedCount}
          </Badge>
        </Button>
      </div>

      {/* Selected rule chips (shown when not all selected and <= 5) */}
      {showChips && (
        <div className="flex flex-wrap gap-1">
          {selectedVisibleRules.map((rule) => (
            <Badge
              key={rule.ruleCode}
              variant="secondary"
              className="gap-1 pl-2 pr-1 py-0.5 font-mono text-xs"
            >
              {rule.ruleCode}
              <button
                type="button"
                onClick={() => removeSelectedRule(rule.ruleCode)}
                className="ml-0.5 rounded-full p-0.5 hover:bg-gray-300 transition-colors"
              >
                <X className="size-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}

      {/* More than 5 selected - show count */}
      {!allSelected && selectedVisibleRules.length > 5 && (
        <div className="text-xs text-muted-foreground">
          {selectedVisibleRules.length} rules selected from {familyFilter === "all" ? "all families" : `"${familyFilter}"`}
        </div>
      )}

      {/* Modal */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl max-h-[80vh] flex flex-col">
          <DialogHeader>
            <DialogTitle>Select Rules for Evaluation</DialogTitle>
            <DialogDescription>
              Choose which rules to evaluate against.
              {familyFilter !== "all" && (
                <> Showing rules from family "{familyFilter}".</>
              )}
            </DialogDescription>
          </DialogHeader>

          {/* Search + Bulk Actions */}
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input
                type="text"
                placeholder="Search rules by code or description..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-8"
                autoFocus
              />
            </div>
            <Button variant="outline" size="sm" onClick={selectAllVisible}>
              Select All
            </Button>
            <Button variant="outline" size="sm" onClick={deselectAllVisible}>
              Clear All
            </Button>
          </div>

          {/* Selection summary */}
          <div className="text-sm text-muted-foreground">
            {localSelection.size} of {visibleRules.length} rules selected
            {search && ` (showing ${filteredRules.length} matching)`}
          </div>

          {/* Rule list */}
          <div className="flex-1 overflow-y-auto border rounded-md min-h-0" style={{ maxHeight: "400px" }}>
            {filteredRules.length === 0 ? (
              <div className="py-8 text-center text-sm text-muted-foreground">
                No rules found
                {search && (
                  <>
                    {" "}matching "{search}"
                  </>
                )}
              </div>
            ) : (
              <div className="divide-y">
                {groupedRules.map(([family, familyRules]) => {
                  const allFamilySelected = familyRules.every((r) =>
                    localSelection.has(r.ruleCode)
                  );
                  const someFamilySelected = familyRules.some((r) =>
                    localSelection.has(r.ruleCode)
                  );

                  return (
                    <div key={family}>
                      {/* Family header */}
                      <div
                        className="flex items-center gap-2 px-3 py-2 bg-muted/50 sticky top-0 z-10 cursor-pointer hover:bg-muted/80 transition-colors"
                        onClick={() => toggleFamily(family, familyRules)}
                      >
                        <Checkbox
                          checked={allFamilySelected}
                          className={cn(
                            !allFamilySelected && someFamilySelected && "opacity-60"
                          )}
                          onCheckedChange={() => toggleFamily(family, familyRules)}
                        />
                        <span className="font-medium text-sm">{family}</span>
                        <Badge variant="outline" className="text-xs">
                          {familyRules.filter((r) => localSelection.has(r.ruleCode)).length}/{familyRules.length}
                        </Badge>
                      </div>

                      {/* Rules in family */}
                      {familyRules.map((rule) => (
                        <div
                          key={rule.ruleCode}
                          className={cn(
                            "flex items-start gap-2 px-3 py-2 pl-8 cursor-pointer hover:bg-gray-50 transition-colors",
                            localSelection.has(rule.ruleCode) && "bg-blue-50/50"
                          )}
                          onClick={() => toggleRule(rule.ruleCode)}
                        >
                          <Checkbox
                            checked={localSelection.has(rule.ruleCode)}
                            onCheckedChange={() => toggleRule(rule.ruleCode)}
                            className="mt-0.5"
                          />
                          <div className="flex-1 min-w-0">
                            <div className="font-mono text-sm truncate">
                              {rule.ruleCode}
                            </div>
                            {rule.description && (
                              <div className="text-xs text-muted-foreground truncate">
                                {rule.description}
                              </div>
                            )}
                          </div>
                          {localSelection.has(rule.ruleCode) && (
                            <Check className="size-4 text-blue-600 shrink-0 mt-0.5" />
                          )}
                        </div>
                      ))}
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={handleCancel}>
              Cancel
            </Button>
            <Button onClick={handleConfirm}>
              Apply ({localSelection.size} rules)
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
