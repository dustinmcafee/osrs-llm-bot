# Training Data Format

## File Format

Training data is a `.jsonl` file (JSON Lines) — one JSON object per line. Each line represents a single conversation example the model should learn from.

---

## Schema

The system prompt contains the full action reference from `SystemPromptBuilder.java`, and the user message
contains the serialized game state from `GameStateSerializer.java`, optionally prepended with `[ACTION_RESULTS]`,
`[CURRENT_GOAL]`, and `[SESSION_NOTES]`.

```json
{
  "conversations": [
    {"role": "system", "content": "<SystemPromptBuilder output with task description>"},
    {"role": "user", "content": "<serialized game state with optional ACTION_RESULTS/GOAL prefixes>"},
    {"role": "assistant", "content": "<brief reasoning + JSON action array>"}
  ]
}
```

---

## Real Game State Format

The user message the bot receives looks like this (from `GameStateSerializer.java`):

```
[CURRENT_GOAL] Mine copper ore at Lumbridge Swamp
[ACTION_RESULTS] Your previous actions:
  1. INTERACT_OBJECT(Copper rocks/Mine) -> SUCCESS
  2. WAIT_ANIMATION -> SUCCESS: Animation finished after 4 ticks
[PLAYER] BotAccount42 | Combat:3 | HP:10/10 | Prayer:1/1 | Run:100% [ON] | Weight:4kg | SpecAtk:100% | Pos:(3230,3148,0)
[STATUS] IDLE
[SKILLS] Atk:1 Str:1 Def:1 Rng:1 Mag:1 WC:1 Mine:4 Fish:1 Cook:1 FM:1 Craft:1 Smith:1 Fletch:1 Slay:1 Farm:1 Con:1 Hunt:1 Agi:1 Thiev:1 Herb:1 RC:1
[XP] Mine:340/388(87%)
[INVENTORY] (5/28) Bronze pickaxe(x1) | Copper ore(x3) | Tin ore(x1)
[EQUIPMENT] None
[NEARBY_OBJECTS] Copper rocks(x2) nearest:pos(3229,3146) dist:2 [Mine] | Tin rocks pos:(3231,3147) dist:1 [Mine]
[NEARBY_NPCS] Giant rat(lvl:1)(x2) nearest:pos(3225,3150) dist:5 [Attack]
[ENVIRONMENT] Region:Lumbridge Swamp(12594) Plane:0 World:301 Tab:Inventory Style:Accurate Tick:4521
[GAME_MESSAGES] You manage to mine some copper.
```

Key sections that may or may not be present:
- `[CURRENT_GOAL]` — persists from last "goal" field set by the bot
- `[SESSION_NOTES]` — compressed history from conversation manager
- `[ACTION_RESULTS]` — results of the previous action batch (SUCCESS/FAILED)
- `[PLAYER]` — always present: name, combat level, HP, prayer, run energy, position
- `[STATUS]` — IDLE, IN_COMBAT, MOVING, ANIMATING(id), or STUCK(N ticks)
- `[SKILLS]` — all 23 skill levels
- `[BOOSTED]` — only when potion boosts active (differs from base)
- `[XP]` — XP progress for non-zero skills (current/next level threshold)
- `[INVENTORY]` — items grouped by name with quantities
- `[EQUIPMENT]` — equipped items by slot
- `[NEARBY_OBJECTS]` — game objects with positions, distances, and right-click actions
- `[NEARBY_NPCS]` — NPCs with combat levels, positions, distances, actions
- `[NEARBY_GROUND_ITEMS]` — items on the ground
- `[NEARBY_PLAYERS]` — other players nearby
- `[ENVIRONMENT]` — region, world, active tab, flags like [BANK_OPEN], [SHOP_OPEN], etc.
- `[INTERACTING]` — current interaction target
- `[UNDER_ATTACK]` — NPCs attacking the player
- `[HINT_ARROW]` — game's "do this next" indicator (tutorials, quests)
- `[INSTRUCTION]` — tutorial overlay text
- `[DIALOGUE]` — NPC/player dialogue with speaker and text
- `[DIALOGUE_OPTIONS]` — numbered dialogue choices
- `[BANK_CONTENTS]` — bank items when bank is open
- `[SHOP_CONTENTS]` — shop items when shop is open
- `[GAME_MESSAGES]` — recent chat messages (feedback, failures)

---

## Data Sources

### 1. Bot Gameplay Sessions (State -> Action pairs) — MOST VALUABLE

Teaches the model the exact game state format and how to respond with correct action JSON.
See `example_data.jsonl` for 12 real-format examples covering mining, smelting, woodcutting,
banking, tutorial island, fishing, combat, failure recovery, and PATH_TO chunking.

### 2. OSRS Wiki Knowledge (Q&A pairs)

Teaches the model OSRS facts: items, NPCs, locations, quests, skills, mechanics.

```json
{"conversations": [{"role": "system", "content": "You are an expert on Old School RuneScape."}, {"role": "user", "content": "What level do I need to mine iron ore?"}, {"role": "assistant", "content": "You need Mining level 15 to mine iron ore. Iron rocks can be found in various locations including the Dwarven Mine, Al Kharid mine, and south-east Varrock mine. Iron ore gives 35 Mining XP per ore mined."}]}
```

### 3. Bot Interface Documentation

Teaches the model what actions are available and how to use them correctly.

```json
{"conversations": [{"role": "system", "content": "You are an OSRS bot controller. You have the following actions available..."}, {"role": "user", "content": "How do I deposit all items in the bank?"}, {"role": "assistant", "content": "Use the BANK_DEPOSIT_ALL action to deposit everything:\n[{\"action\": \"BANK_DEPOSIT_ALL\"}]\n\nOr to deposit a specific item, use BANK_DEPOSIT with quantity -1 for all:\n[{\"action\": \"BANK_DEPOSIT\", \"name\": \"Iron ore\", \"quantity\": -1}]"}]}
```

---

## Data Quality Guidelines

### Good examples
- Diverse game situations (skilling, combat, questing, banking, navigating)
- Correct action JSON format with all required fields
- Reasoning before the action array (the model should explain why)
- Failure recovery examples (what to do when an action fails)
- PATH_TO continuation examples (re-issuing after "tiles remaining")
- Full inventory handling (banking, processing, dropping)

### Bad examples
- Repetitive loops of the same action
- Malformed JSON
- Actions that resulted in errors/failures (unless paired with the correct fix)
- Very long conversations (keep to 1-3 turns max per example)
- Generic game states that don't match the real serializer output

### Minimum dataset sizes

| Dataset Size | Expected Quality |
|---|---|
| 100 examples | Model learns the JSON format but makes many OSRS knowledge errors |
| 500 examples | Decent at common tasks (mining, banking, simple navigation) |
| 1,000+ examples | Good general OSRS bot performance |
| 5,000+ examples | Excellent — covers edge cases, quest dialogues, complex scenarios |

---

## Generating Data from Proxy Logs

If your bot proxy logs the game state and LLM responses, you can convert successful interactions into training data. The `format_training_data.py` script does this.

Only include interactions where:
1. The actions executed successfully (no FAILED results)
2. The response JSON was valid
3. The game state progressed (player didn't get stuck)

Exclude interactions where:
1. Actions failed repeatedly
2. The bot was stuck in a loop
3. The response was malformed or empty
