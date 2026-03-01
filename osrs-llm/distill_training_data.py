#!/usr/bin/env python3
"""
Live Distillation Pipeline — Claude Teacher → Local Student
=============================================================
Reads structured training turns logged by the proxy during real gameplay,
filters out failures, deduplicates, categorizes, and outputs clean training
data ready for format_training_data.py.

Usage:
    python3 distill_training_data.py
    python3 distill_training_data.py --input /tmp/training_turns.jsonl --output ./data/gameplay_logs.jsonl
    python3 distill_training_data.py --min-turns 50 --dedup
    python3 distill_training_data.py --dedup --exclude /tmp/purged_turns.jsonl
"""

import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict


# ─── Defaults ─────────────────────────────────────────────────────────────────

DEFAULT_INPUT = "/tmp/training_turns.jsonl"
DEFAULT_OUTPUT = "./data/gameplay_logs.jsonl"
DEFAULT_MIN_TURNS = 0


# ─── Action Parsing ──────────────────────────────────────────────────────────

def extract_json_array(text):
    """Extract JSON array from Claude's response (mirrors ResponseParser logic)."""
    cleaned = text.strip()
    if "```" in cleaned:
        cleaned = re.sub(r"```[a-zA-Z]*\s*", "", cleaned).replace("```", "").strip()
    start = cleaned.find("[")
    end = cleaned.rfind("]")
    if start >= 0 and end > start:
        try:
            return json.loads(cleaned[start:end + 1])
        except json.JSONDecodeError:
            # Try fixing trailing commas
            fixed = re.sub(r",\s*([}\]])", r"\1", cleaned[start:end + 1])
            try:
                return json.loads(fixed)
            except json.JSONDecodeError:
                return None
    return None


def get_action_names(actions):
    """Extract action type names from a parsed action list."""
    names = []
    for a in actions:
        if isinstance(a, dict) and "action" in a:
            name = a["action"]
            if isinstance(name, int):
                names.append(str(name))
            else:
                names.append(str(name).upper())
    return names


# ─── Result Checking ─────────────────────────────────────────────────────────

def check_action_results(game_state):
    """
    Parse [ACTION_RESULTS] from a game state string.
    Returns (has_results, all_ok, failed_reasons).
    """
    if "[ACTION_RESULTS]" not in game_state:
        return False, False, []

    # Extract the ACTION_RESULTS block
    results_start = game_state.index("[ACTION_RESULTS]")
    # Find the next section header or end of string
    rest = game_state[results_start + len("[ACTION_RESULTS]"):]
    next_section = re.search(r"\n\[(?!ACTION_RESULTS)[A-Z_]+\]", rest)
    if next_section:
        results_text = rest[:next_section.start()]
    else:
        results_text = rest

    lines = results_text.strip().split("\n")
    has_any = False
    all_ok = True
    failed_reasons = []

    for line in lines:
        line = line.strip()
        if not line:
            continue
        has_any = True
        if "-> FAILED" in line:
            all_ok = False
            failed_reasons.append(line)
        elif "-> OK" in line:
            pass  # success
        # Lines without -> OK or -> FAILED are informational, ignore

    return has_any, all_ok, failed_reasons


# ─── Categorization ──────────────────────────────────────────────────────────

CATEGORY_RULES = [
    ("mining", lambda names, resp: any(
        n in ("INTERACT_OBJECT",) for n in names
    ) and any(kw in resp.lower() for kw in ["rock", "ore", "mine", "mining", "pickaxe"])),

    ("banking", lambda names, resp: any(
        n in ("BANK_DEPOSIT", "BANK_WITHDRAW", "BANK_CLOSE", "BANK_DEPOSIT_ALL") for n in names)),

    ("combat", lambda names, resp: any(
        n in ("EAT_FOOD", "SPECIAL_ATTACK") for n in names
    ) or (any(n == "INTERACT_NPC" for n in names) and any(
        kw in resp.lower() for kw in ["attack", "fight"]))),

    ("navigation", lambda names, resp: any(
        n in ("PATH_TO", "WALK_TO", "MINIMAP_WALK") for n in names)
     and all(n in ("PATH_TO", "WALK_TO", "MINIMAP_WALK", "WAIT", "TOGGLE_RUN") for n in names)),

    ("dialogue", lambda names, resp: any(
        n in ("SELECT_DIALOGUE", "CONTINUE_DIALOGUE") for n in names)),

    ("skilling", lambda names, resp: any(
        n in ("USE_ITEM_ON_ITEM", "USE_ITEM_ON_OBJECT", "MAKE_ITEM") for n in names)),

    ("shopping", lambda names, resp: any(
        n in ("SHOP_BUY", "SHOP_SELL", "GE_BUY", "GE_SELL") for n in names)),

    ("equipment", lambda names, resp: any(
        n in ("EQUIP_ITEM", "UNEQUIP_ITEM") for n in names)),
]


