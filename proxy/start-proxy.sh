#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== OSRS Claude Proxy ==="
echo ""

# Check Node.js
if ! command -v node &>/dev/null; then
    echo -e "${RED}Error: Node.js not found.${NC}"
    echo "Install via: curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs"
    exit 1
fi

NODE_VER=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VER" -lt 18 ]; then
    echo -e "${RED}Error: Node.js 18+ required (found v$(node -v))${NC}"
    exit 1
fi
echo -e "  Node.js: ${GREEN}$(node -v)${NC}"

# Check claude CLI
if ! command -v claude &>/dev/null; then
    echo -e "${RED}Error: claude CLI not found.${NC}"
    echo "Install via: npm install -g @anthropic-ai/claude-code"
    echo "Then run: claude login"
    exit 1
fi
echo -e "  Claude:  ${GREEN}$(claude --version 2>/dev/null || echo 'installed')${NC}"

# Install dependencies if needed
if [ ! -d node_modules ]; then
    echo ""
    echo -e "${YELLOW}Installing dependencies...${NC}"
    npm install --production
    echo ""
fi

# Start server
echo ""
echo -e "${GREEN}Starting proxy...${NC}"
echo ""

exec node server.mjs "$@"
