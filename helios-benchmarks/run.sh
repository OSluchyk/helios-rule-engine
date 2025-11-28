# Resolve script directory to handle running from root or module dir
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MODULE_DIR="$SCRIPT_DIR"

# Default to the Java 25 installation found in the environment if JAVA_HOME is not set
# or if the default java is too old.
CANDIDATE_JAVA="/opt/homebrew/Cellar/openjdk/25.0.1/libexec/openjdk.jdk/Contents/Home/bin/java"

if [ -z "$JAVA_HOME" ]; then
    if [ -x "$CANDIDATE_JAVA" ]; then
        JAVA_CMD="$CANDIDATE_JAVA"
    else
        JAVA_CMD="java"
    fi
else
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

echo "Using Java: $($JAVA_CMD -version 2>&1 | head -n 1)"

JAR_FILE="$MODULE_DIR/target/benchmarks.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Benchmark JAR not found at $JAR_FILE. Building..."
    # Run maven from project root
    (cd "$PROJECT_ROOT" && mvn clean package -pl helios-benchmarks -am -DskipTests)
fi

echo "Running SimpleBenchmark (Throughput, Batch 100)..."
"$JAVA_CMD" -jar "$JAR_FILE" SimpleBenchmark.throughput_batch100 -f 1 -wi 5 -i 5 -p ruleCount=500 -p workloadType=MIXED -p cacheScenario=HOT
