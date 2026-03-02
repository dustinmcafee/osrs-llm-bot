#!/bin/bash
# Wrapper that Bolt calls instead of runelite.jar directly.
# Resolves paths relative to this script and $HOME.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BOLT_DATA="$HOME/.var/app/com.adamcake.Bolt/data/bolt-launcher"
PLUGIN_JAR="$SCRIPT_DIR/build/libs/claude-osrs-bot-1.0.0.jar"
LOG="$SCRIPT_DIR/bolt-launch.log"
JAVA="/app/jre/bin/java"
if [ ! -f "$JAVA" ]; then
    JAVA="/usr/bin/java"
fi

echo "=== Launch at $(date) ===" > "$LOG"
echo "Args: $@" >> "$LOG"

# Copy plugin to sideloaded-plugins
mkdir -p "$BOLT_DATA/.runelite/sideloaded-plugins"
cp "$PLUGIN_JAR" "$BOLT_DATA/.runelite/sideloaded-plugins/" 2>> "$LOG"

# Launch runelite.jar with extra flags, passing through all Bolt args
exec "$JAVA" -jar "$BOLT_DATA/runelite.jar" \
    --developer-mode \
    --insecure-write-credentials \
    "$@"
