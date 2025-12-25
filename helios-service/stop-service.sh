#!/bin/bash

# Helios Service - Stop Script
# Stops the Helios Rule Engine backend service

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  Helios Rule Engine - Stop${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# Check if PID file exists
if [ ! -f "$SCRIPT_DIR/.service-pid" ]; then
    echo -e "${YELLOW}⚠ No service PID file found${NC}"
    echo -e "${BLUE}  Checking for running service on port 8080...${NC}"

    # Try to find process by port
    if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
        PID=$(lsof -Pi :8080 -sTCP:LISTEN -t)
        echo -e "${BLUE}  Found process on port 8080 (PID: $PID)${NC}"

        # Check if it's a Helios service
        if ps -p $PID | grep -q "quarkus-run.jar"; then
            echo -e "${BLUE}  Stopping Helios service (PID: $PID)...${NC}"
            kill $PID
            sleep 2

            # Force kill if still running
            if kill -0 $PID 2>/dev/null; then
                echo -e "${YELLOW}  Process still running, forcing shutdown...${NC}"
                kill -9 $PID
            fi

            echo -e "${GREEN}✓ Service stopped${NC}"
            exit 0
        else
            echo -e "${YELLOW}⚠ Process on port 8080 is not a Helios service${NC}"
            exit 1
        fi
    else
        echo -e "${GREEN}✓ No service running on port 8080${NC}"
        exit 0
    fi
fi

# Read PID from file
SERVICE_PID=$(cat "$SCRIPT_DIR/.service-pid")

# Check if process is running
if ! kill -0 $SERVICE_PID 2>/dev/null; then
    echo -e "${YELLOW}⚠ Service process (PID: $SERVICE_PID) is not running${NC}"
    rm "$SCRIPT_DIR/.service-pid"
    echo -e "${GREEN}✓ Cleaned up stale PID file${NC}"
    exit 0
fi

# Stop the service
echo -e "${BLUE}→ Stopping Helios service (PID: $SERVICE_PID)...${NC}"
kill $SERVICE_PID

# Wait for graceful shutdown (max 10 seconds)
for i in {1..10}; do
    if ! kill -0 $SERVICE_PID 2>/dev/null; then
        echo -e "${GREEN}✓ Service stopped gracefully${NC}"
        rm "$SCRIPT_DIR/.service-pid"
        exit 0
    fi
    sleep 1
done

# Force kill if still running
if kill -0 $SERVICE_PID 2>/dev/null; then
    echo -e "${YELLOW}  Service did not stop gracefully, forcing shutdown...${NC}"
    kill -9 $SERVICE_PID
    sleep 1

    if ! kill -0 $SERVICE_PID 2>/dev/null; then
        echo -e "${GREEN}✓ Service stopped (forced)${NC}"
        rm "$SCRIPT_DIR/.service-pid"
        exit 0
    else
        echo -e "${RED}✗ Failed to stop service${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓ Service stopped${NC}"
rm "$SCRIPT_DIR/.service-pid"
