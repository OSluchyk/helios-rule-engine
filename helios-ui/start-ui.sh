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

# Start the development server
echo -e "${BLUE}→ Starting development server...${NC}"
echo -e "${BLUE}  URL: http://localhost:3000${NC}"
echo -e "${BLUE}  Backend API: http://localhost:8080/api${NC}"
echo ""
echo -e "${YELLOW}  Press Ctrl+C to stop${NC}"
echo ""

# Export environment variable for API URL (can be overridden)
export VITE_API_URL=${VITE_API_URL:-http://localhost:8080}

# Start Vite dev server
npm run dev
