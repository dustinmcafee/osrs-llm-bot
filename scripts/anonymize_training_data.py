#!/usr/bin/env python3
"""
Anonymize player names in OSRS training data JSONL files.

Replaces the bot's name and all nearby player names with deterministic
pseudonyms (BotPlayer, Player1, Player2, ...) so training data can be
shared publicly without exposing real usernames.

Usage:
    python3 anonymize_training_data.py                    # dry-run (prints stats)
    python3 anonymize_training_data.py --in-place         # overwrite files
    python3 anonymize_training_data.py --in-place FILE    # specific file only
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Names to always map to "BotPlayer" (the bot's own accounts)
BOT_NAMES = {"SteezyBoBeez"}

# Sections where player names can appear
PLAYER_NAME_PATTERNS = [
    # [NEARBY_PLAYERS] Name(lvl:X) ...
    re.compile(r'\[NEARBY_PLAYERS\](.+?)(?:\n\[|$)', re.DOTALL),
    # [INTERACTING] Name ...
    re.compile(r'\[INTERACTING\](.+?)(?:\n\[|$)', re.DOTALL),
    # [UNDER_ATTACK\] by Name ...
    re.compile(r'\[UNDER_ATTACK\](.+?)(?:\n\[|$)', re.DOTALL),
    # [GAME_MESSAGES] may contain player names in chat
    re.compile(r'\[GAME_MESSAGES\](.+?)(?:\n\[|$)', re.DOTALL),
]

# Extracts individual player name tokens: "Name(lvl:X)" or standalone names
NAME_TOKEN = re.compile(r'([A-Za-z0-9_\- ]+?)\(lvl:\d+\)')


def build_name_map(text: str, existing_map: dict) -> dict:
    """Scan text for player names and assign deterministic replacements."""
    name_map = dict(existing_map)

    # Always map bot names first
    for bot_name in BOT_NAMES:
        if bot_name not in name_map:
            name_map[bot_name] = "BotPlayer"

    # Find all player names in structured sections
    for pattern in PLAYER_NAME_PATTERNS:
        for match in pattern.finditer(text):
            section = match.group(1)
            for name_match in NAME_TOKEN.finditer(section):
                name = name_match.group(1).strip()
                if name and name not in name_map and name not in BOT_NAMES:
                    idx = sum(1 for v in name_map.values() if v.startswith("Player"))
                    name_map[name] = f"Player{idx + 1}"

    return name_map


def anonymize_text(text: str, name_map: dict) -> str:
    """Replace all known player names in text."""
    result = text
    # Sort by length descending to avoid partial replacements
    for name in sorted(name_map, key=len, reverse=True):
        if name in result:
            result = result.replace(name, name_map[name])
    return result


def process_file(filepath: Path, in_place: bool) -> dict:
    """Process a single JSONL file. Returns stats."""
    stats = {"lines": 0, "modified": 0, "names_found": set()}
    name_map = {}
    output_lines = []

    with open(filepath, "r") as f:
        for line in f:
            stats["lines"] += 1
            line = line.rstrip("\n")
            if not line:
                output_lines.append(line)
                continue

            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                output_lines.append(line)
                continue

            original = line
            modified = False

            # Handle both formats: {"conversations": [...]} and {"game_state": ..., "response": ...}
            if "conversations" in record:
                for msg in record["conversations"]:
                    content = msg.get("content", "")
                    name_map = build_name_map(content, name_map)
                    new_content = anonymize_text(content, name_map)
                    if new_content != content:
                        msg["content"] = new_content
                        modified = True
            else:
                for field in ["game_state", "response"]:
                    if field in record:
                        content = record[field]
                        if isinstance(content, str):
                            name_map = build_name_map(content, name_map)
                            new_content = anonymize_text(content, name_map)
                            if new_content != content:
                                record[field] = new_content
                                modified = True

            if modified:
                stats["modified"] += 1
                output_lines.append(json.dumps(record, ensure_ascii=False))
            else:
                output_lines.append(line)

    stats["names_found"] = set(name_map.keys())

    if in_place and stats["modified"] > 0:
        with open(filepath, "w") as f:
            for line in output_lines:
                f.write(line + "\n")

    return stats, name_map


def main():
    parser = argparse.ArgumentParser(description="Anonymize player names in OSRS training data")
    parser.add_argument("files", nargs="*", help="Specific JSONL files to process (default: all in osrs-llm/data/)")
    parser.add_argument("--in-place", action="store_true", help="Overwrite files (default: dry-run)")
    args = parser.parse_args()

    if args.files:
        targets = [Path(f) for f in args.files]
    else:
        data_dir = Path(__file__).parent.parent / "osrs-llm" / "data"
        targets = sorted(data_dir.glob("*.jsonl"))

    if not targets:
        print("No JSONL files found.")
        sys.exit(1)

    total_modified = 0
    all_names = {}

    for filepath in targets:
        if not filepath.exists():
            print(f"  SKIP (not found): {filepath}")
            continue

        stats, name_map = process_file(filepath, args.in_place)
        all_names.update(name_map)
        total_modified += stats["modified"]

        status = "UPDATED" if (args.in_place and stats["modified"] > 0) else "dry-run"
        print(f"  [{status}] {filepath.name}: {stats['lines']} lines, {stats['modified']} modified")

    print(f"\nName mappings ({len(all_names)}):")
    for name, replacement in sorted(all_names.items()):
        print(f"  {name} -> {replacement}")

    print(f"\nTotal lines modified: {total_modified}")
    if not args.in_place and total_modified > 0:
        print("Run with --in-place to apply changes.")


if __name__ == "__main__":
    main()
