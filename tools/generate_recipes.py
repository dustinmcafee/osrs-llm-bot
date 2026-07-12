#!/usr/bin/env python3
"""
Scrapes the OSRS Wiki for every {{Recipe|...}} template invocation across
all item pages and emits an ingredient-indexed JSON used by the bot's
RecipeRegistry. Run:

    python3 tools/generate_recipes.py

Writes `src/main/resources/recipes.json`. Polite to the wiki: batches 50
pages per API request, sleeps 0.4s between batches.

Output schema:
    {
        "<ingredient name lowercased>": [
            {
                "page": "Iron platebody",
                "outputs": ["Iron platebody"],
                "skills": [{"skill": "Smithing", "level": 33}],
                "members": false,
                "quest": null,
                "facilities": "Anvil",
                "ingredients": [{"name": "Iron bar", "qty": "5"}]
            },
            ...
        ]
    }
"""
import json
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

import requests
import mwparserfromhell

API = "https://oldschool.runescape.wiki/api.php"
OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "recipes.json"
HEADERS = {
    # Identify ourselves so the wiki knows who's hitting them.
    "User-Agent": "claude-osrs-bot/1.0 (https://github.com/anthropic-claude-bot)"
}


def fetch_all_items():
    """Enumerate every page in Category:Items via repeated cmcontinue."""
    items, cont = [], ""
    while True:
        params = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": "Category:Items",
            "cmlimit": "500",
            "cmnamespace": "0",
            "format": "json",
        }
        if cont:
            params["cmcontinue"] = cont
        r = requests.get(API, params=params, headers=HEADERS, timeout=30).json()
        for m in r["query"]["categorymembers"]:
            items.append(m["title"])
        if "continue" in r:
            cont = r["continue"]["cmcontinue"]
        else:
            break
        time.sleep(0.4)
    return items


def fetch_wikitext_batch(titles):
    """Fetch wikitext for up to 50 pages in one API hit."""
    r = requests.get(
        API,
        params={
            "action": "query",
            "prop": "revisions",
            "titles": "|".join(titles),
            "rvprop": "content",
            "rvslots": "main",
            "format": "json",
            "formatversion": "2",
        },
        headers=HEADERS,
        timeout=60,
    ).json()
    out = {}
    for p in r.get("query", {}).get("pages", []):
        title = p["title"]
        if "revisions" in p and p["revisions"]:
            content = p["revisions"][0]["slots"]["main"]["content"]
            out[title] = content
        else:
            out[title] = ""
    return out


# Indexed material/output slots like mat1, mat1quantity, output3, output3subtxt.
MAT_KEY = re.compile(r"^mat(\d+)$")
OUTPUT_KEY = re.compile(r"^output(\d+)$")
SKILL_KEY = re.compile(r"^skill(\d+)$")
SKILL_LVL_KEY = re.compile(r"^skill(\d+)lvl$")
SKILL_BOOSTABLE = re.compile(r"^skill(\d+)boostable$")
QUEST_KEY = re.compile(r"^quest(\d+)?$")


def extract_value(value_node):
    """Strip wiki markup from a parameter value, return plain text."""
    return mwparserfromhell.parse(str(value_node)).strip_code().strip()


