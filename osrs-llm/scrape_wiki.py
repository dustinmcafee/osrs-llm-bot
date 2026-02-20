"""
OSRS Wiki Scraper
==================
Scrapes the Old School RuneScape wiki for training data.
Targets: items, NPCs, objects, quests, skills, locations, monsters.

Usage:
    python scrape_wiki.py

Output:
    data/wiki_raw.jsonl  — raw scraped articles
"""

import json
import os
import time
import urllib.request
import urllib.parse

# OSRS Wiki API endpoint
API_URL = "https://oldschool.runescape.wiki/api.php"

# Output file
OUTPUT_DIR = "./data"
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "wiki_raw.jsonl")

# Categories to scrape (these cover the most useful game knowledge)
CATEGORIES = [
    "Items",
    "Non-player_characters",
    "Monsters",
    "Quests",
    "Locations",
    "Skilling",
    "Mining",
    "Smithing",
    "Fishing",
    "Cooking",
    "Woodcutting",
    "Firemaking",
    "Crafting",
    "Fletching",
    "Herblore",
    "Agility",
    "Thieving",
    "Runecrafting",
    "Hunter",
    "Construction",
    "Farming",
    "Prayer",
    "Magic",
    "Combat",
    "Banks",
    "Shops",
    "Transportation",
    "Ores",
    "Bars",
    "Logs",
    "Fish",
    "Food",
    "Potions",
    "Runes",
    "Weapons",
    "Armour",
    "Trees",
    "Rocks",
]

# Rate limiting — be nice to the wiki
REQUEST_DELAY = 0.5  # seconds between requests

# User agent (wiki requires a descriptive user agent)
USER_AGENT = "OSRSBotTrainingDataScraper/1.0 (training LLM on OSRS knowledge)"


def wiki_request(params):
    """Make a request to the OSRS wiki API."""
    params["format"] = "json"
    url = API_URL + "?" + urllib.parse.urlencode(params)

    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_category_members(category, limit=500):
    """Get all page titles in a category."""
    titles = []
    params = {
        "action": "query",
        "list": "categorymembers",
        "cmtitle": f"Category:{category}",
        "cmlimit": str(min(limit, 500)),
        "cmtype": "page",
    }

    while True:
        data = wiki_request(params)
        members = data.get("query", {}).get("categorymembers", [])
        titles.extend(m["title"] for m in members)

        # Handle pagination
        cont = data.get("continue", {}).get("cmcontinue")
        if cont and len(titles) < limit:
            params["cmcontinue"] = cont
            time.sleep(REQUEST_DELAY)
        else:
            break

    return titles[:limit]


def get_page_extract(title):
    """Get the plain text extract of a wiki page."""
    params = {
        "action": "query",
        "titles": title,
        "prop": "extracts",
        "explaintext": "true",       # plain text, no HTML
        "exsectionformat": "plain",
    }

    data = wiki_request(params)
    pages = data.get("query", {}).get("pages", {})

    for page_id, page in pages.items():
        if page_id == "-1":
            return None
        return page.get("extract", "")

    return None


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Collect unique page titles from all categories
    all_titles = set()
    for category in CATEGORIES:
        print(f"Fetching category: {category}...", end=" ", flush=True)
        try:
            titles = get_category_members(category, limit=200)
            new_count = len(set(titles) - all_titles)
            all_titles.update(titles)
            print(f"{len(titles)} pages ({new_count} new, {len(all_titles)} total unique)")
        except Exception as e:
            print(f"ERROR: {e}")
        time.sleep(REQUEST_DELAY)

    print(f"\nTotal unique pages to scrape: {len(all_titles)}")

    # Scrape each page
    scraped = 0
    skipped = 0
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        for i, title in enumerate(sorted(all_titles)):
            if (i + 1) % 50 == 0:
                print(f"  Progress: {i+1}/{len(all_titles)} (scraped: {scraped}, skipped: {skipped})")

            try:
                extract = get_page_extract(title)
                if extract and len(extract.strip()) > 100:
                    entry = {
                        "title": title,
                        "text": extract.strip(),
                    }
                    f.write(json.dumps(entry, ensure_ascii=False) + "\n")
                    scraped += 1
                else:
                    skipped += 1
            except Exception as e:
                print(f"  Error scraping '{title}': {e}")
                skipped += 1

            time.sleep(REQUEST_DELAY)

    print(f"\nDone! Scraped {scraped} pages, skipped {skipped}")
    print(f"Output: {OUTPUT_FILE}")
    print(f"\nNext step: Run format_training_data.py to convert to training format")


if __name__ == "__main__":
    main()
