# Helios API Module

The `helios-api` module defines the core contracts, data models, and service interfaces for the Helios Rule Engine. It serves as the foundation for all other modules, ensuring loose coupling and type safety across the system.

## 1. Core Concepts

### 1.1. Engine Model
The `EngineModel` is the central data structure representing the compiled rule set. It is designed for:
*   **Immutability**: Safe for concurrent access without locking.
*   **Performance**: Optimized for fast traversal during evaluation.
*   **Compactness**: Uses array-based structures (SoA - Structure of Arrays) where possible to minimize object overhead.

```java
// Example usage
EngineModel model = compiler.compile(rulesPath);
int ruleCount = model.getNumRules();
```

### 1.2. Domain Models
*   **`Event`**: Represents the input data to be evaluated. It consists of an ID, a type, and a map of attributes.
*   **`Rule`**: Represents a business rule with a set of conditions and a priority.
*   **`Condition`**: A single predicate (e.g., `amount > 100`) that must be satisfied.

## 2. Service Interfaces

To support modularity and dependency injection (via `ServiceLoader`), the API defines key interfaces:

*   **`IRuleCompiler`**: Contract for compiling raw rules (JSON/files) into an `EngineModel`.
*   **`IRuleEvaluator`**: Contract for evaluating `Event`s against an `EngineModel`.
*   **`IEngineModelManager`**: Contract for managing the lifecycle of the `EngineModel` (loading, updates).

## 3. Design Principles

*   **Zero Dependencies**: This module has minimal external dependencies to keep the API surface clean.
*   **Interface-Based**: All major components are defined by interfaces, allowing for swappable implementations (e.g., different compiler strategies).
