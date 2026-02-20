#!/usr/bin/env python3
"""
Training Data Validation Suite
===============================
Validates OSRS training data for syntactic and semantic correctness.

Usage:
    python3 validate_training_data.py data/train.jsonl
    python3 validate_training_data.py data/synthetic_gameplay.jsonl
    python3 validate_training_data.py example_data.jsonl
"""

import json
import sys
import re
import os
from collections import Counter

# ─── Action Schema ────────────────────────────────────────────────────────────
# Each action type: (id, name, required_fields, optional_fields)
ACTION_SCHEMA = {
    "WALK_TO":           {"id": 1,  "required": ["x", "y"],         "optional": ["plane", "goal"]},
    "INTERACT_NPC":      {"id": 2,  "required": ["name"],           "optional": ["option", "goal"]},
    "INTERACT_OBJECT":   {"id": 3,  "required": ["name"],           "optional": ["option", "goal"]},
    "USE_ITEM":          {"id": 4,  "required": ["name"],           "optional": ["goal"]},
    "USE_ITEM_ON_ITEM":  {"id": 5,  "required": ["item1", "item2"], "optional": ["goal"]},
    "USE_ITEM_ON_NPC":   {"id": 6,  "required": ["item", "npc"],   "optional": ["goal"]},
    "USE_ITEM_ON_OBJECT":{"id": 7,  "required": ["item", "object"],"optional": ["goal"]},
    "EQUIP_ITEM":        {"id": 8,  "required": ["name"],           "optional": ["goal"]},
    "DROP_ITEM":         {"id": 9,  "required": ["name"],           "optional": ["option", "goal"]},
    "PICKUP_ITEM":       {"id": 10, "required": ["name"],           "optional": ["goal"]},
    "EAT_FOOD":          {"id": 11, "required": ["name"],           "optional": ["goal"]},
    "TOGGLE_PRAYER":     {"id": 12, "required": ["name"],           "optional": ["goal"]},
    "TOGGLE_RUN":        {"id": 13, "required": [],                 "optional": ["goal"]},
    "SELECT_DIALOGUE":   {"id": 14, "required": ["option"],         "optional": ["goal"]},
    "CONTINUE_DIALOGUE": {"id": 15, "required": [],                 "optional": ["goal"]},
    "WAIT":              {"id": 16, "required": ["ticks"],           "optional": ["goal"]},
    "SPECIAL_ATTACK":    {"id": 17, "required": [],                 "optional": ["goal"]},
    "BANK_DEPOSIT":      {"id": 18, "required": ["name", "quantity"], "optional": ["goal"]},
    "BANK_WITHDRAW":     {"id": 19, "required": ["name", "quantity"], "optional": ["goal"]},
    "BANK_CLOSE":        {"id": 20, "required": [],                 "optional": ["goal"]},
    "CLICK_WIDGET":      {"id": 21, "required": ["x", "y"],         "optional": ["option", "goal"]},
    "CAST_SPELL":        {"id": 22, "required": ["name"],           "optional": ["item", "npc", "goal"]},
    "MAKE_ITEM":         {"id": 23, "required": ["name"],           "optional": ["goal"]},
    "SHOP_BUY":          {"id": 24, "required": ["name", "quantity"], "optional": ["goal"]},
    "SHOP_SELL":         {"id": 25, "required": ["name", "quantity"], "optional": ["goal"]},
    "MINIMAP_WALK":      {"id": 26, "required": ["x", "y"],         "optional": ["goal"]},
    "ROTATE_CAMERA":     {"id": 27, "required": ["option", "ticks"], "optional": ["goal"]},
    "GE_BUY":            {"id": 28, "required": ["name", "quantity", "x"], "optional": ["goal"]},
    "GE_SELL":           {"id": 29, "required": ["name", "quantity"], "optional": ["goal"]},
    "OPEN_TAB":          {"id": 30, "required": ["name"],           "optional": ["goal"]},
    "TYPE_TEXT":          {"id": 31, "required": ["text"],           "optional": ["option", "goal"]},
    "UNEQUIP_ITEM":      {"id": 32, "required": ["name"],           "optional": ["goal"]},
    "PRESS_KEY":         {"id": 33, "required": ["name"],           "optional": ["goal"]},
    "BANK_DEPOSIT_ALL":  {"id": 34, "required": [],                 "optional": ["goal"]},
    "SET_ATTACK_STYLE":  {"id": 35, "required": ["option"],         "optional": ["goal"]},
    "SET_AUTOCAST":      {"id": 36, "required": ["name"],           "optional": ["option", "goal"]},
    "WORLD_HOP":         {"id": 37, "required": ["x"],              "optional": ["goal"]},
    "PATH_TO":           {"id": 38, "required": ["x", "y"],         "optional": ["plane", "fleeing", "goal"]},
    "WAIT_ANIMATION":    {"id": 39, "required": [],                 "optional": ["ticks", "option", "goal"]},
    "CLEAR_ACTION_QUEUE":{"id": 40, "required": [],                 "optional": []},
}

