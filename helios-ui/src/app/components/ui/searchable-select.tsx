/**
 * Searchable Select Component
 * A custom select component with built-in search functionality
 */

import * as React from "react";
import { Check, ChevronDown, Search } from "lucide-react";
import { cn } from "./utils";
import { Button } from "./button";
import { Input } from "./input";

export interface SearchableSelectOption {
  value: string;
  label: string;
  description?: string;
}

interface SearchableSelectProps {
  options: SearchableSelectOption[];
  value?: string;
  onChange: (value: string) => void;
  placeholder?: string;
  searchPlaceholder?: string;
  emptyMessage?: string;
  className?: string;
}

export function SearchableSelect({
  options,
  value,
  onChange,
  placeholder = "Select an option...",
  searchPlaceholder = "Search...",
  emptyMessage = "No results found",
  className,
}: SearchableSelectProps) {
  const [open, setOpen] = React.useState(false);
  const [search, setSearch] = React.useState("");
  const containerRef = React.useRef<HTMLDivElement>(null);

  // Filter options based on search
  const filteredOptions = React.useMemo(() => {
    const query = search.toLowerCase().trim();
    if (!query) return options;
    return options.filter(
      (option) =>
        option.label.toLowerCase().includes(query) ||
        option.description?.toLowerCase().includes(query)
    );
  }, [options, search]);

  // Get selected option
  const selectedOption = options.find((opt) => opt.value === value);

  // Close dropdown when clicking outside
  React.useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setOpen(false);
      }
    };

    if (open) {
      document.addEventListener("mousedown", handleClickOutside);
    }

    // Always return cleanup to prevent memory leaks when component unmounts
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [open]);

  const handleSelect = (optionValue: string) => {
    onChange(optionValue);
    setOpen(false);
    setSearch("");
  };

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Trigger Button */}
      <Button
        type="button"
        variant="outline"
        role="combobox"
        aria-expanded={open}
        className="w-full justify-between font-normal"
        onClick={() => setOpen(!open)}
      >
        {selectedOption ? (
          <div className="flex flex-col items-start text-left min-w-0 flex-1">
            <span className="font-mono text-sm truncate w-full">
              {selectedOption.label}
            </span>
            {selectedOption.description && (
              <span className="text-xs text-muted-foreground truncate w-full">
                {selectedOption.description}
              </span>
            )}
          </div>
        ) : (
          <span className="text-muted-foreground">{placeholder}</span>
        )}
        <ChevronDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
      </Button>

      {/* Dropdown */}
      {open && (
        <div className="absolute z-50 w-full mt-1 bg-white border rounded-md shadow-lg">
          {/* Search Input */}
          <div className="p-2 border-b">
            <div className="relative">
              <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                type="text"
                placeholder={searchPlaceholder}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-8"
                autoFocus
              />
            </div>
          </div>

          {/* Options List */}
          <div className="max-h-[300px] overflow-y-auto p-1">
            {filteredOptions.length === 0 ? (
              <div className="py-6 text-center text-sm text-muted-foreground">
                {emptyMessage}
                {search && (
                  <>
                    <br />
                    <span className="text-xs">
                      No matches for "{search}"
                    </span>
                  </>
                )}
              </div>
            ) : (
              filteredOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={cn(
                    "w-full flex items-start gap-2 px-2 py-2 text-sm rounded hover:bg-gray-100 transition-colors",
                    value === option.value && "bg-gray-100"
                  )}
                  onClick={() => handleSelect(option.value)}
                >
                  <Check
                    className={cn(
                      "h-4 w-4 shrink-0 mt-0.5",
                      value === option.value ? "opacity-100" : "opacity-0"
                    )}
                  />
                  <div className="flex flex-col items-start text-left min-w-0 flex-1">
                    <span className="font-mono text-sm truncate w-full">
                      {option.label}
                    </span>
                    {option.description && (
                      <span className="text-xs text-muted-foreground truncate w-full">
                        {option.description}
                      </span>
                    )}
                  </div>
                </button>
              ))
            )}
          </div>

          {/* Footer with count */}
          {filteredOptions.length > 0 && (
            <div className="border-t p-2 text-xs text-muted-foreground text-center">
              {filteredOptions.length} {filteredOptions.length === 1 ? "rule" : "rules"}
              {search && ` matching "${search}"`}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
