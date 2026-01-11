package com.helios.ruleengine.service.config;

import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Handles CORS preflight OPTIONS requests for all paths.
 */
@Path("{path:.*}")
public class CorsPreflightHandler {

    @OPTIONS
    public Response handlePreflight() {
        return Response.ok()
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Headers", "origin, content-type, accept, authorization, x-requested-with")
            .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD")
            .header("Access-Control-Max-Age", "86400")
            .build();
    }
}