# Build reverse map: id -> name
ID_TO_NAME = {v["id"]: k for k, v in ACTION_SCHEMA.items()}

# All 21 skill abbreviations in correct order
SKILL_ABBREVS = [
    "Atk", "Str", "Def", "Rng", "Mag", "WC", "Mine", "Fish", "Cook", "FM",
    "Craft", "Smith", "Fletch", "Slay", "Farm", "Con", "Hunt", "Agi", "Thiev", "Herb", "RC"
]

# All 23 skills for coverage tracking
ALL_SKILLS = [
    "Attack", "Strength", "Defence", "Ranged", "Magic", "Hitpoints", "Prayer",
    "Woodcutting", "Mining", "Fishing", "Cooking", "Firemaking",
    "Crafting", "Smithing", "Fletching", "Slayer", "Farming", "Construction",
    "Hunter", "Agility", "Thieving", "Herblore", "Runecrafting"
]

SKILL_ABBREV_TO_FULL = {
    "Atk": "Attack", "Str": "Strength", "Def": "Defence", "Rng": "Ranged",
    "Mag": "Magic", "WC": "Woodcutting", "Mine": "Mining", "Fish": "Fishing",
    "Cook": "Cooking", "FM": "Firemaking", "Craft": "Crafting", "Smith": "Smithing",
    "Fletch": "Fletching", "Slay": "Slayer", "Farm": "Farming", "Con": "Construction",
    "Hunt": "Hunter", "Agi": "Agility", "Thiev": "Thieving", "Herb": "Herblore",
    "RC": "Runecrafting", "HP": "Hitpoints", "Pray": "Prayer",
}


class ValidationResult:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.action_types_seen = set()
        self.skills_seen = set()
        self.total_examples = 0
        self.wiki_examples = 0
        self.gameplay_examples = 0

    def error(self, line_num, msg):
        self.errors.append(f"Line {line_num}: ERROR: {msg}")

    def warn(self, line_num, msg):
        self.warnings.append(f"Line {line_num}: WARN: {msg}")

    def report(self):
        print("\n" + "=" * 60)
        print("VALIDATION REPORT")
        print("=" * 60)
        print(f"Total examples: {self.total_examples}")
        print(f"  Wiki Q&A:    {self.wiki_examples}")
        print(f"  Gameplay:    {self.gameplay_examples}")
        print(f"Errors: {len(self.errors)}")
        print(f"Warnings: {len(self.warnings)}")

        if self.errors:
            print(f"\n--- ERRORS ({len(self.errors)}) ---")
            for e in self.errors[:50]:
                print(f"  {e}")
            if len(self.errors) > 50:
                print(f"  ... and {len(self.errors) - 50} more")

        if self.warnings:
            print(f"\n--- WARNINGS ({len(self.warnings)}) ---")
            for w in self.warnings[:30]:
                print(f"  {w}")
            if len(self.warnings) > 30:
                print(f"  ... and {len(self.warnings) - 30} more")

        # Action type coverage
        print(f"\n--- ACTION TYPE COVERAGE: {len(self.action_types_seen)}/{len(ACTION_SCHEMA)} ---")
        missing_actions = set(ACTION_SCHEMA.keys()) - self.action_types_seen
        if missing_actions:
            print(f"  Missing: {', '.join(sorted(missing_actions))}")
        else:
            print("  All action types covered!")

        # Skill coverage
        print(f"\n--- SKILL COVERAGE: {len(self.skills_seen)}/23 ---")
        missing_skills = set(ALL_SKILLS) - self.skills_seen
        if missing_skills:
            print(f"  Missing: {', '.join(sorted(missing_skills))}")
        else:
            print("  All 23 skills covered!")

        print("=" * 60)
        return len(self.errors) == 0


