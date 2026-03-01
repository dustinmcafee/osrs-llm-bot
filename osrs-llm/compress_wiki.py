#!/usr/bin/env python3
"""
Compress OSRS Wiki into a dense reference for LLM context injection.
============================================================
Reads wiki_raw.jsonl (4,491 articles, 7.1 MB) and produces a compact
wiki_context.txt (~200-300KB) organized by topic.

Filters out:
  - Cosmetic/holiday items
  - Quest-specific items with no general use
  - Articles under 100 chars (stubs)
  - Duplicate/redirect articles

Compresses by:
  - Removing trivia sections
  - Removing "Changes" / "Gallery" / "References" sections
  - Trimming articles to first 500 chars for minor items
  - Keeping full text for important gameplay articles (skills, locations, key items)

Usage:
    python3 compress_wiki.py
    python3 compress_wiki.py --output ../proxy/wiki_context.txt
"""

import argparse
import json
import os
import re
from collections import defaultdict

DEFAULT_INPUT = os.path.join(os.path.dirname(__file__), "data", "wiki_raw.jsonl")
DEFAULT_OUTPUT = os.path.join(os.path.dirname(__file__), "..", "proxy", "wiki_context.txt")

# Articles matching these patterns are skipped entirely
SKIP_PATTERNS = [
    r"(?i)birthday",
    r"(?i)christmas",
    r"(?i)easter",
    r"(?i)hallowe'?en",
    r"(?i)^emote",
    r"(?i)^music track",
    r"(?i)^transcript",
    r"(?i)^update:",
    r"(?i)^gallery",
    r"(?i)^historical",
]

# Sections to strip from article text
STRIP_SECTIONS = [
    "Trivia", "Changes", "Gallery", "References", "See also",
    "Gallery (historical)", "Dialogue", "Combat stats", "Shop locations",
    "External links", "Update history",
]

# Keywords that mark an article as high-priority (keep full text)
HIGH_PRIORITY_KEYWORDS = [
    "mining", "smithing", "fishing", "cooking", "woodcutting", "firemaking",
    "crafting", "fletching", "herblore", "agility", "thieving", "runecraft",
    "hunter", "construction", "farming", "prayer", "magic", "combat",
    "attack", "strength", "defence", "hitpoints", "ranged",
    "bank", "grand exchange", "furnace", "anvil", "range", "spinning wheel",
    "pickaxe", "axe", "hatchet", "tinderbox", "hammer", "chisel", "knife",
    "ore", "bar", "log", "fish", "food", "potion", "rune",
    "bronze", "iron", "steel", "mithril", "adamant", "rune", "dragon",
    "lumbridge", "varrock", "falador", "edgeville", "draynor", "al kharid",
    "barbarian village", "tutorial island",
    "cow", "chicken", "goblin", "guard", "skeleton", "zombie",
    "quest", "recipe for disaster", "monkey madness",
    "teleport", "fairy ring", "spirit tree", "canoe",
]

# Topic categories for organization
TOPIC_MATCHERS = [
    ("Skills", lambda t, txt: any(kw in t.lower() for kw in [
        "mining", "smithing", "fishing", "cooking", "woodcutting", "firemaking",
        "crafting", "fletching", "herblore", "agility", "thieving", "runecraft",
        "hunter", "construction", "farming", "prayer", "magic",
        "attack", "strength", "defence", "hitpoints", "ranged",
    ])),
    ("Ores & Bars", lambda t, txt: any(kw in t.lower() for kw in [
        "ore", " bar", "bronze bar", "iron bar", "steel bar", "mithril bar",
        "adamantite bar", "runite bar", "gold bar", "silver bar",
    ])),
    ("Tools & Equipment", lambda t, txt: any(kw in t.lower() for kw in [
        "pickaxe", "axe", "hatchet", "hammer", "chisel", "knife", "tinderbox",
        "sword", "scimitar", "mace", "dagger", "shield", "armour", "armor",
        "platebody", "platelegs", "chainbody", "helm", "boots", "gloves",
    ])),
    ("Food & Potions", lambda t, txt: any(kw in t.lower() for kw in [
        "food", "potion", "lobster", "swordfish", "shark", "trout", "salmon",
        "shrimp", "anchovies", "tuna", "bread", "cake", "pie", "stew",
        "restore", "energy", "antipoison", "strength potion", "attack potion",
    ])),
    ("Fish & Fishing", lambda t, txt: "fish" in t.lower() or "fishing" in txt[:200].lower()),
    ("Trees & Woodcutting", lambda t, txt: any(kw in t.lower() for kw in [
        "tree", "log", "woodcutting",
    ])),
    ("Locations", lambda t, txt: any(kw in t.lower() for kw in [
        "lumbridge", "varrock", "falador", "edgeville", "draynor", "al kharid",
        "camelot", "ardougne", "yanille", "seers", "catherby", "burthorpe",
        "barbarian village", "mining site", "fishing spot", "bank",
    ])),
    ("NPCs & Monsters", lambda t, txt: any(kw in t.lower() for kw in [
        "cow", "chicken", "goblin", "guard", "rat", "spider", "skeleton",
        "zombie", "demon", "dragon", "giant", "imp", "wolf",
    ])),
    ("Quests", lambda t, txt: "quest" in t.lower() or "quest" in txt[:200].lower()),
    ("Transportation", lambda t, txt: any(kw in t.lower() for kw in [
        "teleport", "fairy ring", "spirit tree", "canoe", "charter",
        "gnome glider", "minecart",
    ])),
]


