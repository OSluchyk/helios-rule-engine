/**
 * React Query hooks for Monitoring API
 * Provides real-time system metrics and health status
 */

import { useQuery, UseQueryOptions } from '@tanstack/react-query';
import { monitoringApi } from '../api/monitoring';
import type { MonitoringMetrics, HealthStatus, ApiError } from '../types/api';

/**
 * Hook to fetch system metrics
 * Auto-refreshes every 5 seconds for real-time monitoring
 */
export const useMetrics = (
  options?: Omit<UseQueryOptions<MonitoringMetrics, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<MonitoringMetrics, ApiError>({
    queryKey: monitoringApi.queryKeys.metrics(),
    queryFn: () => monitoringApi.getMetrics(),
    refetchInterval: 5000, // Auto-refresh every 5 seconds
    staleTime: 0, // Always consider stale for real-time data
    ...options,
  });
};

/**
 * Hook to fetch system health status
 * Auto-refreshes every 10 seconds
 */
export const useHealth = (
  options?: Omit<UseQueryOptions<HealthStatus, ApiError>, 'queryKey' | 'queryFn'>
) => {
  return useQuery<HealthStatus, ApiError>({
    queryKey: monitoringApi.queryKeys.health(),
    queryFn: () => monitoringApi.getHealth(),
    refetchInterval: 10000, // Auto-refresh every 10 seconds
    staleTime: 0,
    ...options,
  });
};