def extract_json_array(text):
    """Extract JSON array from assistant response (same logic as ResponseParser)."""
    cleaned = text.strip()
    # Strip markdown code fences
    if "```" in cleaned:
        cleaned = re.sub(r"```[a-zA-Z]*\s*", "", cleaned).replace("```", "").strip()
    # Find array boundaries
    start = cleaned.find("[")
    end = cleaned.rfind("]")
    if start >= 0 and end > start:
        return cleaned[start:end + 1]
    return None


def validate_format(line_num, data, result):
    """Validate JSONL format and conversation structure."""
    if "conversations" not in data:
        result.error(line_num, "Missing 'conversations' key")
        return False

    convs = data["conversations"]
    if not isinstance(convs, list):
        result.error(line_num, "'conversations' is not a list")
        return False

    if len(convs) < 2:
        result.error(line_num, f"Too few conversation turns: {len(convs)}")
        return False

    # Check roles
    roles = [c.get("role") for c in convs]
    if roles[0] != "system":
        result.error(line_num, f"First role must be 'system', got '{roles[0]}'")
        return False
    if roles[1] != "user":
        result.error(line_num, f"Second role must be 'user', got '{roles[1]}'")
        return False
    if len(roles) >= 3 and roles[2] != "assistant":
        result.error(line_num, f"Third role must be 'assistant', got '{roles[2]}'")
        return False

    # Check content not empty
    for i, c in enumerate(convs):
        if not c.get("content", "").strip():
            result.error(line_num, f"Empty content in turn {i} (role={c.get('role')})")
            return False

    return True


def is_gameplay_example(data):
    """Determine if this is a gameplay example (vs wiki Q&A)."""
    user_content = data["conversations"][1]["content"]
    return "[PLAYER]" in user_content or "[STATUS]" in user_content or "[INVENTORY]" in user_content or "[SESSION_NOTES]" in user_content


