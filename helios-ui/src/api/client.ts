/**
 * Base Axios HTTP client for Helios Rule Engine API
 * Configured with interceptors for error handling and request/response logging
 */

import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios';
import type { ApiError } from '../types/api';

// Determine the API base URL
// In development on localhost: use relative path to go through Vite proxy
// In development on network IP: use same host with backend port (8080)
// In production: use configured API URL or default to localhost:8080
const getBaseUrl = (): string => {
  if (typeof window === 'undefined') {
    return import.meta.env.VITE_API_URL || 'http://localhost:8080';
  }

  const { hostname } = window.location;
  const isLocalhost = hostname === 'localhost' || hostname === '127.0.0.1';

  if (import.meta.env.DEV) {
    // In dev mode on localhost, use proxy (empty base URL)
    // On network IP, connect directly to backend on same host with port 8080
    return isLocalhost ? '' : `http://${hostname}:8080`;
  }

  return import.meta.env.VITE_API_URL || 'http://localhost:8080';
};

const BASE_URL = getBaseUrl();

/**
 * Create and configure the Axios instance
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  timeout: 30000, // 30 second timeout
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * Request interceptor
 * Adds authentication tokens, request IDs, and logs requests in development
 */
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // Add request timestamp for performance tracking
    config.metadata = { startTime: Date.now() };

    // Log requests in development mode
    if (import.meta.env.DEV) {
      console.log(`[API Request] ${config.method?.toUpperCase()} ${config.url}`, {
        params: config.params,
        data: config.data,
      });
    }

    // TODO: Add authentication token when auth is implemented
    // const token = localStorage.getItem('auth_token');
    // if (token) {
    //   config.headers.Authorization = `Bearer ${token}`;
    // }

    return config;
  },
  (error: AxiosError) => {
    console.error('[API Request Error]', error);
    return Promise.reject(error);
  }
);

/**
 * Response interceptor
 * Handles errors, logs responses, and transforms error messages
 */
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    // Calculate request duration
    const duration = Date.now() - (response.config.metadata?.startTime || 0);

    // Log responses in development mode
    if (import.meta.env.DEV) {
      console.log(
        `[API Response] ${response.config.method?.toUpperCase()} ${response.config.url} (${duration}ms)`,
        {
          status: response.status,
          data: response.data,
        }
      );
    }

    return response;
  },
  (error: AxiosError<ApiError>) => {
    // Log errors
    console.error('[API Response Error]', {
      message: error.message,
      status: error.response?.status,
      data: error.response?.data,
    });

    // Transform error for better handling
    const apiError: ApiError = {
      error: error.response?.data?.error || 'Request Failed',
      message: error.response?.data?.message || error.message,
      status: error.response?.status,
      details: error.response?.data?.details,
    };

    // Handle specific error cases
    if (error.response?.status === 401) {
      // TODO: Handle unauthorized - redirect to login
      console.warn('[API] Unauthorized access - authentication required');
    } else if (error.response?.status === 403) {
      console.warn('[API] Forbidden - insufficient permissions');
    } else if (error.response?.status === 404) {
      console.warn('[API] Resource not found');
    } else if (error.response?.status === 500) {
      console.error('[API] Internal server error');
    } else if (error.code === 'ECONNABORTED') {
      apiError.message = 'Request timeout - server did not respond in time';
    } else if (error.code === 'ERR_NETWORK') {
      apiError.message = 'Network error - unable to connect to server';
    }

    return Promise.reject(apiError);
  }
);

/**
 * Utility function to handle API errors in components
 */
export const getErrorMessage = (error: unknown): string => {
  if (typeof error === 'object' && error !== null && 'message' in error) {
    return (error as ApiError).message;
  }
  return 'An unexpected error occurred';
};

/**
 * Type-safe wrapper for GET requests
 */
export const get = async <T>(url: string, params?: Record<string, any>): Promise<T> => {
  const response = await apiClient.get<T>(url, { params });
  return response.data;
};

/**
 * Type-safe wrapper for POST requests
 */
export const post = async <T>(url: string, data?: any): Promise<T> => {
  const response = await apiClient.post<T>(url, data);
  return response.data;
};

/**
 * Type-safe wrapper for PUT requests
 */
export const put = async <T>(url: string, data?: any): Promise<T> => {
  const response = await apiClient.put<T>(url, data);
  return response.data;
};

/**
 * Type-safe wrapper for DELETE requests
 */
export const del = async <T>(url: string): Promise<T> => {
  const response = await apiClient.delete<T>(url);
  return response.data;
};

export default apiClient;

// Extend AxiosRequestConfig to include metadata
declare module 'axios' {
  export interface InternalAxiosRequestConfig {
    metadata?: {
      startTime: number;
    };
  }
}
