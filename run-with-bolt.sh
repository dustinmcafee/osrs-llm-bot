#!/bin/bash
# Launches RuneLite with the Claude Bot plugin via Bolt's RuneLite installation.
# Bolt handles Jagex account auth — launch through Bolt first to create a session,
# then this script reuses the same RuneLite install with --developer-mode.

BOLT_DATA="$HOME/.var/app/com.adamcake.Bolt/data/bolt-launcher"
RUNELITE_DIR="$BOLT_DATA/.runelite"
REPO_DIR="$RUNELITE_DIR/repository2"
PLUGIN_JAR="$HOME/workingdir/apk_source/osrs/build/libs/claude-osrs-bot-1.0.0.jar"
JAVA="/app/jre/bin/java"

# Use system Java if not in flatpak
if [ ! -f "$JAVA" ]; then
    JAVA="$(which java)"
fi

# Build classpath from all RuneLite JARs + our plugin
CP=""
for jar in "$REPO_DIR"/*.jar; do
    CP="$CP:$jar"
done
CP="$CP:$PLUGIN_JAR"
CP="${CP:1}" # strip leading colon

exec "$JAVA" \
    -cp "$CP" \
    -ea \
    -Xmx768m \
    -Xss2m \
    -XX:CompileThreshold=1500 \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    -Dsun.java2d.opengl=false \
    -Drunelite.launcher.version=2.7.6 \
    -Duser.home="$BOLT_DATA" \
    -XX:ErrorFile="$RUNELITE_DIR/logs/jvm_crash_pid_%p.log" \
    net.runelite.client.RuneLite \
    --developer-mode
