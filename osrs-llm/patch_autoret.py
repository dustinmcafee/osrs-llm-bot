#!/usr/bin/env python3
"""
Patches synthetic_gameplay.jsonl to add AutoRet field and SET_AUTO_RETALIATE actions.

For each example:
1. Inject "AutoRet:ON" or "AutoRet:OFF" into [ENVIRONMENT] line
2. If the response attacks (INTERACT_NPC + Attack) and AutoRet was OFF:
   - Prepend SET_AUTO_RETALIATE "on" to the action array
   - Add a note about enabling auto-retaliate to the reasoning
3. If the response flees (PATH_TO + fleeing:true) and AutoRet was ON:
   - Prepend SET_AUTO_RETALIATE "off" to the action array
   - Add a note about disabling auto-retaliate to the reasoning

AutoRet assignment logic:
- Attack examples: ~60% start OFF (need to enable), ~40% start ON (already correct)
- Flee examples: ~70% start ON (need to disable), ~30% start OFF (already correct)
- Skilling/other: ~50/50 random
"""

import json
import random
import re
import sys

INPUT = "./data/synthetic_gameplay.jsonl"
OUTPUT = "./data/synthetic_gameplay.jsonl.patched"

ENABLE_ACTION = {"action": "SET_AUTO_RETALIATE", "option": "on"}
DISABLE_ACTION = {"action": "SET_AUTO_RETALIATE", "option": "off"}

# Reasoning snippets to prepend
ENABLE_REASONS = [
    "Auto-retaliate is OFF — enabling it before combat so I fight back automatically. ",
    "AutoRet is OFF, turning it on for efficient combat. ",
    "I need to enable auto-retaliate before fighting. ",
    "Enabling auto-retaliate for combat. ",
]

DISABLE_REASONS = [
    "Disabling auto-retaliate before fleeing so I don't get stuck in combat. ",
    "Must turn off auto-retaliate to flee safely. ",
    "Auto-retaliate OFF first, then flee. ",
    "Turning off auto-retaliate so I can escape without being dragged into fights. ",
]


def inject_autoret_env(user_content, autoret_on):
    """Inject AutoRet:ON or AutoRet:OFF into the [ENVIRONMENT] line."""
    tag = "AutoRet:ON" if autoret_on else "AutoRet:OFF"
    # Insert before Tick: or Spellbook: or at end of ENVIRONMENT line
    def replace_env(m):
        line = m.group(0)
        # Insert before Tick:
        if "Tick:" in line:
            return line.replace("Tick:", f"{tag} Tick:")
        # Insert before Spellbook:
        if "Spellbook:" in line:
            return line.replace("Spellbook:", f"{tag} Spellbook:")
        # Append to end
        return line.rstrip() + f" {tag}"

    return re.sub(r"\[ENVIRONMENT\].*", replace_env, user_content)


def extract_actions(asst):
    """Extract the JSON action array from the assistant response."""
    start = asst.find("[")
    end = asst.rfind("]")
    if start < 0 or end <= start:
        return None, None, None
    reasoning = asst[:start]
    actions_str = asst[start:end + 1]
    after = asst[end + 1:]
    try:
        actions = json.loads(actions_str)
        return reasoning, actions, after
    except json.JSONDecodeError:
        # Try fixing common issues
        fixed = re.sub(r",\s*([}\]])", r"\1", actions_str)
        try:
            actions = json.loads(fixed)
            return reasoning, actions, after
        except json.JSONDecodeError:
            return None, None, None


def rebuild_response(reasoning, actions, after):
    """Rebuild the assistant response from reasoning + actions."""
    actions_str = json.dumps(actions, separators=(", ", ": "))
    # Format nicely if more than 2 actions
    if len(actions) > 2:
        parts = []
        for a in actions:
            parts.append(json.dumps(a, separators=(", ", ": ")))
        actions_str = "[\n  " + ",\n  ".join(parts) + "\n]"
    else:
        actions_str = "[" + ", ".join(json.dumps(a, separators=(", ", ": ")) for a in actions) + "]"
    return reasoning + actions_str + after