def categorize(action_names, response_text, game_state=""):
    """
    Categorize a turn based on its actions.
    'recovery' takes priority — if the game_state shows a FAILED action result
    and the LLM's response corrects it (successfully), that's a recovery example.
    """
    # Recovery: game_state has a FAILED result, LLM adapts
    if "[ACTION_RESULTS]" in game_state and "-> FAILED" in game_state:
        return "recovery"

    for category, rule in CATEGORY_RULES:
        if rule(action_names, response_text):
            return category
    return "general"


# ─── Priority Scoring ────────────────────────────────────────────────────

# Keywords in LLM reasoning that indicate strategic thinking
REASONING_KEYWORDS = [
    # Goal/plan changes
    r"\bswitch(?:ing)?\b.*\b(?:to|from)\b", r"\bchang(?:e|ing)\b.*\b(?:goal|plan|strategy|approach)\b",
    r"\bmov(?:e|ing)\b.*\b(?:on to|to a better|different)\b", r"\btime to\b",
    r"\bshould (?:try|start|go|move|switch)\b", r"\binstead\b.*\bI(?:'ll| will)\b",
    r"\bre-?evaluat", r"\bupgrad", r"\bbetter (?:weapon|tool|armor|equipment|method|spot|location)\b",
    # Problem solving
    r"\bneed(?:s)? to\b.*\bfirst\b", r"\bbecause\b.*\bfailed\b", r"\bwon't work\b",
    r"\bblocked\b", r"\btoo (?:strong|dangerous|high|low)\b",
    r"\bfle(?:e|eing)\b", r"\brun(?:ning)? away\b", r"\bescape\b",
    # Resource management
    r"\binventory (?:is )?full\b", r"\brun(?:ning)? (?:out|low)\b",
    r"\bneed (?:more|food|runes)\b", r"\bbank(?:ing)?\b.*\b(?:first|before)\b",
    # Multi-step planning
    r"\bstep \d\b", r"\bfirst.*then\b", r"\bafter (?:that|this)\b",
]

REASONING_PATTERNS = [re.compile(p, re.IGNORECASE) for p in REASONING_KEYWORDS]

# Words to ignore when comparing goal similarity
GOAL_NOISE_WORDS = {"the", "a", "an", "to", "and", "then", "continue", "keep", "finish",
                     "wait", "for", "kill", "killing", "off", "loot", "drops", "training",
                     "train", "at", "in", "on", "with", "my", "next", "more"}


def _goals_similar(goal_a, goal_b):
    """Check if two goals are cosmetically similar (same activity, minor wording change)."""
    # Extract significant words from each goal
    words_a = set(re.findall(r"\w+", goal_a.lower())) - GOAL_NOISE_WORDS
    words_b = set(re.findall(r"\w+", goal_b.lower())) - GOAL_NOISE_WORDS
    if not words_a or not words_b:
        return False
    # If 60%+ of significant words overlap, goals are similar
    overlap = len(words_a & words_b)
    smaller = min(len(words_a), len(words_b))
    return overlap / smaller >= 0.6 if smaller > 0 else False


def score_priority(response, action_names, game_state, category):
    """
    Score a turn's priority. Returns ("high", reason) or ("normal", None).
    High-priority turns contain strategic reasoning, decisions, or adaptations.
    """
    # Recovery turns are always high priority — LLM adapting to failure
    if category == "recovery":
        return "high", "recovery_adaptation"

    # Goal changes — only if the goal is MEANINGFULLY different from current [CURRENT_GOAL]
    # Ignore cosmetic rewording like "Finish cow kill" vs "Finish killing cow"
    actions = extract_json_array(response) or []
    current_goal_match = re.search(r"\[CURRENT_GOAL\]\s*(.+?)(?:\n|$)", game_state)
    current_goal = current_goal_match.group(1).strip().lower() if current_goal_match else ""
    for a in actions:
        if isinstance(a, dict) and a.get("goal"):
            new_goal = str(a["goal"]).strip().lower()
            if not current_goal:
                # First goal in session — always interesting
                return "high", "goal_change"
            if new_goal != current_goal and not _goals_similar(current_goal, new_goal):
                return "high", "goal_change"
            break  # goal exists but similar/unchanged

    # Equipment changes — strategic gear decisions
    if any(n in ("EQUIP_ITEM", "UNEQUIP_ITEM") for n in action_names):
        return "high", "equipment_change"

    # Skill switching — response mentions changing activity
    if any(n in ("SHOP_BUY", "SHOP_SELL", "GE_BUY", "GE_SELL") for n in action_names):
        return "high", "shopping_decision"

    # Mixed action types — not just grinding (mine+wait or attack+wait)
    unique_actions = set(action_names)
    unique_actions.discard("WAIT")
    unique_actions.discard("WAIT_ANIMATION")
    if len(unique_actions) >= 3:
        return "high", "complex_action_set"

    # Check reasoning text before the JSON array for strategic keywords
    json_start = response.find("[")
    reasoning_text = response[:json_start] if json_start > 0 else ""
    if len(reasoning_text) > 50:  # non-trivial reasoning
        for pattern in REASONING_PATTERNS:
            if pattern.search(reasoning_text):
                return "high", "strategic_reasoning"

    # Nudge response — user redirected the bot
    if "[USER_NUDGE]" in game_state:
        return "high", "nudge_response"

    return "normal", None