def parse_recipe_template(tpl, page_title):
    """Convert a single {{Recipe|...}} template node into a dict."""
    params = {}
    for p in tpl.params:
        key = str(p.name).strip().lower()
        params[key] = str(p.value).strip()

    # Materials.
    ingredients = []
    for k, v in params.items():
        m = MAT_KEY.match(k)
        if m:
            idx = m.group(1)
            qty = params.get(f"mat{idx}quantity", "1")
            ingredients.append({
                "name": extract_value(v),
                "qty": extract_value(qty),
            })

    # Outputs.
    outputs = []
    for k, v in params.items():
        m = OUTPUT_KEY.match(k)
        if m:
            idx = m.group(1)
            name = extract_value(v)
            if name:
                outputs.append(name)

    # Skills.
    skills = []
    skill_lvls = {m.group(1): v for k, v in params.items() if (m := SKILL_LVL_KEY.match(k))}
    for k, v in params.items():
        m = SKILL_KEY.match(k)
        if m and not k.endswith("lvl") and not k.endswith("exp") and not k.endswith("boostable"):
            idx = m.group(1)
            skill_name = extract_value(v)
            lvl_str = skill_lvls.get(idx, "1")
            try:
                lvl = int(re.sub(r"[^0-9]", "", lvl_str) or "1")
            except ValueError:
                lvl = 1
            if skill_name:
                skills.append({"skill": skill_name, "level": lvl})

    # Quest gates.
    quest = None
    for k, v in params.items():
        if QUEST_KEY.match(k):
            value = extract_value(v).strip()
            if value and value.lower() not in ("no", "none", "n/a"):
                quest = value
                break

    members = params.get("members", "no").lower() in ("yes", "true", "y", "1")
    facilities = extract_value(params.get("facilities") or params.get("facility") or "")

    return {
        "page": page_title,
        "outputs": outputs,
        "skills": skills,
        "members": members,
        "quest": quest,
        "facilities": facilities or None,
        "ingredients": ingredients,
    }


def extract_recipes_from_page(title, wikitext):
    """Find every {{Recipe|...}} template in the page and return parsed dicts."""
    if not wikitext:
        return []
    parsed = mwparserfromhell.parse(wikitext)
    recipes = []
    for tpl in parsed.filter_templates(recursive=True):
        if str(tpl.name).strip().lower() == "recipe":
            try:
                rec = parse_recipe_template(tpl, title)
                if rec["ingredients"] or rec["outputs"]:
                    recipes.append(rec)
            except Exception as e:
                print(f"  warn: failed to parse recipe on {title}: {e}", file=sys.stderr)
    return recipes


def build_ingredient_index(all_recipes):
    """Invert: ingredient name (lowercase) → list of recipe dicts."""
    idx = defaultdict(list)
    for rec in all_recipes:
        # Don't store the same recipe twice for the same ingredient if it
        # appears in mat1 and mat2 (e.g. 2x logs). Use a per-ingredient
        # set keyed by (page, output) to dedupe.
        seen = set()
        for ing in rec["ingredients"]:
            key = ing["name"].lower().strip()
            if not key:
                continue
            tag = (rec["page"], tuple(rec["outputs"]))
            if (key, tag) in seen:
                continue
            seen.add((key, tag))
            idx[key].append(rec)
    return idx


def main():
    print("Fetching item index...", flush=True)
    items = fetch_all_items()
    print(f"  {len(items)} item pages", flush=True)

    all_recipes = []
    BATCH = 50
    for i in range(0, len(items), BATCH):
        batch = items[i : i + BATCH]
        try:
            wikitexts = fetch_wikitext_batch(batch)
        except Exception as e:
            print(f"  batch {i} failed: {e}; sleeping 5s and skipping", file=sys.stderr)
            time.sleep(5)
            continue
        for title, wt in wikitexts.items():
            all_recipes.extend(extract_recipes_from_page(title, wt))
        if (i // BATCH) % 5 == 0:
            print(f"  processed {min(i + BATCH, len(items))}/{len(items)}  recipes so far: {len(all_recipes)}", flush=True)
        time.sleep(0.4)

    print(f"Total recipe template invocations: {len(all_recipes)}", flush=True)

    idx = build_ingredient_index(all_recipes)
    print(f"Ingredients indexed: {len(idx)}", flush=True)
    print(f"Recipe entries indexed: {sum(len(v) for v in idx.values())}", flush=True)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        json.dump(idx, f, ensure_ascii=False, indent=1)
    print(f"Wrote {OUT} ({OUT.stat().st_size:,} bytes)", flush=True)


if __name__ == "__main__":
    main()
