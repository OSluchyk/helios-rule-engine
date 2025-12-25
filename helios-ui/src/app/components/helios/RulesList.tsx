/**
 * RulesList Component
 * Demonstrates how to use the API client with React Query hooks
 */

import { useRules } from '../../../hooks/useRules';
import { getErrorMessage } from '../../../api/client';

export const RulesList = () => {
  // Fetch rules using the custom hook
  const { data: rules, isLoading, error, refetch } = useRules();

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8">
        <div className="text-gray-500">Loading rules...</div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="p-4 bg-red-50 border border-red-200 rounded-md">
        <h3 className="text-red-800 font-semibold mb-2">Error Loading Rules</h3>
        <p className="text-red-600 text-sm mb-4">{getErrorMessage(error)}</p>
        <button
          onClick={() => refetch()}
          className="px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700"
        >
          Retry
        </button>
      </div>
    );
  }

  // Empty state
  if (!rules || rules.length === 0) {
    return (
      <div className="flex items-center justify-center p-8">
        <div className="text-gray-500">No rules found</div>
      </div>
    );
  }

  // Success state - display rules
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-2xl font-bold">Rules ({rules.length})</h2>
        <button
          onClick={() => refetch()}
          className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
        >
          Refresh
        </button>
      </div>

      <div className="grid gap-4">
        {rules.map((rule) => (
          <div
            key={rule.rule_code}
            className="p-4 border border-gray-200 rounded-lg hover:shadow-md transition-shadow"
          >
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <h3 className="text-lg font-semibold text-gray-900">
                  {rule.rule_code}
                </h3>
                <p className="text-sm text-gray-600 mt-1">{rule.description}</p>

                <div className="flex items-center gap-4 mt-3">
                  <span className="text-xs text-gray-500">
                    Priority: <span className="font-medium">{rule.priority}</span>
                  </span>
                  <span className={`text-xs px-2 py-1 rounded ${
                    rule.enabled
                      ? 'bg-green-100 text-green-800'
                      : 'bg-gray-100 text-gray-800'
                  }`}>
                    {rule.enabled ? 'Enabled' : 'Disabled'}
                  </span>
                  {rule.compilation_status && (
                    <span className={`text-xs px-2 py-1 rounded ${
                      rule.compilation_status === 'OK'
                        ? 'bg-blue-100 text-blue-800'
                        : 'bg-yellow-100 text-yellow-800'
                    }`}>
                      {rule.compilation_status}
                    </span>
                  )}
                </div>

                {rule.tags && rule.tags.length > 0 && (
                  <div className="flex items-center gap-2 mt-2">
                    {rule.tags.map((tag) => (
                      <span
                        key={tag}
                        className="text-xs px-2 py-1 bg-gray-100 text-gray-700 rounded"
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
              </div>

              <div className="text-right">
                <div className="text-xs text-gray-500">
                  {rule.combination_ids && rule.combination_ids.length > 0 && (
                    <span>
                      {rule.combination_ids.length} combination{rule.combination_ids.length !== 1 ? 's' : ''}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default RulesList;
