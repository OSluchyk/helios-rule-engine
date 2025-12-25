#!/bin/bash

# Helios UI - Stop Script
# Stops all running Vite development servers

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  Helios Rule Engine - UI${NC}"
echo -e "${BLUE}  Stopping Development Server${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# Find and kill Vite processes
VITE_PIDS=$(pgrep -f "vite.*helios-ui" || true)

if [ -z "$VITE_PIDS" ]; then
    echo -e "${YELLOW}⚠ No Vite development servers found running${NC}"
    exit 0
fi

echo -e "${BLUE}→ Found Vite processes:${NC}"
echo "$VITE_PIDS" | while read -r pid; do
    echo -e "  PID: $pid"
done
echo ""

echo -e "${BLUE}→ Stopping processes...${NC}"
echo "$VITE_PIDS" | xargs kill -TERM 2>/dev/null || true

# Wait for processes to terminate
sleep 2

# Force kill if still running
REMAINING=$(pgrep -f "vite.*helios-ui" || true)
if [ -n "$REMAINING" ]; then
    echo -e "${YELLOW}→ Force killing remaining processes...${NC}"
    echo "$REMAINING" | xargs kill -9 2>/dev/null || true
fi

echo -e "${GREEN}✓ Development server stopped${NC}"
