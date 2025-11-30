# Getting Started with Helios Rule Engine

## Prerequisites
- **JDK 25** or higher (Required for Vector API and Compact Headers)
- **Apache Maven 3.8+**

## Installation

```bash
# Clone and build
git clone https://github.com/your-org/helios-rule-engine.git
cd helios-rule-engine
mvn clean package

# Run the server
java -jar target/rule-engine-1.0.0.jar
```

## Your First Rule

1. **Create a rules file** (`rules.json`):
   ```json
   [
     {
       "rule_code": "HIGH_VALUE_ORDER",
       "priority": 100,
       "description": "Flag high-value orders from premium customers",
       "conditions": [
         {"field": "order_amount", "operator": "GREATER_THAN", "value": 10000},
         {"field": "customer_tier", "operator": "EQUAL_TO", "value": "PLATINUM"}
       ]
     }
   ]
   ```

2. **Compile and Evaluate**:
   ```java
   RuleCompiler compiler = new RuleCompiler(tracer);
   EngineModel model = compiler.compile(Paths.get("rules.json"));
   RuleEvaluator evaluator = new RuleEvaluator(model);

   Event event = new Event("evt-123", "ORDER", Map.of(
       "order_amount", 15000,
       "customer_tier", "PLATINUM"
   ));

   MatchResult result = evaluator.evaluate(event);
   System.out.println("Matched rules: " + result.matchedRules());
   ```

## Working with Events

Events are the data payload evaluated against rules.

```java
Event event = new Event(
    "unique-event-id",      // Event ID (required)
    "EVENT_TYPE",           // Event type (optional, used for routing)
    Map.of(                 // Attributes
        "field1", "value1",
        "field2", 42,
        "field3", true
    )
);
```

### Supported Attribute Types
- **String:** `"status": "ACTIVE"`
- **Integer/Long:** `"amount": 1000`
- **Double:** `"price": 99.99`
- **Boolean:** `"verified": true`

> **Note:** Field names are automatically normalized (uppercased, hyphens to underscores).

## Loading Rules

### From File
```java
EngineModel model = compiler.compile(Paths.get("rules.json"));
```

### Hot Reload (Production)
Automatically reload rules when the file changes without downtime.

```java
EngineModelManager manager = new EngineModelManager(Paths.get("rules.json"), tracer);
manager.start();  // Starts background watcher

// Always get the latest model
RuleEvaluator evaluator = new RuleEvaluator(manager.getEngineModel());
```

## Caching Configuration

For production deployments, configure caching to improve performance.

### Quick Start (Default Cache)
```java
// Uses default Caffeine cache
RuleEvaluator evaluator = new RuleEvaluator(model);
```

### Production Configuration
```java
// Using CacheConfig and CacheFactory (recommended)
CacheConfig config = CacheConfig.forProduction();
BaseConditionCache cache = CacheFactory.create(config);
RuleEvaluator evaluator = new RuleEvaluator(model, cache, true);
```

### Environment-Based Configuration
```bash
# Set environment variables
export CACHE_TYPE=ADAPTIVE
export CACHE_MAX_SIZE=100000
export CACHE_TTL_MINUTES=10
```

```java
// Load from environment
CacheConfig config = CacheConfig.fromEnvironment();
BaseConditionCache cache = CacheFactory.create(config);
RuleEvaluator evaluator = new RuleEvaluator(model, cache, true);
```

For more details, see [Performance Tuning Guide](performance-tuning.md) and [Configuration Guide](configuration.md).
