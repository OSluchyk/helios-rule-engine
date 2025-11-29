# Helios Service Module

The `helios-service` module is the application layer of the Helios Rule Engine. It packages the core engine into a runnable service with an HTTP interface.

## 1. Application Entry Point

The `RuleEngineApplication` class is the main entry point. It is responsible for:
1.  **Configuration**: Loading environment variables and system properties.
2.  **Wiring**: Instantiating the `TracingService`, `EngineModelManager`, and `HttpServer`.
3.  **Lifecycle**: Managing the startup and graceful shutdown of the application.

## 2. HTTP Server

The module includes a lightweight, non-blocking `HttpServer` (based on `com.sun.net.httpserver` or similar lightweight implementation) to expose the rule engine's capabilities.

### 2.1. Endpoints
*   `POST /evaluate`: Accepts a JSON event and returns the evaluation result (matched rules).
*   `/health`: Health check endpoint for orchestration (Kubernetes/Docker).

## 3. Deployment

This module is configured to build an **executable JAR** (Uber-JAR) using the `maven-shade-plugin`. This JAR contains all dependencies and can be run directly:

```bash
java -jar helios-service-1.0.0-SNAPSHOT.jar
```
