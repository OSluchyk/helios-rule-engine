#!/bin/bash

# Helios UI - Start Script
# Starts the development server for the Helios Rule Engine UI

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  Helios Rule Engine - UI${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
    echo -e "${YELLOW}⚠ Dependencies not installed${NC}"
    echo -e "${BLUE}→ Running npm install...${NC}"
    npm install
    echo ""
fi

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo -e "${RED}✗ Node.js is not installed${NC}"
    echo -e "  Please install Node.js >= 18.0.0"
    echo -e "  https://nodejs.org/"
    exit 1
fi

# Check Node version
NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo -e "${RED}✗ Node.js version 18 or higher is required${NC}"
    echo -e "  Current version: $(node -v)"
    echo -e "  Please upgrade Node.js"
    exit 1
fi

echo -e "${GREEN}✓ Node.js version: $(node -v)${NC}"
echo -e "${GREEN}✓ npm version: $(npm -v)${NC}"
echo ""

# Check if backend service is running
echo -e "${BLUE}→ Checking backend service...${NC}"
BACKEND_URL=${VITE_API_URL:-http://localhost:8080}

if ! curl -s --connect-timeout 3 "$BACKEND_URL/health" > /dev/null 2>&1; then
    echo -e "${YELLOW}⚠ Backend service is not running${NC}"
    echo -e "${BLUE}→ Starting backend service...${NC}"

    # Navigate to project root
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

    # Check if backend JAR exists
    BACKEND_JAR="$PROJECT_ROOT/helios-service/target/quarkus-app/quarkus-run.jar"

    if [ ! -f "$BACKEND_JAR" ]; then
        echo -e "${YELLOW}  Backend JAR not found, building...${NC}"
        cd "$PROJECT_ROOT"
        mvn clean package -DskipTests -pl helios-service -am

        if [ $? -ne 0 ]; then
            echo -e "${RED}✗ Failed to build backend service${NC}"
            exit 1
        fi
    fi

    # Start backend service in background
    echo -e "${BLUE}  Starting backend on port 8080...${NC}"
    cd "$PROJECT_ROOT"
    nohup java --add-modules jdk.incubator.vector -jar "$BACKEND_JAR" > helios-service/backend.log 2>&1 &
    BACKEND_PID=$!

    # Save PID for cleanup
    echo $BACKEND_PID > "$SCRIPT_DIR/.backend-pid"

    # Wait for backend to be ready (max 30 seconds)
    echo -e "${BLUE}  Waiting for backend to start...${NC}"
    for i in {1..30}; do
        if curl -s --connect-timeout 1 "$BACKEND_URL/health" > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Backend service started (PID: $BACKEND_PID)${NC}"
            echo -e "${GREEN}  Logs: helios-service/backend.log${NC}"
            break
        fi
        sleep 1
        if [ $i -eq 30 ]; then
            echo -e "${RED}✗ Backend service failed to start${NC}"
            echo -e "${YELLOW}  Check logs: tail -f $PROJECT_ROOT/helios-service/backend.log${NC}"
            exit 1
        fi
    done
else
    echo -e "${GREEN}✓ Backend service is running${NC}"
fi

echo ""

# Start the development server
echo -e "${BLUE}→ Starting UI development server...${NC}"
echo -e "${BLUE}  URL: http://localhost:3000${NC}"
echo -e "${BLUE}  Backend API: $BACKEND_URL/api/v1${NC}"
echo ""
echo -e "${YELLOW}  Press Ctrl+C to stop${NC}"
echo ""

# Note: VITE_API_URL is configured in .env file
# Vite will automatically load environment variables from .env

# Cleanup function to stop backend if we started it
cleanup() {
    echo ""
    echo -e "${BLUE}→ Shutting down...${NC}"

    # Stop backend if we started it
    if [ -f "$SCRIPT_DIR/.backend-pid" ]; then
        BACKEND_PID=$(cat "$SCRIPT_DIR/.backend-pid")
        if kill -0 $BACKEND_PID 2>/dev/null; then
            echo -e "${BLUE}  Stopping backend service (PID: $BACKEND_PID)...${NC}"
            kill $BACKEND_PID
            rm "$SCRIPT_DIR/.backend-pid"
        fi
    fi

    echo -e "${GREEN}✓ Cleanup complete${NC}"
}

# Register cleanup function
trap cleanup EXIT INT TERM

# Start Vite dev server
npm run dev