def validate_game_state(line_num, game_state, result):
    """Validate game state format matches GameStateSerializer output."""
    lines = game_state.strip().split("\n")

    has_player = False
    has_status = False
    has_skills = False
    has_inventory = False
    has_environment = False

    for line in lines:
        line = line.strip()
        if not line:
            continue

        if line.startswith("[PLAYER]"):
            has_player = True
            # Check format: [PLAYER] Name | Combat:N | HP:N/N | Prayer:N/N | Run:N% [ON/OFF] | Weight:Nkg | SpecAtk:N% | Pos:(N,N,N)
            if "Combat:" not in line or "HP:" not in line or "Pos:" not in line:
                result.error(line_num, f"Malformed [PLAYER] line: {line[:100]}")

        elif line.startswith("[STATUS]"):
            has_status = True
            status = line[len("[STATUS]"):].strip()
            valid_statuses = ["IDLE", "IN_COMBAT", "MOVING"]
            if not any(status.startswith(s) for s in valid_statuses) and not status.startswith("ANIMATING(") and not status.startswith("STUCK("):
                result.warn(line_num, f"Unusual status: {status}")

        elif line.startswith("[SKILLS]"):
            has_skills = True
            # Check all 21 abbreviations present
            for abbrev in SKILL_ABBREVS:
                if f"{abbrev}:" not in line:
                    result.error(line_num, f"Missing skill '{abbrev}' in [SKILLS] line")
                    break
            # Track skills for coverage
            for abbrev in SKILL_ABBREVS:
                match = re.search(rf"{abbrev}:(\d+)", line)
                if match and int(match.group(1)) > 1:
                    full_name = SKILL_ABBREV_TO_FULL.get(abbrev)
                    if full_name:
                        result.skills_seen.add(full_name)

        elif line.startswith("[INVENTORY]"):
            has_inventory = True
            # Check (N/28) format
            match = re.search(r"\((\d+)/28\)", line)
            if not match:
                result.error(line_num, f"Malformed [INVENTORY] line - missing (N/28): {line[:80]}")
            else:
                n = int(match.group(1))
                if n < 0 or n > 28:
                    result.error(line_num, f"Invalid inventory count: {n}/28")

        elif line.startswith("[ENVIRONMENT]"):
            has_environment = True
            required = ["Region:", "Plane:", "World:", "Tab:", "Style:"]
            for req in required:
                if req not in line:
                    result.error(line_num, f"Missing '{req}' in [ENVIRONMENT]: {line[:100]}")
                    break
            if "Tick:" not in line:
                result.error(line_num, f"Missing 'Tick:' in [ENVIRONMENT]: {line[:100]}")

    if not has_player:
        result.error(line_num, "Missing [PLAYER] section")
    if not has_status:
        result.error(line_num, "Missing [STATUS] section")
    if not has_skills:
        result.warn(line_num, "Missing [SKILLS] section (acceptable for shortened examples)")
    if not has_inventory:
        result.error(line_num, "Missing [INVENTORY] section")
    if not has_environment:
        result.error(line_num, "Missing [ENVIRONMENT] section")

    return has_player and has_status and has_skills and has_inventory and has_environment


def validate_actions(line_num, assistant_content, game_state, result):
    """Validate action JSON in assistant response."""
    json_str = extract_json_array(assistant_content)
    if json_str is None:
        # Wiki Q&A responses don't have JSON arrays
        if not is_gameplay_example({"conversations": [{"role": "system", "content": ""}, {"role": "user", "content": game_state}]}):
            return True
        result.error(line_num, "No JSON array found in gameplay assistant response")
        return False

    try:
        actions = json.loads(json_str)
    except json.JSONDecodeError as e:
        # Try fixing common issues: trailing commas, truncated
        fixed = re.sub(r",\s*([}\]])", r"\1", json_str)
        fixed = re.sub(r"\}\s*\{", r"},{", fixed)
        try:
            actions = json.loads(fixed)
        except json.JSONDecodeError:
            result.warn(line_num, f"Invalid JSON in assistant response: {e}")
            return False

    if not isinstance(actions, list):
        result.error(line_num, "Actions is not a list")
        return False

    if len(actions) == 0:
        result.error(line_num, "Empty action array")
        return False

    if len(actions) > 5:
        result.warn(line_num, f"More than 5 actions: {len(actions)}")

    for i, action in enumerate(actions):
        if not isinstance(action, dict):
            result.error(line_num, f"Action {i} is not an object")
            continue

        if "action" not in action:
            result.error(line_num, f"Action {i} missing 'action' field")
            continue

        action_name = action["action"]

        # Resolve integer IDs
        if isinstance(action_name, int):
            if action_name in ID_TO_NAME:
                action_name = ID_TO_NAME[action_name]
            else:
                result.error(line_num, f"Action {i}: unknown action ID {action_name}")
                continue
        elif isinstance(action_name, str):
            action_name = action_name.strip().upper().replace("-", "_").replace(" ", "_")

        # Look up schema
        schema = ACTION_SCHEMA.get(action_name)
        if schema is None:
            result.error(line_num, f"Action {i}: unknown action type '{action_name}'")
            continue

        result.action_types_seen.add(action_name)

        # Check required fields
        for field in schema["required"]:
            if field not in action:
                result.error(line_num, f"Action {i} ({action_name}): missing required field '{field}'")

        # Type checks
        if "x" in action and action_name not in ("CLICK_WIDGET",):
            x = action["x"]
            if isinstance(x, (int, float)):
                if action_name == "WORLD_HOP":
                    if x < 300 or x > 600:
                        result.warn(line_num, f"Action {i} ({action_name}): unusual world number {x}")
                elif action_name == "GE_BUY":
                    pass  # price, can be 0
                elif x < 2400 or x > 4500:
                    result.warn(line_num, f"Action {i} ({action_name}): x={x} outside normal world range")

        if "y" in action and action_name not in ("CLICK_WIDGET",):
            y = action["y"]
            if isinstance(y, (int, float)) and (y < 2400 or y > 13000):
                result.warn(line_num, f"Action {i} ({action_name}): y={y} outside normal world range")

        if "ticks" in action:
            ticks = action["ticks"]
            if isinstance(ticks, (int, float)) and (ticks < 0 or ticks > 100):
                result.warn(line_num, f"Action {i} ({action_name}): unusual ticks value {ticks}")

        if "quantity" in action:
            qty = action["quantity"]
            if isinstance(qty, (int, float)) and qty < -1:
                result.error(line_num, f"Action {i} ({action_name}): invalid quantity {qty}")

    return True


