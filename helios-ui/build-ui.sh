#!/bin/bash

# Helios UI - Build Script
# Creates production build of the Helios Rule Engine UI

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
echo -e "${BLUE}  Production Build${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
    echo -e "${YELLOW}⚠ Dependencies not installed${NC}"
    echo -e "${BLUE}→ Running npm install...${NC}"
    npm install
    echo ""
fi

# Clean previous build
if [ -d "dist" ]; then
    echo -e "${BLUE}→ Cleaning previous build...${NC}"
    rm -rf dist
fi

# Type check
echo -e "${BLUE}→ Running type check...${NC}"
npm run type-check

if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Type check failed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Type check passed${NC}"
echo ""

# Build
echo -e "${BLUE}→ Building production bundle...${NC}"
npm run build

if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}✓ Build completed successfully${NC}"
echo -e "${GREEN}  Output directory: dist/${NC}"
echo ""

# Show build stats
if [ -d "dist" ]; then
    DIST_SIZE=$(du -sh dist | cut -f1)
    echo -e "${BLUE}Build Statistics:${NC}"
    echo -e "  Total size: ${GREEN}$DIST_SIZE${NC}"
    echo ""
    echo -e "${BLUE}Files:${NC}"
    find dist -type f -name "*.js" -o -name "*.css" | while read -r file; do
        SIZE=$(du -h "$file" | cut -f1)
        FILENAME=$(basename "$file")
        echo -e "  $FILENAME: $SIZE"
    done
fi

echo ""
echo -e "${BLUE}To preview the build:${NC}"
echo -e "  ${YELLOW}npm run preview${NC}"
echo ""
echo -e "${BLUE}To deploy to production:${NC}"
echo -e "  ${YELLOW}Copy dist/ contents to your web server${NC}"
