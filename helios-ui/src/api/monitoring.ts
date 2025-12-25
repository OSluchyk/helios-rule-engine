/**
 * Monitoring and Metrics API
 * Endpoints for system health, performance metrics, and monitoring
 */

import { get } from './client';
import type {
  MonitoringMetrics,
  HealthStatus,
} from '../types/api';

/**
 * Get current system metrics
 */
export const getMetrics = async (): Promise<MonitoringMetrics> => {
  return get<MonitoringMetrics>('/monitoring/metrics');
};

/**
 * Get system health status
 */
export const getHealth = async (): Promise<HealthStatus> => {
  return get<HealthStatus>('/monitoring/health');
};

/**
 * React Query hooks for monitoring API
 */
export const monitoringQueryKeys = {
  all: ['monitoring'] as const,
  metrics: () => [...monitoringQueryKeys.all, 'metrics'] as const,
  health: () => [...monitoringQueryKeys.all, 'health'] as const,
};

// Export all as a single object for convenience
export const monitoringApi = {
  getMetrics,
  getHealth,
  queryKeys: monitoringQueryKeys,
};

export default monitoringApi;