def validate_semantic(line_num, game_state, actions_str, result):
    """Semantic validation: context-appropriate actions."""
    json_str = extract_json_array(actions_str)
    if json_str is None:
        return

    try:
        actions = json.loads(json_str)
    except (json.JSONDecodeError, TypeError):
        return

    has_bank_open = "[BANK_OPEN]" in game_state
    has_shop_open = "[SHOP_OPEN]" in game_state
    has_make_interface = "[MAKE_INTERFACE_OPEN]" in game_state
    has_ge_open = "[GE_OPEN]" in game_state
    has_dialogue = "[DIALOGUE]" in game_state

    for i, action in enumerate(actions):
        if not isinstance(action, dict) or "action" not in action:
            continue
        aname = action["action"]
        if isinstance(aname, int):
            aname = ID_TO_NAME.get(aname, "")
        aname = aname.strip().upper().replace("-", "_").replace(" ", "_")

        # BANK_* requires bank open (unless opening it in same batch)
        if aname in ("BANK_WITHDRAW", "BANK_DEPOSIT", "BANK_DEPOSIT_ALL"):
            if not has_bank_open and i == 0:
                result.warn(line_num, f"Action {i} ({aname}): bank action without [BANK_OPEN] in state (may be opening in sequence)")

        # SHOP_* requires shop open
        if aname in ("SHOP_BUY", "SHOP_SELL"):
            if not has_shop_open and i == 0:
                result.warn(line_num, f"Action {i} ({aname}): shop action without [SHOP_OPEN]")

        # MAKE_ITEM requires make interface
        if aname == "MAKE_ITEM":
            if not has_make_interface and i == 0:
                result.warn(line_num, f"Action {i} (MAKE_ITEM): without [MAKE_INTERFACE_OPEN]")

        # GE_* requires GE open
        if aname in ("GE_BUY", "GE_SELL"):
            if not has_ge_open and i == 0:
                result.warn(line_num, f"Action {i} ({aname}): GE action without [GE_OPEN]")

        # WAIT_ANIMATION should not follow PATH_TO
        if aname == "WAIT_ANIMATION" and i > 0:
            prev = actions[i - 1]
            prev_name = prev.get("action", "")
            if isinstance(prev_name, int):
                prev_name = ID_TO_NAME.get(prev_name, "")
            prev_name = str(prev_name).strip().upper().replace("-", "_").replace(" ", "_")
            if prev_name in ("PATH_TO", "WALK_TO", "MINIMAP_WALK"):
                result.error(line_num, f"Action {i}: WAIT_ANIMATION after {prev_name} (walking has no animation)")

    # Full inventory check
    inv_match = re.search(r"\(28/28\)", game_state)
    if inv_match:
        # With full inventory, should be banking, dropping, processing, or walking to bank
        action_names = []
        for a in actions:
            if isinstance(a, dict) and "action" in a:
                n = a["action"]
                if isinstance(n, int):
                    n = ID_TO_NAME.get(n, "")
                action_names.append(str(n).upper())
        gathering_actions = {"INTERACT_OBJECT"}
        gathering_options = {"Mine", "Chop down", "Chop"}
        is_gathering = False
        for a in actions:
            if isinstance(a, dict):
                n = str(a.get("action", "")).upper()
                opt = str(a.get("option", ""))
                if n == "INTERACT_OBJECT" and opt in gathering_options:
                    is_gathering = True
        if is_gathering:
            result.warn(line_num, "Gathering with 28/28 inventory - should bank or drop first")