# ─── Deduplication ────────────────────────────────────────────────────────────

def dedup_key(game_state, action_names):
    """
    Generate a dedup key from position, inventory summary, and action sequence.
    Near-identical states with the same actions are duplicates.
    """
    # Extract position
    pos_match = re.search(r"Pos:\((\d+),(\d+),(\d+)\)", game_state)
    pos = pos_match.group(0) if pos_match else ""

    # Extract inventory summary (item count)
    inv_match = re.search(r"\((\d+)/28\)", game_state)
    inv = inv_match.group(0) if inv_match else ""

    # Action sequence
    actions_str = ",".join(action_names)

    key_str = f"{pos}|{inv}|{actions_str}"
    return hashlib.md5(key_str.encode()).hexdigest()


# ─── Main Pipeline ────────────────────────────────────────────────────────────

def load_turns(input_path):
    """Load and group turns by session."""
    sessions = defaultdict(list)
    markers = []

    with open(input_path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                print(f"  WARN: Skipping malformed JSON on line {line_num}")
                continue

            if entry.get("event") == "session_reset":
                markers.append(entry)
                continue

            session = entry.get("session", "unknown")
            sessions[session].append(entry)

    return sessions, markers


def load_exclusions(exclude_path):
    """Load (session, turn) pairs to exclude from a purge file."""
    exclusions = set()
    if not exclude_path or not os.path.exists(exclude_path):
        return exclusions
    with open(exclude_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
                session = entry.get("session", "")
                turn = entry.get("turn", -1)
                if session and turn >= 0:
                    exclusions.add((session, turn))
            except json.JSONDecodeError:
                continue
    return exclusions


def process_session(turns, args, exclusions=None):
    """
    Process a single session's turns. Returns (accepted, rejected_reasons).
    accepted: list of output dicts
    rejected_reasons: Counter of rejection reasons
    """
    accepted = []
    rejected = Counter()
    exclusions = exclusions or set()

    # Track recent action sequences for loop detection
    recent_actions = []

    for i, turn in enumerate(turns):
        game_state = turn.get("game_state", "")
        response = turn.get("response", "")
        turn_num = turn.get("turn", i)
        session_id = turn.get("session", "unknown")

        # ── Filter: purged after bug fix ──
        if (session_id, turn_num) in exclusions:
            rejected["purged_after_fix"] += 1
            recent_actions.append(None)
            continue

        # ── Filter: parse response as actions ──
        actions = extract_json_array(response)
        if actions is None:
            rejected["unparseable_response"] += 1
            recent_actions.append(None)
            continue

        if not isinstance(actions, list) or len(actions) == 0:
            rejected["empty_actions"] += 1
            recent_actions.append(None)
            continue

        action_names = get_action_names(actions)

        # ── Filter: all-WAIT turns (idle/stuck) ──
        if all(n == "WAIT" for n in action_names):
            rejected["all_wait"] += 1
            recent_actions.append(tuple(action_names))
            continue

        # ── Filter: STUCK status ──
        if "[STATUS] STUCK" in game_state:
            rejected["stuck_state"] += 1
            recent_actions.append(tuple(action_names))
            continue

        # ── Filter: loop detection (same action sequence 3+ times) ──
        action_tuple = tuple(action_names)
        recent_actions.append(action_tuple)
        if len(recent_actions) >= 3:
            last_three = recent_actions[-3:]
            if last_three[0] == last_three[1] == last_three[2] and last_three[0] is not None:
                rejected["action_loop"] += 1
                continue

        # ── Filter: check next turn's ACTION_RESULTS ──
        if i + 1 < len(turns):
            next_state = turns[i + 1].get("game_state", "")
            has_results, all_ok, failed_reasons = check_action_results(next_state)
            if has_results and not all_ok:
                rejected["action_failed"] += 1
                continue
        # Last turn in session — no next turn to verify, skip it
        else:
            rejected["no_next_turn"] += 1
            continue

        # ── Categorize ──
        category = categorize(action_names, response, game_state)

        # ── Priority scoring ──
        priority, priority_reason = score_priority(response, action_names, game_state, category)

        accepted.append({
            "game_state": game_state,
            "response": response,
            "actions_succeeded": True,
            "category": category,
            "priority": priority,
            "priority_reason": priority_reason,
            "turn": turn_num,
            "session": session_id,
        })

    return accepted, rejected


def main():
    parser = argparse.ArgumentParser(
        description="Distill Claude gameplay into clean training data"
    )
    parser.add_argument(
        "--input", default=DEFAULT_INPUT,
        help=f"Input training turns file (default: {DEFAULT_INPUT})"
    )
    parser.add_argument(
        "--output", default=DEFAULT_OUTPUT,
        help=f"Output gameplay logs file (default: {DEFAULT_OUTPUT})"
    )
    parser.add_argument(
        "--min-turns", type=int, default=DEFAULT_MIN_TURNS,
        help="Skip sessions shorter than N turns (default: 0)"
    )
    parser.add_argument(
        "--dedup", action="store_true",
        help="Remove near-duplicate game states"
    )
    parser.add_argument(
        "--exclude", default=None,
        help="JSONL file of {session, turn} pairs to exclude (from retroactive purge)"
    )
    args = parser.parse_args()

    # ── Load ──
    if not os.path.exists(args.input):
        print(f"ERROR: Input file not found: {args.input}")
        sys.exit(1)

    print(f"Loading turns from {args.input}...")
    sessions, markers = load_turns(args.input)

    total_turns = sum(len(t) for t in sessions.values())
    print(f"  {total_turns} turns across {len(sessions)} session(s)")
    print(f"  {len(markers)} session reset marker(s)")

    # ── Load exclusions ──
    exclusions = load_exclusions(args.exclude)
    if exclusions:
        print(f"  {len(exclusions)} turn(s) in exclusion list (from {args.exclude})")

    # ── Process each session ──
    all_accepted = []
    total_rejected = Counter()

    for session_id, turns in sessions.items():
        if len(turns) < args.min_turns:
            total_rejected["session_too_short"] += len(turns)
            print(f"  Skipping session {session_id[:8]}... ({len(turns)} turns < {args.min_turns} min)")
            continue

        accepted, rejected = process_session(turns, args, exclusions)
        all_accepted.extend(accepted)
        total_rejected += rejected
        print(f"  Session {session_id[:8]}...: {len(turns)} turns -> {len(accepted)} accepted")

    # ── Dedup ──
    dedup_removed = 0
    if args.dedup and all_accepted:
        seen_keys = set()
        deduped = []
        for entry in all_accepted:
            actions = extract_json_array(entry["response"])
            action_names = get_action_names(actions) if actions else []
            key = dedup_key(entry["game_state"], action_names)
            if key not in seen_keys:
                seen_keys.add(key)
                deduped.append(entry)
            else:
                dedup_removed += 1
        all_accepted = deduped

    # ── Write output ──
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        for entry in all_accepted:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    # ── Stats ──
    category_counts = Counter(e["category"] for e in all_accepted)
    priority_counts = Counter(e.get("priority", "normal") for e in all_accepted)
    priority_reason_counts = Counter(
        e.get("priority_reason") for e in all_accepted if e.get("priority") == "high"
    )

    print(f"\n{'=' * 55}")
    print(f"  DISTILLATION RESULTS")
    print(f"{'=' * 55}")
    print(f"  Total turns processed:  {total_turns}")
    print(f"  Turns accepted:         {len(all_accepted)}")
    print(f"  Turns rejected:         {sum(total_rejected.values())}")
    if dedup_removed:
        print(f"  Duplicates removed:     {dedup_removed}")
    print()

    # Priority breakdown
    high_count = priority_counts.get("high", 0)
    normal_count = priority_counts.get("normal", 0)
    if all_accepted:
        high_pct = 100 * high_count / len(all_accepted)
        print(f"  Priority distribution:")
        print(f"    high (reasoning)   {high_count:5d}  ({high_pct:5.1f}%)")
        print(f"    normal (routine)   {normal_count:5d}  ({100-high_pct:5.1f}%)")
        if priority_reason_counts:
            print(f"  High-priority reasons:")
            for reason, count in priority_reason_counts.most_common():
                print(f"    {reason:25s} {count:5d}")
        print()

    if total_rejected:
        print(f"  Rejection breakdown:")
        for reason, count in total_rejected.most_common():
            print(f"    {reason:25s} {count:5d}")
        print()

    if category_counts:
        print(f"  Category distribution:")
        for cat, count in category_counts.most_common():
            pct = 100 * count / len(all_accepted)
            print(f"    {cat:15s} {count:5d}  ({pct:5.1f}%)")
        print()

    print(f"  Output: {args.output}")
    print(f"{'=' * 55}")
    print(f"\nNext: python3 format_training_data.py  (merges with wiki + synthetic data)")


if __name__ == "__main__":
    main()
