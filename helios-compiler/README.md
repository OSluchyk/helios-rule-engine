# Helios Compiler Module

The `helios-compiler` module is responsible for transforming human-readable rules (JSON) into the optimized `EngineModel` used by the runtime. It applies several compile-time optimizations to ensure high-performance evaluation.

## 1. Compilation Process

The compilation pipeline consists of the following steps:

1.  **Parsing**: Reads JSON rule definitions into intermediate objects.
2.  **Validation**: Checks for structural correctness and valid operators.
3.  **Dictionary Encoding**: Converts string values (field names, string literals) into integer IDs. This "interning" process significantly reduces memory usage and allows for faster integer-based comparisons during runtime.
4.  **Optimization**: Applies heuristic-based transformations to the rule set (see below).
5.  **Model Generation**: Constructs the final immutable `EngineModel`.

## 2. Optimizations

### 2.1. Smart `IS_ANY_OF` Factorization
The `SmartIsAnyOfFactorizer` is a key component that optimizes `IS_ANY_OF` (set membership) predicates.

*   **Problem**: Naively evaluating `field IN [v1, v2, ..., vn]` is O(N).
*   **Solution**: The compiler detects large sets and converts them into a `HashSet` (or optimized bitmap equivalent) during compilation.
*   **Benefit**: Runtime evaluation becomes O(1), regardless of the set size.

### 2.2. Predicate Deduplication
Identical predicates across different rules are detected and stored only once.
*   **Example**: If Rule A and Rule B both check `status == "ACTIVE"`, this condition is stored once in the `EngineModel`.
*   **Benefit**: Reduces memory footprint and allows the evaluator to cache the result of the condition once for all rules that use it.

## 3. Key Components

*   **`RuleCompiler`**: The main entry point. Implements `IRuleCompiler`.
*   **`SmartIsAnyOfFactorizer`**: Logic for optimizing set membership.
*   **`Dictionary`**: Manages the bidirectional mapping between Strings and Integers.