def validate_file(filepath):
    """Validate an entire JSONL file."""
    result = ValidationResult()

    if not os.path.exists(filepath):
        print(f"ERROR: File not found: {filepath}")
        return result

    print(f"Validating {filepath}...")

    with open(filepath, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue

            result.total_examples += 1

            # Parse JSON
            try:
                data = json.loads(line)
            except json.JSONDecodeError as e:
                result.error(line_num, f"Invalid JSON: {e}")
                continue

            # Format validation
            if not validate_format(line_num, data, result):
                continue

            # Determine type
            if is_gameplay_example(data):
                result.gameplay_examples += 1
                game_state = data["conversations"][1]["content"]

                # Validate game state format
                validate_game_state(line_num, game_state, result)

                # Validate actions
                if len(data["conversations"]) >= 3:
                    assistant = data["conversations"][2]["content"]
                    validate_actions(line_num, assistant, game_state, result)
                    validate_semantic(line_num, game_state, assistant, result)

                    # Track skills from context (task description, actions)
                    task_text = data["conversations"][0]["content"].lower()
                    for skill, full in [("mining", "Mining"), ("mine", "Mining"),
                                       ("woodcutting", "Woodcutting"), ("chop", "Woodcutting"),
                                       ("fishing", "Fishing"), ("fish", "Fishing"),
                                       ("cooking", "Cooking"), ("cook", "Cooking"),
                                       ("firemaking", "Firemaking"), ("fire", "Firemaking"),
                                       ("crafting", "Crafting"), ("craft", "Crafting"),
                                       ("smithing", "Smithing"), ("smith", "Smithing"), ("smelt", "Smithing"),
                                       ("fletching", "Fletching"), ("fletch", "Fletching"),
                                       ("herblore", "Herblore"), ("herb", "Herblore"), ("potion", "Herblore"),
                                       ("combat", "Attack"), ("attack", "Attack"), ("fight", "Attack"),
                                       ("strength", "Strength"), ("defence", "Defence"),
                                       ("ranged", "Ranged"), ("magic", "Magic"),
                                       ("prayer", "Prayer"), ("pray", "Prayer"),
                                       ("agility", "Agility"), ("thieving", "Thieving"), ("pickpocket", "Thieving"),
                                       ("slayer", "Slayer"), ("runecraft", "Runecrafting"),
                                       ("farming", "Farming"), ("hunter", "Hunter"),
                                       ("construction", "Construction"),
                                       ("hitpoints", "Hitpoints"), ("hp", "Hitpoints")]:
                        if skill in task_text:
                            result.skills_seen.add(full)
            else:
                result.wiki_examples += 1

    return result


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 validate_training_data.py <file.jsonl> [file2.jsonl ...]")
        sys.exit(1)

    all_ok = True
    for filepath in sys.argv[1:]:
        result = validate_file(filepath)
        ok = result.report()
        if not ok:
            all_ok = False

    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
