"""
Training Data Formatter
========================
Merges all OSRS training data sources into a single train.jsonl.

Usage:
    python3 format_training_data.py

Input:
    data/wiki_raw.jsonl              — from scrape_wiki.py (~12.9K Q&A)
    data/gameplay_logs.jsonl         — (optional) real gameplay logs
    data/synthetic_gameplay.jsonl    — from generate_gameplay_data.py (~5K)
    example_data.jsonl               — 12 hand-crafted gold examples

Output:
    data/train.jsonl                 — merged training data (~18K examples)
"""

import json
import os
import re
import random

# Input files
WIKI_RAW = "./data/wiki_raw.jsonl"
GAMEPLAY_LOGS = "./data/gameplay_logs.jsonl"       # optional — real gameplay logs
SYNTHETIC_GAMEPLAY = "./data/synthetic_gameplay.jsonl"  # from generate_gameplay_data.py
EXAMPLE_DATA = "./example_data.jsonl"              # 12 hand-crafted gold examples

# Output
OUTPUT_FILE = "./data/train.jsonl"

# System prompts for different data types
WIKI_SYSTEM_PROMPT = (
    "You are an expert on Old School RuneScape (OSRS). "
    "Answer questions accurately based on your knowledge of the game, "
    "including items, NPCs, quests, skills, locations, and game mechanics."
)

BOT_SYSTEM_PROMPT = (
    "You are an OSRS bot controller. Analyze the game state and respond "
    "with a JSON array of actions. Think step by step about what to do, "
    "then output your actions."
)


def chunk_text(text, max_chars=1500):
    """Split long text into chunks at paragraph boundaries."""
    paragraphs = text.split("\n\n")
    chunks = []
    current = ""

    for para in paragraphs:
        para = para.strip()
        if not para:
            continue
        if len(current) + len(para) > max_chars and current:
            chunks.append(current.strip())
            current = para
        else:
            current = current + "\n\n" + para if current else para

    if current.strip():
        chunks.append(current.strip())

    return chunks


def generate_qa_from_wiki(title, text):
    """
    Generate Q&A training pairs from a wiki article.
    Uses simple heuristics to create questions from article content.
    """
    examples = []
    chunks = chunk_text(text, max_chars=1200)

    # Basic: "Tell me about X" -> article content
    if chunks:
        examples.append({
            "conversations": [
                {"role": "system", "content": WIKI_SYSTEM_PROMPT},
                {"role": "user", "content": f"Tell me about {title} in OSRS."},
                {"role": "assistant", "content": chunks[0]},
            ]
        })

    # "What is X?" variant
    if chunks:
        examples.append({
            "conversations": [
                {"role": "system", "content": WIKI_SYSTEM_PROMPT},
                {"role": "user", "content": f"What is {title}?"},
                {"role": "assistant", "content": chunks[0]},
            ]
        })

    # If there are multiple sections, create more pairs
    for i, chunk in enumerate(chunks[1:], 1):
        if len(chunk) > 200:
            examples.append({
                "conversations": [
                    {"role": "system", "content": WIKI_SYSTEM_PROMPT},
                    {"role": "user", "content": f"Tell me more about {title} in OSRS."},
                    {"role": "assistant", "content": chunk},
                ]
            })

    return examples


def format_gameplay_log(log_entry):
    """
    Convert a gameplay log entry to training format.

    Expected log format:
    {
        "game_state": "...",      # the game state sent to the LLM
        "response": "...",        # the LLM's response
        "actions_succeeded": true # whether the actions executed successfully
    }
    """
    if not log_entry.get("actions_succeeded", False):
        return None  # skip failed interactions

    game_state = log_entry.get("game_state", "")
    response = log_entry.get("response", "")

    if not game_state or not response:
        return None

    return {
        "conversations": [
            {"role": "system", "content": BOT_SYSTEM_PROMPT},
            {"role": "user", "content": game_state},
            {"role": "assistant", "content": response},
        ]
    }


def main():
    os.makedirs(os.path.dirname(OUTPUT_FILE), exist_ok=True)
    all_examples = []

    # Process wiki data
    if os.path.exists(WIKI_RAW):
        print(f"Processing wiki data from {WIKI_RAW}...")
        wiki_count = 0
        with open(WIKI_RAW, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                entry = json.loads(line)
                examples = generate_qa_from_wiki(entry["title"], entry["text"])
                all_examples.extend(examples)
                wiki_count += 1

        print(f"  {wiki_count} wiki articles -> {len(all_examples)} training examples")
    else:
        print(f"No wiki data found at {WIKI_RAW} (run scrape_wiki.py first)")

    # Process gameplay logs
    gameplay_count = 0
    if os.path.exists(GAMEPLAY_LOGS):
        print(f"Processing gameplay logs from {GAMEPLAY_LOGS}...")
        before = len(all_examples)
        with open(GAMEPLAY_LOGS, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                log_entry = json.loads(line)
                example = format_gameplay_log(log_entry)
                if example:
                    all_examples.append(example)
                    gameplay_count += 1

        print(f"  {gameplay_count} gameplay examples added")
    else:
        print(f"No gameplay logs found at {GAMEPLAY_LOGS} (optional — skipping)")

    # Process synthetic gameplay data
    synthetic_count = 0
    if os.path.exists(SYNTHETIC_GAMEPLAY):
        print(f"Processing synthetic gameplay from {SYNTHETIC_GAMEPLAY}...")
        with open(SYNTHETIC_GAMEPLAY, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                example = json.loads(line)
                # Already in correct format: {"conversations": [...]}
                all_examples.append(example)
                synthetic_count += 1
        print(f"  {synthetic_count} synthetic gameplay examples added")
    else:
        print(f"No synthetic data found at {SYNTHETIC_GAMEPLAY} (run generate_gameplay_data.py first)")

    # Process hand-crafted example data
    example_count = 0
    if os.path.exists(EXAMPLE_DATA):
        print(f"Processing hand-crafted examples from {EXAMPLE_DATA}...")
        with open(EXAMPLE_DATA, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                example = json.loads(line)
                all_examples.append(example)
                example_count += 1
        print(f"  {example_count} hand-crafted examples added")
    else:
        print(f"No example data found at {EXAMPLE_DATA} (optional)")

    if not all_examples:
        print("\nERROR: No training data generated. Run scrape_wiki.py first.")
        return

    # Shuffle
    random.seed(42)
    random.shuffle(all_examples)

    # Write output
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        for example in all_examples:
            f.write(json.dumps(example, ensure_ascii=False) + "\n")

    print(f"\nWrote {len(all_examples)} examples to {OUTPUT_FILE}")
    print(f"  Wiki Q&A:    {len(all_examples) - gameplay_count - synthetic_count - example_count}")
    print(f"  Gameplay:    {gameplay_count}")
    print(f"  Synthetic:   {synthetic_count}")
    print(f"  Hand-crafted: {example_count}")
    print(f"\nNext step: Transfer {OUTPUT_FILE} to your Windows machine and run train.py")


if __name__ == "__main__":
    main()
