/**
 * Test Utilities
 * Provides common testing utilities and wrappers for React Testing Library
 */

import React, { ReactElement, ReactNode } from 'react';
import { render, RenderOptions, RenderResult } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Create a new QueryClient for each test to ensure test isolation
const createTestQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: {
        retry: false, // Don't retry failed queries in tests
        gcTime: 0, // Garbage collect immediately
        staleTime: 0, // Data is immediately stale
      },
      mutations: {
        retry: false,
      },
    },
  });

interface WrapperProps {
  children: ReactNode;
}

/**
 * Custom wrapper that provides all necessary providers
 */
const AllTheProviders = ({ children }: WrapperProps) => {
  const queryClient = createTestQueryClient();

  return (
    <QueryClientProvider client={queryClient}>
      {children}
    </QueryClientProvider>
  );
};

/**
 * Custom render function that wraps components in necessary providers
 */
const customRender = (
  ui: ReactElement,
  options?: Omit<RenderOptions, 'wrapper'>
): RenderResult =>
  render(ui, { wrapper: AllTheProviders, ...options });

/**
 * Create a wrapper with a specific QueryClient (for hook testing)
 */
export const createWrapper = () => {
  const queryClient = createTestQueryClient();
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
};

/**
 * Create a test event object
 */
export const createTestEvent = (overrides?: Partial<{
  eventId: string;
  eventType: string | null;
  timestamp: number;
  attributes: Record<string, unknown>;
}>) => ({
  eventId: 'test-event-001',
  eventType: 'TRANSACTION',
  timestamp: Date.now(),
  attributes: {
    amount: 10000,
    status: 'ACTIVE',
    country: 'US',
  },
  ...overrides,
});

/**
 * Wait for async operations to complete
 */
export const waitForLoadingToFinish = () =>
  new Promise((resolve) => setTimeout(resolve, 0));

/**
 * Mock event JSON for evaluation tests
 */
export const mockEventJson = JSON.stringify(
  {
    eventId: 'test-001',
    timestamp: 1704067200000,
    attributes: {
      amount: 10000,
      status: 'ACTIVE',
      country: 'US',
    },
  },
  null,
  2
);

/**
 * Mock event JSON that should not match any rules
 */
export const mockEventJsonNoMatch = JSON.stringify(
  {
    eventId: 'test-002',
    timestamp: 1704067200000,
    attributes: {
      amount: 100,
      status: 'PENDING',
      country: 'FR',
    },
  },
  null,
  2
);

/**
 * Invalid JSON for error testing
 */
export const invalidJson = '{ invalid json }';

// Re-export everything from React Testing Library
export * from '@testing-library/react';

// Override the render function
export { customRender as render };
