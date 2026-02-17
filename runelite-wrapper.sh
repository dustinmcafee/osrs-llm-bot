#!/bin/bash
# Wrapper that Bolt calls instead of runelite.jar directly.
# Uses hardcoded paths because $HOME is remapped inside flatpak sandbox.

BOLT_DATA="/home/dustin/.var/app/com.adamcake.Bolt/data/bolt-launcher"
PLUGIN_JAR="/home/dustin/workingdir/apk_source/osrs/build/libs/claude-osrs-bot-1.0.0.jar"
LOG="/home/dustin/workingdir/apk_source/osrs/bolt-launch.log"
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
