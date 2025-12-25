#!/bin/bash

# Helios Service - Start Script
# Starts the Helios Rule Engine backend service

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  Helios Rule Engine - Service${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo -e "${RED}✗ Java is not installed${NC}"
    echo -e "  Please install Java 25 or higher"
    echo -e "  https://adoptium.net/"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 25 ]; then
    echo -e "${RED}✗ Java version 25 or higher is required${NC}"
    echo -e "  Current version: $(java -version 2>&1 | head -n 1)"
    echo -e "  Please upgrade Java"
    exit 1
fi

echo -e "${GREEN}✓ Java version: $(java -version 2>&1 | head -n 1 | cut -d'"' -f2)${NC}"
echo ""

# Check if service is already running
if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${YELLOW}⚠ Port 8080 is already in use${NC}"
    echo -e "${BLUE}  Checking if it's the Helios service...${NC}"

    if curl -s --connect-timeout 1 http://localhost:8080/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Helios service is already running${NC}"
        echo -e "${BLUE}  URL: http://localhost:8080${NC}"
        echo -e "${BLUE}  Health: http://localhost:8080/health${NC}"
        echo -e "${BLUE}  API: http://localhost:8080/api/v1${NC}"
        echo ""
        echo -e "${YELLOW}  Use stop-service.sh to stop the running instance${NC}"
        exit 0
    else
        echo -e "${RED}✗ Another service is using port 8080${NC}"
        echo -e "  Please stop it or use a different port"
        exit 1
    fi
fi

# Check if JAR exists
JAR_FILE="$SCRIPT_DIR/target/quarkus-app/quarkus-run.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo -e "${YELLOW}⚠ Backend JAR not found${NC}"
    echo -e "${BLUE}→ Building service...${NC}"
    echo ""

    cd "$PROJECT_ROOT"
    mvn clean package -DskipTests -pl helios-service -am

    if [ $? -ne 0 ]; then
        echo -e "${RED}✗ Build failed${NC}"
        exit 1
    fi

    echo ""
    echo -e "${GREEN}✓ Build successful${NC}"
    echo ""
fi

# Check if rules.json exists
RULES_FILE="${RULES_FILE:-rules.json}"
if [ ! -f "$PROJECT_ROOT/$RULES_FILE" ]; then
    echo -e "${YELLOW}⚠ Rules file not found: $RULES_FILE${NC}"
    echo -e "${BLUE}  Service will start but may have no rules loaded${NC}"
    echo -e "${BLUE}  Create a rules.json file in the project root or set RULES_FILE env var${NC}"
    echo ""
fi

# Start the service
echo -e "${BLUE}→ Starting Helios Rule Engine service...${NC}"
echo -e "${BLUE}  Port: 8080${NC}"
echo -e "${BLUE}  Rules file: $RULES_FILE${NC}"
echo -e "${BLUE}  Log level: ${LOG_LEVEL:-INFO}${NC}"
echo ""

# Export default environment variables if not set
export RULES_FILE="${RULES_FILE:-rules.json}"
export QUARKUS_LOG_LEVEL="${LOG_LEVEL:-INFO}"

# Start service in background
cd "$PROJECT_ROOT"
nohup java --add-modules jdk.incubator.vector -jar "$JAR_FILE" > "$SCRIPT_DIR/service.log" 2>&1 &
SERVICE_PID=$!

# Save PID for later
echo $SERVICE_PID > "$SCRIPT_DIR/.service-pid"

# Wait for service to be ready (max 30 seconds)
echo -e "${BLUE}→ Waiting for service to start...${NC}"
for i in {1..30}; do
    if curl -s --connect-timeout 1 http://localhost:8080/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Service started successfully (PID: $SERVICE_PID)${NC}"
        echo ""
        echo -e "${GREEN}Service Information:${NC}"
        echo -e "${BLUE}  URL: http://localhost:8080${NC}"
        echo -e "${BLUE}  Health: http://localhost:8080/health${NC}"
        echo -e "${BLUE}  API: http://localhost:8080/api/v1${NC}"
        echo -e "${BLUE}  Logs: $SCRIPT_DIR/service.log${NC}"
        echo -e "${BLUE}  PID: $SERVICE_PID${NC}"
        echo ""
        echo -e "${BLUE}Available Endpoints:${NC}"
        echo -e "  GET  /api/v1/rules                    - List all rules"
        echo -e "  GET  /api/v1/rules/{ruleCode}         - Get rule details"
        echo -e "  GET  /api/v1/compilation/stats        - Compilation statistics"
        echo -e "  GET  /api/v1/monitoring/metrics       - System metrics"
        echo -e "  GET  /health                          - Health check"
        echo ""
        echo -e "${YELLOW}To stop:${NC}"
        echo -e "  ./stop-service.sh"
        echo -e "  OR: kill $SERVICE_PID"
        echo ""
        echo -e "${BLUE}To view logs:${NC}"
        echo -e "  tail -f $SCRIPT_DIR/service.log"
        echo ""
        exit 0
    fi
    sleep 1

    # Check if process is still running
    if ! kill -0 $SERVICE_PID 2>/dev/null; then
        echo -e "${RED}✗ Service process died${NC}"
        echo -e "${YELLOW}  Check logs for errors:${NC}"
        echo -e "  tail -50 $SCRIPT_DIR/service.log"
        echo ""
        tail -50 "$SCRIPT_DIR/service.log"
        exit 1
    fi

    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ Service failed to start within 30 seconds${NC}"
        echo -e "${YELLOW}  The service may still be starting. Check:${NC}"
        echo -e "  tail -f $SCRIPT_DIR/service.log"
        echo ""
        echo -e "${YELLOW}Recent logs:${NC}"
        tail -20 "$SCRIPT_DIR/service.log"
        exit 1
    fi
done
