/**
 * VirtualizedList Component
 *
 * A wrapper around @tanstack/react-virtual for rendering large lists efficiently.
 * Only renders visible items plus a small overscan buffer, dramatically improving
 * performance for lists with 100+ items.
 *
 * @example
 * <VirtualizedList
 *   items={rules}
 *   estimateSize={200}
 *   renderItem={(rule, index) => <RuleCard key={rule.id} rule={rule} />}
 * />
 */

import * as React from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

interface VirtualizedListProps<T> {
  /** Array of items to render */
  items: T[];
  /** Estimated height of each item in pixels (used for initial calculation) */
  estimateSize: number;
  /** Render function for each item */
  renderItem: (item: T, index: number) => React.ReactNode;
  /** Optional className for the container */
  className?: string;
  /** Number of items to render above/below visible area (default: 3) */
  overscan?: number;
  /** Optional key extractor for items */
  getItemKey?: (item: T, index: number) => string | number;
  /** Optional gap between items in pixels */
  gap?: number;
}

export function VirtualizedList<T>({
  items,
  estimateSize,
  renderItem,
  className = '',
  overscan = 3,
  getItemKey,
  gap = 0,
}: VirtualizedListProps<T>) {
  const parentRef = React.useRef<HTMLDivElement>(null);

  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => estimateSize,
    overscan,
    getItemKey: getItemKey
      ? (index) => getItemKey(items[index], index)
      : undefined,
    gap,
  });

  const virtualItems = virtualizer.getVirtualItems();

  // Don't use virtualization for small lists (< 50 items)
  if (items.length < 50) {
    return (
      <div className={className}>
        {items.map((item, index) => (
          <div key={getItemKey ? getItemKey(item, index) : index}>
            {renderItem(item, index)}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div
      ref={parentRef}
      className={`overflow-auto ${className}`}
      style={{ contain: 'strict' }}
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualItems.map((virtualItem) => {
          const item = items[virtualItem.index];
          return (
            <div
              key={virtualItem.key}
              data-index={virtualItem.index}
              ref={virtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualItem.start}px)`,
              }}
            >
              {renderItem(item, virtualItem.index)}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Hook for manual virtualization control
 * Use this when you need more control over the virtualization behavior
 */
export function useVirtualList<T>({
  items,
  estimateSize,
  overscan = 3,
  getItemKey,
  gap = 0,
}: {
  items: T[];
  estimateSize: number;
  overscan?: number;
  getItemKey?: (item: T, index: number) => string | number;
  gap?: number;
}) {
  const parentRef = React.useRef<HTMLDivElement>(null);

  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => estimateSize,
    overscan,
    getItemKey: getItemKey
      ? (index) => getItemKey(items[index], index)
      : undefined,
    gap,
  });

  return {
    parentRef,
    virtualizer,
    virtualItems: virtualizer.getVirtualItems(),
    totalSize: virtualizer.getTotalSize(),
    isVirtualized: items.length >= 50,
  };
}

export default VirtualizedList;