def has_attack(actions):
    """Check if action array contains an Attack interaction."""
    for a in actions:
        if not isinstance(a, dict):
            continue
        action_type = a.get("action", "")
        option = a.get("option", "")
        if action_type == "INTERACT_NPC" and option == "Attack":
            return True
    return False


def has_flee(actions):
    """Check if action array contains a fleeing PATH_TO."""
    for a in actions:
        if not isinstance(a, dict):
            continue
        if a.get("action") == "PATH_TO" and a.get("fleeing") is True:
            return True
    return False


def main():
    random.seed(42)

    with open(INPUT, "r") as f:
        lines = f.readlines()

    patched = 0
    attack_enabled = 0
    attack_already_on = 0
    flee_disabled = 0
    flee_already_off = 0
    skipped = 0
    total = len(lines)

    output_lines = []

    for i, line in enumerate(lines):
        line = line.strip()
        if not line:
            output_lines.append(line)
            continue

        d = json.loads(line)
        convs = d["conversations"]
        if len(convs) < 3:
            output_lines.append(json.dumps(d, ensure_ascii=False))
            skipped += 1
            continue

        user = convs[1]["content"]
        asst = convs[2]["content"]

        reasoning, actions, after = extract_actions(asst)
        if actions is None:
            output_lines.append(json.dumps(d, ensure_ascii=False))
            skipped += 1
            continue

        is_attack = has_attack(actions)
        is_flee = has_flee(actions)

        if is_attack:
            # 60% chance AutoRet starts OFF (need to enable), 40% already ON
            autoret_on = random.random() < 0.4
            user = inject_autoret_env(user, autoret_on)

            if not autoret_on:
                # Prepend enable action
                actions.insert(0, ENABLE_ACTION.copy())
                reason_add = random.choice(ENABLE_REASONS)
                reasoning = reasoning.rstrip()
                if reasoning:
                    reasoning = reasoning + " " + reason_add + "\n"
                else:
                    reasoning = reason_add + "\n"
                attack_enabled += 1
            else:
                attack_already_on += 1

            asst = rebuild_response(reasoning, actions, after)
            patched += 1

        elif is_flee:
            # 70% chance AutoRet starts ON (need to disable), 30% already OFF
            autoret_on = random.random() < 0.7
            user = inject_autoret_env(user, autoret_on)

            if autoret_on:
                # Prepend disable action
                actions.insert(0, DISABLE_ACTION.copy())
                reason_add = random.choice(DISABLE_REASONS)
                reasoning = reasoning.rstrip()
                if reasoning:
                    reasoning = reasoning + " " + reason_add + "\n"
                else:
                    reasoning = reason_add + "\n"
                flee_disabled += 1
            else:
                flee_already_off += 1

            asst = rebuild_response(reasoning, actions, after)
            patched += 1

        else:
            # Other examples: random AutoRet state, no action changes
            autoret_on = random.random() < 0.5
            user = inject_autoret_env(user, autoret_on)
            patched += 1

        convs[1]["content"] = user
        convs[2]["content"] = asst
        d["conversations"] = convs
        output_lines.append(json.dumps(d, ensure_ascii=False))

    with open(OUTPUT, "w") as f:
        for line in output_lines:
            f.write(line + "\n")

    print(f"Patched {patched}/{total} examples ({skipped} skipped)")
    print(f"  Attack examples: {attack_enabled} enabled autoret + {attack_already_on} already ON = {attack_enabled + attack_already_on}")
    print(f"  Flee examples: {flee_disabled} disabled autoret + {flee_already_off} already OFF = {flee_disabled + flee_already_off}")
    print(f"  Other examples: {patched - attack_enabled - attack_already_on - flee_disabled - flee_already_off}")
    print(f"Output: {OUTPUT}")


if __name__ == "__main__":
    main()
