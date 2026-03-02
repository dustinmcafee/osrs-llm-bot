# Setup Guide

Complete walkthrough for building, running, and testing every component of the OSRS Claude Bot.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Building the Plugin](#building-the-plugin)
3. [Setting Up RuneLite](#setting-up-runelite)
4. [Proxy Server Setup](#proxy-server-setup)
5. [Configuring the Bot](#configuring-the-bot)
6. [Running Modes](#running-modes)
7. [Testing & Verification](#testing--verification)
8. [Training Pipeline Setup](#training-pipeline-setup)
9. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required

| Tool | Version | Purpose |
|------|---------|---------|
| **Java JDK** | 11+ | Building and running the RuneLite plugin |
| **Gradle** | 7+ (or use included `gradlew`) | Build system |
| **RuneLite** | Latest | OSRS client that hosts the plugin |
| **Node.js** | 18+ | Proxy server |

### Choose One LLM Backend

| Backend | Pros | Cons |
|---------|------|------|
| **Claude API** (via proxy) | Best quality, persistent sessions, wiki injection | Requires Anthropic API key, ongoing cost |
| **Claude API** (direct) | Simpler setup, no proxy needed | No persistent sessions, no wiki context |
| **Local LLM** (LM Studio/Ollama) | Free, private, low latency | Lower quality, requires GPU (8GB+ VRAM) |

### Install Java

```bash
# Ubuntu/Debian
sudo apt install openjdk-11-jdk

# macOS
brew install openjdk@11

# Verify
java -version
```

### Install Node.js

```bash
# Ubuntu/Debian (via NodeSource)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

# macOS
brew install node

# Verify
node -v  # Should be 18+
```

---

## Building the Plugin

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/claude-plays-runescape.git
cd claude-plays-runescape

# Build the fat JAR (includes all dependencies)
./gradlew clean shadowJar
```

**Output:** `build/libs/claude-osrs-bot-1.0.0.jar`

### Verify the Build

```bash
# Check the JAR exists and has reasonable size (~9MB)
ls -lh build/libs/claude-osrs-bot-1.0.0.jar

# List contents to verify classes are included
jar tf build/libs/claude-osrs-bot-1.0.0.jar | head -20
```

---

## Setting Up RuneLite

### Option A: Standard RuneLite

1. Download RuneLite from [runelite.net](https://runelite.net/)
2. Copy the plugin JAR to the sideloaded plugins directory:

```bash
mkdir -p ~/.runelite/sideloaded-plugins
cp build/libs/claude-osrs-bot-1.0.0.jar ~/.runelite/sideloaded-plugins/
```

3. Launch RuneLite with developer mode:

```bash
java -jar runelite.jar --developer-mode
```

### Option B: Bolt Launcher (Flatpak)

Bolt handles Jagex account authentication and launches RuneLite inside a Flatpak sandbox.

1. Install Bolt from [Flathub](https://flathub.org/apps/com.adamcake.Bolt)
2. Use the included wrapper script:

```bash
# Build first
./gradlew clean shadowJar

# The wrapper copies the JAR and launches RuneLite with developer mode
./runelite-wrapper.sh
```

Or use the direct classpath launcher:

```bash
./run-with-bolt.sh
```

### Verify Plugin Loaded

1. Open RuneLite
2. Go to **Settings** (gear icon)
3. Search for "**Claude Bot**" in the plugin list
4. If it appears, the plugin loaded successfully
5. If not, check RuneLite's console output for class loading errors

---

## Proxy Server Setup

The proxy server sits between the RuneLite plugin and Claude. It provides:
- **Persistent sessions**: Claude remembers the full conversation history
- **Wiki context injection**: OSRS game knowledge loaded on the first turn
- **Training data logging**: Every turn is saved for later distillation

### Install & Start

```bash
cd proxy

# Install dependencies (first time only)
npm install

# Start the proxy
node server.mjs
```

**Default port:** 8082

### Configuration via Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8082` | HTTP port for the proxy |
| `CLAUDE_MODEL` | `claude-sonnet-4-6` | Model to use for Claude API |
| `LOG_BODIES` | `1` | Log full request/response bodies |
| `TRAINING_LOG` | `/tmp/training_turns.jsonl` | Where to write training data |
| `WIKI_CONTEXT` | `./wiki_context.txt` | OSRS wiki reference file |
| `MANUAL_MODE` | `0` | Set to `1` for manual/human-in-the-loop mode |

### Example: Start with Opus

```bash
CLAUDE_MODEL=claude-opus-4-6 node server.mjs
```

### Verify Proxy is Running

```bash
# Health check
curl http://localhost:8082/health

# Expected response: JSON with session status and stats
```

### Using the Start Script

The included `start-proxy.sh` validates prerequisites before starting:

```bash
cd proxy
./start-proxy.sh
```

It checks for Node.js 18+, the `claude` CLI, and installs dependencies if needed.

---

## Configuring the Bot

All configuration is done through RuneLite's settings panel.

### In RuneLite:

1. Open **Settings** → search **"Claude Bot"**
2. Configure the following sections:

### API Settings

| Setting | Value | Notes |
|---------|-------|-------|
| **API Base URL** | `http://localhost:8082/v1` | For proxy mode |
| | `http://localhost:1234/v1` | For LM Studio |
| | *(empty)* | For direct Anthropic API |
| **API Key** | Your Anthropic key | Only needed for direct API (not proxy) |
| **Model** | `claude-sonnet-4-6` | Or local model name |
| **Max Tokens** | `512` | Usually sufficient for 3-5 actions |

### Bot Behavior

| Setting | Default | Description |
|---------|---------|-------------|
| **Task Description** | *"Chop trees and bank the logs"* | What the bot should do — be specific |
| **Tick Rate** | `5` | Query LLM every N ticks (1 tick = 0.6s). Lower = more responsive but more API calls |
| **Max Actions Per Batch** | `5` | Cap actions per LLM response |
| **Entity Scan Radius** | `25` | Tile radius for detecting nearby entities |
| **Nudge / Hint** | *(empty)* | One-time hint for the bot (auto-cleared after reading) |

### Humanization

| Setting | Default | Description |
|---------|---------|-------------|
| **Mouse Speed** | `15` | Lower = slower, more human-like |
| **Min/Max Action Delay** | `80-300ms` | Random delay between actions |
| **Breaks Enabled** | `true` | AFK break scheduling |
| **Break Interval** | `15-45 min` | Time between breaks |
| **Break Duration** | `5-30 min` | How long each break lasts |

### Enabling the Bot

1. Set your task description
2. Configure API settings
3. Toggle **"Bot Enabled"** to ON
4. The bot will start on the next game tick cycle

---

## Running Modes

### Mode 1: Claude API via Proxy (Recommended)

Best quality. Claude maintains a persistent session with full conversation history.

```
Plugin → Proxy (localhost:8082) → Claude API → Proxy → Plugin
```

**Setup:**
1. Start proxy: `cd proxy && node server.mjs`
2. Set API Base URL: `http://localhost:8082/v1`
3. Leave API Key empty (proxy handles auth via `claude` CLI)

### Mode 2: Direct Anthropic API

Simpler but no persistent sessions — each turn is independent.

```
Plugin → Claude API (api.anthropic.com) → Plugin
```

**Setup:**
1. Leave API Base URL empty
2. Set API Key to your Anthropic key
3. No proxy needed

### Mode 3: Local LLM

Free, private, runs on your GPU. Lower quality than Claude but zero API cost.

```
Plugin → LM Studio / Ollama (localhost:1234) → Plugin
```

**Setup:**
1. Install [LM Studio](https://lmstudio.ai/) or [Ollama](https://ollama.ai/)
2. Load a model (e.g., fine-tuned GGUF from the training pipeline, or any Llama/Mistral model)
3. Set API Base URL: `http://localhost:1234/v1`
4. Leave API Key empty

### Mode 4: Manual / Human-in-the-Loop

The proxy writes game state to a file and waits for a human (or Claude Code session) to respond. Useful for debugging.

```
Plugin → Proxy → writes /tmp/bot_pending_state.txt → waits for response → Plugin
```

**Setup:**
1. Start proxy: `MANUAL_MODE=1 node server.mjs`
2. Configure plugin to use proxy as normal
3. When the bot queries, the state appears in `/tmp/bot_pending_state.txt`
4. Write your response to the expected file location

---

## Testing & Verification

### 1. Verify Plugin Compiles

```bash
./gradlew clean shadowJar
# Should see: BUILD SUCCESSFUL
```

### 2. Verify Plugin Loads in RuneLite

- Launch RuneLite with `--developer-mode`
- Check Settings for "Claude Bot" plugin
- Enable the debug overlay (Debug → Show Overlay)

### 3. Test Proxy Connectivity

```bash
# Start proxy
cd proxy && node server.mjs &

# Test the endpoint
curl -X POST http://localhost:8082/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "You are an OSRS bot."},
      {"role": "user", "content": "[PLAYER] Test | HP:10/10\n[STATUS] IDLE"}
    ],
    "max_tokens": 256
  }'

# Should return a JSON response with actions
```

### 4. Test the Overlay

With the plugin enabled and overlay on:
- The overlay shows: bot status, last game state sent, last Claude response, and any parse errors
- Use **Log Game State** (Debug settings) to see full serialized state in the RuneLite console
- Use **Log API Calls** to see request/response bodies

### 5. Dry Run: Enable with Safe Task

Start with a simple, safe task to verify everything works:

```
Task: "Stand still and observe your surroundings. Describe what you see."
```

The bot should output WAIT actions and reasoning about the environment without taking risky actions.

### 6. Verify Training Data Collection

If using the proxy, check that training data is being logged:

```bash
# After a few turns
wc -l /tmp/training_turns.jsonl
# Should show increasing line count

# Inspect a turn
head -1 /tmp/training_turns.jsonl | python3 -m json.tool
```

---

## Training Pipeline Setup

### Prerequisites

```bash
# Python 3.10+
python3 --version

# Install dependencies
cd osrs-llm
pip install torch transformers peft bitsandbytes trl datasets
```

### Step 1: Collect Training Data

Play the bot using the Claude API proxy. The proxy logs every turn to `/tmp/training_turns.jsonl`.

### Step 2: Distill

Filter successful turns into clean training data:

```bash
cd osrs-llm
python3 distill_training_data.py --dedup --min-turns 50
# Output: data/gameplay_logs.jsonl
```

### Step 3: Merge Data Sources

Combine all data sources into a single training file:

```bash
python3 format_training_data.py
# Output: data/train.jsonl (~21K examples)
```

### Step 4: Validate

```bash
python3 validate_training_data.py data/train.jsonl
# Should report: all entries valid
```

### Step 5: Fine-Tune

```bash
python3 train.py
# Requires: GPU with 10GB+ VRAM (RTX 3080 or better)
# Output: merged model + GGUF export
```

**Training takes approximately 4-8 hours on an RTX 3080.**

### Step 6: Serve

Load the GGUF file in LM Studio or Ollama and point the plugin at `http://localhost:1234/v1`.

### Anonymizing Data for Sharing

Before sharing training data publicly, remove player names:

```bash
python3 scripts/anonymize_training_data.py --in-place data/example_data.jsonl
```

---

## Troubleshooting

### Plugin doesn't appear in RuneLite

- Verify the JAR is in `~/.runelite/sideloaded-plugins/`
- Ensure RuneLite is launched with `--developer-mode`
- Check RuneLite console for `ClassNotFoundException` or version mismatches
- Rebuild with `./gradlew clean shadowJar` to ensure fresh build

### "Connection refused" when bot queries

- Verify proxy is running: `curl http://localhost:8082/health`
- Check the API Base URL matches the proxy port
- If using local LLM, verify it's running and serving on the expected port

### Bot does nothing / stays idle

- Check the overlay for error messages
- Enable **Log API Calls** in debug settings
- Verify the task description is set
- Check that **Bot Enabled** is toggled on
- Tick rate may be too high — try lowering to 3

### Proxy shows "rate limit" errors

- The proxy has built-in exponential backoff for rate limits
- Consider using a lower tick rate (5-10) to reduce API calls
- Switch to a local LLM for development/testing

### Build fails with dependency errors

```bash
# Clean everything and rebuild
./gradlew clean
rm -rf .gradle/caches
./gradlew shadowJar
```

### Bank operations silently fail

This is a known issue. All bank operations must go through `BankMenuSwap` (PostMenuSort swap + click). If you see bank-related failures in the overlay, check that the bank interface is fully loaded before the bot acts.

### Bot gets stuck on pathfinding

- The A* pathfinder uses a pre-built collision map that may not reflect recent game updates
- Stuck detection kicks in after 4 non-moves (re-click) and 8 non-moves (reroute)
- Check the overlay for pathfinding errors
- The bot will report being stuck in its action results, and the LLM should try an alternative route

### Training data contains personal info

Run the anonymization script before sharing:

```bash
python3 scripts/anonymize_training_data.py --in-place data/*.jsonl
```

This deterministically replaces all player names with `BotPlayer`, `Player1`, `Player2`, etc.
