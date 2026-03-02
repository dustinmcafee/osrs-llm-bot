# Action Reference

The bot is driven by an LLM that outputs a JSON array of **actions**. Each action is an object with an `"action"` field that identifies the type, plus type-specific parameters. The bot executes actions sequentially in the order they appear in the array.

```json
[
  {"action": "PATH_TO", "x": 3200, "y": 3200, "plane": 0},
  {"action": "INTERACT_OBJECT", "name": "Oak tree", "option": "Chop down"},
  {"action": "WAIT_ANIMATION", "timeout": 10}
]
```

---

## Summary Table

| Category | Actions |
|----------|---------|
| [Movement](#movement) | WALK_TO, MINIMAP_WALK, PATH_TO, ROTATE_CAMERA |
| [NPC Interaction](#npc-interaction) | INTERACT_NPC, USE_ITEM_ON_NPC |
| [Object Interaction](#object-interaction) | INTERACT_OBJECT, USE_ITEM_ON_OBJECT |
| [Inventory](#inventory) | USE_ITEM, USE_ITEM_ON_ITEM, EQUIP_ITEM, UNEQUIP_ITEM, DROP_ITEM, PICKUP_ITEM, EAT_FOOD |
| [Banking](#banking) | BANK_DEPOSIT, BANK_WITHDRAW, BANK_DEPOSIT_ALL, BANK_CLOSE |
| [Shopping](#shopping) | SHOP_BUY, SHOP_SELL |
| [Grand Exchange](#grand-exchange) | GE_BUY, GE_SELL, GE_COLLECT, GE_ABORT |
| [Combat](#combat) | SPECIAL_ATTACK, TOGGLE_PRAYER, SET_ATTACK_STYLE, SET_AUTOCAST, SET_AUTO_RETALIATE |
| [Magic](#magic) | CAST_SPELL |
| [Crafting](#crafting) | MAKE_ITEM |
| [Dialogue](#dialogue) | SELECT_DIALOGUE, CONTINUE_DIALOGUE |
| [UI](#ui) | CLICK_WIDGET, OPEN_TAB, TYPE_TEXT, PRESS_KEY |
| [Utility](#utility) | WAIT, WAIT_ANIMATION, TOGGLE_RUN, WORLD_HOP, CLEAR_ACTION_QUEUE |

---

## Movement

| ID | Action | Description |
|----|--------|-------------|
| 1 | WALK_TO | Walk to a nearby visible tile |
| 26 | MINIMAP_WALK | Click the minimap to walk |
| 38 | PATH_TO | A* pathfind to any reachable tile (chunked, ~10 tiles per call) |
| 27 | ROTATE_CAMERA | Rotate the camera to a compass direction or raw yaw |

### WALK_TO

Walk to a nearby tile that is currently visible on screen.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| x | int | yes | World tile X coordinate |
| y | int | yes | World tile Y coordinate |

```json
{"action": "WALK_TO", "x": 3200, "y": 3200}
```

### MINIMAP_WALK

Click the minimap to walk to a tile. Useful for short-to-medium distances where the destination is within minimap range.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| x | int | yes | World tile X coordinate |
| y | int | yes | World tile Y coordinate |

```json
{"action": "MINIMAP_WALK", "x": 3210, "y": 3210}
```

### PATH_TO

A* pathfinding to any reachable tile on the map. Uses a pre-built collision map and transport database (doors, stairs, ladders). The path is **chunked**: each call walks roughly 10 tiles, then returns progress to the LLM so it can react to combat, eat food, or re-issue PATH_TO to continue. Path results are cached, so re-issuing is free.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| x | int | yes | Destination world tile X |
| y | int | yes | Destination world tile Y |
| plane | int | yes | Destination plane (0 = surface) |

```json
{"action": "PATH_TO", "x": 3200, "y": 3200, "plane": 0}
```

### ROTATE_CAMERA

Rotate the camera. Accepts either a compass direction string or a raw yaw value (0-2048 JAU, where 0 = north, 512 = west, 1024 = south, 1536 = east).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| direction | string | one of these | `"north"`, `"south"`, `"east"`, `"west"` |
| yaw | int | one of these | Raw yaw value (0-2048) |

```json
{"action": "ROTATE_CAMERA", "direction": "north"}
```

```json
{"action": "ROTATE_CAMERA", "yaw": 512}
```

---

## NPC Interaction

| ID | Action | Description |
|----|--------|-------------|
| 2 | INTERACT_NPC | Interact with an NPC (attack, talk, trade, etc.) |
| 6 | USE_ITEM_ON_NPC | Use an inventory item on an NPC |

### INTERACT_NPC

Left-click or right-click interact with an NPC by name and menu option.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | NPC name (case-insensitive) |
| option | string | yes | Menu option (e.g. `"Attack"`, `"Talk-to"`, `"Trade"`, `"Pickpocket"`) |

```json
{"action": "INTERACT_NPC", "name": "Goblin", "option": "Attack"}
```

### USE_ITEM_ON_NPC

Use an inventory item on a nearby NPC.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| item | string | yes | Inventory item name |
| name | string | yes | Target NPC name |

```json
{"action": "USE_ITEM_ON_NPC", "item": "Bones", "name": "Monk"}
```

---

## Object Interaction

| ID | Action | Description |
|----|--------|-------------|
| 3 | INTERACT_OBJECT | Interact with a game object (chop, mine, open, etc.) |
| 7 | USE_ITEM_ON_OBJECT | Use an inventory item on an object |

### INTERACT_OBJECT

Interact with a game object (trees, rocks, doors, banks, anvils, etc.) by name and menu option.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Object name (case-insensitive) |
| option | string | yes | Menu option (e.g. `"Chop down"`, `"Mine"`, `"Open"`, `"Use"`, `"Bank"`) |

```json
{"action": "INTERACT_OBJECT", "name": "Oak tree", "option": "Chop down"}
```

### USE_ITEM_ON_OBJECT

Use an inventory item on a game object.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| item | string | yes | Inventory item name |
| name | string | yes | Target object name |

```json
{"action": "USE_ITEM_ON_OBJECT", "item": "Tin ore", "name": "Furnace"}
```

---

## Inventory

| ID | Action | Description |
|----|--------|-------------|
| 4 | USE_ITEM | Use an item (eat, bury, read, etc.) |
| 5 | USE_ITEM_ON_ITEM | Use one item on another |
| 8 | EQUIP_ITEM | Equip a wearable item |
| 32 | UNEQUIP_ITEM | Unequip a worn item |
| 9 | DROP_ITEM | Drop an item |
| 10 | PICKUP_ITEM | Pick up a ground item |
| 11 | EAT_FOOD | Eat food to restore HP |

### USE_ITEM

Use an item from the inventory. Triggers the default left-click action (e.g. Bury for bones, Read for scrolls).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |

```json
{"action": "USE_ITEM", "name": "Bones"}
```

### USE_ITEM_ON_ITEM

Use one inventory item on another (e.g. knife on logs, needle on leather).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| item | string | yes | Source item name |
| target | string | yes | Target item name |

```json
{"action": "USE_ITEM_ON_ITEM", "item": "Knife", "target": "Logs"}
```

### EQUIP_ITEM

Equip a wearable item from the inventory.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |

```json
{"action": "EQUIP_ITEM", "name": "Steel longsword"}
```

### UNEQUIP_ITEM

Unequip a currently worn item, moving it back to the inventory.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |

```json
{"action": "UNEQUIP_ITEM", "name": "Steel longsword"}
```

### DROP_ITEM

Drop an item from the inventory. Uses shift-click by default for speed. Set `option` to `"menu"` to use the right-click drop method instead.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| option | string | no | `"menu"` for right-click drop (default: shift-click) |

```json
{"action": "DROP_ITEM", "name": "Tin ore"}
```

```json
{"action": "DROP_ITEM", "name": "Tin ore", "option": "menu"}
```

### PICKUP_ITEM

Pick up an item from the ground near the player.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Ground item name |

```json
{"action": "PICKUP_ITEM", "name": "Bones"}
```

### EAT_FOOD

Eat food from the inventory to restore hitpoints.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Food item name |

```json
{"action": "EAT_FOOD", "name": "Cooked meat"}
```

---

## Banking

| ID | Action | Description |
|----|--------|-------------|
| 18 | BANK_DEPOSIT | Deposit items into the bank |
| 19 | BANK_WITHDRAW | Withdraw items from the bank |
| 34 | BANK_DEPOSIT_ALL | Deposit entire inventory |
| 20 | BANK_CLOSE | Close the bank interface |

All bank operations use widget-level click swapping (PostMenuSort) rather than direct client menu actions, because `client.menuAction()` silently fails for bank widget entries.

### BANK_DEPOSIT

Deposit items into the bank. Quantity can be a number or a preset string.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | string or int | yes | `1`, `5`, `10`, `"X"`, or `"All"` |

```json
{"action": "BANK_DEPOSIT", "name": "Logs", "quantity": "All"}
```

```json
{"action": "BANK_DEPOSIT", "name": "Coins", "quantity": 10}
```

### BANK_WITHDRAW

Withdraw items from the bank.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | string or int | yes | `1`, `5`, `10`, `"X"`, or `"All"` |

```json
{"action": "BANK_WITHDRAW", "name": "Coins", "quantity": 100}
```

### BANK_DEPOSIT_ALL

Deposit the entire inventory into the bank with a single click.

No additional fields required.

```json
{"action": "BANK_DEPOSIT_ALL"}
```

### BANK_CLOSE

Close the bank interface.

No additional fields required.

```json
{"action": "BANK_CLOSE"}
```

---

## Shopping

| ID | Action | Description |
|----|--------|-------------|
| 24 | SHOP_BUY | Buy items from a shop |
| 25 | SHOP_SELL | Sell items to a shop |

### SHOP_BUY

Buy items from an open shop interface.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | int | yes | Number to buy (1, 5, 10, or 50) |

```json
{"action": "SHOP_BUY", "name": "Bronze axe", "quantity": 1}
```

### SHOP_SELL

Sell items to an open shop.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | int | yes | Number to sell (1, 5, 10, or 50) |

```json
{"action": "SHOP_SELL", "name": "Cowhide", "quantity": 10}
```

---

## Grand Exchange

| ID | Action | Description |
|----|--------|-------------|
| 28 | GE_BUY | Create a buy offer |
| 29 | GE_SELL | Create a sell offer |
| 42 | GE_COLLECT | Collect completed offers |
| 43 | GE_ABORT | Abort an active offer |

### GE_BUY

Create a Grand Exchange buy offer. The GE interface must already be open.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | int | yes | Number to buy |
| price | int | yes | Price per item in coins |

```json
{"action": "GE_BUY", "name": "Iron ore", "quantity": 100, "price": 50}
```

### GE_SELL

Create a Grand Exchange sell offer.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name |
| quantity | int | yes | Number to sell |
| price | int | yes | Price per item in coins |

```json
{"action": "GE_SELL", "name": "Bronze bar", "quantity": 50, "price": 75}
```

### GE_COLLECT

Collect all completed Grand Exchange offers (items and coins).

No additional fields required.

```json
{"action": "GE_COLLECT"}
```

### GE_ABORT

Abort an active Grand Exchange offer by slot number.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| slot | int | yes | GE slot number (1-8) |

```json
{"action": "GE_ABORT", "slot": 1}
```

---

## Combat

| ID | Action | Description |
|----|--------|-------------|
| 17 | SPECIAL_ATTACK | Toggle the special attack bar |
| 12 | TOGGLE_PRAYER | Toggle a prayer on or off |
| 35 | SET_ATTACK_STYLE | Set the melee attack style |
| 36 | SET_AUTOCAST | Set a spell for autocasting |
| 41 | SET_AUTO_RETALIATE | Toggle auto-retaliate |

### SPECIAL_ATTACK

Toggle the special attack bar. The player must have a special attack weapon equipped.

No additional fields required.

```json
{"action": "SPECIAL_ATTACK"}
```

### TOGGLE_PRAYER

Toggle a prayer on or off in the prayer book.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Prayer name (e.g. `"Protect from Melee"`, `"Eagle Eye"`, `"Piety"`) |

```json
{"action": "TOGGLE_PRAYER", "name": "Protect from Melee"}
```

### SET_ATTACK_STYLE

Set the melee attack style from the combat tab.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| style | string | yes | Style name (e.g. `"Accurate"`, `"Aggressive"`, `"Defensive"`, `"Controlled"`) |

```json
{"action": "SET_ATTACK_STYLE", "style": "Aggressive"}
```

### SET_AUTOCAST

Set a spell for autocasting on the combat tab.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| spell | string | yes | Spell name (e.g. `"Fire Strike"`, `"Wind Blast"`) |

```json
{"action": "SET_AUTOCAST", "spell": "Fire Strike"}
```

### SET_AUTO_RETALIATE

Toggle auto-retaliate on or off.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| enabled | boolean | yes | `true` to enable, `false` to disable |

```json
{"action": "SET_AUTO_RETALIATE", "enabled": true}
```

---

## Magic

| ID | Action | Description |
|----|--------|-------------|
| 22 | CAST_SPELL | Cast a spell with optional target |

### CAST_SPELL

Cast a spell from the spellbook. Supports three modes:

1. **No target** -- spells like Bones to Bananas, teleports
2. **NPC target** -- combat spells on a specific NPC
3. **Item target** -- High Level Alchemy, Superheat Item, enchant spells on an inventory item

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| spell | string | yes | Spell name (e.g. `"High Level Alchemy"`, `"Fire Strike"`, `"Varrock Teleport"`) |
| target | string | no | NPC name for targeted combat spells |
| item | string | no | Inventory item name for alchemy/enchant spells |

```json
{"action": "CAST_SPELL", "spell": "Varrock Teleport"}
```

```json
{"action": "CAST_SPELL", "spell": "Fire Strike", "target": "Goblin"}
```

```json
{"action": "CAST_SPELL", "spell": "High Level Alchemy", "item": "Steel platebody"}
```

---

## Crafting

| ID | Action | Description |
|----|--------|-------------|
| 23 | MAKE_ITEM | Select an item from a make/craft interface |

### MAKE_ITEM

Select an item from the make/craft/smelt interface that appears after using items together (e.g. the "How many do you wish to make?" screen).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | yes | Item name as shown in the interface |
| quantity | string or int | no | `1`, `5`, `10`, `"X"`, or `"All"` (default: `"All"`) |

```json
{"action": "MAKE_ITEM", "name": "Bronze bar", "quantity": "All"}
```

---

## Dialogue

| ID | Action | Description |
|----|--------|-------------|
| 14 | SELECT_DIALOGUE | Select a numbered dialogue option |
| 15 | CONTINUE_DIALOGUE | Click "Click here to continue" |

### SELECT_DIALOGUE

Select a dialogue option by its number (1-based) from a multi-choice dialogue.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| option | int | yes | Option number (1, 2, 3, ...) |

```json
{"action": "SELECT_DIALOGUE", "option": 2}
```

### CONTINUE_DIALOGUE

Click through a "Click here to continue" dialogue prompt.

No additional fields required.

```json
{"action": "CONTINUE_DIALOGUE"}
```

---

## UI

| ID | Action | Description |
|----|--------|-------------|
| 21 | CLICK_WIDGET | Click a specific interface widget |
| 30 | OPEN_TAB | Open a game tab |
| 31 | TYPE_TEXT | Type text into an input field |
| 33 | PRESS_KEY | Press a keyboard key |

### CLICK_WIDGET

Click a specific widget by its group and child ID. Used for interface elements that don't have a dedicated action type.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| group | int | yes | Widget group ID |
| child | int | yes | Widget child ID |

```json
{"action": "CLICK_WIDGET", "group": 270, "child": 14}
```

### OPEN_TAB

Open one of the game's side panel tabs.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| tab | string | yes | Tab name (e.g. `"Inventory"`, `"Prayer"`, `"Magic"`, `"Equipment"`, `"Combat"`) |

```json
{"action": "OPEN_TAB", "tab": "Inventory"}
```

### TYPE_TEXT

Type text into the currently active input field (chatbox, bank search, GE search, quantity dialogs, etc.).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| text | string | yes | Text to type |

```json
{"action": "TYPE_TEXT", "text": "100"}
```

### PRESS_KEY

Press a single keyboard key.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| key | string | yes | Key name (e.g. `"SPACE"`, `"ENTER"`, `"ESCAPE"`, `"1"`, `"F1"`) |

```json
{"action": "PRESS_KEY", "key": "SPACE"}
```

---

## Utility

| ID | Action | Description |
|----|--------|-------------|
| 16 | WAIT | Wait for a number of game ticks |
| 39 | WAIT_ANIMATION | Wait until the current animation finishes |
| 13 | TOGGLE_RUN | Toggle run on or off |
| 37 | WORLD_HOP | Hop to a different world |
| 40 | CLEAR_ACTION_QUEUE | Clear all pending actions |

### WAIT

Pause execution for a number of game ticks (1 tick = 600 ms).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| ticks | int | no | Number of ticks to wait (default: 1) |

```json
{"action": "WAIT", "ticks": 3}
```

### WAIT_ANIMATION

Wait until the player's current animation returns to idle. Useful after actions like woodcutting, mining, cooking, or smithing where you want to wait for the activity to finish before continuing.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| timeout | int | no | Maximum ticks to wait before giving up (default: 10) |

```json
{"action": "WAIT_ANIMATION", "timeout": 10}
```

### TOGGLE_RUN

Toggle the run mode on or off.

No additional fields required.

```json
{"action": "TOGGLE_RUN"}
```

### WORLD_HOP

Hop to a different game world. If no world is specified, a random valid members/free-to-play world is selected.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| world | int | no | Specific world number to hop to |

```json
{"action": "WORLD_HOP"}
```

```json
{"action": "WORLD_HOP", "world": 301}
```

### CLEAR_ACTION_QUEUE

Immediately clear all pending actions in the queue. Useful when the LLM needs to abort a plan and start fresh (e.g. emergency eating, fleeing combat).

No additional fields required.

```json
{"action": "CLEAR_ACTION_QUEUE"}
```

---

## Action Aliases

The `ResponseParser` maps over 130 alternative action names to their canonical types, so the LLM can use natural, context-appropriate names instead of memorizing the exact action strings. A few examples:

| Alias | Maps To |
|-------|---------|
| `CHOP`, `CHOP_TREE` | INTERACT_OBJECT |
| `MINE`, `MINE_ROCK` | INTERACT_OBJECT |
| `FISH` | INTERACT_NPC |
| `ATTACK`, `FIGHT` | INTERACT_NPC |
| `TALK`, `TALK_TO` | INTERACT_NPC |
| `COOK`, `SMELT`, `SMITH`, `FLETCH`, `CRAFT` | MAKE_ITEM |
| `EAT`, `DRINK` | EAT_FOOD |
| `BURY` | USE_ITEM |
| `OPEN_DOOR`, `OPEN_GATE` | INTERACT_OBJECT |
| `CLIMB_STAIRS`, `CLIMB_LADDER` | INTERACT_OBJECT |
| `PICK_UP`, `TAKE`, `LOOT` | PICKUP_ITEM |
| `WEAR`, `WIELD` | EQUIP_ITEM |
| `REMOVE` | UNEQUIP_ITEM |
| `DEPOSIT`, `DEPOSIT_ALL` | BANK_DEPOSIT / BANK_DEPOSIT_ALL |
| `WITHDRAW` | BANK_WITHDRAW |
| `BUY` | SHOP_BUY |
| `SELL` | SHOP_SELL |
| `TELEPORT` | CAST_SPELL |
| `ALCH`, `HIGH_ALCH` | CAST_SPELL |
| `HOP`, `HOP_WORLD` | WORLD_HOP |
| `RUN` | TOGGLE_RUN |
| `PRAY` | TOGGLE_PRAYER |
| `SPEC` | SPECIAL_ATTACK |

When an alias is used, the parser automatically fills in default values where possible. For example, `CHOP` sets the option to `"Chop down"`, `MINE` sets it to `"Mine"`, and `ATTACK` sets it to `"Attack"`.

This means the LLM can output either of these and get the same result:

```json
{"action": "INTERACT_NPC", "name": "Goblin", "option": "Attack"}
```

```json
{"action": "ATTACK", "name": "Goblin"}
```

---

## Batch Size Limit

The `maxActionsPerBatch` configuration option (default: 5) caps the number of actions the bot will accept from a single LLM response. Any actions beyond the limit are silently dropped. This keeps the bot responsive -- after each batch completes, the LLM receives fresh game state and can adjust its plan accordingly.
