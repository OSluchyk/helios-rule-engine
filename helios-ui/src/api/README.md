# Helios UI API Client

This directory contains the API client setup for connecting the Helios UI to the backend REST API.

## Architecture

The API client is built using:
- **Axios** - HTTP client with interceptors for request/response handling
- **React Query** - Data fetching, caching, and state management
- **TypeScript** - Full type safety across all API calls

## Structure

```
src/api/
├── client.ts         # Base Axios instance with interceptors
├── rules.ts          # Rule management endpoints
├── monitoring.ts     # System monitoring endpoints
├── compilation.ts    # Compilation statistics endpoints
├── index.ts          # Central exports
└── README.md         # This file

src/hooks/
├── useRules.ts       # React Query hooks for rules
├── useMonitoring.ts  # React Query hooks for monitoring
└── useCompilation.ts # React Query hooks for compilation

src/types/
└── api.ts            # TypeScript type definitions
```

## Usage

### Basic API Calls

```typescript
import { rulesApi } from '@/api';

// Direct API call
const rules = await rulesApi.listRules();
```

### React Query Hooks (Recommended)

```typescript
import { useRules } from '@/hooks/useRules';

function MyComponent() {
  const { data: rules, isLoading, error } = useRules();

  if (isLoading) return <div>Loading...</div>;
  if (error) return <div>Error: {error.message}</div>;

  return (
    <ul>
      {rules?.map(rule => (
        <li key={rule.rule_code}>{rule.description}</li>
      ))}
    </ul>
  );
}
```

## Available Hooks

### Rules API

```typescript
import { useRules, useRule, useRuleCombinations } from '@/hooks/useRules';

// List all rules
const { data: rules } = useRules();

// Get single rule
const { data: rule } = useRule('RULE_001');

// Get rule combinations
const { data: combinations } = useRuleCombinations('RULE_001');

// Filter by tag
const { data: rules } = useRules({ tag: 'premium' });
```

### Monitoring API

```typescript
import { useMetrics, useHealth } from '@/hooks/useMonitoring';

// Get system metrics (auto-refreshes every 5s)
const { data: metrics } = useMetrics();

// Get health status (auto-refreshes every 10s)
const { data: health } = useHealth();
```

### Compilation API

```typescript
import {
  useCompilationStats,
  useFieldDictionary,
  useDeduplicationAnalysis
} from '@/hooks/useCompilation';

// Get compilation statistics
const { data: stats } = useCompilationStats();

// Get field dictionary
const { data: dictionary } = useFieldDictionary();

// Get deduplication analysis
const { data: analysis } = useDeduplicationAnalysis();
```

## Error Handling

All hooks automatically handle errors and provide error states:

```typescript
import { useRules } from '@/hooks/useRules';
import { getErrorMessage } from '@/api/client';

function MyComponent() {
  const { data, error, refetch } = useRules();

  if (error) {
    return (
      <div>
        <p>Error: {getErrorMessage(error)}</p>
        <button onClick={() => refetch()}>Retry</button>
      </div>
    );
  }

  // ... render data
}
```

## Loading States

React Query provides built-in loading state management:

```typescript
const { data, isLoading, isFetching, isRefetching } = useRules();

// isLoading: true on first load
// isFetching: true during any fetch (including refetch)
// isRefetching: true only during background refetch
```

## Caching

React Query automatically caches responses:

- **Rules**: 30 seconds stale time
- **Rule Details**: 60 seconds stale time
- **Monitoring Metrics**: 0 seconds (always fresh, auto-refreshes every 5s)
- **Compilation Stats**: 5 minutes stale time

## Configuration

### Base URL

Set the backend URL via environment variable:

```bash
# .env
VITE_API_URL=http://localhost:8080
```

Default: `http://localhost:8080`

### Request Timeout

Default: 30 seconds (configured in `client.ts`)

### Auto-Refresh

Monitoring metrics auto-refresh:
- Metrics: every 5 seconds
- Health: every 10 seconds

Disable auto-refresh:

```typescript
const { data } = useMetrics({ refetchInterval: false });
```

## API Interceptors

### Request Interceptor

- Adds timestamp for performance tracking
- Logs requests in development mode
- TODO: Add authentication tokens

### Response Interceptor

- Logs responses in development mode
- Transforms error messages
- Handles common HTTP error codes (401, 403, 404, 500)
- Provides user-friendly error messages

## Type Safety

All API responses are fully typed:

```typescript
import type { RuleMetadata, MonitoringMetrics } from '@/types/api';

const { data } = useRules();
// data is typed as RuleMetadata[] | undefined

const { data: metrics } = useMetrics();
// metrics is typed as MonitoringMetrics | undefined
```

## Testing

The API client can be mocked for testing:

```typescript
import { rest } from 'msw';

const handlers = [
  rest.get('/api/v1/rules', (req, res, ctx) => {
    return res(ctx.json([
      { rule_code: 'TEST_RULE', description: 'Test' }
    ]));
  }),
];
```

## Troubleshooting

### CORS Issues

If you see CORS errors, ensure the backend is running and the Vite proxy is configured in `vite.config.ts`:

```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
}
```

### Network Errors

Check:
1. Backend is running on port 8080
2. `VITE_API_URL` environment variable is correct
3. Firewall/network settings allow connections

### Type Errors

Ensure TypeScript definitions match backend DTOs. Update `src/types/api.ts` if backend changes.

## Future Enhancements

- [ ] Add authentication tokens to requests
- [ ] Implement request retry logic with exponential backoff
- [ ] Add request deduplication
- [ ] Implement optimistic updates for mutations
- [ ] Add GraphQL support