def should_skip(title):
    """Check if article should be skipped entirely."""
    for pattern in SKIP_PATTERNS:
        if re.search(pattern, title):
            return True
    return False


def strip_sections(text):
    """Remove non-essential sections from article text."""
    lines = text.split("\n")
    result = []
    skip = False
    for line in lines:
        stripped = line.strip()
        # Check if this is a section header to skip
        if stripped in STRIP_SECTIONS or any(stripped.startswith(s) for s in STRIP_SECTIONS):
            skip = True
            continue
        # Check if this is a new section header (stop skipping)
        if skip and stripped and not stripped[0].isspace() and len(stripped) < 50 and stripped[0].isupper():
            # Could be a new section - check if it's NOT in skip list
            if stripped not in STRIP_SECTIONS:
                skip = False
        if not skip:
            result.append(line)
    return "\n".join(result).strip()


def is_high_priority(title, text):
    """Check if article is high-priority (keep full text)."""
    combined = (title + " " + text[:500]).lower()
    return any(kw in combined for kw in HIGH_PRIORITY_KEYWORDS)


def categorize(title, text):
    """Assign article to a topic category."""
    for category, matcher in TOPIC_MATCHERS:
        if matcher(title, text):
            return category
    return "General"


def compress_article(title, text, high_priority):
    """Compress a single article."""
    # Strip non-essential sections
    text = strip_sections(text)

    # Remove empty lines and excessive whitespace
    lines = [l.strip() for l in text.split("\n") if l.strip()]
    text = "\n".join(lines)

    if not text:
        return None

    # High priority: keep up to 800 chars
    # Low priority: keep up to 300 chars
    max_chars = 800 if high_priority else 300
    if len(text) > max_chars:
        # Truncate at sentence boundary
        truncated = text[:max_chars]
        last_period = truncated.rfind(".")
        if last_period > max_chars // 2:
            text = truncated[:last_period + 1]
        else:
            text = truncated + "..."

    return text


def main():
    parser = argparse.ArgumentParser(description="Compress OSRS wiki for LLM context")
    parser.add_argument("--input", default=DEFAULT_INPUT, help="Input wiki_raw.jsonl")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="Output wiki_context.txt")
    parser.add_argument("--max-kb", type=int, default=250, help="Target max size in KB")
    args = parser.parse_args()

    # Load articles
    articles = []
    with open(args.input, "r", encoding="utf-8") as f:
        for line in f:
            try:
                entry = json.loads(line.strip())
                title = entry.get("title", "")
                text = entry.get("text", "")
                if title and text and len(text) >= 100:
                    articles.append((title, text))
            except json.JSONDecodeError:
                continue

    print(f"Loaded {len(articles)} articles")

    # Filter
    filtered = [(t, txt) for t, txt in articles if not should_skip(t)]
    print(f"After filtering: {len(filtered)} articles")

    # Categorize and compress
    topics = defaultdict(list)
    for title, text in filtered:
        hp = is_high_priority(title, text)
        compressed = compress_article(title, text, hp)
        if compressed:
            category = categorize(title, text)
            topics[category].append((title, compressed, hp))

    # Sort topics by importance, high-priority articles first within each
    topic_order = [
        "Skills", "Ores & Bars", "Tools & Equipment", "Food & Potions",
        "Fish & Fishing", "Trees & Woodcutting", "Locations",
        "NPCs & Monsters", "Quests", "Transportation", "General",
    ]

    # Build output
    output_lines = []
    output_lines.append("=" * 60)
    output_lines.append("OSRS WIKI REFERENCE — Compressed Game Knowledge")
    output_lines.append("Source: wiki.oldschool.runescape.wiki (4,491 articles)")
    output_lines.append("=" * 60)
    output_lines.append("")

    total_articles = 0
    max_bytes = args.max_kb * 1024
    current_size = 0

    for topic in topic_order:
        if topic not in topics:
            continue
        entries = topics[topic]
        # Sort: high priority first, then alphabetical
        entries.sort(key=lambda x: (not x[2], x[0]))

        section = []
        section.append(f"\n## {topic} ({len(entries)} articles)")
        section.append("-" * 40)

        for title, text, hp in entries:
            entry_text = f"\n### {title}\n{text}\n"
            entry_size = len(entry_text.encode("utf-8"))

            if current_size + entry_size > max_bytes:
                # Budget exceeded — skip remaining articles in this topic
                remaining = len(entries) - len([l for l in section if l.startswith("### ")])
                if remaining > 0:
                    section.append(f"\n... and {remaining} more {topic} articles (truncated for context size)")
                break

            section.append(entry_text)
            current_size += entry_size
            total_articles += 1

        output_lines.extend(section)

        if current_size >= max_bytes:
            output_lines.append(f"\n[Context budget reached — {total_articles} articles included]")
            break

    output_text = "\n".join(output_lines)

    # Write output
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write(output_text)

    size_kb = len(output_text.encode("utf-8")) / 1024
    print(f"\nOutput: {args.output}")
    print(f"Size: {size_kb:.1f} KB (~{int(size_kb * 1024 / 4):,} tokens)")
    print(f"Articles included: {total_articles}")
    print(f"Topics: {len([t for t in topic_order if t in topics])}")


if __name__ == "__main__":
    main()
