#!/bin/bash

# Script to run trace overhead benchmarks with JFR profiling
# This helps validate allocation reduction from trace optimizations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BENCHMARK_JAR="$SCRIPT_DIR/target/benchmarks.jar"
JFR_DIR="$PROJECT_ROOT/jfr-trace-profiles"

# Check if benchmark JAR exists
if [ ! -f "$BENCHMARK_JAR" ]; then
    echo "ERROR: Benchmark JAR not found at $BENCHMARK_JAR"
    echo "Run: mvn clean package -pl helios-benchmarks -am -DskipTests"
    exit 1
fi

# Create JFR output directory
mkdir -p "$JFR_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "═══════════════════════════════════════════════════════════════"
echo "  Trace Overhead Benchmark with JFR Profiling"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Output directory: $JFR_DIR"
echo ""

# Function to run benchmark with JFR
run_with_jfr() {
    local scenario=$1
    local jfr_file="$JFR_DIR/trace_${scenario}_${TIMESTAMP}.jfr"

    echo "──────────────────────────────────────────────────────────────"
    echo "Running: $scenario"
    echo "JFR output: $jfr_file"
    echo "──────────────────────────────────────────────────────────────"

    # Resolve Java 25
    JAVA_CMD="java"
    if [ -x "/usr/libexec/java_home" ]; then
        if /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
             JAVA_CMD="$(/usr/libexec/java_home -v 25)/bin/java"
        fi
    fi

    echo "Using Java: $JAVA_CMD"

    $JAVA_CMD \
        -XX:+UnlockExperimentalVMOptions \
        -XX:+UseCompactObjectHeaders \
        -XX:+UseZGC \
        -Xms4g -Xmx4g \
        -XX:+AlwaysPreTouch \
        -XX:MaxInlineLevel=15 \
        -XX:InlineSmallCode=2000 \
        --add-modules=jdk.incubator.vector \
        -XX:StartFlightRecording=filename="$jfr_file",settings=profile,dumponexit=true \
        -jar "$BENCHMARK_JAR" \
        "SimpleBenchmark.latency_withTrace" \
        -p ruleCount=5000 \
        -p cacheScenario=HOT \
        -p workloadType=MIXED \
        -f 1 \
        -wi 2 \
        -i 3 \
        -rf json \
        -rff "$JFR_DIR/trace_${scenario}_${TIMESTAMP}.json"

    echo ""
    echo "✓ JFR recording saved: $jfr_file"
    echo ""
}

# Run illustrative benchmark scenarios
echo "═══════════════════════════════════════════════════════════════"
echo "  Scenario: HOT cache, 5000 rules (trace overhead validation)"
echo "═══════════════════════════════════════════════════════════════"
echo ""

run_with_jfr "hot_5000"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Profiling Complete!"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "JFR files saved to: $JFR_DIR"
echo ""
echo "To analyze with JDK Mission Control:"
echo "  jmc $JFR_DIR/trace_hot_5000_${TIMESTAMP}.jfr"
echo ""
echo "To analyze allocations programmatically:"
echo "  jfr print --events jdk.ObjectAllocationInNewTLAB $JFR_DIR/trace_hot_5000_${TIMESTAMP}.jfr | head -100"
echo ""
echo "Key metrics to check:"
echo "  1. char[] allocation rate (target: <10% of samples)"
echo "  2. IntOpenHashSet allocations in hot path (target: minimal)"
echo "  3. String.format() in buildTrace() only (not in evaluate path)"
echo ""
