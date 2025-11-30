# Configuration Guide

Helios Rule Engine is configured using Java System Properties (passed via `-D` flags).

## Core Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP server port. |
| `rules.file` | `rules.json` | Path to the JSON rules file. |

## Caching Configuration

The caching layer is configured via **Environment Variables** or a `cache.properties` file.

### Common Cache Settings

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `CACHE_TYPE` | `CAFFEINE` | `IN_MEMORY`, `CAFFEINE`, `ADAPTIVE`, `REDIS`, `NO_OP` |
| `CACHE_MAX_SIZE` | `100000` | Maximum number of cache entries |
| `CACHE_INITIAL_CAPACITY` | `0` | Initial capacity hint (0 = auto) |
| `CACHE_TTL_MINUTES` | `10` | Time-to-live in minutes |
| `CACHE_TTL_SECONDS` | - | Time-to-live in seconds (overrides minutes) |
| `CACHE_RECORD_STATS` | `true` | Enable cache statistics |

### Redis-Specific Settings

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `CACHE_REDIS_ADDRESS` | `redis://localhost:6379` | Redis connection string |
| `CACHE_REDIS_PASSWORD` | - | Redis password (optional) |
| `CACHE_REDIS_CONNECTION_POOL_SIZE` | `64` | Connection pool size |
| `CACHE_REDIS_MIN_IDLE_SIZE` | `24` | Minimum idle connections |
| `CACHE_REDIS_TIMEOUT_MS` | `3000` | Operation timeout in milliseconds |
| `CACHE_REDIS_COMPRESSION_THRESHOLD` | `1024` | Compress values larger than this (bytes) |
| `CACHE_REDIS_USE_CLUSTER` | `false` | Enable Redis cluster mode |

### Adaptive Cache Settings

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `CACHE_ENABLE_ADAPTIVE_SIZING` | `false` | Enable auto-sizing |
| `CACHE_MIN_CACHE_SIZE` | `10000` | Minimum cache size |
| `CACHE_MAX_CACHE_SIZE` | `10000000` | Maximum cache size |
| `CACHE_LOW_HIT_RATE_THRESHOLD` | `0.70` | Low hit rate threshold (0.0-1.0) |
| `CACHE_HIGH_HIT_RATE_THRESHOLD` | `0.95` | High hit rate threshold (0.0-1.0) |
| `CACHE_TUNING_INTERVAL_SECONDS` | `30` | How often to adjust size (seconds) |

### Using cache.properties File

You can also place a `cache.properties` file in the classpath or working directory:

```properties
# Cache type selection
cache.type=ADAPTIVE

# Common settings
cache.max.size=250000
cache.ttl.minutes=10
cache.record.stats=true

# Adaptive settings
cache.adaptive.enabled=true
cache.adaptive.min.size=100000
cache.adaptive.max.size=5000000
cache.adaptive.low.threshold=0.70
cache.adaptive.high.threshold=0.95
cache.adaptive.tuning.interval.seconds=30

# Redis settings (if using REDIS type)
cache.redis.address=redis://localhost:6379
cache.redis.password=secret
cache.redis.pool.size=64
cache.redis.min.idle=24
cache.redis.timeout.ms=3000
cache.redis.compression.threshold=512
cache.redis.use.cluster=false
```

Then load in code:
```java
CacheConfig config = CacheConfig.loadDefault();
BaseConditionCache cache = CacheFactory.create(config);
```

> **Note:** Environment variables take precedence over properties file values.

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

