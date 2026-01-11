package com.helios.ruleengine.service.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * CORS filter to allow cross-origin requests from the UI development server.
 * This is necessary when the UI runs on a different port (e.g., 3000) than the backend (8080).
 */
@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // Allow requests from any origin in development
        String origin = requestContext.getHeaderString("Origin");
        if (origin != null) {
            responseContext.getHeaders().add("Access-Control-Allow-Origin", origin);
        } else {
            responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        }

        responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");
        responseContext.getHeaders().add("Access-Control-Allow-Headers",
            "origin, content-type, accept, authorization, x-requested-with");
        responseContext.getHeaders().add("Access-Control-Allow-Methods",
            "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        responseContext.getHeaders().add("Access-Control-Max-Age", "86400");
    }
}
