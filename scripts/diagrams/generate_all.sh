#!/bin/bash
# Regenerate all PNG diagrams in docs/images/
# Requires: pip install matplotlib numpy

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for script in "$SCRIPT_DIR"/generate_*.py; do
    echo "Running $(basename "$script")..."
    python3 "$script"
done

echo "All diagrams regenerated."
