# Configuration Guide

Helios Rule Engine is configured using Java System Properties (passed via `-D` flags).

## Core Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP server port. |
| `rules.file` | `rules.json` | Path to the JSON rules file. |

## Caching Configuration

The caching layer is configured via **Environment Variables** or a `cache.properties` file.

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `CACHE_TYPE` | `IN_MEMORY` | `IN_MEMORY`, `REDIS`, `ADAPTIVE`, `NO_OP` |
| `CACHE_MAX_SIZE` | `10000` | Max entries (in-memory) |
| `CACHE_REDIS_ADDRESS` | `redis://localhost:6379` | Redis connection string |
| `CACHE_REDIS_POOL_SIZE` | `64` | Connection pool size |

> **Note:** You can also place a `cache.properties` file in the classpath or working directory.

## Observability

OpenTelemetry is configured via standard environment variables.

| Env Variable | Description |
|--------------|-------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry collector endpoint. |
| `OTEL_SERVICE_NAME` | Service name (default: `helios-rule-engine`). |

## Startup Example

```bash
java \
  -Dserver.port=9090 \
  -Drules.file=/etc/helios/prod-rules.json \
  -jar helios-service-1.0.0.jar
```

