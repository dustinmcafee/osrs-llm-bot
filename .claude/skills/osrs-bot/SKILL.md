---
name: osrs-bot
description: Build, deploy, launch, monitor, and diagnose the OSRS Claude bot. Handles proxy, RuneLite client, log analysis, and live self-diagnosis.
argument-hint: [start|stop|status|logs|diagnose]
---

# OSRS Bot Live Testing Skill

Build, deploy, and run the OSRS Claude bot with live monitoring and self-diagnosis.

## Commands

### `/osrs-bot start`

Full startup sequence:

1. **Build**: `./gradlew clean shadowJar` (from project root)
2. **Kill existing**: Kill any running proxy (`node server.mjs`) and RuneLite (`gradlew runClient`) processes
3. **Start proxy**: `cd proxy && LOG_BODIES=1 nohup node server.mjs > /tmp/proxy.log 2>&1 &`
4. **Verify proxy**: `curl -s http://localhost:8082/health` — wait until it returns `{"status":"ok"}`
5. **Start RuneLite**: `DISPLAY=:0 nohup ./gradlew runClient > /tmp/runelite.log 2>&1 &`
6. **Wait for connection**: Monitor `/tmp/proxy.log` for the first `"Request received"` entry (takes 30-60s for RuneLite to load)
7. **Report**: Show proxy health, session ID, and first bot request

### `/osrs-bot stop`

Graceful shutdown:

1. Kill proxy process: `pkill -f 'node server.mjs'`
2. Kill RuneLite: `pkill -f 'gradlew runClient'` and `pkill -f 'PluginLauncher'`
3. Show final proxy stats from health endpoint before killing

### `/osrs-bot status`

Quick health check (no restarts):

1. `ps aux | grep -E 'server\.mjs|PluginLauncher|runClient' | grep -v grep` — are processes alive?
2. `curl -s http://localhost:8082/health` — proxy stats (turns, errors, latency, session info)
3. Last 5 lines of `/tmp/proxy.log` — recent activity
4. Last 5 lines of `/tmp/runelite.log` — recent client output

### `/osrs-bot logs`

Show recent logs for analysis:

1. **Proxy logs**: `tail -30 /tmp/proxy.log` — shows request/response with game state and Claude's actions
2. **Client logs**: `tail -30 /tmp/runelite.log` — shows action execution, session notes, errors
3. Parse and summarize: extract [STATUS], [ACTION_RESULTS], errors, and Claude's reasoning

### `/osrs-bot diagnose`

Analyze current state and find issues. Read both log files, then check for these common problems:

**Game State Issues:**
- False STUCK status: player idle after action completion, not actually stuck. Check if `[STATUS] STUCK` appears without an intended destination.
- Missing nearby objects: scan radius too small, or objects not loading. Check `[NEARBY_OBJECTS]` in prompts.
- Stale game messages: same `[GAME_MESSAGES]` repeating across turns.

**Action Execution Issues:**
- `ACTION_RESULTS` showing failures: `INTERACT_OBJECT -> FAILED` means object not found or out of range
- `PATH_TO -> FAILED`: pathfinder couldn't find route, check collision map coverage
- `WALK_TO -> FAILED`: minimap click missed, check canvas/coordinate conversion

**Proxy/Connection Issues:**
- High latency (>30s): session context growing too large, consider `curl -X POST http://localhost:8082/reset`
- Rate limits (429): check `consecutiveErrors` in health endpoint, backoff in effect
- Session errors: proxy auto-resets, check `sessionResets` count in health

**Claude Response Issues:**
- Parse failures in client log: Claude returned malformed JSON. Check proxy response body in LOG_BODIES output.
- Repeated WAIT actions: Claude may be confused by game state. Check if status info is clear.
- Wrong action types: alias mapping may be missing. Check ResponseParser aliases.

After diagnosis, create tasks for any issues found and suggest fixes.

## Key Paths

| What | Path |
|------|------|
| Project root | `/home/dustin/workingdir/apk_source/osrs` |
| Proxy server | `proxy/server.mjs` |
| Proxy log | `/tmp/proxy.log` |
| Client log | `/tmp/runelite.log` |
| Build output | `build/libs/claude-osrs-bot-1.0.0.jar` |
| Bot config | `~/.runelite/profiles2/default-*.properties` (keys: `claudebot.*`) |
| Plugin source | `src/main/java/com/osrsbot/claude/` |

## Architecture Quick Reference

- **Proxy** (port 8082): Translates OpenAI format to Claude Code CLI with persistent session
- **Bot plugin**: RuneLite sideloaded plugin, queries proxy every N ticks (configurable)
- **3-Phase actions**: (1) client thread API calls, (2) background humanized input, (3) client thread fire-and-forget
- **All input through**: HumanSimulator -> MouseController -> EventQueue -> Canvas (Bezier curves, overshoot, tremor)
- **Pathfinder**: A* with Chebyshev heuristic, collision-map.zip + transports.txt, chunked 3-step PATH_TO
