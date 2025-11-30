# Configuration Guide

Helios Rule Engine is configured using Java System Properties (passed via `-D` flags).

## Core Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP server port. |
| `rules.file` | `rules.json` | Path to the JSON rules file. |

## Caching Configuration

## Caching Configuration

The caching layer is configured via **Environment Variables** (highest precedence) or a `cache.properties` file (classpath or working directory).

### 1. Common Cache Settings
Applies to all cache types (`IN_MEMORY`, `CAFFEINE`, `ADAPTIVE`, `REDIS`).

| Env Variable | Property | Default | Description |
|--------------|----------|---------|-------------|
| `CACHE_TYPE` | `cache.type` | `CAFFEINE` | Implementation type. |
| `CACHE_MAX_SIZE` | `cache.max.size` | `100000` | Max entries (local caches). |
| `CACHE_TTL_MINUTES` | `cache.ttl.minutes` | `10` | Time-to-live. |
| `CACHE_RECORD_STATS` | `cache.record.stats` | `true` | Enable metrics. |

### 2. Redis-Specific Settings
Required when `CACHE_TYPE=REDIS`.

| Env Variable | Property | Default | Description |
|--------------|----------|---------|-------------|
| `CACHE_REDIS_ADDRESS` | `cache.redis.address` | `redis://localhost:6379` | Connection string. |
| `CACHE_REDIS_PASSWORD` | `cache.redis.password` | - | Auth password. |
| `CACHE_REDIS_CONNECTION_POOL_SIZE` | `cache.redis.pool.size` | `64` | Max connections. |
| `CACHE_REDIS_MIN_IDLE_SIZE` | `cache.redis.min.idle` | `24` | Min idle connections. |
| `CACHE_REDIS_TIMEOUT_MS` | `cache.redis.timeout.ms` | `3000` | Socket timeout. |
| `CACHE_REDIS_COMPRESSION_THRESHOLD` | `cache.redis.compression.threshold` | `1024` | Compress values > N bytes. |
| `CACHE_REDIS_USE_CLUSTER` | `cache.redis.use.cluster` | `false` | Enable cluster mode. |

### 3. Adaptive Cache Settings
Required when `CACHE_TYPE=ADAPTIVE`.

| Env Variable | Property | Default | Description |
|--------------|----------|---------|-------------|
| `CACHE_ENABLE_ADAPTIVE_SIZING` | `cache.adaptive.enabled` | `false` | Enable auto-sizing. |
| `CACHE_MIN_CACHE_SIZE` | `cache.adaptive.min.size` | `10000` | Minimum size floor. |
| `CACHE_MAX_CACHE_SIZE` | `cache.adaptive.max.size` | `10000000` | Maximum size ceiling. |
| `CACHE_LOW_HIT_RATE_THRESHOLD` | `cache.adaptive.low.threshold` | `0.70` | Scale up trigger (<70%). |
| `CACHE_HIGH_HIT_RATE_THRESHOLD` | `cache.adaptive.high.threshold` | `0.95` | Scale down trigger (>95%). |
| `CACHE_TUNING_INTERVAL_SECONDS` | `cache.adaptive.tuning.interval.seconds` | `30` | Tuning frequency. |

### Example `cache.properties`

```properties
# General
cache.type=ADAPTIVE
cache.ttl.minutes=15
cache.record.stats=true

# Adaptive Tuning
cache.adaptive.enabled=true
cache.adaptive.min.size=50000
cache.adaptive.max.size=500000
cache.adaptive.low.threshold=0.75

# Redis Fallback (if switched)
cache.redis.address=redis://prod-cluster:6379
cache.redis.pool.size=128
```

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

