#!/usr/bin/env python3
"""
Synthetic OSRS Training Data Generator
=======================================
Generates ~6,500 synthetic game state -> action training examples
covering all 39 action types, 23 skills, session notes chains, and quest walkthroughs.

Usage:
    python generate_gameplay_data.py

Output:
    data/synthetic_gameplay.jsonl
"""

import json
import os
import random
import copy

OUTPUT_FILE = "./data/synthetic_gameplay.jsonl"

# ─── XP Table (exact copy from GameStateSerializer.java) ─────────────────────
XP_TABLE = [
    0, 83, 174, 276, 388, 512, 650, 801, 969, 1154,           # 1-10
    1358, 1584, 1833, 2107, 2411, 2746, 3115, 3523, 3973, 4470, # 11-20
    5018, 5624, 6291, 7028, 7842, 8740, 9730, 10824, 12031, 13363, # 21-30
    14833, 16456, 18247, 20224, 22406, 24815, 27473, 30408, 33648, 37224, # 31-40
    41171, 45529, 50339, 55649, 61512, 67983, 75127, 83014, 91721, 101333, # 41-50
    111945, 123660, 136594, 150872, 166636, 184040, 203254, 224466, 247886, 273742, # 51-60
    302288, 333804, 368599, 407015, 449428, 496254, 547953, 605032, 668051, 737627, # 61-70
    814445, 899257, 992895, 1096278, 1210421, 1336443, 1475581, 1629200, 1798808, 1986068, # 71-80
    2192818, 2421087, 2673114, 2951373, 3258594, 3597792, 3972294, 4385776, 4842295, 5346332, # 81-90
    5902831, 6517253, 7195629, 7944614, 8771558, 9684577, 10692629, 11805606, 13034431 # 91-99
]

def xp_for_level(level):
    """Get XP needed for a given level (1-99)."""
    if level <= 1: return 0
    if level > 99: return XP_TABLE[-1]
    return XP_TABLE[level - 1]

def random_xp_at_level(level):
    """Get a random XP value for someone at the given level."""
    if level <= 1: return 0
    if level >= 99: return XP_TABLE[-1] + random.randint(0, 1000000)
    low = XP_TABLE[level - 1]
    high = XP_TABLE[level] - 1
    return random.randint(low, high)

# ─── Region Names (exact copy from GameStateReader.java lines 31-85) ─────────
REGION_NAMES = {
    12850: "Lumbridge", 12851: "Lumbridge East", 12594: "Lumbridge Swamp",
    12595: "Lumbridge Swamp East", 12849: "Lumbridge West",
    12342: "Varrock West", 12854: "Varrock East", 12853: "Varrock South",
    12598: "Grand Exchange", 12597: "GE Area",
    11828: "Falador", 11827: "Falador East", 12084: "Falador West", 11571: "Falador South",
    13104: "Al Kharid", 13105: "Al Kharid Mine", 13106: "Shantay Pass",
    12593: "Draynor Village", 12336: "Tutorial Island", 12337: "Tutorial Island",
    12442: "Edgeville", 12443: "Edgeville",
    11062: "Barbarian Village", 11318: "Barbarian Village",
    12340: "Wizard Tower", 11826: "Rimmington",
    11570: "Port Sarim", 11569: "Port Sarim Docks",
    10804: "Karamja", 10805: "Karamja East", 10547: "Brimhaven",
    11061: "Ice Mountain", 10548: "Catherby", 10292: "Seers Village",
    10036: "Ardougne East", 9780: "Ardougne West", 9781: "Yanille",
    11321: "Canifis", 13878: "Burgh de Rott", 14646: "Piscatoris",
    12341: "Champions Guild", 12596: "Cooking Guild",
    12338: "Lumbridge Farm", 12339: "Lumbridge Farms East",
    13100: "Duel Arena", 9275: "Hosidius", 7222: "Fossil Island",
    13358: "Mort Myre Swamp", 11319: "Stronghold of Security",
    12855: "Varrock Palace", 11574: "Monastery", 11575: "Black Knights Fortress",
}

# ─── OSRS Data Tables ────────────────────────────────────────────────────────

MINING_DATA = [
    {"ore": "Copper rocks", "item": "Copper ore", "level": 1, "xp": 17.5, "locations": [
        {"name": "Lumbridge Swamp", "region": 12594, "coords": [(3229, 3146), (3230, 3148)]},
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3300, 3300), (3298, 3298)]},
        {"name": "Varrock SE Mine", "region": 12853, "coords": [(3285, 3365), (3283, 3363)]},
    ]},
    {"ore": "Tin rocks", "item": "Tin ore", "level": 1, "xp": 17.5, "locations": [
        {"name": "Lumbridge Swamp", "region": 12594, "coords": [(3231, 3147), (3228, 3147)]},
        {"name": "Varrock SE Mine", "region": 12853, "coords": [(3282, 3362), (3280, 3360)]},
    ]},
    {"ore": "Iron rocks", "item": "Iron ore", "level": 15, "xp": 35, "locations": [
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3296, 3310), (3294, 3308)]},
        {"name": "Varrock SE Mine", "region": 12853, "coords": [(3286, 3369), (3284, 3367)]},
    ]},
    {"ore": "Silver rocks", "item": "Silver ore", "level": 20, "xp": 40, "locations": [
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3294, 3302)]},
    ]},
    {"ore": "Coal rocks", "item": "Coal", "level": 30, "xp": 50, "locations": [
        {"name": "Barbarian Village", "region": 11062, "coords": [(3035, 3508), (3037, 3506)]},
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3301, 3309)]},
    ]},
    {"ore": "Gold rocks", "item": "Gold ore", "level": 40, "xp": 65, "locations": [
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3295, 3296)]},
    ]},
    {"ore": "Mithril rocks", "item": "Mithril ore", "level": 55, "xp": 80, "locations": [
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3303, 3305)]},
    ]},
    {"ore": "Adamantite rocks", "item": "Adamantite ore", "level": 70, "xp": 95, "locations": [
        {"name": "Al Kharid Mine", "region": 13105, "coords": [(3299, 3294)]},
    ]},
]

WOODCUTTING_DATA = [
    {"tree": "Tree", "item": "Logs", "level": 1, "xp": 25, "locations": [
        {"name": "Lumbridge", "region": 12850, "coords": [(3200, 3240), (3198, 3242)]},
        {"name": "Varrock West", "region": 12342, "coords": [(3165, 3420), (3163, 3418)]},
    ]},
    {"tree": "Oak tree", "item": "Oak logs", "level": 15, "xp": 37.5, "locations": [
        {"name": "Varrock West", "region": 12342, "coords": [(3167, 3416), (3169, 3418)]},
        {"name": "Lumbridge", "region": 12850, "coords": [(3203, 3244)]},
    ]},
    {"tree": "Willow tree", "item": "Willow logs", "level": 30, "xp": 67.5, "locations": [
        {"name": "Draynor Village", "region": 12593, "coords": [(3090, 3232), (3088, 3230)]},
        {"name": "Lumbridge", "region": 12850, "coords": [(3233, 3215)]},
    ]},
    {"tree": "Maple tree", "item": "Maple logs", "level": 45, "xp": 100, "locations": [
        {"name": "Seers Village", "region": 10292, "coords": [(2722, 3501), (2724, 3499)]},
    ]},
    {"tree": "Yew tree", "item": "Yew logs", "level": 60, "xp": 175, "locations": [
        {"name": "Varrock Palace", "region": 12855, "coords": [(3207, 3502)]},
        {"name": "Edgeville", "region": 12442, "coords": [(3087, 3481)]},
    ]},
    {"tree": "Magic tree", "item": "Magic logs", "level": 75, "xp": 250, "locations": [
        {"name": "Seers Village", "region": 10292, "coords": [(2698, 3423)]},
    ]},
]

FISHING_DATA = [
    {"method": "Net", "tool": "Small fishing net", "fish": ["Raw shrimps", "Raw anchovies"], "level": 1, "xp": 10, "locations": [
        {"name": "Lumbridge Swamp", "region": 12594, "coords": [(3246, 3155)]},
        {"name": "Draynor Village", "region": 12593, "coords": [(3085, 3231)]},
    ]},
    {"method": "Bait", "tool": "Fishing rod", "fish": ["Raw sardine", "Raw herring"], "level": 5, "xp": 20, "locations": [
        {"name": "Lumbridge Swamp", "region": 12594, "coords": [(3246, 3155)]},
    ]},
    {"method": "Lure", "tool": "Fly fishing rod", "fish": ["Raw trout", "Raw salmon"], "level": 20, "xp": 50, "locations": [
        {"name": "Barbarian Village", "region": 11062, "coords": [(3110, 3434), (3108, 3432)]},
    ]},
    {"method": "Cage", "tool": "Lobster pot", "fish": ["Raw lobster"], "level": 40, "xp": 90, "locations": [
        {"name": "Karamja", "region": 10804, "coords": [(2924, 3178)]},
        {"name": "Catherby", "region": 10548, "coords": [(2837, 3431)]},
    ]},
    {"method": "Harpoon", "tool": "Harpoon", "fish": ["Raw tuna", "Raw swordfish"], "level": 35, "xp": 80, "locations": [
        {"name": "Karamja", "region": 10804, "coords": [(2924, 3178)]},
        {"name": "Catherby", "region": 10548, "coords": [(2837, 3431)]},
    ]},
]

COOKING_DATA = [
    {"food": "Raw shrimps", "cooked": "Shrimps", "level": 1, "xp": 30, "burn_stop": 34},
    {"food": "Raw sardine", "cooked": "Sardine", "level": 1, "xp": 40, "burn_stop": 38},
    {"food": "Raw herring", "cooked": "Herring", "level": 5, "xp": 50, "burn_stop": 41},
    {"food": "Raw anchovies", "cooked": "Anchovies", "level": 1, "xp": 30, "burn_stop": 34},
    {"food": "Raw trout", "cooked": "Trout", "level": 15, "xp": 70, "burn_stop": 50},
    {"food": "Raw salmon", "cooked": "Salmon", "level": 25, "xp": 90, "burn_stop": 58},
    {"food": "Raw tuna", "cooked": "Tuna", "level": 30, "xp": 100, "burn_stop": 63},
    {"food": "Raw lobster", "cooked": "Lobster", "level": 40, "xp": 120, "burn_stop": 74},
    {"food": "Raw swordfish", "cooked": "Swordfish", "level": 45, "xp": 140, "burn_stop": 86},
]

FOOD_HEALING = {
    "Shrimps": 3, "Sardine": 4, "Herring": 5, "Anchovies": 1,
    "Trout": 7, "Salmon": 9, "Tuna": 10, "Lobster": 12, "Swordfish": 14,
    "Monkfish": 16, "Shark": 20, "Manta ray": 22,
    "Bread": 5, "Cake": 12, "Meat pie": 6, "Cooked chicken": 3, "Cooked meat": 3,
}

SMELTING_DATA = [
    {"bar": "Bronze bar", "ingredients": ["Copper ore", "Tin ore"], "level": 1, "xp": 6.2},
    {"bar": "Iron bar", "ingredients": ["Iron ore"], "level": 15, "xp": 12.5},
    {"bar": "Silver bar", "ingredients": ["Silver ore"], "level": 20, "xp": 13.7},
    {"bar": "Steel bar", "ingredients": ["Iron ore", "Coal"], "coal": 2, "level": 30, "xp": 17.5},
    {"bar": "Gold bar", "ingredients": ["Gold ore"], "level": 40, "xp": 22.5},
    {"bar": "Mithril bar", "ingredients": ["Mithril ore", "Coal"], "coal": 4, "level": 50, "xp": 30},
    {"bar": "Adamantite bar", "ingredients": ["Adamantite ore", "Coal"], "coal": 6, "level": 70, "xp": 37.5},
]

SMITHING_ITEMS = [
    {"item": "Bronze dagger", "bar": "Bronze bar", "bars": 1, "level": 1},
    {"item": "Bronze sword", "bar": "Bronze bar", "bars": 1, "level": 4},
    {"item": "Bronze scimitar", "bar": "Bronze bar", "bars": 2, "level": 5},
    {"item": "Bronze med helm", "bar": "Bronze bar", "bars": 1, "level": 3},
    {"item": "Iron dagger", "bar": "Iron bar", "bars": 1, "level": 15},
    {"item": "Iron sword", "bar": "Iron bar", "bars": 1, "level": 19},
    {"item": "Iron scimitar", "bar": "Iron bar", "bars": 2, "level": 20},
    {"item": "Steel dagger", "bar": "Steel bar", "bars": 1, "level": 30},
    {"item": "Steel sword", "bar": "Steel bar", "bars": 1, "level": 34},
    {"item": "Steel scimitar", "bar": "Steel bar", "bars": 2, "level": 35},
    {"item": "Mithril dagger", "bar": "Mithril bar", "bars": 1, "level": 50},
    {"item": "Mithril scimitar", "bar": "Mithril bar", "bars": 2, "level": 55},
]

CRAFTING_DATA = [
    {"item": "Leather gloves", "material": "Leather", "level": 1, "xp": 13.8},
    {"item": "Leather boots", "material": "Leather", "level": 7, "xp": 16.3},
    {"item": "Leather body", "material": "Leather", "level": 14, "xp": 25},
    {"item": "Leather chaps", "material": "Leather", "level": 18, "xp": 27},
    {"item": "Gold ring", "material": "Gold bar", "level": 5, "xp": 15},
    {"item": "Gold necklace", "material": "Gold bar", "level": 6, "xp": 20},
    {"item": "Gold bracelet", "material": "Gold bar", "level": 7, "xp": 25},
    {"item": "Gold amulet (u)", "material": "Gold bar", "level": 8, "xp": 30},
    {"item": "Uncut sapphire", "cut": "Sapphire", "level": 20, "xp": 50},
    {"item": "Uncut emerald", "cut": "Emerald", "level": 27, "xp": 67.5},
    {"item": "Uncut ruby", "cut": "Ruby", "level": 63, "xp": 85},
    {"item": "Uncut diamond", "cut": "Diamond", "level": 43, "xp": 107.5},
]

FLETCHING_DATA = [
    {"item": "Arrow shaft", "material": "Logs", "tool": "Knife", "level": 1, "xp": 5},
    {"item": "Shortbow (u)", "material": "Logs", "tool": "Knife", "level": 5, "xp": 5},
    {"item": "Longbow (u)", "material": "Logs", "tool": "Knife", "level": 10, "xp": 10},
    {"item": "Oak shortbow (u)", "material": "Oak logs", "tool": "Knife", "level": 20, "xp": 16.5},
    {"item": "Oak longbow (u)", "material": "Oak logs", "tool": "Knife", "level": 25, "xp": 25},
    {"item": "Willow shortbow (u)", "material": "Willow logs", "tool": "Knife", "level": 35, "xp": 33.3},
    {"item": "Willow longbow (u)", "material": "Willow logs", "tool": "Knife", "level": 40, "xp": 41.5},
]

HERBLORE_DATA = [
    {"potion": "Attack potion(3)", "herb": "Guam leaf", "secondary": "Eye of newt", "level": 3, "xp": 25},
    {"potion": "Strength potion(3)", "herb": "Tarromin", "secondary": "Limpwurt root", "level": 12, "xp": 50},
    {"potion": "Defence potion(3)", "herb": "Ranarr weed", "secondary": "White berries", "level": 30, "xp": 75},
    {"potion": "Prayer potion(3)", "herb": "Ranarr weed", "secondary": "Snape grass", "level": 38, "xp": 87.5},
    {"potion": "Super attack(3)", "herb": "Irit leaf", "secondary": "Eye of newt", "level": 45, "xp": 100},
    {"potion": "Super strength(3)", "herb": "Kwuarm", "secondary": "Limpwurt root", "level": 55, "xp": 125},
]

COMBAT_NPCS = [
    {"name": "Chicken", "level": 1, "hp": 3, "locations": [
        {"name": "Lumbridge", "region": 12850, "coords": [(3235, 3295), (3237, 3297)]},
    ]},
    {"name": "Cow", "level": 2, "hp": 8, "locations": [
        {"name": "Lumbridge East", "region": 12851, "coords": [(3253, 3270), (3255, 3272)]},
    ]},
    {"name": "Goblin", "level": 2, "hp": 5, "locations": [
        {"name": "Lumbridge", "region": 12850, "coords": [(3259, 3227), (3257, 3225)]},
    ]},
    {"name": "Giant rat", "level": 1, "hp": 5, "locations": [
        {"name": "Lumbridge Swamp", "region": 12594, "coords": [(3225, 3150), (3227, 3148)]},
    ]},
    {"name": "Al-Kharid warrior", "level": 9, "hp": 14, "locations": [
        {"name": "Al Kharid", "region": 13104, "coords": [(3293, 3173), (3291, 3171)]},
    ]},
    {"name": "Guard", "level": 21, "hp": 22, "locations": [
        {"name": "Varrock", "region": 12853, "coords": [(3258, 3408), (3260, 3410)]},
        {"name": "Falador", "region": 11828, "coords": [(2965, 3394)]},
    ]},
    {"name": "Moss giant", "level": 42, "hp": 60, "locations": [
        {"name": "Varrock", "region": 12853, "coords": [(3160, 9900)]},
    ]},
]

EQUIPMENT_DATA = {
    "weapons": [
        {"name": "Bronze sword", "slot": "Weapon", "atk_level": 1},
        {"name": "Bronze scimitar", "slot": "Weapon", "atk_level": 1},
        {"name": "Iron sword", "slot": "Weapon", "atk_level": 1},
        {"name": "Iron scimitar", "slot": "Weapon", "atk_level": 1},
        {"name": "Steel sword", "slot": "Weapon", "atk_level": 5},
        {"name": "Steel scimitar", "slot": "Weapon", "atk_level": 5},
        {"name": "Mithril scimitar", "slot": "Weapon", "atk_level": 20},
        {"name": "Adamant scimitar", "slot": "Weapon", "atk_level": 30},
        {"name": "Rune scimitar", "slot": "Weapon", "atk_level": 40},
    ],
    "armor": [
        {"name": "Bronze med helm", "slot": "Head", "def_level": 1},
        {"name": "Iron med helm", "slot": "Head", "def_level": 1},
        {"name": "Steel full helm", "slot": "Head", "def_level": 5},
        {"name": "Bronze platebody", "slot": "Body", "def_level": 1},
        {"name": "Iron platebody", "slot": "Body", "def_level": 1},
        {"name": "Steel platebody", "slot": "Body", "def_level": 5},
        {"name": "Mithril platebody", "slot": "Body", "def_level": 20},
        {"name": "Bronze platelegs", "slot": "Legs", "def_level": 1},
        {"name": "Iron platelegs", "slot": "Legs", "def_level": 1},
        {"name": "Steel platelegs", "slot": "Legs", "def_level": 5},
        {"name": "Leather body", "slot": "Body", "def_level": 1},
        {"name": "Leather chaps", "slot": "Legs", "def_level": 1},
        {"name": "Wooden shield", "slot": "Shield", "def_level": 1},
        {"name": "Bronze kiteshield", "slot": "Shield", "def_level": 1},
        {"name": "Iron kiteshield", "slot": "Shield", "def_level": 1},
    ],
    "ranged": [
        {"name": "Shortbow", "slot": "Weapon", "rng_level": 1},
        {"name": "Oak shortbow", "slot": "Weapon", "rng_level": 5},
        {"name": "Willow shortbow", "slot": "Weapon", "rng_level": 20},
        {"name": "Leather body", "slot": "Body", "rng_level": 1},
        {"name": "Studded body", "slot": "Body", "rng_level": 20},
    ],
}

BANK_LOCATIONS = [
    {"name": "Lumbridge", "region": 12850, "coords": (3208, 3220), "plane": 2, "objects": ["Bank booth"]},
    {"name": "Varrock West", "region": 12342, "coords": (3185, 3436), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Varrock East", "region": 12854, "coords": (3253, 3420), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Al Kharid", "region": 13104, "coords": (3269, 3167), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Draynor", "region": 12593, "coords": (3092, 3243), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Falador East", "region": 11827, "coords": (3013, 3355), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Edgeville", "region": 12442, "coords": (3094, 3491), "plane": 0, "objects": ["Bank booth"]},
    {"name": "Grand Exchange", "region": 12598, "coords": (3165, 3487), "plane": 0, "objects": ["Bank booth"]},
]

SPELLS = {
    "combat": [
        {"name": "Wind Strike", "level": 1, "runes": ["Air rune", "Mind rune"]},
        {"name": "Water Strike", "level": 5, "runes": ["Water rune", "Air rune", "Mind rune"]},
        {"name": "Earth Strike", "level": 9, "runes": ["Earth rune", "Air rune", "Mind rune"]},
        {"name": "Fire Strike", "level": 13, "runes": ["Fire rune", "Air rune", "Mind rune"]},
        {"name": "Wind Bolt", "level": 17, "runes": ["Air rune", "Chaos rune"]},
        {"name": "Water Bolt", "level": 23, "runes": ["Water rune", "Air rune", "Chaos rune"]},
        {"name": "Earth Bolt", "level": 29, "runes": ["Earth rune", "Air rune", "Chaos rune"]},
        {"name": "Fire Bolt", "level": 35, "runes": ["Fire rune", "Air rune", "Chaos rune"]},
    ],
    "teleports": [
        {"name": "Lumbridge Teleport", "level": 31, "runes": ["Law rune", "Air rune", "Earth rune"]},
        {"name": "Varrock Teleport", "level": 25, "runes": ["Law rune", "Air rune", "Fire rune"]},
        {"name": "Falador Teleport", "level": 37, "runes": ["Law rune", "Air rune", "Water rune"]},
        {"name": "Camelot Teleport", "level": 45, "runes": ["Law rune", "Air rune"]},
    ],
    "utility": [
        {"name": "High Level Alchemy", "level": 55, "runes": ["Nature rune", "Fire rune"]},
        {"name": "Low Level Alchemy", "level": 21, "runes": ["Nature rune", "Fire rune"]},
        {"name": "Superheat Item", "level": 43, "runes": ["Nature rune", "Fire rune"]},
        {"name": "Enchant Level 1 Jewellery", "level": 7, "runes": ["Cosmic rune", "Water rune"]},
    ],
}

PRAYERS = [
    "Thick Skin", "Burst of Strength", "Clarity of Thought", "Sharp Eye", "Mystic Will",
    "Rock Skin", "Superhuman Strength", "Improved Reflexes", "Rapid Restore", "Rapid Heal",
    "Protect Item", "Hawk Eye", "Mystic Lore", "Steel Skin", "Ultimate Strength",
    "Incredible Reflexes", "Protect from Magic", "Protect from Missiles", "Protect from Melee",
    "Eagle Eye", "Mystic Might", "Retribution", "Redemption", "Smite",
    "Preserve", "Chivalry", "Piety", "Rigour", "Augury",
]

TOOL_TIERS = {
    "pickaxe": [
        ("Bronze pickaxe", 1), ("Iron pickaxe", 1), ("Steel pickaxe", 6),
        ("Mithril pickaxe", 21), ("Adamant pickaxe", 31), ("Rune pickaxe", 41),
    ],
    "axe": [
        ("Bronze axe", 1), ("Iron axe", 1), ("Steel axe", 6),
        ("Mithril axe", 21), ("Adamant axe", 31), ("Rune axe", 41),
    ],
    "scimitar": [
        ("Bronze scimitar", 1), ("Iron scimitar", 1), ("Steel scimitar", 5),
        ("Mithril scimitar", 20), ("Adamant scimitar", 30), ("Rune scimitar", 40),
    ],
}

LEVEL_TRANSITIONS = {
    "Mining": [(15, "Iron rocks", "Copper rocks"), (30, "Coal rocks", "Iron rocks"), (40, "Gold rocks", "Coal rocks")],
    "Woodcutting": [(15, "Oak tree", "Tree"), (30, "Willow tree", "Oak tree"), (45, "Maple tree", "Willow tree")],
    "Fishing": [(20, "Fly fishing spot", "Fishing spot"), (40, "Lobster cage", "Fly fishing spot")],
    "Attack": [(5, "Iron scimitar", "Bronze scimitar"), (20, "Mithril scimitar", "Steel scimitar"), (30, "Adamant scimitar", "Mithril scimitar"), (40, "Rune scimitar", "Adamant scimitar")],
}

DEATH_MESSAGES = [
    "Oh dear, you are dead!",
    "You have been defeated.",
]

TABS = ["inventory", "prayer", "magic", "combat", "equipment", "skills", "quest"]
ATTACK_STYLES = ["Accurate", "Aggressive", "Defensive", "Controlled"]
PLAYER_NAMES = [
    "BotAccount42", "WoodGuy", "MinerKing", "FishLord", "CookMaster",
    "SmithPro", "CraftGod", "FletchBow", "HerbMix", "Fighter1",
    "MagicUser", "PrayerBoy", "AgilityRun", "ThiefHand", "NewPlayer",
    "Fisher1", "SlayerDude", "RuneCraft", "FarmGrow", "HunterTrap",
    "IronMan99", "SkillGrind", "QuestHero", "BankStand", "LumbJack",
]

FIREMAKING_DATA = [
    {"log": "Logs", "level": 1, "xp": 40},
    {"log": "Oak logs", "level": 15, "xp": 60},
    {"log": "Willow logs", "level": 30, "xp": 90},
    {"log": "Maple logs", "level": 45, "xp": 135},
    {"log": "Yew logs", "level": 60, "xp": 202.5},
    {"log": "Magic logs", "level": 75, "xp": 303.8},
]

SHOP_NPCS = [
    {"name": "Shop keeper", "location": "Lumbridge", "region": 12850, "items": [
        "Pot", "Jug", "Shears", "Bucket", "Tinderbox", "Chisel", "Hammer",
    ]},
    {"name": "Bob", "location": "Lumbridge", "region": 12850, "items": [
        "Bronze pickaxe", "Bronze axe", "Bronze sword", "Iron sword", "Steel sword",
    ]},
    {"name": "Zaff", "location": "Varrock", "region": 12854, "items": [
        "Staff", "Magic staff", "Staff of air", "Staff of water", "Staff of earth", "Staff of fire",
    ]},
    {"name": "Aubury", "location": "Varrock", "region": 12854, "items": [
        "Air rune", "Water rune", "Earth rune", "Fire rune", "Mind rune", "Body rune", "Chaos rune", "Nature rune",
    ]},
]


# ─── System Prompt (verbatim from SystemPromptBuilder.java) ───────────────────

PROMPT_HEADER = "You are an OSRS (Old School RuneScape) bot brain. You read game state and output JSON actions.\n\n"

PROMPT_BODY = (
    "## Response Format\n"
    "You MUST output a JSON array of 1-5 action objects. Brief reasoning before the array is allowed.\n"
    "Use the string action name (e.g. \"INTERACT_OBJECT\") or the integer ID (e.g. 3). String names are preferred.\n"
    "Set \"goal\" on your first action to declare your current objective. It persists as [CURRENT_GOAL] until you change it.\n\n"
    "## Action Reference\n"
    "Each action shows: ID NAME — description — required JSON fields — example.\n\n"
    "### Movement\n"
    "38 PATH_TO — Walk to any coordinate, auto-handles doors/stairs/ladders/obstacles. Use for ALL travel.\n"
    "   Fields: x, y, plane (0=ground, 1=1st floor, 2=2nd floor)\n"
    "   Example: {\"action\":\"PATH_TO\",\"x\":3208,\"y\":3220,\"plane\":2}\n"
    "   NOTE: PATH_TO is chunked — it walks ~10 tiles per call and returns progress. You MUST re-issue PATH_TO with the same destination to continue walking. Check [ACTION_RESULTS] for \"tiles remaining\".\n"
    "1 WALK_TO — Click a visible tile. Only for very short distances (<5 tiles).\n"
    "   Fields: x, y\n"
    "   Example: {\"action\":\"WALK_TO\",\"x\":3200,\"y\":3200}\n"
    "26 MINIMAP_WALK — Click minimap. For short-medium distances (<15 tiles), same plane only.\n"
    "   Fields: x, y\n"
    "   Example: {\"action\":\"MINIMAP_WALK\",\"x\":3200,\"y\":3200}\n"
    "27 ROTATE_CAMERA — Rotate the camera.\n"
    "   Fields: option (\"north\"/\"south\"/\"east\"/\"west\"/\"left\"/\"right\"), ticks (duration)\n"
    "   Example: {\"action\":\"ROTATE_CAMERA\",\"option\":\"north\",\"ticks\":3}\n\n"
    "### NPC Interaction\n"
    "2 INTERACT_NPC — Click an NPC with a menu option. Used for: Talk-to, Attack, Bank, Trade, Pickpocket, etc.\n"
    "   Fields: name (NPC name from [NEARBY_NPCS]), option (from the NPC's [...] action list)\n"
    "   IMPORTANT: Use \"name\" for the NPC name and \"option\" for the verb. Do NOT put the verb in \"action\".\n"
    "   Example: {\"action\":\"INTERACT_NPC\",\"name\":\"Banker\",\"option\":\"Bank\"}\n"
    "   Example: {\"action\":\"INTERACT_NPC\",\"name\":\"Goblin\",\"option\":\"Attack\"}\n"
    "6 USE_ITEM_ON_NPC — Use an inventory item on an NPC.\n"
    "   Fields: item (inventory item name), npc (NPC name)\n"
    "   Example: {\"action\":\"USE_ITEM_ON_NPC\",\"item\":\"Bones\",\"npc\":\"Banker\"}\n\n"
    "### Object Interaction\n"
    "3 INTERACT_OBJECT — Click a game object with a menu option. Used for: Mine, Chop down, Bank, Open, Close, Climb, Pick, etc.\n"
    "   Fields: name (object name from [NEARBY_OBJECTS]), option (from the object's [...] action list)\n"
    "   IMPORTANT: Use \"name\" for the object name and \"option\" for the verb. Do NOT use \"object\" as a field name. Do NOT put the verb in \"action\".\n"
    "   Example: {\"action\":\"INTERACT_OBJECT\",\"name\":\"Copper rocks\",\"option\":\"Mine\"}\n"
    "   Example: {\"action\":\"INTERACT_OBJECT\",\"name\":\"Bank booth\",\"option\":\"Bank\"}\n"
    "   Example: {\"action\":\"INTERACT_OBJECT\",\"name\":\"Oak tree\",\"option\":\"Chop down\"}\n"
    "   Example: {\"action\":\"INTERACT_OBJECT\",\"name\":\"Potato\",\"option\":\"Pick\"}\n"
    "7 USE_ITEM_ON_OBJECT — Use an inventory item on an object.\n"
    "   Fields: item (inventory item name), object (object name)\n"
    "   Example: {\"action\":\"USE_ITEM_ON_OBJECT\",\"item\":\"Iron ore\",\"object\":\"Furnace\"}\n\n"
    "### Inventory Items\n"
    "4 USE_ITEM — Left-click use an inventory item.\n"
    "   Fields: name (item name)\n"
    "   Example: {\"action\":\"USE_ITEM\",\"name\":\"Bones\"}\n"
    "5 USE_ITEM_ON_ITEM — Use one inventory item on another.\n"
    "   Fields: item1, item2\n"
    "   Example: {\"action\":\"USE_ITEM_ON_ITEM\",\"item1\":\"Knife\",\"item2\":\"Logs\"}\n"
    "8 EQUIP_ITEM — Equip a weapon/armor from inventory.\n"
    "   Fields: name (item name)\n"
    "   Example: {\"action\":\"EQUIP_ITEM\",\"name\":\"Bronze sword\"}\n"
    "32 UNEQUIP_ITEM — Remove equipped item.\n"
    "   Fields: name (item name)\n"
    "   Example: {\"action\":\"UNEQUIP_ITEM\",\"name\":\"Bronze sword\"}\n"
    "9 DROP_ITEM — Drop an item from inventory.\n"
    "   Fields: name (item name)\n"
    "   Example: {\"action\":\"DROP_ITEM\",\"name\":\"Copper ore\"}\n"
    "10 PICKUP_ITEM — Pick up an item from the ground. Must be listed in [NEARBY_GROUND_ITEMS].\n"
    "   Fields: name (item name)\n"
    "   Example: {\"action\":\"PICKUP_ITEM\",\"name\":\"Bones\"}\n"
    "11 EAT_FOOD — Eat food from inventory to restore HP.\n"
    "   Fields: name (food item name)\n"
    "   Example: {\"action\":\"EAT_FOOD\",\"name\":\"Shrimps\"}\n\n"
    "### Banking\n"
    "To open a bank: use INTERACT_OBJECT on \"Bank booth\" with option \"Bank\", or INTERACT_NPC on \"Banker\" with option \"Bank\".\n"
    "19 BANK_WITHDRAW — Withdraw item from bank. Bank must be open ([BANK_OPEN] in environment).\n"
    "   Fields: name (item name), quantity (1/5/10/N/-1 for all)\n"
    "   Example: {\"action\":\"BANK_WITHDRAW\",\"name\":\"Bronze pickaxe\",\"quantity\":1}\n"
    "18 BANK_DEPOSIT — Deposit item into bank.\n"
    "   Fields: name (item name), quantity (1/5/10/N/-1 for all)\n"
    "   Example: {\"action\":\"BANK_DEPOSIT\",\"name\":\"Copper ore\",\"quantity\":-1}\n"
    "34 BANK_DEPOSIT_ALL — Deposit entire inventory. No fields needed.\n"
    "   Example: {\"action\":\"BANK_DEPOSIT_ALL\"}\n"
    "20 BANK_CLOSE — Close the bank interface. No fields needed.\n"
    "   Example: {\"action\":\"BANK_CLOSE\"}\n\n"
    "### Shopping\n"
    "24 SHOP_BUY — Buy from a shop. Shop must be open.\n"
    "   Fields: name (item name), quantity (1/5/10/50)\n"
    "   Example: {\"action\":\"SHOP_BUY\",\"name\":\"Bronze pickaxe\",\"quantity\":1}\n"
    "25 SHOP_SELL — Sell to a shop.\n"
    "   Fields: name (item name), quantity (1/5/10/50)\n"
    "   Example: {\"action\":\"SHOP_SELL\",\"name\":\"Copper ore\",\"quantity\":10}\n\n"
    "### Grand Exchange\n"
    "28 GE_BUY — Buy from GE. Fields: name, quantity, x (price, 0=market price)\n"
    "29 GE_SELL — Sell on GE. Fields: name, quantity\n\n"
    "### Combat & Prayer\n"
    "17 SPECIAL_ATTACK — Activate special attack. No fields.\n"
    "12 TOGGLE_PRAYER — Toggle a prayer on/off. Fields: name (prayer name)\n"
    "35 SET_ATTACK_STYLE — Change attack style. Fields: option (\"Accurate\"/\"Aggressive\"/\"Defensive\"/\"Controlled\")\n"
    "36 SET_AUTOCAST — Set autocast spell. Fields: name (spell name), option (optional: \"defensive\")\n\n"
    "### Magic\n"
    "22 CAST_SPELL — Cast a spell. Can target nothing, an NPC, or an inventory item.\n"
    "   No target: {\"action\":\"CAST_SPELL\",\"name\":\"High Level Alchemy\",\"item\":\"Gold bracelet\"}\n"
    "   On NPC: {\"action\":\"CAST_SPELL\",\"name\":\"Fire Strike\",\"npc\":\"Goblin\"}\n"
    "   No target: {\"action\":\"CAST_SPELL\",\"name\":\"Lumbridge Teleport\"}\n\n"
    "### Dialogue & UI\n"
    "15 CONTINUE_DIALOGUE — Click \"Click here to continue\" in chat. No fields.\n"
    "   Example: {\"action\":\"CONTINUE_DIALOGUE\"}\n"
    "14 SELECT_DIALOGUE — Choose a dialogue option. Fields: option (the text or number of the choice)\n"
    "   Example: {\"action\":\"SELECT_DIALOGUE\",\"option\":\"Yes\"}\n"
    "23 MAKE_ITEM — Select an item in a make/craft/smelt interface. Fields: name (item name)\n"
    "   Example: {\"action\":\"MAKE_ITEM\",\"name\":\"Bronze bar\"}\n"
    "30 OPEN_TAB — Open a game tab. Fields: name (\"inventory\"/\"prayer\"/\"magic\"/\"combat\"/\"equipment\"/\"skills\"/\"quest\")\n"
    "   Example: {\"action\":\"OPEN_TAB\",\"name\":\"inventory\"}\n"
    "21 CLICK_WIDGET — Click a UI widget at pixel coordinates. ONLY for interface buttons, never for game-world objects.\n"
    "   Fields: x, y (screen pixel coordinates), option (optional: \"right\" for right-click)\n"
    "31 TYPE_TEXT — Type text into a chatbox/input. Fields: text, option (optional: \"enter\" to press enter after)\n"
    "33 PRESS_KEY — Press a keyboard key. Fields: name (key name like \"space\", \"enter\", \"escape\")\n\n"
    "### Other\n"
    "16 WAIT — Wait a number of game ticks (1 tick = 0.6s). Fields: ticks\n"
    "   Example: {\"action\":\"WAIT\",\"ticks\":3}\n"
    "39 WAIT_ANIMATION — Wait until the player's current animation finishes (mining, woodcutting, cooking, etc). Aborts early if attacked.\n"
    "   Fields: ticks (max wait, default 20), option (optional: \"ignore_combat\" to not abort on combat)\n"
    "   Example: {\"action\":\"WAIT_ANIMATION\",\"ticks\":20}\n"
    "13 TOGGLE_RUN — Toggle run on/off. No fields.\n"
    "37 WORLD_HOP — Hop to a different world. Fields: x (world number)\n"
    "40 CLEAR_ACTION_QUEUE — Immediately discard all remaining queued actions. Place this FIRST in your array when you need to cancel previous actions and react to something urgent (e.g. suddenly low HP, being attacked, failed action). Actions after this in the same array will execute normally.\n"
    "   Example: [{\"action\":\"CLEAR_ACTION_QUEUE\"},{\"action\":\"EAT_FOOD\",\"name\":\"Lobster\"}]\n\n"
    "## CRITICAL RULES — Read These Carefully\n"
    "1. Your response MUST contain a JSON array. No JSON = bot does nothing.\n"
    "2. Use INTERACT_OBJECT (3) to interact with objects. Use INTERACT_NPC (2) to interact with NPCs. Match \"name\" and \"option\" EXACTLY from [NEARBY_OBJECTS] or [NEARBY_NPCS].\n"
    "3. NEVER use CLICK_WIDGET (21) for game-world NPCs or objects. CLICK_WIDGET is ONLY for UI interface buttons.\n"
    "4. WAIT_ANIMATION (39) is ONLY for waiting during skilling/combat animations (mining, chopping, fishing, cooking, smithing, fighting). Do NOT use WAIT_ANIMATION after PATH_TO or WALK_TO — walking has no animation to wait for.\n"
    "5. After INTERACT_OBJECT(Mine/Chop/Fish) → use WAIT_ANIMATION. After PATH_TO → do NOT use WAIT_ANIMATION.\n"
    "6. PATH_TO is chunked: it walks ~10 tiles and returns \"X tiles remaining\". Re-issue PATH_TO with the SAME destination to keep walking. Do NOT add WAIT_ANIMATION after PATH_TO.\n"
    "7. If [ACTION_RESULTS] shows FAILED, the remaining queued actions were automatically cleared. Do NOT repeat the same failed action. Re-assess the situation and try a different target, approach, or action.\n"
    "8. Eat food if HP below 50%. Bank or drop when inventory full.\n"
    "9. [HINT_ARROW] = highest priority. Follow it immediately.\n"
    "10. [INSTRUCTION] = direct game instruction. Do what it says.\n"
    "11. [SESSION_NOTES] = your compressed history. Use it to avoid repeating mistakes.\n"
    "12. \"I can't reach that\" / \"You can't do that\" = change approach immediately.\n"
    "13. \"Someone else is fighting that\" / \"no ore\" / \"tree fell\" = switch to a different target.\n"
    "14. Use OPEN_TAB (30) for game tabs — never guess screen coordinates for tabs.\n"
    "15. [BANK_CONTENTS] shows bank items when bank is open. [BANK_OPEN] in environment means bank is open.\n"
    "16. **STUCK handling**: If [STATUS] shows STUCK, you MUST take action to unstick. NEVER just WAIT when stuck. If STUCK at a skilling spot, click a DIFFERENT nearby rock/tree/fishing spot. If STUCK while walking, use PATH_TO to a slightly different coordinate. Stuck means your current approach failed — change it.\n"
    "17. **Full inventory (28/28)**: You cannot gather more items. Decide what to do with what you have — bank, drop, sell, process (smelt, cook, fletch, alch, smith), or move on to a different activity. Do NOT just WAIT with a full inventory.\n"
    "18. **Never stop working.** There is always something productive to do. If your current task is blocked or complete, set a new goal and keep going. NEVER use WAIT repeatedly with no plan. NEVER declare \"session complete\" or \"mission accomplished\" — sessions don't end.\n\n"
    "## Common Action Patterns\n"
    "Mining copper: [{\"action\":\"INTERACT_OBJECT\",\"name\":\"Copper rocks\",\"option\":\"Mine\",\"goal\":\"Mine copper ore\"},{\"action\":\"WAIT_ANIMATION\"}]\n"
    "Chopping tree: [{\"action\":\"INTERACT_OBJECT\",\"name\":\"Oak tree\",\"option\":\"Chop down\"},{\"action\":\"WAIT_ANIMATION\"}]\n"
    "Walking somewhere: [{\"action\":\"PATH_TO\",\"x\":3208,\"y\":3220,\"plane\":2,\"goal\":\"Walk to Lumbridge bank\"}]\n"
    "Continue walking (after \"tiles remaining\"): [{\"action\":\"PATH_TO\",\"x\":3208,\"y\":3220,\"plane\":2}]\n"
    "Open bank + withdraw: [{\"action\":\"INTERACT_OBJECT\",\"name\":\"Bank booth\",\"option\":\"Bank\"},{\"action\":\"BANK_WITHDRAW\",\"name\":\"Bronze pickaxe\",\"quantity\":1}]\n"
    "Deposit all + withdraw: [{\"action\":\"BANK_DEPOSIT_ALL\"},{\"action\":\"BANK_WITHDRAW\",\"name\":\"Bronze pickaxe\",\"quantity\":1},{\"action\":\"BANK_CLOSE\"}]\n"
    "Attack NPC: [{\"action\":\"INTERACT_NPC\",\"name\":\"Goblin\",\"option\":\"Attack\"},{\"action\":\"WAIT_ANIMATION\",\"ticks\":30}]\n"
    "Eat food: [{\"action\":\"EAT_FOOD\",\"name\":\"Shrimps\"}]\n"
    "Drop inventory items: [{\"action\":\"DROP_ITEM\",\"name\":\"Copper ore\"},{\"action\":\"DROP_ITEM\",\"name\":\"Copper ore\"},{\"action\":\"DROP_ITEM\",\"name\":\"Copper ore\"}]\n"
    "Pick up ground item: [{\"action\":\"PICKUP_ITEM\",\"name\":\"Bones\"}]\n"
    "Pick object (potato/cabbage/wheat): [{\"action\":\"INTERACT_OBJECT\",\"name\":\"Potato\",\"option\":\"Pick\"}]\n\n"
    "## Navigation\n"
    "- Coordinates: X increases east, Y increases north. Plane: 0=ground, 1=1st floor, 2=2nd floor.\n"
    "- Use PATH_TO for ALL travel. It handles doors, stairs, ladders, and obstacles automatically.\n"
    "- If PATH_TO returns \"tiles remaining\" → re-issue PATH_TO with same x,y,plane to continue.\n"
    "- If PATH_TO fails → try nearby coordinates or a different route.\n\n"
    "## Key Locations (x,y,plane)\n"
    "Banks: Lumbridge(3208,3220,2) | Varrock W(3185,3436,0) | Varrock E(3253,3420,0) | Al Kharid(3269,3167,0) | Draynor(3092,3243,0) | Falador E(3013,3355,0) | Edgeville(3094,3491,0) | GE(3165,3487,0)\n"
    "Mining: Lumbridge Swamp(3230,3148,0) | Varrock SE(3285,3365,0) | Al Kharid(3300,3300,0) | Mining Guild(3046,9756,0)\n"
    "Woodcutting: Lumbridge trees(3200,3240,0) | Varrock W oaks(3167,3416,0) | Draynor willows(3090,3232,0)\n"
    "Fishing: Lumbridge shrimp(3246,3156,0) | Barbarian trout(3110,3434,0)\n"
    "Combat: Lumbridge cows(3253,3270,0) | Lumbridge goblins(3259,3227,0) | Al Kharid warriors(3293,3173,0)\n\n"
    "## Training Progression\n"
    "Mining: 1-15 copper/tin → 15-45 iron(Al Kharid) → 45+ granite/gold\n"
    "WC: 1-15 trees → 15-30 oaks → 30-60 willows(Draynor) → 60+ yews\n"
    "Fishing: 1-20 shrimp → 20-40 trout(Barbarian) → 40-62 lobster → 62+ monkfish\n"
    "Cooking: cook what you fish, use a range for lower burn rate\n"
    "Combat: 1-20 chickens/cows → 20-40 warriors → 40-60 flesh crawlers → 60+ sand crabs\n"
    "Prayer: bury bones as you get them. Big bones = 15xp each.\n\n"
    "## Tips\n"
    "- **Equipment matters.** Always use the best tool/weapon you can for the job. Higher-tier pickaxes mine faster, better axes chop faster, stronger weapons kill faster. If you have access to a better tool (in bank or from a shop), get it before grinding. Efficiency is everything.\n"
    "  Tools: Bronze(1) → Iron(1) → Steel(6) → Mithril(21) → Adamant(31) → Rune(41) → Dragon(61)\n"
    "  Weapons: Match your Attack level. Scimitars are best for melee training.\n"
    "- [XP] shows progress toward next level. Focus on skills close to leveling.\n"
    "- Skilling loop: gather → bank when inventory full → return to gather spot → repeat.\n"
    "- Power-training: DROP_ITEM is faster than banking. Use for copper/tin/oak.\n"
    "- Turn run ON for long travel. Weight affects run drain.\n"
    "- When [STATUS] is not IDLE, the player is already doing something. Use WAIT_ANIMATION to wait.\n"
    "- When [STATUS] is IDLE, the player needs a new action.\n"
)

def build_system_prompt(task):
    return PROMPT_HEADER + "## Your Task\n" + task + "\n\n" + PROMPT_BODY

def build_simple_system_prompt(task):
    """Shorter system prompt for 30% of examples."""
    return (
        "You are an OSRS (Old School RuneScape) bot brain. You read game state and output JSON actions.\n\n"
        "## Your Task\n" + task + "\n\n"
        "## Response Format\n"
        "You MUST output a JSON array of 1-5 action objects. Brief reasoning before the array is allowed.\n"
    )


# ─── Game State Serializer (replicates GameStateSerializer.java) ─────────────

SKILL_ORDER = [
    "Atk", "Str", "Def", "Rng", "Mag", "WC", "Mine", "Fish", "Cook", "FM",
    "Craft", "Smith", "Fletch", "Slay", "Farm", "Con", "Hunt", "Agi", "Thiev", "Herb", "RC"
]

XP_SKILL_ORDER = [
    "Atk", "Str", "Def", "Rng", "Mag", "HP", "Pray", "WC", "Mine", "Fish", "Cook", "FM",
    "Craft", "Smith", "Fletch", "Slay", "Farm", "Con", "Hunt", "Agi", "Thiev", "Herb", "RC"
]

EQUIPMENT_SLOTS = ["Head", "Cape", "Amulet", "Weapon", "Body", "Shield", "Legs", "Gloves", "Boots", "Ring", "Ammo"]

def make_default_skills(level=1):
    """Create default skill dict all at given level."""
    return {s: level for s in SKILL_ORDER}

def serialize_xp_line(skills, xp_dict):
    """Replicate [XP] line from GameStateSerializer."""
    parts = []
    for abbrev in XP_SKILL_ORDER:
        xp = xp_dict.get(abbrev, 0)
        if xp <= 0:
            continue
        # Determine level for this skill
        if abbrev in ("HP", "Pray"):
            level = xp_dict.get(f"_{abbrev}_level", 10 if abbrev == "HP" else 1)
        else:
            level = skills.get(abbrev, 1)
        if level >= 99:
            parts.append(f" {abbrev}:{xp}(MAX)")
        else:
            next_xp = XP_TABLE[level] if level < len(XP_TABLE) else XP_TABLE[-1]
            pct = (xp * 100) // next_xp if next_xp > 0 else 100
            parts.append(f" {abbrev}:{xp}/{next_xp}({pct}%)")
    if parts:
        return "[XP]" + "".join(parts)
    return None

def serialize_game_state(
    player_name, combat_level, hp, max_hp, prayer, max_prayer, run_energy, run_on,
    weight, spec_pct, pos_x, pos_y, plane,
    status, skills, inventory, equipment, nearby_npcs, nearby_objects,
    nearby_ground_items, nearby_players, region_id, world, tab, attack_style, tick,
    # Optional sections
    xp_dict=None, boosted=None, dest_x=0, dest_y=0, stuck_ticks=0, anim_id=-1,
    current_goal=None, action_results=None, dialogue=None, dialogue_options=None,
    dialogue_speaker=None, dialogue_text=None, hint_arrow=None, instruction=None,
    tutorial_progress=0, active_prayers=None, game_messages=None,
    bank_open=False, bank_contents=None, bank_unique=0,
    shop_open=False, shop_contents=None, ge_open=False, ge_offers=None,
    make_interface_open=False, instanced=False, interacting_with=None,
    under_attack=None, poison_status=0,
    session_notes=None,
):
    """Replicates GameStateSerializer.java output exactly."""
    sb = []

    # [SESSION_NOTES]
    if session_notes:
        sb.append("[SESSION_NOTES] Summary of earlier activity:")
        sb.append(session_notes)
        sb.append("")

    # [CURRENT_GOAL]
    if current_goal:
        sb.append(f"[CURRENT_GOAL] {current_goal}")

    # [ACTION_RESULTS]
    if action_results:
        sb.append(f"[ACTION_RESULTS] Your previous actions:")
        for i, ar in enumerate(action_results, 1):
            sb.append(f"  {i}. {ar}")

    # [PLAYER] line
    run_str = "[ON]" if run_on else "[OFF]"
    sb.append(
        f"[PLAYER] {player_name} | Combat:{combat_level} | HP:{hp}/{max_hp} | "
        f"Prayer:{prayer}/{max_prayer} | Run:{run_energy}% {run_str} | "
        f"Weight:{weight}kg | SpecAtk:{spec_pct}% | Pos:({pos_x},{pos_y},{plane})"
    )

    # [STATUS] line
    if status == "IDLE":
        if stuck_ticks >= 4 and (dest_x != 0 or dest_y != 0):
            sb.append(f"[STATUS] STUCK({stuck_ticks} ticks) intended_dest:({dest_x},{dest_y}) *** PATH BLOCKED — use PATH_TO or open nearby doors/gates ***")
        else:
            sb.append("[STATUS] IDLE")
    elif status == "IN_COMBAT":
        sb.append("[STATUS] IN_COMBAT")
    elif status == "MOVING":
        line = "[STATUS] MOVING"
        if dest_x != 0 or dest_y != 0:
            line += f" dest:({dest_x},{dest_y})"
        sb.append(line)
    elif status.startswith("ANIMATING"):
        sb.append(f"[STATUS] ANIMATING({anim_id})")
    else:
        sb.append(f"[STATUS] {status}")

    # [SKILLS] line
    skills_line = "[SKILLS] " + " ".join(f"{s}:{skills.get(s, 1)}" for s in SKILL_ORDER)
    sb.append(skills_line)

    # [BOOSTED] line
    if boosted:
        parts = []
        for s in ["Atk", "Str", "Def", "Rng", "Mag"]:
            if s in boosted and boosted[s] != skills.get(s, 1):
                parts.append(f" {s}:{boosted[s]}")
        if parts:
            sb.append("[BOOSTED]" + "".join(parts))

    # [XP] line
    if xp_dict:
        xp_line = serialize_xp_line(skills, xp_dict)
        if xp_line:
            sb.append(xp_line)

    # [INVENTORY] line
    # In OSRS, stackable items (runes, coins, arrows) use 1 slot regardless of quantity.
    # Non-stackable items each use 1 slot. We simplify: each inventory tuple = N slots
    # where N is qty for non-stackable items, or 1 for stackable items.
    STACKABLE_KEYWORDS = ["rune", "arrow", "bolt", "coin", "feather", "bait", "dart", "knife", "needle", "thread"]
    used_slots = 0
    for name, qty in inventory:
        if any(kw in name.lower() for kw in STACKABLE_KEYWORDS):
            used_slots += 1  # stackable = 1 slot
        else:
            used_slots += qty
    used_slots = min(used_slots, 28)  # cap at 28
    sb.append(f"[INVENTORY] ({used_slots}/28) " + (
        " | ".join(f"{name}(x{qty})" for name, qty in inventory) if inventory else "Empty"
    ))

    # [EQUIPMENT] line
    if equipment:
        sb.append("[EQUIPMENT] " + " | ".join(f"{slot}:{name}" for slot, name in equipment))
    else:
        sb.append("[EQUIPMENT] None")

    # [NEARBY_*] sections
    for label, entities in [
        ("NEARBY_NPCS", nearby_npcs), ("NEARBY_OBJECTS", nearby_objects),
        ("NEARBY_GROUND_ITEMS", nearby_ground_items), ("NEARBY_PLAYERS", nearby_players)
    ]:
        if not entities:
            continue
        entries = []
        # Group by name for deduplication
        groups = {}
        for e in entities[:15]:
            key = e["name"]
            if e.get("level", 0) > 0:
                key += f"(lvl:{e['level']})"
            groups.setdefault(key, []).append(e)

        for key, group in groups.items():
            nearest = group[0]
            entry = ""
            if len(group) > 1:
                entry = f"{key}(x{len(group)}) nearest:pos({nearest['x']},{nearest['y']}) dist:{nearest['dist']}"
            else:
                entry = f"{key} pos:({nearest['x']},{nearest['y']}) dist:{nearest['dist']}"
            if nearest.get("qty", 0) > 1:
                entry += f" qty:{nearest['qty']}"
            if nearest.get("hp_pct"):
                entry += f" hp:{nearest['hp_pct']}%"
            if nearest.get("actions"):
                entry += f" [{','.join(nearest['actions'])}]"
            entries.append(entry)
        sb.append(f"[{label}] " + " | ".join(entries))

    # [ENVIRONMENT] line
    region_name = REGION_NAMES.get(region_id)
    env_line = "[ENVIRONMENT] Region:"
    if region_name:
        env_line += f"{region_name}({region_id})"
    else:
        env_line += str(region_id)
    env_line += f" Plane:{plane} World:{world} Tab:{tab} Style:{attack_style}"
    if instanced:
        env_line += " [INSTANCED]"
    if bank_open:
        env_line += " [BANK_OPEN]"
    if shop_open:
        env_line += " [SHOP_OPEN]"
    if make_interface_open:
        env_line += " [MAKE_INTERFACE_OPEN]"
    if ge_open:
        env_line += " [GE_OPEN]"
    if poison_status > 0:
        env_line += " [VENOMED]" if poison_status > 1000000 else " [POISONED]"
    env_line += f" Tick:{tick}"
    sb.append(env_line)

    # [INTERACTING]
    if interacting_with:
        sb.append(f"[INTERACTING] {interacting_with}")

    # [UNDER_ATTACK]
    if under_attack:
        sb.append(f"[UNDER_ATTACK] {', '.join(under_attack)} *** YOU ARE BEING ATTACKED ***")

    # [HINT_ARROW]
    if hint_arrow:
        sb.append(f"[HINT_ARROW] {hint_arrow['type']} -> {hint_arrow['target']} at ({hint_arrow['x']},{hint_arrow['y']}) *** THIS IS WHAT YOU SHOULD INTERACT WITH NEXT ***")

    # [INSTRUCTION]
    if instruction:
        sb.append(f"[INSTRUCTION] {instruction}")

    # [TUTORIAL_PROGRESS]
    if 0 < tutorial_progress < 1000:
        sb.append(f"[TUTORIAL_PROGRESS] step:{tutorial_progress}")

    # [DIALOGUE]
    if dialogue:
        d_line = f"[DIALOGUE] type:{dialogue}"
        if dialogue_speaker:
            d_line += f' speaker:"{dialogue_speaker}"'
        if dialogue_text:
            d_line += f' text:"{dialogue_text}"'
        if dialogue in ("npc_continue", "player_continue", "sprite_continue"):
            d_line += " -> Use CONTINUE_DIALOGUE to proceed"
        sb.append(d_line)
        if dialogue_options:
            sb.append(f"[DIALOGUE_OPTIONS] {' | '.join(dialogue_options)} -> Use SELECT_DIALOGUE with option number")

    # [ACTIVE_PRAYERS]
    if active_prayers:
        sb.append(f"[ACTIVE_PRAYERS] {', '.join(active_prayers)}")

    # [GAME_MESSAGES]
    if game_messages:
        sb.append(f"[GAME_MESSAGES] {' | '.join(game_messages)}")

    # [BANK_CONTENTS]
    if bank_contents:
        display = bank_contents[:40]
        line = f"[BANK_CONTENTS] ({bank_unique} unique items) " + " | ".join(display)
        if len(bank_contents) > 40:
            line += f" | ... ({len(bank_contents) - 40} more)"
        sb.append(line)

    # [SHOP_CONTENTS]
    if shop_contents:
        sb.append(f"[SHOP_CONTENTS] {' | '.join(shop_contents)}")

    # [GE_OFFERS]
    if ge_offers:
        sb.append(f"[GE_OFFERS] {' | '.join(ge_offers)}")

    return "\n".join(sb)


# ─── Helper Functions ─────────────────────────────────────────────────────────

def random_tick():
    return random.randint(100, 50000)

def random_world():
    return random.choice([301, 302, 303, 308, 309, 310, 318, 320, 329, 330, 335, 340, 341, 350, 354, 360, 362, 369, 370, 373, 374, 376, 378, 386, 390, 394])

def random_player():
    return random.choice(PLAYER_NAMES)

def combat_level_for_skills(atk, str_, def_, hp, pray, rng, mag):
    """Calculate OSRS combat level."""
    base = 0.25 * (def_ + hp + (pray // 2))  # Using floor division for prayer
    melee = 0.325 * (atk + str_)
    ranged = 0.325 * (rng * 3 // 2)
    magic = 0.325 * (mag * 3 // 2)
    return int(base + max(melee, ranged, magic))

def nearby_entity(name, x, y, dist, actions=None, level=0, hp_pct=None, qty=0):
    """Create a nearby entity dict."""
    e = {"name": name, "x": x, "y": y, "dist": dist}
    if actions:
        e["actions"] = actions
    if level > 0:
        e["level"] = level
    if hp_pct:
        e["hp_pct"] = hp_pct
    if qty > 1:
        e["qty"] = qty
    return e

def jitter(x, amount=3):
    return x + random.randint(-amount, amount)

def make_example(system_prompt, game_state, assistant_response):
    return {
        "conversations": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": game_state},
            {"role": "assistant", "content": assistant_response},
        ]
    }

def pick_prompt(task):
    """70% full prompt, 30% simple."""
    if random.random() < 0.3:
        return build_simple_system_prompt(task)
    return build_system_prompt(task)

def pick_bank_near(region_id):
    """Find nearest bank to a region."""
    for b in BANK_LOCATIONS:
        if b["region"] == region_id:
            return b
    # Fallback to a random bank
    return random.choice(BANK_LOCATIONS)

def make_skilling_inventory(tool, gathered_item, count, extra=None):
    """Create a typical skilling inventory."""
    inv = [(tool, 1)]
    if count > 0:
        inv.append((gathered_item, count))
    if extra:
        inv.extend(extra)
    return inv


def compress_to_note(pos_x, pos_y, status, inv_used, actions, failed=False):
    """Compress a turn into a session note line matching ConversationManager.java."""
    action_strs = []
    for a in actions:
        name = a.get("action", "?")
        if name == "PATH_TO":
            action_strs.append(f"PATH_TO({a.get('x','?')},{a.get('y','?')})")
        elif name in ("INTERACT_OBJECT", "INTERACT_NPC"):
            action_strs.append(f"{name}({a.get('name','?')})")
        elif name in ("BANK_WITHDRAW", "BANK_DEPOSIT"):
            action_strs.append(f"{name}({a.get('name','?')})")
        elif name == "WAIT_ANIMATION":
            t = a.get("ticks", 20)
            action_strs.append(f"WAIT_ANIMATION({t}t)")
        elif name == "WAIT":
            action_strs.append(f"WAIT({a.get('ticks',5)}t)")
        elif name == "EAT_FOOD":
            action_strs.append(f"EAT_FOOD({a.get('name','?')})")
        elif name == "BANK_DEPOSIT_ALL":
            action_strs.append("BANK_DEPOSIT_ALL")
        elif name == "BANK_CLOSE":
            action_strs.append("BANK_CLOSE")
        elif name in ("CONTINUE_DIALOGUE", "SELECT_DIALOGUE"):
            opt = a.get("option", "")
            action_strs.append(f"{name}({opt})" if opt else name)
        elif name == "PICKUP_ITEM":
            action_strs.append(f"PICKUP_ITEM({a.get('name','?')})")
        elif name == "DROP_ITEM":
            action_strs.append(f"DROP_ITEM({a.get('name','?')})")
        elif name == "USE_ITEM_ON_OBJECT":
            action_strs.append(f"USE_ITEM_ON_OBJECT({a.get('item','?')}/{a.get('object','?')})")
        elif name == "MAKE_ITEM":
            action_strs.append(f"MAKE_ITEM({a.get('name','?')})")
        elif name == "EQUIP_ITEM":
            action_strs.append(f"EQUIP_ITEM({a.get('name','?')})")
        elif name == "CAST_SPELL":
            action_strs.append(f"CAST_SPELL({a.get('name','?')})")
        else:
            action_strs.append(name)
    line = f"- @({pos_x},{pos_y}) {status} inv:{inv_used}/28 -> {','.join(action_strs)}"
    if failed:
        line += " [FAILURES]"
    return line


def best_tool_for_level(tool_type, level):
    """Return the best tool the player can use at given level."""
    tiers = TOOL_TIERS.get(tool_type, [])
    best = tiers[0][0] if tiers else "Bronze pickaxe"
    for name, req in tiers:
        if level >= req:
            best = name
    return best


def worse_tool_for_level(tool_type, level):
    """Return a tool 1-2 tiers below what the player could use."""
    tiers = TOOL_TIERS.get(tool_type, [])
    best_idx = 0
    for i, (name, req) in enumerate(tiers):
        if level >= req:
            best_idx = i
    worse_idx = max(0, best_idx - random.randint(1, 2))
    return tiers[worse_idx][0]


# ═══════════════════════════════════════════════════════════════════════════════
# SCENARIO GENERATORS
# ═══════════════════════════════════════════════════════════════════════════════

def gen_mining_scenarios():
    """Generate mining training examples."""
    examples = []
    for ore_data in MINING_DATA:
        ore_name = ore_data["ore"]
        item_name = ore_data["item"]
        req_level = ore_data["level"]
        for loc in ore_data["locations"]:
            region = loc["region"]
            coords = loc["coords"]
            for _ in range(8):  # 8 variations per ore/location
                level = random.randint(max(req_level, 1), min(req_level + 20, 99))
                skills = make_default_skills()
                skills["Mine"] = level
                xp = {"Mine": random_xp_at_level(level)}
                tick = random_tick()
                world = random_world()
                name = random_player()
                cx, cy = random.choice(coords)
                px, py = jitter(cx), jitter(cy)
                inv_count = random.randint(0, 26)
                tool = "Bronze pickaxe" if level < 6 else ("Iron pickaxe" if level < 21 else ("Steel pickaxe" if level < 31 else "Mithril pickaxe"))
                inv = make_skilling_inventory(tool, item_name, inv_count)
                used = sum(q for _, q in inv)
                cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

                nearby_obj = [
                    nearby_entity(ore_name, cx, cy, random.randint(1, 4), ["Mine"]),
                ]
                if len(coords) > 1:
                    cx2, cy2 = coords[1] if coords[0] == (cx, cy) else coords[0]
                    nearby_obj.append(nearby_entity(ore_name, cx2, cy2, random.randint(2, 6), ["Mine"]))
                nearby_obj.append(nearby_entity("Rocks", jitter(cx, 5), jitter(cy, 5), random.randint(3, 8), ["Mine"]))

                task = f"Mine {item_name.lower()} at {loc['name']}."

                # Scenario 1: Mining (IDLE, start mining)
                if used < 28:
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                        random.randint(2, 15), 100, px, py, 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp,
                        game_messages=["You manage to mine some " + item_name.lower().replace(" ore", "") + "."] if inv_count > 0 else None,
                        action_results=[f"INTERACT_OBJECT({ore_name}/Mine) -> SUCCESS", "WAIT_ANIMATION -> SUCCESS: Animation finished after 4 ticks"] if inv_count > 0 else None,
                        current_goal=f"Mine {item_name.lower()} at {loc['name']}" if inv_count > 0 else None,
                    )
                    reasoning = f"I'm at {loc['name']} with {used}/28 inventory. Mining {ore_name.lower()} — they're {nearby_obj[0]['dist']} tiles away."
                    actions = json.dumps([
                        {"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine", **( {"goal": f"Mine {item_name.lower()}"} if inv_count == 0 else {})},
                        {"action": "WAIT_ANIMATION"}
                    ])
                    examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

                # Scenario 2: Full inventory -> bank or drop
                if random.random() < 0.5:
                    full_inv = [(tool, 1), (item_name, 27)]
                    bank = pick_bank_near(region)
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                        random.randint(15, 30), 100, px, py, 0,
                        "IDLE", skills, full_inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Mine {item_name.lower()}",
                    )
                    if random.random() < 0.6:
                        reasoning = f"Inventory full at 28/28. Time to bank at {bank['name']}."
                        actions = json.dumps([{"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1], "plane": bank["plane"], "goal": f"Bank {item_name.lower()} at {bank['name']}"}])
                    else:
                        reasoning = f"Inventory full. Power-mining so I'll drop the ore."
                        drop_actions = [{"action": "DROP_ITEM", "name": item_name} for _ in range(min(5, 27))]
                        actions = json.dumps(drop_actions)
                    examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_woodcutting_scenarios():
    """Generate woodcutting training examples."""
    examples = []
    for tree_data in WOODCUTTING_DATA:
        tree_name = tree_data["tree"]
        item_name = tree_data["item"]
        req_level = tree_data["level"]
        for loc in tree_data["locations"]:
            region = loc["region"]
            coords = loc["coords"]
            for _ in range(3):
                level = random.randint(max(req_level, 1), min(req_level + 20, 99))
                skills = make_default_skills()
                skills["WC"] = level
                xp = {"WC": random_xp_at_level(level)}
                tick = random_tick()
                world = random_world()
                name = random_player()
                cx, cy = random.choice(coords)
                px, py = jitter(cx), jitter(cy)
                inv_count = random.randint(0, 26)
                tool = "Bronze axe" if level < 6 else ("Iron axe" if level < 21 else ("Steel axe" if level < 31 else "Mithril axe"))
                inv = make_skilling_inventory(tool, item_name, inv_count)
                used = sum(q for _, q in inv)
                cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

                nearby_obj = [nearby_entity(tree_name, cx, cy, random.randint(1, 4), ["Chop down"])]
                if len(coords) > 1:
                    cx2, cy2 = coords[1] if coords[0] == (cx, cy) else coords[0]
                    nearby_obj.append(nearby_entity(tree_name, cx2, cy2, random.randint(2, 6), ["Chop down"]))

                task = f"Chop {tree_name.lower()}s and bank the logs."

                # Chopping
                if used < 28:
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(2, 20), 100, px, py, 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp,
                        current_goal=f"Chop {tree_name.lower()}s" if inv_count > 0 else None,
                        action_results=[f"INTERACT_OBJECT({tree_name}/Chop down) -> SUCCESS", "WAIT_ANIMATION -> SUCCESS: Animation finished after 6 ticks"] if inv_count > 0 else None,
                        game_messages=["You get some " + item_name.lower().rstrip("s") + "."] if inv_count > 0 else None,
                    )
                    reasoning = f"{'Got some logs. ' if inv_count > 0 else ''}I have {used}/28 slots used. Chopping more {tree_name.lower()}s."
                    actions = json.dumps([
                        {"action": "INTERACT_OBJECT", "name": tree_name, "option": "Chop down", **({"goal": f"Chop {tree_name.lower()}s"} if inv_count == 0 else {})},
                        {"action": "WAIT_ANIMATION"}
                    ])
                    examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

                # Full -> bank
                if random.random() < 0.5:
                    full_inv = [(tool, 1), (item_name, 27)]
                    bank = pick_bank_near(region)
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                        random.randint(15, 30), 100, px, py, 0,
                        "IDLE", skills, full_inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Chop {tree_name.lower()}s",
                        game_messages=["You get some " + item_name.lower().rstrip("s") + "."],
                    )
                    reasoning = f"Inventory full with 27 {item_name.lower()}. Banking at {bank['name']}."
                    actions = json.dumps([{"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1], "plane": bank["plane"], "goal": f"Bank {item_name.lower()} at {bank['name']}"}])
                    examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_fishing_scenarios():
    """Generate fishing training examples."""
    examples = []
    for fish_data in FISHING_DATA:
        method = fish_data["method"]
        tool_name = fish_data["tool"]
        fishes = fish_data["fish"]
        req_level = fish_data["level"]
        for loc in fish_data["locations"]:
            region = loc["region"]
            coords = loc["coords"]
            for _ in range(3):
                level = random.randint(max(req_level, 1), min(req_level + 20, 99))
                skills = make_default_skills()
                skills["Fish"] = level
                xp = {"Fish": random_xp_at_level(level)}
                tick = random_tick()
                world = random_world()
                name = random_player()
                cx, cy = random.choice(coords)
                px, py = jitter(cx), jitter(cy)
                fish_name = random.choice(fishes)
                inv_count = random.randint(0, 25)
                extra = [("Feather", random.randint(50, 200))] if method == "Lure" else ([("Fishing bait", random.randint(20, 100))] if method == "Bait" else [])
                inv = [(tool_name, 1)] + ([(fish_name, inv_count)] if inv_count > 0 else []) + extra
                used = sum(q for _, q in inv)
                cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

                nearby_npc = [nearby_entity("Fishing spot", cx, cy, random.randint(1, 3), [method] + (["Bait"] if method == "Net" else []))]
                task = f"Fish {fish_name.lower().replace('raw ', '')} at {loc['name']}."

                if used < 28:
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(1, 10), 100, px, py, 0,
                        "IDLE", skills, inv, [], nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp,
                        current_goal=f"Fish at {loc['name']}" if inv_count > 0 else None,
                        action_results=[f"INTERACT_NPC(Fishing spot/{method}) -> SUCCESS", "WAIT_ANIMATION -> SUCCESS: Animation finished after 5 ticks"] if inv_count > 0 else None,
                        game_messages=[f"You catch some {fish_name.lower().replace('raw ', '')}."] if inv_count > 0 else None,
                    )
                    reasoning = f"{'Caught a fish. ' if inv_count > 0 else ''}{used}/28 inventory. Fishing more."
                    actions = json.dumps([
                        {"action": "INTERACT_NPC", "name": "Fishing spot", "option": method, **({"goal": f"Fish {fish_name.lower().replace('raw ', '')}"} if inv_count == 0 else {})},
                        {"action": "WAIT_ANIMATION"}
                    ])
                    examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_cooking_scenarios():
    """Generate cooking training examples."""
    examples = []
    for cook_data in COOKING_DATA:
        raw = cook_data["food"]
        cooked = cook_data["cooked"]
        req_level = cook_data["level"]
        for _ in range(3):
            level = random.randint(max(req_level, 1), min(req_level + 15, 99))
            skills = make_default_skills()
            skills["Cook"] = level
            xp = {"Cook": random_xp_at_level(level)}
            tick = random_tick()
            world = random_world()
            name = random_player()
            # Cook at Lumbridge range or fire
            use_range = random.random() < 0.6
            if use_range:
                obj_name = "Cooking range"
                obj_option = "Cook"
                px, py = 3211 + random.randint(-2, 2), 3215 + random.randint(-2, 2)
                region = 12850
            else:
                obj_name = "Fire"
                obj_option = "Cook"
                px, py = 3200 + random.randint(-5, 5), 3240 + random.randint(-5, 5)
                region = 12850

            raw_count = random.randint(5, 27)
            inv = [(raw, raw_count)]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_obj = [nearby_entity(obj_name, jitter(px), jitter(py), random.randint(1, 3), [obj_option])]

            task = f"Cook {raw.lower().replace('raw ', '')}."

            # Use item on range/fire
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                random.randint(1, 10), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick,
                xp_dict=xp,
                current_goal=f"Cook {raw.lower().replace('raw ', '')}",
            )
            reasoning = f"I have {raw_count} {raw.lower()} to cook. Using the {obj_name.lower()} nearby."
            actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": raw, "object": obj_name}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Make interface open -> MAKE_ITEM
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                random.randint(1, 10), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick + 3,
                xp_dict=xp, make_interface_open=True,
                current_goal=f"Cook {raw.lower().replace('raw ', '')}",
                action_results=[f"USE_ITEM_ON_OBJECT({raw}/{obj_name}) -> SUCCESS"],
            )
            reasoning2 = f"Cooking interface is open. Selecting {cooked.lower()} to cook."
            actions2 = json.dumps([{"action": "MAKE_ITEM", "name": cooked}, {"action": "WAIT_ANIMATION", "ticks": 30}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

            # Burn failure scenario
            if level < cook_data["burn_stop"] and random.random() < 0.4:
                burned_count = random.randint(1, 5)
                remaining = max(1, raw_count - burned_count - random.randint(3, 10))
                inv3 = [(raw, remaining)] if remaining > 0 else []
                if remaining > 0:
                    state3 = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(1, 10), 100, px, py, 0,
                        "IDLE", skills, inv3, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick + 50,
                        xp_dict=xp,
                        current_goal=f"Cook {raw.lower().replace('raw ', '')}",
                        game_messages=["You accidentally burn the " + raw.lower().replace('raw ', '') + "."],
                    )
                    reasoning3 = f"Burned some food, but I still have {remaining} {raw.lower()} left. Continuing to cook."
                    actions3 = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": raw, "object": obj_name}])
                    examples.append(make_example(pick_prompt(task), state3, f"{reasoning3}\n{actions3}"))

    return examples


def gen_combat_scenarios():
    """Generate combat training examples."""
    examples = []
    for npc_data in COMBAT_NPCS:
        npc_name = npc_data["name"]
        npc_level = npc_data["level"]
        npc_hp = npc_data["hp"]
        for loc in npc_data["locations"]:
            region = loc["region"]
            coords = loc["coords"]
            for _ in range(4):
                atk = random.randint(1, 50)
                str_ = random.randint(1, 50)
                def_ = random.randint(1, 50)
                hp_level = max(10, (atk + str_ + def_) // 3)
                max_hp = hp_level
                current_hp = random.randint(max(1, max_hp // 3), max_hp)
                pray = random.randint(1, 30)
                cb = combat_level_for_skills(atk, str_, def_, hp_level, pray, 1, 1)
                skills = make_default_skills()
                skills.update({"Atk": atk, "Str": str_, "Def": def_})
                xp = {"Atk": random_xp_at_level(atk), "Str": random_xp_at_level(str_), "Def": random_xp_at_level(def_)}

                tick = random_tick()
                world = random_world()
                name = random_player()
                cx, cy = random.choice(coords)
                px, py = jitter(cx), jitter(cy)

                weapon = random.choice(EQUIPMENT_DATA["weapons"][:5])
                equip = [("Weapon", weapon["name"])]
                food_name = random.choice(["Trout", "Salmon", "Lobster", "Shrimps", "Tuna"])
                food_count = random.randint(3, 20)
                inv = [(food_name, food_count)]
                used = food_count
                style = random.choice(["Accurate", "Aggressive", "Controlled"])

                nearby_npc = [
                    nearby_entity(npc_name, cx, cy, random.randint(1, 5), ["Attack"], level=npc_level),
                ]
                if len(coords) > 1:
                    cx2, cy2 = coords[1]
                    nearby_npc.append(nearby_entity(npc_name, cx2, cy2, random.randint(2, 7), ["Attack"], level=npc_level))
                nearby_ground = []

                task = f"Train combat on {npc_name.lower()}s."

                # Scenario: IDLE, attack next NPC
                state = serialize_game_state(
                    name, cb, current_hp, max_hp, pray, pray, random.randint(50, 100), True,
                    random.randint(5, 15), 100, px, py, 0,
                    "IDLE", skills, inv, equip, nearby_npc, [], [], [],
                    region, world, "Inventory", style, tick,
                    xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                )
                reasoning = f"I'm idle. Attacking the nearest {npc_name.lower()}."
                actions = json.dumps([
                    {"action": "INTERACT_NPC", "name": npc_name, "option": "Attack"},
                    {"action": "WAIT_ANIMATION", "ticks": 20}
                ])
                examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

                # Scenario: IN_COMBAT, wait
                state2 = serialize_game_state(
                    name, cb, current_hp, max_hp, pray, pray, random.randint(50, 100), True,
                    random.randint(5, 15), 100, px, py, 0,
                    "IN_COMBAT", skills, inv, equip, nearby_npc, [], [], [],
                    region, world, "Inventory", style, tick + 5,
                    xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                    interacting_with=npc_name,
                    under_attack=[f"{npc_name}(lvl:{npc_level})"],
                )
                if current_hp < max_hp // 2 and food_count > 0:
                    reasoning2 = f"In combat and HP is low ({current_hp}/{max_hp}). Eating {food_name.lower()} first."
                    actions2 = json.dumps([{"action": "EAT_FOOD", "name": food_name}, {"action": "WAIT_ANIMATION", "ticks": 20}])
                else:
                    reasoning2 = f"Fighting {npc_name.lower()}. HP is {current_hp}/{max_hp}, fine. Waiting for the kill."
                    actions2 = json.dumps([{"action": "WAIT_ANIMATION", "ticks": 20}])
                examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

                # Scenario: Loot drops
                if random.random() < 0.5:
                    nearby_ground = [
                        nearby_entity("Bones", cx, cy, 0, ["Take"]),
                    ]
                    if npc_name == "Cow":
                        nearby_ground.append(nearby_entity("Cowhide", cx, cy, 0, ["Take"]))
                    state3 = serialize_game_state(
                        name, cb, current_hp, max_hp, pray, pray, random.randint(50, 100), True,
                        random.randint(5, 15), 100, cx, cy, 0,
                        "IDLE", skills, inv, equip, nearby_npc, [],
                        nearby_ground, [],
                        region, world, "Inventory", style, tick + 20,
                        xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                        game_messages=[f"You defeated the {npc_name.lower()}."] if random.random() < 0.3 else None,
                    )
                    reasoning3 = f"Kill complete. Picking up bones" + (" and cowhide" if npc_name == "Cow" else "") + ", then attacking the next one."
                    loot_actions = [{"action": "PICKUP_ITEM", "name": "Bones"}]
                    if npc_name == "Cow" and used < 27:
                        loot_actions.append({"action": "PICKUP_ITEM", "name": "Cowhide"})
                    loot_actions.append({"action": "INTERACT_NPC", "name": npc_name, "option": "Attack"})
                    actions3 = json.dumps(loot_actions[:5])
                    examples.append(make_example(pick_prompt(task), state3, f"{reasoning3}\n{actions3}"))

    return examples


def gen_smithing_scenarios():
    """Generate smelting and smithing training examples."""
    examples = []
    # Smelting at furnace
    for smelt in SMELTING_DATA:
        bar = smelt["bar"]
        ingredients = smelt["ingredients"]
        req_level = smelt["level"]
        for _ in range(3):
            level = random.randint(max(req_level, 1), min(req_level + 15, 99))
            skills = make_default_skills()
            skills["Smith"] = level
            xp = {"Smith": random_xp_at_level(level)}
            tick = random_tick()
            world = random_world()
            name = random_player()
            # Al Kharid furnace
            px, py = 3275 + random.randint(-2, 2), 3186 + random.randint(-2, 2)
            region = 13104
            ore_count = random.randint(5, 14)
            inv = [(ing, ore_count) for ing in ingredients]
            if smelt.get("coal"):
                inv = [(ingredients[0], ore_count), ("Coal", ore_count * (smelt["coal"] - (1 if len(ingredients) > 1 else 0)))]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_obj = [nearby_entity("Furnace", 3275, 3185, random.randint(1, 3), ["Smelt"])]

            task = f"Smelt {bar.lower()}s at Al Kharid furnace."

            # Use ore on furnace
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                random.randint(10, 25), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Smelt {bar.lower()}s",
            )
            reasoning = f"At the furnace with {ore_count} ore. Using {ingredients[0].lower()} on the furnace."
            actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": ingredients[0], "object": "Furnace"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Make interface -> select bar
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                random.randint(10, 25), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick + 3,
                xp_dict=xp, make_interface_open=True,
                current_goal=f"Smelt {bar.lower()}s",
                action_results=[f"USE_ITEM_ON_OBJECT({ingredients[0]}/Furnace) -> SUCCESS"],
            )
            reasoning2 = f"Smelting interface open. Selecting {bar.lower()}."
            actions2 = json.dumps([{"action": "MAKE_ITEM", "name": bar}, {"action": "WAIT_ANIMATION", "ticks": 30}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

    # Smithing at anvil
    for smith_item in SMITHING_ITEMS[:6]:
        item = smith_item["item"]
        bar = smith_item["bar"]
        bars_needed = smith_item["bars"]
        req_level = smith_item["level"]
        level = random.randint(max(req_level, 1), min(req_level + 10, 99))
        skills = make_default_skills()
        skills["Smith"] = level
        xp = {"Smith": random_xp_at_level(level)}
        tick = random_tick()
        name = random_player()
        px, py = 3188 + random.randint(-2, 2), 3426 + random.randint(-2, 2)
        region = 12342  # Varrock West
        bar_count = random.randint(bars_needed, 27)
        inv = [("Hammer", 1), (bar, bar_count)]
        nearby_obj = [nearby_entity("Anvil", 3188, 3426, random.randint(1, 3), ["Smith"])]
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

        task = f"Smith {item.lower()}s at Varrock."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
            random.randint(5, 15), 100, px, py, 0,
            "IDLE", skills, inv, [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal=f"Smith {item.lower()}s",
        )
        reasoning = f"I have {bar_count} {bar.lower()}s and a hammer. Using bar on anvil."
        actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": bar, "object": "Anvil"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_firemaking_scenarios():
    """Generate firemaking training examples."""
    examples = []
    for fm_data in FIREMAKING_DATA:
        log = fm_data["log"]
        req_level = fm_data["level"]
        for _ in range(3):
            level = random.randint(max(req_level, 1), min(req_level + 15, 99))
            skills = make_default_skills()
            skills["FM"] = level
            xp = {"FM": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = 3200 + random.randint(-10, 10), 3240 + random.randint(-5, 5)
            region = 12850
            log_count = random.randint(5, 27)
            inv = [("Tinderbox", 1), (log, log_count)]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

            task = f"Train firemaking with {log.lower()}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                random.randint(1, 5), 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Train firemaking",
            )
            reasoning = f"Lighting {log.lower()} with tinderbox. {log_count} logs remaining."
            actions = json.dumps([{"action": "USE_ITEM_ON_ITEM", "item1": "Tinderbox", "item2": log}, {"action": "WAIT_ANIMATION"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_fletching_scenarios():
    """Generate fletching training examples."""
    examples = []
    for fl_data in FLETCHING_DATA:
        item = fl_data["item"]
        material = fl_data["material"]
        tool = fl_data["tool"]
        req_level = fl_data["level"]
        for _ in range(2):
            level = random.randint(max(req_level, 1), min(req_level + 15, 99))
            skills = make_default_skills()
            skills["Fletch"] = level
            xp = {"Fletch": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = 3185, 3436  # Varrock West bank area
            region = 12342
            mat_count = random.randint(5, 27)
            inv = [(tool, 1), (material, mat_count)]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

            task = f"Fletch {item.lower()}s."
            # Use knife on logs
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True,
                random.randint(1, 5), 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Fletch {item.lower()}s",
            )
            reasoning = f"Using {tool.lower()} on {material.lower()} to fletch."
            actions = json.dumps([{"action": "USE_ITEM_ON_ITEM", "item1": tool, "item2": material}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Make interface -> select
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True,
                random.randint(1, 5), 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick + 3,
                xp_dict=xp, make_interface_open=True,
                current_goal=f"Fletch {item.lower()}s",
                action_results=[f"USE_ITEM_ON_ITEM({tool}/{material}) -> SUCCESS"],
            )
            reasoning2 = f"Fletching interface open. Selecting {item.lower()}."
            actions2 = json.dumps([{"action": "MAKE_ITEM", "name": item}, {"action": "WAIT_ANIMATION", "ticks": 30}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

    return examples


def gen_herblore_scenarios():
    """Generate herblore training examples."""
    examples = []
    for herb_data in HERBLORE_DATA:
        potion = herb_data["potion"]
        herb = herb_data["herb"]
        secondary = herb_data["secondary"]
        req_level = herb_data["level"]
        for _ in range(2):
            level = random.randint(max(req_level, 1), min(req_level + 15, 99))
            skills = make_default_skills()
            skills["Herb"] = level
            xp = {"Herb": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = 3185, 3436
            region = 12342
            count = random.randint(5, 14)
            inv = [(herb, count), (secondary, count)]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

            task = f"Make {potion.lower()}s."

            # Cleaning herbs
            state_clean = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True,
                random.randint(1, 5), 100, px, py, 0,
                "IDLE", skills, [("Grimy " + herb.lower(), count)], [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Clean herbs for {potion.lower()}",
            )
            reasoning_c = f"Cleaning grimy {herb.lower()} before making potions."
            actions_c = json.dumps([{"action": "USE_ITEM", "name": "Grimy " + herb.lower()}])
            examples.append(make_example(pick_prompt(task), state_clean, f"{reasoning_c}\n{actions_c}"))

            # Making potions (use herb on secondary)
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True,
                random.randint(1, 5), 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Make {potion.lower()}s",
            )
            reasoning = f"Combining {herb.lower()} with {secondary.lower()} to make {potion.lower()}."
            actions = json.dumps([{"action": "USE_ITEM_ON_ITEM", "item1": herb, "item2": secondary}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_banking_scenarios():
    """Generate banking sequence examples."""
    examples = []
    for bank in BANK_LOCATIONS:
        for _ in range(5):
            tick = random_tick()
            name = random_player()
            world = random_world()
            bx, by = bank["coords"]
            plane = bank["plane"]
            region = bank["region"]
            px, py = jitter(bx, 2), jitter(by, 2)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            skills = make_default_skills()

            # Random items to bank
            items_pool = ["Copper ore", "Tin ore", "Iron ore", "Oak logs", "Willow logs", "Raw shrimps", "Lobster", "Coal", "Bronze bar"]
            banked_item = random.choice(items_pool)
            tool = random.choice(["Bronze pickaxe", "Bronze axe", "Small fishing net", "Hammer"])
            inv = [(tool, 1), (banked_item, 27)]

            bank_items = [f"{banked_item}(x{random.randint(50, 500)})"] + [f"{random.choice(items_pool)}(x{random.randint(10, 200)})" for _ in range(random.randint(3, 10))]

            nearby_obj = [nearby_entity("Bank booth", bx, by, random.randint(1, 3), ["Bank", "Collect"])]
            task = f"Bank items at {bank['name']}."

            # Opening bank
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                random.randint(10, 30), 100, px, py, plane,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick,
                current_goal=f"Bank {banked_item.lower()} at {bank['name']}",
            )
            reasoning = f"At {bank['name']} bank with full inventory. Opening bank to deposit."
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_DEPOSIT_ALL"},
                {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1},
                {"action": "BANK_CLOSE"}
            ])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Bank already open
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                random.randint(10, 30), 100, px, py, plane,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, world, "Inventory", "Accurate", tick + 5,
                bank_open=True, bank_contents=bank_items, bank_unique=len(bank_items),
                current_goal=f"Bank {banked_item.lower()}",
                action_results=["INTERACT_OBJECT(Bank booth/Bank) -> SUCCESS"],
            )
            reasoning2 = f"Bank is open. Depositing all and withdrawing {tool.lower()}."
            actions2 = json.dumps([
                {"action": "BANK_DEPOSIT_ALL"},
                {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1},
                {"action": "BANK_CLOSE"}
            ])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

            # Individual deposit
            if random.random() < 0.3:
                state3 = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                    random.randint(10, 30), 100, px, py, plane,
                    "IDLE", skills, inv, [], [], nearby_obj, [], [],
                    region, world, "Inventory", "Accurate", tick + 5,
                    bank_open=True, bank_contents=bank_items, bank_unique=len(bank_items),
                    current_goal=f"Bank {banked_item.lower()}",
                )
                reasoning3 = f"Bank open. Depositing {banked_item.lower()} but keeping my {tool.lower()}."
                actions3 = json.dumps([{"action": "BANK_DEPOSIT", "name": banked_item, "quantity": -1}, {"action": "BANK_CLOSE"}])
                examples.append(make_example(pick_prompt(task), state3, f"{reasoning3}\n{actions3}"))

    return examples


def gen_navigation_scenarios():
    """Generate PATH_TO walking and continuation examples."""
    examples = []
    destinations = [
        ("Lumbridge bank", 3208, 3220, 2, 12850),
        ("Varrock West bank", 3185, 3436, 0, 12342),
        ("Al Kharid furnace", 3275, 3186, 0, 13104),
        ("Lumbridge Swamp mine", 3230, 3148, 0, 12594),
        ("Draynor willows", 3090, 3232, 0, 12593),
        ("Barbarian fishing", 3110, 3434, 0, 11062),
        ("Edgeville bank", 3094, 3491, 0, 12442),
        ("Grand Exchange", 3165, 3487, 0, 12598),
        ("Varrock East bank", 3253, 3420, 0, 12854),
        ("Falador East bank", 3013, 3355, 0, 11827),
    ]
    for dest_name, dx, dy, dp, dest_region in destinations:
        for _ in range(6):
            tick = random_tick()
            name = random_player()
            world = random_world()
            # Start from random location 20-80 tiles away
            offset = random.randint(20, 80)
            angle = random.random() * 6.28
            sx = dx + int(offset * 0.7 * (1 if random.random() > 0.5 else -1))
            sy = dy + int(offset * 0.7 * (1 if random.random() > 0.5 else -1))
            sx = max(2400, min(4000, sx))
            sy = max(2400, min(10000, sy))
            # Find start region
            start_region = random.choice(list(REGION_NAMES.keys()))
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            skills = make_default_skills()
            inv = [("Bronze pickaxe", 1)]
            task = f"Walk to {dest_name}."

            # Initial PATH_TO
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                random.randint(2, 10), 100, sx, sy, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                start_region, world, "Inventory", "Accurate", tick,
            )
            reasoning = f"I need to get to {dest_name}. Using PATH_TO."
            actions = json.dumps([{"action": "PATH_TO", "x": dx, "y": dy, "plane": dp, "goal": f"Walk to {dest_name}"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Continuation (tiles remaining)
            tiles_walked = random.randint(8, 14)
            tiles_remaining = max(5, offset - tiles_walked)
            mid_x = sx + (dx - sx) * tiles_walked // max(offset, 1)
            mid_y = sy + (dy - sy) * tiles_walked // max(offset, 1)
            mid_region = random.choice(list(REGION_NAMES.keys()))
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(40, 95), True,
                random.randint(2, 10), 100, mid_x, mid_y, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                mid_region, world, "Inventory", "Accurate", tick + 20,
                current_goal=f"Walk to {dest_name}",
                action_results=[f"PATH_TO({dx},{dy},{dp}) -> SUCCESS: Walked {tiles_walked} tiles. {tiles_remaining} tiles remaining to destination."],
            )
            reasoning2 = f"Still {tiles_remaining} tiles from {dest_name}. Re-issuing PATH_TO to continue."
            actions2 = json.dumps([{"action": "PATH_TO", "x": dx, "y": dy, "plane": dp}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

            # Arrival
            if random.random() < 0.5:
                state3 = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(30, 90), True,
                    random.randint(2, 10), 100, dx + random.randint(-1, 1), dy + random.randint(-1, 1), dp,
                    "IDLE", skills, inv, [], [], [], [], [],
                    dest_region, world, "Inventory", "Accurate", tick + 60,
                    current_goal=f"Walk to {dest_name}",
                    action_results=[f"PATH_TO({dx},{dy},{dp}) -> SUCCESS: Arrived at destination."],
                )
                reasoning3 = f"Arrived at {dest_name}. Now I can proceed with my task."
                # What to do on arrival depends on destination
                if "bank" in dest_name.lower():
                    actions3 = json.dumps([{"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank", "goal": f"Bank items at {dest_name}"}])
                elif "mine" in dest_name.lower():
                    actions3 = json.dumps([{"action": "INTERACT_OBJECT", "name": "Copper rocks", "option": "Mine", "goal": "Mine copper ore"}, {"action": "WAIT_ANIMATION"}])
                else:
                    actions3 = json.dumps([{"action": "WAIT", "ticks": 2}])
                examples.append(make_example(pick_prompt(task), state3, f"{reasoning3}\n{actions3}"))

    # MINIMAP_WALK examples
    for _ in range(20):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        tx, ty = px + random.randint(-12, 12), py + random.randint(-12, 12)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
            random.randint(0, 5), 100, px, py, 0,
            "IDLE", skills, [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Short walk to ({tx},{ty}) using minimap."
        actions = json.dumps([{"action": "MINIMAP_WALK", "x": tx, "y": ty}])
        examples.append(make_example(pick_prompt("Walk around."), state, f"{reasoning}\n{actions}"))

    return examples


def gen_dialogue_scenarios():
    """Generate dialogue interaction examples."""
    examples = []
    npcs_with_dialogue = [
        ("Gielinor Guide", "Tutorial Island", 12336, 3093, 3108),
        ("Survival Expert", "Tutorial Island", 12336, 3104, 3095),
        ("Hans", "Lumbridge", 12850, 3222, 3218),
        ("Duke Horacio", "Lumbridge", 12850, 3210, 3220),
        ("Shop keeper", "Lumbridge", 12850, 3211, 3247),
        ("Banker", "Varrock West", 12342, 3185, 3437),
    ]
    dialogue_types = [
        ("npc_continue", "Hello there, adventurer! Welcome to my shop.", "CONTINUE_DIALOGUE"),
        ("player_continue", "Thank you for your help!", "CONTINUE_DIALOGUE"),
        ("options", None, "SELECT_DIALOGUE"),
    ]
    option_sets = [
        ["1: Yes, please.", "2: No thanks."],
        ["1: I'd like to buy something.", "2: I'd like to sell something.", "3: Goodbye."],
        ["1: Tell me about this place.", "2: I'm just passing through."],
        ["1: I am an experienced player.", "2: I am brand new to this game."],
    ]
    for npc_name, loc_name, region, nx, ny in npcs_with_dialogue:
        for _ in range(5):
            tick = random_tick()
            name = random_player()
            world = random_world()
            px, py = jitter(nx), jitter(ny)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            skills = make_default_skills()
            nearby_npc = [nearby_entity(npc_name, nx, ny, random.randint(1, 3), ["Talk-to"])]
            task = f"Talk to {npc_name}."

            dtype, dtext, action_type = random.choice(dialogue_types)

            if dtype == "options":
                opts = random.choice(option_sets)
                choice = random.choice(["1", "2"])
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                    "IDLE", skills, [], [], nearby_npc, [], [], [],
                    region, world, "Inventory", "Accurate", tick,
                    dialogue="options", dialogue_options=opts,
                    current_goal=f"Talk to {npc_name}",
                    action_results=[f"INTERACT_NPC({npc_name}/Talk-to) -> SUCCESS"],
                )
                chosen_text = opts[int(choice) - 1].split(": ", 1)[1]
                reasoning = f"Dialogue options showing. Selecting option {choice}: {chosen_text}."
                actions = json.dumps([{"action": "SELECT_DIALOGUE", "option": choice}])
            else:
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                    "IDLE", skills, [], [], nearby_npc, [], [], [],
                    region, world, "Inventory", "Accurate", tick,
                    dialogue=dtype, dialogue_speaker=npc_name,
                    dialogue_text=dtext or "Let me tell you something important.",
                    current_goal=f"Talk to {npc_name}",
                )
                reasoning = f"{npc_name} is speaking. Continuing the dialogue."
                actions = json.dumps([{"action": "CONTINUE_DIALOGUE"}])

            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_magic_scenarios():
    """Generate magic spell casting examples."""
    examples = []
    # Combat spells
    for spell in SPELLS["combat"]:
        for _ in range(3):
            level = random.randint(spell["level"], min(spell["level"] + 20, 99))
            skills = make_default_skills()
            skills["Mag"] = level
            xp = {"Mag": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            npc = random.choice(COMBAT_NPCS[:4])
            loc = random.choice(npc["locations"])
            cx, cy = random.choice(loc["coords"])
            px, py = jitter(cx), jitter(cy)
            region = loc["region"]
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, level)
            inv = [(rune, random.randint(100, 500)) for rune in spell["runes"]]
            food = random.choice(["Trout", "Lobster"])
            inv.append((food, random.randint(5, 15)))
            nearby_npc = [nearby_entity(npc["name"], cx, cy, random.randint(1, 5), ["Attack"], level=npc["level"])]

            task = f"Train magic with {spell['name']} on {npc['name'].lower()}s."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                random.randint(1, 10), 100, px, py, 0,
                "IDLE", skills, inv, [], nearby_npc, [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Train magic on {npc['name'].lower()}s",
            )
            reasoning = f"Casting {spell['name']} on {npc['name'].lower()}."
            actions = json.dumps([
                {"action": "CAST_SPELL", "name": spell["name"], "npc": npc["name"]},
                {"action": "WAIT_ANIMATION", "ticks": 15}
            ])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Teleport spells
    for spell in SPELLS["teleports"]:
        for _ in range(3):
            level = random.randint(spell["level"], min(spell["level"] + 20, 99))
            skills = make_default_skills()
            skills["Mag"] = level
            tick = random_tick()
            name = random_player()
            px, py = random.randint(3000, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, level)
            inv = [(rune, random.randint(10, 50)) for rune in spell["runes"]]

            task = f"Teleport to {spell['name'].replace(' Teleport', '')}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                0, 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
            )
            reasoning = f"Casting {spell['name']} to teleport."
            actions = json.dumps([{"action": "CAST_SPELL", "name": spell["name"], "goal": f"Teleport to {spell['name'].replace(' Teleport', '')}"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # High Alchemy
    alch_items = ["Gold bracelet", "Gold necklace", "Maple longbow", "Rune dagger", "Steel platebody"]
    for _ in range(15):
        level = random.randint(55, 80)
        skills = make_default_skills()
        skills["Mag"] = level
        xp = {"Mag": random_xp_at_level(level)}
        tick = random_tick()
        name = random_player()
        item = random.choice(alch_items)
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, level)
        inv = [("Nature rune", random.randint(50, 500)), ("Fire rune", random.randint(200, 2000)), (item, random.randint(5, 27))]

        task = f"High alch {item.lower()}s."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, random.randint(0, 5), 100, px, py, 0,
            "IDLE", skills, inv, [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal=f"High alch {item.lower()}s",
        )
        reasoning = f"Casting High Level Alchemy on {item.lower()}."
        actions = json.dumps([{"action": "CAST_SPELL", "name": "High Level Alchemy", "item": item}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_prayer_scenarios():
    """Generate prayer training examples."""
    examples = []
    bones = [("Bones", 4.5), ("Big bones", 15), ("Dragon bones", 72)]
    for bone_name, bone_xp in bones:
        for _ in range(5):
            level = random.randint(1, 45)
            skills = make_default_skills()
            skills["Pray"] = level if level > 1 else 1
            xp = {"Pray": random_xp_at_level(level)} if level > 1 else {}
            xp["_Pray_level"] = level
            tick = random_tick()
            name = random_player()
            bone_count = random.randint(5, 27)
            inv = [(bone_name, bone_count)]
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            cb = combat_level_for_skills(1, 1, 1, 10, level, 1, 1)

            task = f"Train prayer by burying {bone_name.lower()}."
            state = serialize_game_state(
                name, cb, 10, 10, level, level, 100, True,
                random.randint(0, 5), 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Bury {bone_name.lower()} for prayer XP",
            )
            reasoning = f"Burying {bone_name.lower()} for prayer XP. {bone_count} remaining."
            actions = json.dumps([{"action": "USE_ITEM", "name": bone_name}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Toggle prayer
    for _ in range(10):
        prayer_name = random.choice(PRAYERS[:15])
        level = random.randint(10, 50)
        skills = make_default_skills()
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(10, 10, 10, 15, level, 1, 1)

        task = "Train combat with prayer."
        state = serialize_game_state(
            name, cb, 12, 15, level, level, 80, True,
            random.randint(5, 15), 100, px, py, 0,
            "IN_COMBAT", skills, [("Lobster", 10)],
            [("Weapon", "Iron scimitar")], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            current_goal="Train combat",
            interacting_with="Guard",
            under_attack=["Guard(lvl:21)"],
        )
        reasoning = f"In combat. Activating {prayer_name} for the fight."
        actions = json.dumps([{"action": "TOGGLE_PRAYER", "name": prayer_name}, {"action": "WAIT_ANIMATION", "ticks": 20}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_shopping_scenarios():
    """Generate shop buy/sell examples."""
    examples = []
    for shop in SHOP_NPCS:
        for _ in range(3):
            tick = random_tick()
            name = random_player()
            world = random_world()
            region = shop["region"]
            px, py = random.randint(3190, 3220), random.randint(3210, 3260)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            skills = make_default_skills()
            nearby_npc = [nearby_entity(shop["name"], jitter(px), jitter(py), random.randint(1, 4), ["Talk-to", "Trade"])]
            task = f"Buy items from {shop['name']}."

            # Talk to shopkeeper to open
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, [], [], nearby_npc, [], [], [],
                region, world, "Inventory", "Accurate", tick,
            )
            reasoning = f"Opening {shop['name']}'s shop to buy items."
            actions = json.dumps([{"action": "INTERACT_NPC", "name": shop["name"], "option": "Trade", "goal": f"Buy from {shop['name']}"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Shop open -> buy
            buy_item = random.choice(shop["items"])
            buy_qty = random.choice([1, 5, 10])
            shop_items = [f"{it}(x{random.randint(1, 50)})" for it in shop["items"]]
            state2 = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, [], [], nearby_npc, [], [], [],
                region, world, "Inventory", "Accurate", tick + 3,
                shop_open=True, shop_contents=shop_items,
                current_goal=f"Buy from {shop['name']}",
                action_results=[f"INTERACT_NPC({shop['name']}/Trade) -> SUCCESS"],
            )
            reasoning2 = f"Shop is open. Buying {buy_qty} {buy_item.lower()}."
            actions2 = json.dumps([{"action": "SHOP_BUY", "name": buy_item, "quantity": buy_qty}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

    # Sell examples
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        region = 12850
        px, py = 3211, 3247
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        sell_item = random.choice(["Copper ore", "Tin ore", "Oak logs", "Raw shrimps"])
        sell_qty = random.choice([1, 5, 10, 50])
        inv = [(sell_item, random.randint(10, 27))]
        nearby_npc = [nearby_entity("Shop keeper", 3211, 3247, 2, ["Talk-to", "Trade"])]
        shop_items = [f"Pot(x5)", f"Jug(x3)", f"Bucket(x10)"]

        task = f"Sell {sell_item.lower()} at shop."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, inv, [], nearby_npc, [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            shop_open=True, shop_contents=shop_items,
            current_goal=f"Sell {sell_item.lower()}",
        )
        reasoning = f"Shop open. Selling {sell_qty} {sell_item.lower()}."
        actions = json.dumps([{"action": "SHOP_SELL", "name": sell_item, "quantity": sell_qty}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_ge_scenarios():
    """Generate Grand Exchange examples."""
    examples = []
    ge_items = ["Iron ore", "Coal", "Lobster", "Nature rune", "Yew logs", "Steel bar", "Gold ore"]
    for _ in range(20):
        tick = random_tick()
        name = random_player()
        px, py = 3165 + random.randint(-3, 3), 3487 + random.randint(-3, 3)
        region = 12598
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        item = random.choice(ge_items)
        qty = random.choice([100, 500, 1000, 5000])
        price = random.choice([0, random.randint(50, 5000)])

        # Buy
        task = f"Buy {item.lower()} on GE."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, [("Coins", random.randint(10000, 500000))], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            ge_open=True, current_goal=f"Buy {item.lower()} on GE",
        )
        reasoning = f"GE open. Placing buy offer for {qty} {item.lower()}" + (f" at {price}gp each." if price > 0 else " at market price.")
        actions = json.dumps([{"action": "GE_BUY", "name": item, "quantity": qty, "x": price}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Sell
    for _ in range(15):
        tick = random_tick()
        name = random_player()
        px, py = 3165, 3487
        region = 12598
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        item = random.choice(ge_items)
        qty = random.randint(50, 1000)

        task = f"Sell {item.lower()} on GE."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, [(item, qty)], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            ge_open=True, current_goal=f"Sell {item.lower()} on GE",
        )
        reasoning = f"GE open. Selling {qty} {item.lower()}."
        actions = json.dumps([{"action": "GE_SELL", "name": item, "quantity": qty}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_tutorial_island_scenarios():
    """Generate Tutorial Island progression examples."""
    examples = []
    steps = [
        (1, "Gielinor Guide", 3093, 3108, "npc", "Getting started Talk to the Gielinor Guide to begin.", None),
        (2, None, 3097, 3107, "object", "Open the settings panel to continue.", "Settings"),
        (10, "Survival Expert", 3104, 3095, "npc", "Talk to the Survival Expert to learn about survival skills.", None),
        (20, None, 3100, 3092, "object", "Try fishing from the pond. Click on the fishing spot.", "Fishing spot"),
        (30, None, 3102, 3095, "object", "Now try cooking the shrimp on the fire.", "Fire"),
        (40, "Master Chef", 3076, 3085, "npc", "Talk to the Master Chef to continue.", None),
        (60, "Quest Guide", 3086, 3126, "npc", "Talk to the Quest Guide.", None),
        (70, "Mining Instructor", 3081, 3115, "npc", "Talk to the Mining Instructor.", None),
        (80, None, 3077, 3113, "object", "Mine some tin ore from the rocks.", "Tin rocks"),
        (90, None, 3079, 3113, "object", "Mine some copper ore.", "Copper rocks"),
        (100, None, 3079, 3110, "object", "Smelt the ore in the furnace.", "Furnace"),
        (120, "Combat Instructor", 3104, 3095, "npc", "Talk to the Combat Instructor.", None),
    ]
    for step_num, target_name, tx, ty, target_type, instruction_text, obj_name in steps:
        for _ in range(3):
            tick = random_tick()
            name = "NewPlayer"
            px, py = jitter(tx), jitter(ty)
            region = 12336
            cb = 3
            skills = make_default_skills()
            nearby_npc = []
            nearby_obj = []
            inv = []
            hint = None

            if target_type == "npc" and target_name:
                nearby_npc = [nearby_entity(target_name, tx, ty, random.randint(1, 3), ["Talk-to"])]
                hint = {"type": "npc", "target": target_name, "x": tx, "y": ty}
            elif target_type == "object" and obj_name:
                action = "Mine" if "rocks" in (obj_name or "").lower() else ("Smelt" if obj_name == "Furnace" else ("Cook" if obj_name == "Fire" else "Net"))
                nearby_obj = [nearby_entity(obj_name, tx, ty, random.randint(1, 3), [action])]
                hint = {"type": "object", "target": obj_name, "x": tx, "y": ty}

            if step_num >= 20:
                inv = [("Small fishing net", 1)]
            if step_num >= 80:
                inv.append(("Bronze pickaxe", 1))

            task = "Complete Tutorial Island."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, inv, [], nearby_npc, nearby_obj, [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                hint_arrow=hint, instruction=instruction_text,
                tutorial_progress=step_num,
                current_goal="Complete Tutorial Island" if step_num > 1 else None,
            )

            if target_type == "npc" and target_name:
                reasoning = f"Tutorial step {step_num}. Hint arrow points to {target_name}. Talking to them."
                actions = json.dumps([{"action": "INTERACT_NPC", "name": target_name, "option": "Talk-to", **({"goal": "Complete Tutorial Island"} if step_num == 1 else {})}])
            elif obj_name:
                action = "Mine" if "rocks" in obj_name.lower() else ("Smelt" if obj_name == "Furnace" else ("Cook" if obj_name == "Fire" else "Net"))
                reasoning = f"Tutorial step {step_num}. Instruction says to interact with {obj_name}."
                if obj_name in ("Tin rocks", "Copper rocks"):
                    actions = json.dumps([{"action": "INTERACT_OBJECT", "name": obj_name, "option": action}, {"action": "WAIT_ANIMATION"}])
                elif obj_name == "Fire":
                    actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": "Raw shrimps", "object": "Fire"}])
                elif obj_name == "Furnace":
                    actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": "Copper ore", "object": "Furnace"}])
                elif obj_name == "Fishing spot":
                    actions = json.dumps([{"action": "INTERACT_NPC", "name": "Fishing spot", "option": "Net"}, {"action": "WAIT_ANIMATION"}])
                else:
                    actions = json.dumps([{"action": "INTERACT_OBJECT", "name": obj_name, "option": action}])
            else:
                reasoning = f"Tutorial step {step_num}."
                actions = json.dumps([{"action": "WAIT", "ticks": 2}])

            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_edge_case_scenarios():
    """Generate edge cases: full inv, low HP, stuck, failures, attacked while skilling."""
    examples = []

    # Full inventory while skilling - must handle it
    for _ in range(30):
        skill_type = random.choice(["mining", "woodcutting", "fishing"])
        tick = random_tick()
        name = random_player()
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()

        if skill_type == "mining":
            ore = random.choice(MINING_DATA[:3])
            loc = random.choice(ore["locations"])
            cx, cy = random.choice(loc["coords"])
            tool = "Bronze pickaxe"
            item = ore["item"]
            skills["Mine"] = random.randint(ore["level"], ore["level"] + 15)
            nearby_obj = [nearby_entity(ore["ore"], cx, cy, 2, ["Mine"])]
        elif skill_type == "woodcutting":
            tree = random.choice(WOODCUTTING_DATA[:3])
            loc = random.choice(tree["locations"])
            cx, cy = random.choice(loc["coords"])
            tool = "Bronze axe"
            item = tree["item"]
            skills["WC"] = random.randint(tree["level"], tree["level"] + 15)
            nearby_obj = [nearby_entity(tree["tree"], cx, cy, 2, ["Chop down"])]
        else:
            fish = random.choice(FISHING_DATA[:2])
            loc = random.choice(fish["locations"])
            cx, cy = random.choice(loc["coords"])
            tool = fish["tool"]
            item = fish["fish"][0]
            skills["Fish"] = random.randint(fish["level"], fish["level"] + 15)
            nearby_obj = []

        region = loc["region"]
        inv = [(tool, 1), (item, 27)]
        bank = pick_bank_near(region)

        task = f"Gather {item.lower()}."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
            random.randint(15, 30), 100, jitter(cx), jitter(cy), 0,
            "IDLE", skills, inv, [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            current_goal=f"Gather {item.lower()}",
            game_messages=[f"You manage to mine some {item.lower().replace(' ore', '')}." if skill_type == "mining" else f"You get some {item.lower()}."],
        )
        if random.random() < 0.6:
            reasoning = f"Inventory full at 28/28. Need to bank at {bank['name']}."
            actions = json.dumps([{"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1], "plane": bank["plane"], "goal": f"Bank {item.lower()}"}])
        else:
            reasoning = f"Inventory full. Power-gathering so I'll drop everything."
            actions = json.dumps([{"action": "DROP_ITEM", "name": item} for _ in range(5)])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Low HP in combat - eat food
    for _ in range(30):
        npc = random.choice(COMBAT_NPCS[:5])
        loc = random.choice(npc["locations"])
        cx, cy = random.choice(loc["coords"])
        atk = random.randint(5, 40)
        str_ = random.randint(5, 40)
        def_ = random.randint(5, 40)
        max_hp = max(10, (atk + str_ + def_) // 3)
        current_hp = random.randint(1, max(1, max_hp // 3))
        cb = combat_level_for_skills(atk, str_, def_, max_hp, 1, 1, 1)
        skills = make_default_skills()
        skills.update({"Atk": atk, "Str": str_, "Def": def_})
        food = random.choice(["Trout", "Salmon", "Lobster", "Swordfish"])
        food_count = random.randint(1, 15)
        inv = [(food, food_count)]

        task = f"Train combat on {npc['name'].lower()}s."
        state = serialize_game_state(
            name, cb, current_hp, max_hp, 1, 1, random.randint(50, 100), True,
            random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
            "IN_COMBAT", skills, inv,
            [("Weapon", random.choice(EQUIPMENT_DATA["weapons"][:5])["name"])],
            [nearby_entity(npc["name"], cx, cy, 1, ["Attack"], level=npc["level"])],
            [], [], [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Train combat on {npc['name'].lower()}s",
            interacting_with=npc["name"],
            under_attack=[f"{npc['name']}(lvl:{npc['level']})"],
        )
        reasoning = f"HP critically low at {current_hp}/{max_hp}! Eating {food.lower()} immediately."
        actions = json.dumps([{"action": "EAT_FOOD", "name": food}, {"action": "WAIT_ANIMATION", "ticks": 20}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # STUCK while walking
    for _ in range(20):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        dx, dy = px + random.randint(10, 50), py + random.randint(10, 50)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        stuck_ticks = random.randint(4, 12)

        task = "Walk to destination."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
            0, 100, px, py, 0,
            "IDLE", skills, [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            dest_x=dx, dest_y=dy, stuck_ticks=stuck_ticks,
            current_goal="Walking to destination",
        )
        # Try slightly different coordinates
        alt_x = dx + random.choice([-5, -3, 3, 5])
        alt_y = dy + random.choice([-5, -3, 3, 5])
        reasoning = f"Stuck for {stuck_ticks} ticks! Trying a slightly different path."
        actions = json.dumps([{"action": "PATH_TO", "x": alt_x, "y": alt_y, "plane": 0}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Attacked while skilling
    for _ in range(20):
        ore = random.choice(MINING_DATA[:3])
        loc = random.choice(ore["locations"])
        cx, cy = random.choice(loc["coords"])
        region = loc["region"]
        skills = make_default_skills()
        skills["Mine"] = random.randint(ore["level"], ore["level"] + 15)
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        max_hp = 10
        current_hp = random.randint(5, 10)
        nearby_npc_atk = random.choice(COMBAT_NPCS[:3])
        attacker = nearby_npc_atk["name"]
        atk_level = nearby_npc_atk["level"]

        inv = [("Bronze pickaxe", 1), (ore["item"], random.randint(3, 20))]
        if random.random() < 0.5:
            inv.append(("Trout", random.randint(3, 10)))

        task = f"Mine {ore['item'].lower()}."
        state = serialize_game_state(
            random_player(), cb, current_hp, max_hp, 1, 1, random.randint(50, 100), True,
            random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
            "IN_COMBAT", skills, inv, [],
            [nearby_entity(attacker, jitter(cx), jitter(cy), 1, ["Attack"], level=atk_level)],
            [nearby_entity(ore["ore"], cx, cy, 2, ["Mine"])],
            [], [],
            region, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Mine {ore['item'].lower()}",
            interacting_with=attacker,
            under_attack=[f"{attacker}(lvl:{atk_level})"],
        )
        has_food = any(name == "Trout" for name, _ in inv)
        if has_food and current_hp < max_hp // 2:
            reasoning = f"Being attacked by {attacker.lower()} while mining! HP low, eating first."
            actions = json.dumps([{"action": "EAT_FOOD", "name": "Trout"}])
        else:
            reasoning = f"Being attacked by {attacker.lower()} while mining. Ignoring it and continuing to mine."
            actions = json.dumps([{"action": "INTERACT_OBJECT", "name": ore["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Resource depleted
    for _ in range(20):
        ore = random.choice(MINING_DATA[:3])
        loc = random.choice(ore["locations"])
        cx, cy = random.choice(loc["coords"])
        region = loc["region"]
        skills = make_default_skills()
        skills["Mine"] = random.randint(ore["level"], ore["level"] + 15)
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        inv = [("Bronze pickaxe", 1), (ore["item"], random.randint(5, 20))]
        # Other rocks nearby
        other_obj = [nearby_entity("Rocks", cx, cy, 2, ["Mine"]), nearby_entity(ore["ore"], cx + 5, cy + 3, 5, ["Mine"])]

        task = f"Mine {ore['item'].lower()}."
        state = serialize_game_state(
            random_player(), cb, 10, 10, 1, 1, random.randint(50, 100), True,
            random.randint(3, 10), 100, jitter(cx), jitter(cy), 0,
            "IDLE", skills, inv, [], [], other_obj, [], [],
            region, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Mine {ore['item'].lower()}",
            game_messages=["There is no ore currently available in this rock."],
            action_results=[f"INTERACT_OBJECT({ore['ore']}/Mine) -> FAILED: Could not find object"],
        )
        reasoning = f"Rock is depleted. Switching to another {ore['ore'].lower()} nearby."
        actions = json.dumps([{"action": "INTERACT_OBJECT", "name": ore["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Failed action - can't reach
    for _ in range(15):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        nearby_obj = [
            nearby_entity("Door", px + 2, py, 2, ["Open"]),
            nearby_entity("Bank booth", px + 5, py + 3, 6, ["Bank"]),
        ]

        task = "Bank items."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, [("Iron ore", 27)], [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            current_goal="Bank items",
            game_messages=["I can't reach that!"],
            action_results=["INTERACT_OBJECT(Bank booth/Bank) -> FAILED: Could not find object"],
        )
        reasoning = "Can't reach the bank booth. There might be a door in the way. Opening the door first."
        actions = json.dumps([{"action": "INTERACT_OBJECT", "name": "Door", "option": "Open"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # FLEEING — low HP, under attack, need to escape to safety
    flee_scenarios = [
        {"npc": "Al-Kharid warrior", "level": 9, "region": 13104, "cx": 3293, "cy": 3173,
         "bank": "Al Kharid bank", "bx": 3269, "by": 3167, "bp": 0},
        {"npc": "Dark wizard", "level": 7, "region": 12338, "cx": 3230, "cy": 3370,
         "bank": "Varrock West bank", "bx": 3185, "by": 3436, "bp": 0},
        {"npc": "Giant rat", "level": 1, "region": 12594, "cx": 3225, "cy": 3150,
         "bank": "Lumbridge bank", "bx": 3208, "by": 3220, "bp": 2},
        {"npc": "Goblin", "level": 2, "region": 12850, "cx": 3259, "cy": 3227,
         "bank": "Lumbridge bank", "bx": 3208, "by": 3220, "bp": 2},
        {"npc": "Guard", "level": 21, "region": 12342, "cx": 3210, "cy": 3420,
         "bank": "Varrock West bank", "bx": 3185, "by": 3436, "bp": 0},
    ]
    for fs in flee_scenarios:
        for _ in range(6):
            tick = random_tick()
            name = random_player()
            atk = random.randint(5, 30)
            str_ = random.randint(5, 30)
            def_ = random.randint(5, 30)
            max_hp = max(10, (atk + str_ + def_) // 3)
            current_hp = random.randint(1, max(1, max_hp // 4))  # critically low
            cb = combat_level_for_skills(atk, str_, def_, max_hp, 1, 1, 1)
            skills = make_default_skills()
            skills.update({"Atk": atk, "Str": str_, "Def": def_})
            run_pct = random.randint(30, 100)
            run_on = random.random() < 0.5  # might not have run on

            food_count = random.randint(0, 2)
            inv_items = []
            if food_count > 0:
                food = random.choice(["Trout", "Salmon", "Lobster"])
                inv_items.append((food, food_count))
            inv_items.append((random.choice(["Iron scimitar", "Steel scimitar", "Mithril scimitar"]), 1))

            task = f"Train combat on {fs['npc'].lower()}s."
            state = serialize_game_state(
                name, cb, current_hp, max_hp, 1, 1, run_pct, run_on,
                random.randint(5, 15), 100,
                jitter(fs["cx"]), jitter(fs["cy"]), 0,
                "IN_COMBAT", skills, inv_items,
                [("Weapon", inv_items[-1][0])],
                [nearby_entity(fs["npc"], jitter(fs["cx"]), jitter(fs["cy"]), 1, ["Attack"], level=fs["level"])],
                [], [], [],
                fs["region"], random_world(), "Inventory", "Accurate", tick,
                current_goal=f"Train combat on {fs['npc'].lower()}s",
                interacting_with=fs["npc"],
                under_attack=[f"{fs['npc']}(lvl:{fs['level']})"],
            )

            # Decide: eat first if we have food, then flee
            action_list = []
            if food_count > 0 and current_hp <= max_hp // 4:
                food_name = inv_items[0][0]
                action_list.append({"action": "EAT_FOOD", "name": food_name})
                reasoning = f"HP critically low at {current_hp}/{max_hp}! Eating {food_name.lower()} and fleeing to {fs['bank']}."
            else:
                reasoning = f"HP critically low at {current_hp}/{max_hp} with no food! Must flee to {fs['bank']} immediately."
            action_list.append({"action": "PATH_TO", "x": fs["bx"], "y": fs["by"], "plane": fs["bp"], "fleeing": True, "goal": f"Flee to {fs['bank']}"})
            actions = json.dumps(action_list)
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

            # Continuation while fleeing
            tiles_remaining = random.randint(5, 30)
            mid_x = fs["cx"] + (fs["bx"] - fs["cx"]) // 2 + random.randint(-5, 5)
            mid_y = fs["cy"] + (fs["by"] - fs["cy"]) // 2 + random.randint(-5, 5)
            state2 = serialize_game_state(
                name, cb, min(current_hp + 7, max_hp), max_hp, 1, 1,
                max(10, run_pct - 15), True,
                random.randint(3, 10), 100, mid_x, mid_y, 0,
                "MOVING", skills, inv_items, [("Weapon", inv_items[-1][0])],
                [], [], [], [],
                random.choice(list(REGION_NAMES.keys())), random_world(), "Inventory", "Accurate", tick + 25,
                current_goal=f"Flee to {fs['bank']}",
                action_results=[f"PATH_TO({fs['bx']},{fs['by']},{fs['bp']}) -> SUCCESS: Walking to ({fs['bx']},{fs['by']}): {tiles_remaining} tiles remaining. Re-issue PATH_TO to continue."],
            )
            reasoning2 = f"Still fleeing, {tiles_remaining} tiles from {fs['bank']}. Keep going with fleeing mode."
            actions2 = json.dumps([{"action": "PATH_TO", "x": fs["bx"], "y": fs["by"], "plane": fs["bp"], "fleeing": True}])
            examples.append(make_example(pick_prompt(task), state2, f"{reasoning2}\n{actions2}"))

    return examples


def gen_equipment_scenarios():
    """Generate equipment equip/unequip examples."""
    examples = []
    for _ in range(30):
        tick = random_tick()
        name = random_player()
        cb = random.randint(3, 60)
        skills = make_default_skills()
        weapon = random.choice(EQUIPMENT_DATA["weapons"])
        armor = random.choice(EQUIPMENT_DATA["armor"])
        inv = [(weapon["name"], 1), (armor["name"], 1), ("Lobster", random.randint(5, 20))]
        region = random.choice(list(REGION_NAMES.keys()))
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)

        task = "Equip gear for combat."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, random.randint(2, 10), 100, px, py, 0,
            "IDLE", skills, inv, [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Equipping {weapon['name'].lower()} and {armor['name'].lower()} for combat."
        actions = json.dumps([
            {"action": "EQUIP_ITEM", "name": weapon["name"], "goal": "Equip gear"},
            {"action": "EQUIP_ITEM", "name": armor["name"]}
        ])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Unequip
    for _ in range(15):
        tick = random_tick()
        name = random_player()
        weapon = random.choice(EQUIPMENT_DATA["weapons"])
        equip = [("Weapon", weapon["name"])]
        skills = make_default_skills()
        region = random.choice(list(REGION_NAMES.keys()))
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)

        task = "Manage equipment."
        state = serialize_game_state(
            name, random.randint(3, 40), 10, 10, 1, 1, 100, True, random.randint(2, 10), 100, px, py, 0,
            "IDLE", skills, [], equip, [], [], [], [],
            region, random_world(), "Equipment", "Accurate", tick,
        )
        reasoning = f"Unequipping {weapon['name'].lower()}."
        actions = json.dumps([{"action": "UNEQUIP_ITEM", "name": weapon["name"]}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


def gen_misc_scenarios():
    """Generate miscellaneous: TOGGLE_RUN, OPEN_TAB, ROTATE_CAMERA, WORLD_HOP, WAIT, WALK_TO, CLICK_WIDGET, TYPE_TEXT, PRESS_KEY, SET_ATTACK_STYLE, SET_AUTOCAST, SPECIAL_ATTACK, CRAFTING, AGILITY, THIEVING, RC, SLAYER."""
    examples = []

    # TOGGLE_RUN
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        run_on = random.random() < 0.5
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, random.randint(20, 100), run_on, 0, 100, px, py, 0,
            "IDLE", make_default_skills(), [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        if not run_on:
            reasoning = "Run is off. Turning it on for faster travel."
            actions = json.dumps([{"action": "TOGGLE_RUN"}])
        else:
            reasoning = "Run is on but energy is low. Turning it off to conserve."
            actions = json.dumps([{"action": "TOGGLE_RUN"}])
        examples.append(make_example(pick_prompt("Travel efficiently."), state, f"{reasoning}\n{actions}"))

    # OPEN_TAB
    for tab in TABS:
        for _ in range(2):
            tick = random_tick()
            name = random_player()
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            state = serialize_game_state(
                name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", make_default_skills(), [], [], [], [], [], [],
                region, random_world(), random.choice(TABS), "Accurate", tick,
            )
            reasoning = f"Opening {tab} tab."
            actions = json.dumps([{"action": "OPEN_TAB", "name": tab}])
            examples.append(make_example(pick_prompt("Check game tabs."), state, f"{reasoning}\n{actions}"))

    # ROTATE_CAMERA
    for direction in ["north", "south", "east", "west"]:
        for _ in range(2):
            tick = random_tick()
            name = random_player()
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            state = serialize_game_state(
                name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", make_default_skills(), [], [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
            )
            reasoning = f"Rotating camera {direction} to look around."
            actions = json.dumps([{"action": "ROTATE_CAMERA", "option": direction, "ticks": random.randint(2, 5)}])
            examples.append(make_example(pick_prompt("Look around."), state, f"{reasoning}\n{actions}"))

    # WORLD_HOP
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        current_world = random_world()
        target_world = random_world()
        while target_world == current_world:
            target_world = random_world()
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", make_default_skills(), [], [], [], [], [], [],
            region, current_world, "Inventory", "Accurate", tick,
            game_messages=["This spot is crowded."] if random.random() < 0.5 else None,
        )
        reasoning = f"This world is crowded. Hopping to world {target_world}."
        actions = json.dumps([{"action": "WORLD_HOP", "x": target_world}])
        examples.append(make_example(pick_prompt("Find a less crowded world."), state, f"{reasoning}\n{actions}"))

    # WAIT
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        wait_ticks = random.choice([2, 3, 5, 10])
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "MOVING", make_default_skills(), [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            dest_x=px + random.randint(3, 10), dest_y=py + random.randint(3, 10),
        )
        reasoning = f"Player is still moving. Waiting {wait_ticks} ticks."
        actions = json.dumps([{"action": "WAIT", "ticks": wait_ticks}])
        examples.append(make_example(pick_prompt("Wait for movement."), state, f"{reasoning}\n{actions}"))

    # WALK_TO (short distance)
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        tx, ty = px + random.randint(-3, 3), py + random.randint(-3, 3)
        region = random.choice(list(REGION_NAMES.keys()))
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", make_default_skills(), [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Short walk to ({tx},{ty})."
        actions = json.dumps([{"action": "WALK_TO", "x": tx, "y": ty}])
        examples.append(make_example(pick_prompt("Walk nearby."), state, f"{reasoning}\n{actions}"))

    # CLICK_WIDGET
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        wx, wy = random.randint(200, 600), random.randint(200, 400)
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", make_default_skills(), [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Clicking UI button at ({wx},{wy})."
        actions = json.dumps([{"action": "CLICK_WIDGET", "x": wx, "y": wy}])
        examples.append(make_example(pick_prompt("Interact with UI."), state, f"{reasoning}\n{actions}"))

    # TYPE_TEXT
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        text = random.choice(["Hello", "Buying iron ore 100gp", "ty", "gl", "50"])
        state = serialize_game_state(
            name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", make_default_skills(), [], [], [], [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Typing '{text}' into chatbox."
        actions = json.dumps([{"action": "TYPE_TEXT", "text": text, "option": "enter"}])
        examples.append(make_example(pick_prompt("Chat."), state, f"{reasoning}\n{actions}"))

    # PRESS_KEY
    for key_name in ["space", "enter", "escape"]:
        for _ in range(3):
            tick = random_tick()
            name = random_player()
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            state = serialize_game_state(
                name, 3, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", make_default_skills(), [], [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
            )
            reasoning = f"Pressing {key_name} key."
            actions = json.dumps([{"action": "PRESS_KEY", "name": key_name}])
            examples.append(make_example(pick_prompt("Use keyboard."), state, f"{reasoning}\n{actions}"))

    # SET_ATTACK_STYLE
    for style in ATTACK_STYLES:
        for _ in range(2):
            tick = random_tick()
            name = random_player()
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            region = random.choice(list(REGION_NAMES.keys()))
            state = serialize_game_state(
                name, random.randint(3, 40), 10, 10, 1, 1, 100, True,
                random.randint(2, 10), 100, px, py, 0,
                "IDLE", make_default_skills(), [],
                [("Weapon", "Iron scimitar")], [], [], [], [],
                region, random_world(), "Combat", random.choice(ATTACK_STYLES), tick,
            )
            reasoning = f"Switching to {style} attack style."
            actions = json.dumps([{"action": "SET_ATTACK_STYLE", "option": style}])
            examples.append(make_example(pick_prompt("Change combat style."), state, f"{reasoning}\n{actions}"))

    # SET_AUTOCAST
    for _ in range(8):
        spell = random.choice(SPELLS["combat"])
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        level = random.randint(spell["level"], 70)
        skills = make_default_skills()
        skills["Mag"] = level
        state = serialize_game_state(
            name, random.randint(3, 40), 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, [], [("Weapon", "Staff of fire")], [], [], [], [],
            region, random_world(), "Combat", "Accurate", tick,
        )
        reasoning = f"Setting autocast to {spell['name']}."
        actions = json.dumps([{"action": "SET_AUTOCAST", "name": spell["name"]}])
        examples.append(make_example(pick_prompt("Set up magic combat."), state, f"{reasoning}\n{actions}"))

    # SPECIAL_ATTACK
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        npc = random.choice(COMBAT_NPCS[:5])
        loc = random.choice(npc["locations"])
        cx, cy = random.choice(loc["coords"])
        cb = random.randint(20, 80)
        state = serialize_game_state(
            name, cb, random.randint(10, 30), 30, 1, 1, 100, True,
            random.randint(5, 15), random.randint(50, 100), jitter(cx), jitter(cy), 0,
            "IN_COMBAT", make_default_skills(),
            [("Lobster", random.randint(5, 20))],
            [("Weapon", "Dragon scimitar")],
            [nearby_entity(npc["name"], cx, cy, 1, ["Attack"], level=npc["level"])],
            [], [], [],
            loc["region"], random_world(), "Inventory", "Accurate", tick,
            interacting_with=npc["name"],
        )
        reasoning = "Spec energy available. Activating special attack."
        actions = json.dumps([{"action": "SPECIAL_ATTACK"}, {"action": "WAIT_ANIMATION", "ticks": 5}])
        examples.append(make_example(pick_prompt("Fight with special attacks."), state, f"{reasoning}\n{actions}"))

    # Crafting (leather, gems, jewelry)
    for craft in CRAFTING_DATA[:4]:
        for _ in range(3):
            level = random.randint(craft["level"], min(craft["level"] + 15, 99))
            skills = make_default_skills()
            skills["Craft"] = level
            xp = {"Craft": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            inv = [("Needle", 1), ("Thread", random.randint(10, 50)), (craft["material"], random.randint(5, 26))]
            region = random.choice(list(REGION_NAMES.keys()))
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

            task = f"Craft {craft['item'].lower()}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Craft {craft['item'].lower()}",
            )
            reasoning = f"Using needle on leather to craft {craft['item'].lower()}."
            actions = json.dumps([{"action": "USE_ITEM_ON_ITEM", "item1": "Needle", "item2": craft["material"]}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Jewelry crafting at furnace
    for craft in CRAFTING_DATA[4:8]:
        for _ in range(2):
            level = random.randint(craft["level"], min(craft["level"] + 15, 99))
            skills = make_default_skills()
            skills["Craft"] = level
            inv = [(craft["material"], random.randint(5, 27))]
            region = 13104
            px, py = 3275, 3186
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_obj = [nearby_entity("Furnace", 3275, 3185, 1, ["Smelt"])]

            task = f"Craft {craft['item'].lower()}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=f"Craft {craft['item'].lower()}",
            )
            reasoning = f"Using {craft['material'].lower()} on furnace to craft {craft['item'].lower()}."
            actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": craft["material"], "object": "Furnace"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Gem cutting
    for craft in CRAFTING_DATA[8:]:
        for _ in range(2):
            level = random.randint(craft["level"], min(craft["level"] + 15, 99))
            skills = make_default_skills()
            skills["Craft"] = level
            inv = [("Chisel", 1), (craft["item"], random.randint(5, 27))]
            region = random.choice(list(REGION_NAMES.keys()))
            px, py = random.randint(3100, 3300), random.randint(3100, 3500)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)

            task = f"Cut {craft['item'].lower()}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, inv, [], [], [], [], [],
                region, random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=f"Cut gems",
            )
            reasoning = f"Using chisel on {craft['item'].lower()} to cut."
            actions = json.dumps([{"action": "USE_ITEM_ON_ITEM", "item1": "Chisel", "item2": craft["item"]}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Agility
    agility_obstacles = [
        ("Rooftop", "Gap", "Cross", "Varrock", 12342, 3220, 3414),
        ("Rooftop", "Tightrope", "Cross", "Varrock", 12342, 3210, 3410),
        ("Rooftop", "Wall", "Climb", "Varrock", 12342, 3221, 3399),
    ]
    for _, obs_name, action, loc_name, region, ox, oy in agility_obstacles:
        for _ in range(5):
            level = random.randint(1, 50)
            skills = make_default_skills()
            skills["Agi"] = level
            xp = {"Agi": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = jitter(ox), jitter(oy)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_obj = [nearby_entity(obs_name, ox, oy, random.randint(1, 3), [action])]

            task = f"Train agility at {loc_name}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(30, 100), True,
                0, 100, px, py, 0,
                "IDLE", skills, [], [], [], nearby_obj, [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Train agility at {loc_name}",
            )
            reasoning = f"Crossing {obs_name.lower()} obstacle."
            actions = json.dumps([{"action": "INTERACT_OBJECT", "name": obs_name, "option": action}, {"action": "WAIT_ANIMATION"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Thieving
    thiev_targets = [
        ("Man", 1, "Lumbridge", 12850, 3222, 3218),
        ("Farmer", 10, "Draynor Village", 12593, 3080, 3250),
        ("Master Farmer", 38, "Draynor Village", 12593, 3080, 3250),
        ("Guard", 40, "Falador", 11828, 2965, 3394),
    ]
    for target, req_level, loc_name, region, tx, ty in thiev_targets:
        for _ in range(3):
            level = random.randint(req_level, min(req_level + 20, 99))
            skills = make_default_skills()
            skills["Thiev"] = level
            xp = {"Thiev": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = jitter(tx), jitter(ty)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_npc = [nearby_entity(target, tx, ty, random.randint(1, 3), ["Pickpocket", "Talk-to"], level=0 if target in ("Man", "Farmer", "Master Farmer") else 21)]

            task = f"Pickpocket {target.lower()}s."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, [], [], nearby_npc, [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Pickpocket {target.lower()}s",
            )
            reasoning = f"Pickpocketing {target.lower()} for thieving XP."
            actions = json.dumps([{"action": "INTERACT_NPC", "name": target, "option": "Pickpocket"}, {"action": "WAIT_ANIMATION"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Runecrafting
    rc_altars = [
        ("Air altar", "Air rune", "Rune essence", 1, 12850, 3127, 3407),
        ("Mind altar", "Mind rune", "Rune essence", 2, 12850, 2982, 3514),
        ("Body altar", "Body rune", "Rune essence", 20, 12850, 3053, 3445),
    ]
    for altar_name, rune_name, essence, req_level, region, ax, ay in rc_altars:
        for _ in range(3):
            level = random.randint(req_level, min(req_level + 20, 99))
            skills = make_default_skills()
            skills["RC"] = level
            xp = {"RC": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            inv = [(essence, random.randint(20, 28))]
            px, py = jitter(ax), jitter(ay)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            nearby_obj = [nearby_entity(altar_name, ax, ay, random.randint(1, 4), ["Craft-rune"])]

            task = f"Craft {rune_name.lower()}s."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(40, 100), True,
                random.randint(5, 15), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Craft {rune_name.lower()}s",
            )
            reasoning = f"At the {altar_name.lower()}. Crafting {rune_name.lower()}s."
            actions = json.dumps([{"action": "INTERACT_OBJECT", "name": altar_name, "option": "Craft-rune"}, {"action": "WAIT_ANIMATION"}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Slayer
    slayer_tasks_list = [
        ("Cows", "Cow", 2, 12851, 3253, 3270),
        ("Goblins", "Goblin", 2, 12850, 3259, 3227),
        ("Guards", "Guard", 21, 12853, 3258, 3408),
    ]
    for task_name, npc_name, npc_level, region, nx, ny in slayer_tasks_list:
        for _ in range(5):
            atk = random.randint(10, 50)
            str_ = random.randint(10, 50)
            def_ = random.randint(10, 50)
            slay = random.randint(1, 30)
            hp_level = max(10, (atk + str_ + def_) // 3)
            cb = combat_level_for_skills(atk, str_, def_, hp_level, 1, 1, 1)
            skills = make_default_skills()
            skills.update({"Atk": atk, "Str": str_, "Def": def_, "Slay": slay})
            xp = {"Atk": random_xp_at_level(atk), "Slay": random_xp_at_level(slay)}
            tick = random_tick()
            name = random_player()
            px, py = jitter(nx), jitter(ny)
            remaining = random.randint(1, 30)
            inv = [("Lobster", random.randint(5, 20))]
            nearby_npc = [nearby_entity(npc_name, nx, ny, random.randint(1, 5), ["Attack"], level=npc_level)]

            task = f"Slayer task: Kill {remaining} {task_name.lower()}."
            state = serialize_game_state(
                name, cb, hp_level, hp_level, 1, 1, random.randint(50, 100), True,
                random.randint(5, 15), 100, px, py, 0,
                "IDLE", skills, inv,
                [("Weapon", random.choice(EQUIPMENT_DATA["weapons"][:5])["name"])],
                nearby_npc, [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Slayer: Kill {remaining} {task_name.lower()}",
            )
            reasoning = f"Slayer task: {remaining} {task_name.lower()} left. Attacking."
            actions = json.dumps([
                {"action": "INTERACT_NPC", "name": npc_name, "option": "Attack"},
                {"action": "WAIT_ANIMATION", "ticks": 20}
            ])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # USE_ITEM_ON_NPC
    for _ in range(10):
        tick = random_tick()
        name = random_player()
        px, py = random.randint(3100, 3300), random.randint(3100, 3500)
        region = random.choice(list(REGION_NAMES.keys()))
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()
        npc_name = random.choice(["Banker", "Hans", "Man"])
        item_used = random.choice(["Bones", "Coins", "Bronze sword"])
        nearby_npc = [nearby_entity(npc_name, jitter(px), jitter(py), random.randint(1, 3), ["Talk-to"])]
        inv = [(item_used, random.randint(1, 10))]

        task = f"Use {item_used.lower()} on {npc_name}."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, inv, [], nearby_npc, [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
        )
        reasoning = f"Using {item_used.lower()} on {npc_name}."
        actions = json.dumps([{"action": "USE_ITEM_ON_NPC", "item": item_used, "npc": npc_name}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Ranged combat
    for _ in range(15):
        rng = random.randint(1, 60)
        skills = make_default_skills()
        skills["Rng"] = rng
        xp = {"Rng": random_xp_at_level(rng)}
        cb = combat_level_for_skills(1, 1, 1, 10, 1, rng, 1)
        tick = random_tick()
        name = random_player()
        npc = random.choice(COMBAT_NPCS[:4])
        loc = random.choice(npc["locations"])
        cx, cy = random.choice(loc["coords"])
        px, py = jitter(cx), jitter(cy)
        region = loc["region"]
        bow = random.choice(["Shortbow", "Oak shortbow", "Willow shortbow"])
        inv = [("Bronze arrow", random.randint(100, 500)), ("Lobster", random.randint(5, 15))]
        equip = [("Weapon", bow), ("Ammo", "Bronze arrow")]
        nearby_npc = [nearby_entity(npc["name"], cx, cy, random.randint(1, 5), ["Attack"], level=npc["level"])]

        task = f"Train ranged on {npc['name'].lower()}s."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
            random.randint(1, 10), 100, px, py, 0,
            "IDLE", skills, inv, equip, nearby_npc, [], [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal=f"Train ranged on {npc['name'].lower()}s",
        )
        reasoning = f"Attacking {npc['name'].lower()} with {bow.lower()} for ranged XP."
        actions = json.dumps([
            {"action": "INTERACT_NPC", "name": npc["name"], "option": "Attack"},
            {"action": "WAIT_ANIMATION", "ticks": 20}
        ])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Farming
    farm_patches = [
        ("Allotment", "Potato seed", "Potatoes", "Lumbridge", 12850, 3054, 3307),
        ("Herb patch", "Guam seed", "Grimy guam leaf", "Falador", 11828, 3058, 3311),
    ]
    for patch_name, seed, product, loc_name, region, fx, fy in farm_patches:
        for _ in range(5):
            level = random.randint(1, 40)
            skills = make_default_skills()
            skills["Farm"] = level
            xp = {"Farm": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = jitter(fx), jitter(fy)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            inv = [("Seed dibber", 1), (seed, random.randint(3, 10)), ("Watering can(4)", 1)]
            nearby_obj = [nearby_entity(patch_name, fx, fy, random.randint(1, 3), ["Rake", "Inspect", "Plant"])]

            task = f"Farm {product.lower()} at {loc_name}."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True,
                random.randint(2, 10), 100, px, py, 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Farm {product.lower()}",
            )
            reasoning = f"Planting {seed.lower()} in the {patch_name.lower()}."
            actions = json.dumps([{"action": "USE_ITEM_ON_OBJECT", "item": seed, "object": patch_name}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Hunter
    hunter_traps = [
        ("Bird snare", "Crimson swift", "Feldip Hunter area", 9275, 2558, 2913),
        ("Box trap", "Chinchompa", "Feldip Hunter area", 9275, 2556, 2912),
    ]
    for trap_name, creature, loc_name, region, hx, hy in hunter_traps:
        for _ in range(5):
            level = random.randint(1, 50)
            skills = make_default_skills()
            skills["Hunt"] = level
            xp = {"Hunt": random_xp_at_level(level)}
            tick = random_tick()
            name = random_player()
            px, py = jitter(hx), jitter(hy)
            cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
            inv = [(trap_name, random.randint(1, 5))]
            nearby_npc = [nearby_entity(creature, hx, hy, random.randint(2, 6), ["Catch"], level=0)]

            task = f"Hunt {creature.lower()}s."
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
                "IDLE", skills, inv, [], nearby_npc, [], [], [],
                region, random_world(), "Inventory", "Accurate", tick,
                xp_dict=xp, current_goal=f"Hunt {creature.lower()}s",
            )
            reasoning = f"Laying {trap_name.lower()} to catch {creature.lower()}."
            actions = json.dumps([{"action": "USE_ITEM", "name": trap_name}])
            examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # Construction
    for _ in range(10):
        level = random.randint(1, 50)
        skills = make_default_skills()
        skills["Con"] = level
        xp = {"Con": random_xp_at_level(level)}
        tick = random_tick()
        name = random_player()
        px, py = 1929, 5023  # POH
        region = 7513
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        inv = [("Oak plank", random.randint(5, 20)), ("Hammer", 1), ("Saw", 1)]
        nearby_obj = [nearby_entity("Oak larder space", 1929, 5023, 1, ["Build"])]

        task = "Train construction."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, 100, True, 0, 100, px, py, 0,
            "IDLE", skills, inv, [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal="Build oak larders", instanced=True,
        )
        reasoning = "Building oak larder for construction XP."
        actions = json.dumps([{"action": "INTERACT_OBJECT", "name": "Oak larder space", "option": "Build"}, {"action": "WAIT_ANIMATION"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    return examples


# ═══════════════════════════════════════════════════════════════════════════════
# SESSION NOTES / CHAIN / QUEST GENERATORS
# ═══════════════════════════════════════════════════════════════════════════════

def gen_chain_scenarios():
    """Generate connected gameplay chains with accumulating session notes (~800 examples)."""
    examples = []

    def _mining_chain():
        """Mining loop: walk to mine -> mine ore -> full inv -> walk to bank -> deposit -> walk back."""
        chain_examples = []
        for ore_data in MINING_DATA[:4]:  # copper, tin, iron, silver
            for loc in ore_data["locations"][:1]:
                for _ in range(3):
                    notes = []
                    ore_name = ore_data["ore"]
                    item_name = ore_data["item"]
                    level = random.randint(max(ore_data["level"], 1), min(ore_data["level"] + 20, 99))
                    skills = make_default_skills()
                    skills["Mine"] = level
                    xp = {"Mine": random_xp_at_level(level)}
                    tick = random_tick()
                    world = random_world()
                    name = random_player()
                    cx, cy = random.choice(loc["coords"])
                    region = loc["region"]
                    bank = pick_bank_near(region)
                    bx, by, bp = bank["coords"][0], bank["coords"][1], bank["plane"]
                    tool = "Bronze pickaxe" if level < 6 else ("Iron pickaxe" if level < 21 else "Mithril pickaxe")
                    cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                    task = f"Mine {item_name.lower()} and bank at {bank['name']}."
                    inv_count = 0

                    # Step 1: Arrive at mine, start mining
                    px, py = jitter(cx), jitter(cy)
                    inv = [(tool, 1)]
                    nearby_obj = [nearby_entity(ore_name, cx, cy, random.randint(1, 4), ["Mine"])]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine", "goal": f"Mine {item_name.lower()}"}, {"action": "WAIT_ANIMATION"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(2, 10), 100, px, py, 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, session_notes="\n".join(notes) if notes else None,
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"At {loc['name']} with empty inventory. Starting to mine {ore_name.lower()}.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(px, py, "IDLE", 1, actions_list))
                    tick += random.randint(10, 20)

                    # Steps 2-4: Mine more ore (inventory fills)
                    for step in range(3):
                        inv_count = 5 + step * 8 + random.randint(0, 3)
                        inv_count = min(inv_count, 26)
                        inv = [(tool, 1), (item_name, inv_count)]
                        used = inv_count + 1
                        px, py = jitter(cx), jitter(cy)
                        actions_list = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
                        state = serialize_game_state(
                            name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                            random.randint(5, 15), 100, px, py, 0,
                            "IDLE", skills, inv, [], [], nearby_obj, [], [],
                            region, world, "Inventory", "Accurate", tick,
                            xp_dict=xp, current_goal=f"Mine {item_name.lower()}",
                            action_results=[f"INTERACT_OBJECT({ore_name}/Mine) -> SUCCESS", "WAIT_ANIMATION -> SUCCESS: Animation finished after 4 ticks"],
                            game_messages=[f"You manage to mine some {item_name.lower().replace(' ore', '')}."],
                            session_notes="\n".join(notes),
                        )
                        chain_examples.append(make_example(pick_prompt(task), state,
                            f"Got ore. {used}/28 inventory. Mining more.\n{json.dumps(actions_list)}"))
                        notes.append(compress_to_note(px, py, "IDLE", used, actions_list))
                        tick += random.randint(10, 20)

                    # Step 5: Full inventory -> walk to bank
                    inv = [(tool, 1), (item_name, 27)]
                    px, py = jitter(cx), jitter(cy)
                    actions_list = [{"action": "PATH_TO", "x": bx, "y": by, "plane": bp, "goal": f"Bank {item_name.lower()} at {bank['name']}"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                        random.randint(15, 30), 100, px, py, 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Mine {item_name.lower()}",
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Inventory full at 28/28. Walking to {bank['name']} to bank.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(px, py, "IDLE", 28, actions_list))
                    tick += random.randint(20, 40)

                    # Step 6: At bank, deposit and withdraw tool
                    px, py = jitter(bx, 2), jitter(by, 2)
                    nearby_bank = [nearby_entity("Bank booth", bx, by, random.randint(1, 3), ["Bank", "Collect"])]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"}, {"action": "BANK_DEPOSIT_ALL"}, {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1}, {"action": "BANK_CLOSE"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 90), True,
                        random.randint(15, 30), 100, px, py, bp,
                        "IDLE", skills, inv, [], [], nearby_bank, [], [],
                        bank["region"], world, "Inventory", "Accurate", tick,
                        current_goal=f"Bank {item_name.lower()} at {bank['name']}",
                        action_results=[f"PATH_TO({bx},{by},{bp}) -> SUCCESS: Arrived at destination."],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Arrived at {bank['name']}. Depositing all and withdrawing {tool.lower()}.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(px, py, "IDLE", 28, actions_list))
                    tick += random.randint(5, 10)

                    # Step 7: Walk back to mine
                    inv = [(tool, 1)]
                    actions_list = [{"action": "PATH_TO", "x": cx, "y": cy, "plane": 0, "goal": f"Return to {loc['name']} to mine"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 90), True,
                        random.randint(2, 8), 100, px, py, bp,
                        "IDLE", skills, inv, [], [], nearby_bank, [], [],
                        bank["region"], world, "Inventory", "Accurate", tick,
                        current_goal=f"Bank {item_name.lower()} at {bank['name']}",
                        action_results=["INTERACT_OBJECT(Bank booth/Bank) -> SUCCESS", "BANK_DEPOSIT_ALL -> SUCCESS", f"BANK_WITHDRAW({tool}/1) -> SUCCESS", "BANK_CLOSE -> SUCCESS"],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Banked everything. Heading back to {loc['name']} to mine more.\n{json.dumps(actions_list)}"))
        return chain_examples

    def _woodcutting_chain():
        """Woodcutting loop: chop -> bank -> return."""
        chain_examples = []
        for tree_data in WOODCUTTING_DATA[:3]:
            for loc in tree_data["locations"][:1]:
                for _ in range(3):
                    notes = []
                    tree_name = tree_data["tree"]
                    item_name = tree_data["item"]
                    level = random.randint(max(tree_data["level"], 1), min(tree_data["level"] + 15, 99))
                    skills = make_default_skills()
                    skills["WC"] = level
                    xp = {"WC": random_xp_at_level(level)}
                    tick = random_tick()
                    world = random_world()
                    name = random_player()
                    cx, cy = random.choice(loc["coords"])
                    region = loc["region"]
                    bank = pick_bank_near(region)
                    bx, by, bp = bank["coords"][0], bank["coords"][1], bank["plane"]
                    tool = "Bronze axe" if level < 6 else ("Iron axe" if level < 21 else "Mithril axe")
                    cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                    task = f"Chop {tree_name.lower()}s and bank logs."
                    nearby_obj = [nearby_entity(tree_name, cx, cy, random.randint(1, 4), ["Chop down"])]

                    # Step 1: Start chopping
                    px, py = jitter(cx), jitter(cy)
                    inv = [(tool, 1)]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": tree_name, "option": "Chop down", "goal": f"Chop {tree_name.lower()}s"}, {"action": "WAIT_ANIMATION"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(2, 10), 100, px, py, 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, session_notes="\n".join(notes) if notes else None,
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Starting to chop {tree_name.lower()}s.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(px, py, "IDLE", 1, actions_list))
                    tick += random.randint(10, 20)

                    # Steps 2-3: Chop more
                    for step in range(2):
                        inv_count = 8 + step * 10 + random.randint(0, 4)
                        inv = [(tool, 1), (item_name, inv_count)]
                        used = inv_count + 1
                        actions_list = [{"action": "INTERACT_OBJECT", "name": tree_name, "option": "Chop down"}, {"action": "WAIT_ANIMATION"}]
                        state = serialize_game_state(
                            name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                            random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
                            "IDLE", skills, inv, [], [], nearby_obj, [], [],
                            region, world, "Inventory", "Accurate", tick,
                            xp_dict=xp, current_goal=f"Chop {tree_name.lower()}s",
                            action_results=[f"INTERACT_OBJECT({tree_name}/Chop down) -> SUCCESS", "WAIT_ANIMATION -> SUCCESS"],
                            session_notes="\n".join(notes),
                        )
                        chain_examples.append(make_example(pick_prompt(task), state,
                            f"Got logs. {used}/28. Chopping more.\n{json.dumps(actions_list)}"))
                        notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", used, actions_list))
                        tick += random.randint(10, 20)

                    # Step 4: Full -> bank
                    inv = [(tool, 1), (item_name, 27)]
                    actions_list = [{"action": "PATH_TO", "x": bx, "y": by, "plane": bp, "goal": f"Bank logs at {bank['name']}"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 100), True,
                        random.randint(15, 30), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Chop {tree_name.lower()}s",
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Full inventory. Banking at {bank['name']}.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", 28, actions_list))
                    tick += random.randint(20, 40)

                    # Step 5: Deposit at bank
                    px, py = jitter(bx, 2), jitter(by, 2)
                    nearby_bank = [nearby_entity("Bank booth", bx, by, random.randint(1, 3), ["Bank", "Collect"])]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"}, {"action": "BANK_DEPOSIT_ALL"}, {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1}, {"action": "BANK_CLOSE"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 90), True,
                        random.randint(10, 25), 100, px, py, bp,
                        "IDLE", skills, inv, [], [], nearby_bank, [], [],
                        bank["region"], world, "Inventory", "Accurate", tick,
                        current_goal=f"Bank logs at {bank['name']}",
                        action_results=[f"PATH_TO({bx},{by},{bp}) -> SUCCESS: Arrived at destination."],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"At bank. Depositing all, withdrawing {tool.lower()}.\n{json.dumps(actions_list)}"))
        return chain_examples

    def _combat_chain():
        """Combat loop: attack -> loot -> eat -> attack -> flee when low."""
        chain_examples = []
        for npc_data in COMBAT_NPCS[:4]:
            for loc in npc_data["locations"][:1]:
                for _ in range(3):
                    notes = []
                    npc_name = npc_data["name"]
                    npc_level = npc_data["level"]
                    cx, cy = random.choice(loc["coords"])
                    region = loc["region"]
                    atk = random.randint(5, 40)
                    str_ = random.randint(5, 40)
                    def_ = random.randint(5, 40)
                    max_hp = max(10, (atk + str_ + def_) // 3)
                    cb = combat_level_for_skills(atk, str_, def_, max_hp, 1, 1, 1)
                    skills = make_default_skills()
                    skills.update({"Atk": atk, "Str": str_, "Def": def_})
                    xp = {"Atk": random_xp_at_level(atk), "Str": random_xp_at_level(str_)}
                    tick = random_tick()
                    world = random_world()
                    name = random_player()
                    food = random.choice(["Trout", "Salmon", "Lobster"])
                    food_count = random.randint(10, 20)
                    weapon = random.choice(EQUIPMENT_DATA["weapons"][:5])
                    equip = [("Weapon", weapon["name"])]
                    task = f"Train combat on {npc_name.lower()}s."
                    nearby_npc = [nearby_entity(npc_name, cx, cy, random.randint(1, 5), ["Attack"], level=npc_level)]
                    current_hp = max_hp

                    # Step 1: Attack first NPC
                    inv = [(food, food_count)]
                    actions_list = [{"action": "INTERACT_NPC", "name": npc_name, "option": "Attack", "goal": f"Train combat on {npc_name.lower()}s"}, {"action": "WAIT_ANIMATION", "ticks": 20}]
                    state = serialize_game_state(
                        name, cb, current_hp, max_hp, 1, 1, random.randint(80, 100), True,
                        random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, equip, nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, session_notes="\n".join(notes) if notes else None,
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Starting combat training. Attacking {npc_name.lower()}.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", food_count, actions_list))
                    tick += random.randint(15, 30)

                    # Step 2: In combat, waiting
                    current_hp = max(max_hp // 2, current_hp - random.randint(2, 8))
                    actions_list = [{"action": "WAIT_ANIMATION", "ticks": 20}]
                    state = serialize_game_state(
                        name, cb, current_hp, max_hp, 1, 1, random.randint(70, 100), True,
                        random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
                        "IN_COMBAT", skills, inv, equip, nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                        interacting_with=npc_name, under_attack=[f"{npc_name}(lvl:{npc_level})"],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Fighting {npc_name.lower()}. HP {current_hp}/{max_hp}, still fine. Waiting.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IN_COMBAT", food_count, actions_list))
                    tick += random.randint(10, 20)

                    # Step 3: Kill, loot bones, attack next
                    nearby_ground = [nearby_entity("Bones", cx, cy, 0, ["Take"])]
                    actions_list = [{"action": "PICKUP_ITEM", "name": "Bones"}, {"action": "INTERACT_NPC", "name": npc_name, "option": "Attack"}, {"action": "WAIT_ANIMATION", "ticks": 20}]
                    state = serialize_game_state(
                        name, cb, current_hp, max_hp, 1, 1, random.randint(70, 100), True,
                        random.randint(5, 15), 100, cx, cy, 0,
                        "IDLE", skills, inv, equip, nearby_npc, [], nearby_ground, [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Kill done. Picking up bones and attacking the next one.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(cx, cy, "IDLE", food_count, actions_list))
                    tick += random.randint(15, 30)

                    # Step 4: Low HP, eat food
                    current_hp = random.randint(1, max(1, max_hp // 3))
                    actions_list = [{"action": "EAT_FOOD", "name": food}, {"action": "WAIT_ANIMATION", "ticks": 20}]
                    state = serialize_game_state(
                        name, cb, current_hp, max_hp, 1, 1, random.randint(60, 100), True,
                        random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
                        "IN_COMBAT", skills, inv, equip, nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Train combat on {npc_name.lower()}s",
                        interacting_with=npc_name, under_attack=[f"{npc_name}(lvl:{npc_level})"],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"HP low at {current_hp}/{max_hp}! Eating {food.lower()} then continuing.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IN_COMBAT", food_count, actions_list))
        return chain_examples

    def _power_mining_chain():
        """Power-mining loop: mine -> drop -> mine -> drop."""
        chain_examples = []
        for ore_data in MINING_DATA[:2]:  # copper, tin
            for loc in ore_data["locations"][:1]:
                for _ in range(4):
                    notes = []
                    ore_name = ore_data["ore"]
                    item_name = ore_data["item"]
                    level = random.randint(1, 30)
                    skills = make_default_skills()
                    skills["Mine"] = level
                    xp = {"Mine": random_xp_at_level(level)}
                    tick = random_tick()
                    world = random_world()
                    name = random_player()
                    cx, cy = random.choice(loc["coords"])
                    region = loc["region"]
                    tool = "Bronze pickaxe" if level < 6 else "Iron pickaxe"
                    cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                    task = f"Power-mine {item_name.lower()} (drop, don't bank)."
                    nearby_obj = [nearby_entity(ore_name, cx, cy, random.randint(1, 4), ["Mine"])]

                    # Step 1: Mine
                    inv = [(tool, 1)]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine", "goal": f"Power-mine {item_name.lower()}"}, {"action": "WAIT_ANIMATION"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(2, 10), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, session_notes="\n".join(notes) if notes else None,
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Power-mining {ore_name.lower()}. Starting fresh.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", 1, actions_list))
                    tick += random.randint(60, 100)

                    # Step 2: Full inv, drop all ore
                    inv = [(tool, 1), (item_name, 27)]
                    drop_actions = [{"action": "DROP_ITEM", "name": item_name} for _ in range(5)]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                        random.randint(10, 25), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Power-mine {item_name.lower()}",
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Inventory full. Power-mining so dropping all {item_name.lower()}.\n{json.dumps(drop_actions)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", 28, drop_actions))
                    tick += random.randint(10, 20)

                    # Step 3: Mine again after dropping
                    inv = [(tool, 1)]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                        random.randint(2, 10), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], [], nearby_obj, [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Power-mine {item_name.lower()}",
                        action_results=["DROP_ITEM -> SUCCESS"] * 5,
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Dropped everything. Back to mining.\n{json.dumps(actions_list)}"))
        return chain_examples

    def _cooking_chain():
        """Cooking loop: bank fish -> walk to range -> cook -> bank food."""
        chain_examples = []
        for cook_data in COOKING_DATA[:4]:
            for _ in range(2):
                notes = []
                raw = cook_data["food"]
                cooked = cook_data["cooked"]
                level = random.randint(max(cook_data["level"], 1), min(cook_data["level"] + 15, 99))
                skills = make_default_skills()
                skills["Cook"] = level
                xp = {"Cook": random_xp_at_level(level)}
                tick = random_tick()
                world = random_world()
                name = random_player()
                cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                task = f"Cook {raw.lower().replace('raw ', '')} at Lumbridge."
                range_x, range_y = 3211, 3215
                bx, by, bp = 3208, 3220, 2

                # Step 1: At bank, withdraw raw fish
                actions_list = [{"action": "BANK_WITHDRAW", "name": raw, "quantity": -1}, {"action": "BANK_CLOSE"}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                    random.randint(1, 5), 100, jitter(bx, 2), jitter(by, 2), bp,
                    "IDLE", skills, [], [], [], [nearby_entity("Bank booth", bx, by, 1, ["Bank"])], [], [],
                    12850, world, "Inventory", "Accurate", tick,
                    bank_open=True, bank_contents=[f"{raw}(x{random.randint(50, 200)})"],
                    bank_unique=5, session_notes="\n".join(notes) if notes else None,
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Bank open. Withdrawing {raw.lower()} to cook.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(bx, 2), jitter(by, 2), "IDLE", 0, actions_list))
                tick += random.randint(5, 10)

                # Step 2: Walk to range
                inv = [(raw, 28)]
                actions_list = [{"action": "PATH_TO", "x": range_x, "y": range_y, "plane": 0, "goal": f"Cook {raw.lower().replace('raw ', '')} at range"}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                    random.randint(5, 15), 100, jitter(bx, 2), jitter(by, 2), bp,
                    "IDLE", skills, inv, [], [], [], [], [],
                    12850, world, "Inventory", "Accurate", tick,
                    action_results=[f"BANK_WITHDRAW({raw}/-1) -> SUCCESS", "BANK_CLOSE -> SUCCESS"],
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Got {raw.lower()}. Walking to cooking range.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(bx, 2), jitter(by, 2), "IDLE", 28, actions_list))
                tick += random.randint(10, 20)

                # Step 3: Use fish on range
                nearby_obj = [nearby_entity("Cooking range", range_x, range_y, random.randint(1, 3), ["Cook"])]
                actions_list = [{"action": "USE_ITEM_ON_OBJECT", "item": raw, "object": "Cooking range"}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                    random.randint(5, 15), 100, jitter(range_x), jitter(range_y), 0,
                    "IDLE", skills, inv, [], [], nearby_obj, [], [],
                    12850, world, "Inventory", "Accurate", tick,
                    current_goal=f"Cook {raw.lower().replace('raw ', '')} at range",
                    action_results=[f"PATH_TO({range_x},{range_y},0) -> SUCCESS: Arrived at destination."],
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"At the range. Cooking {raw.lower()}.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(range_x), jitter(range_y), "IDLE", 28, actions_list))
                tick += random.randint(3, 5)

                # Step 4: Make interface -> cook
                actions_list = [{"action": "MAKE_ITEM", "name": cooked}, {"action": "WAIT_ANIMATION", "ticks": 30}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                    random.randint(5, 15), 100, jitter(range_x), jitter(range_y), 0,
                    "IDLE", skills, inv, [], [], nearby_obj, [], [],
                    12850, world, "Inventory", "Accurate", tick,
                    make_interface_open=True, current_goal=f"Cook {raw.lower().replace('raw ', '')}",
                    action_results=[f"USE_ITEM_ON_OBJECT({raw}/Cooking range) -> SUCCESS"],
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Cooking interface open. Selecting {cooked.lower()}.\n{json.dumps(actions_list)}"))
        return chain_examples

    def _smithing_chain():
        """Smithing loop: bank ore -> walk to furnace -> smelt -> bank bars."""
        chain_examples = []
        for smelt in SMELTING_DATA[:3]:
            for _ in range(2):
                notes = []
                bar = smelt["bar"]
                ingredients = smelt["ingredients"]
                level = random.randint(max(smelt["level"], 1), min(smelt["level"] + 15, 99))
                skills = make_default_skills()
                skills["Smith"] = level
                xp = {"Smith": random_xp_at_level(level)}
                tick = random_tick()
                world = random_world()
                name = random_player()
                cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                task = f"Smelt {bar.lower()}s at Al Kharid."
                furnace_x, furnace_y = 3275, 3186
                bx, by = 3269, 3167

                # Step 1: At bank, withdraw ore
                ore_count = 14
                actions_list = [{"action": "BANK_WITHDRAW", "name": ingredients[0], "quantity": ore_count}]
                if len(ingredients) > 1:
                    actions_list.append({"action": "BANK_WITHDRAW", "name": ingredients[1], "quantity": ore_count})
                actions_list.append({"action": "BANK_CLOSE"})
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                    random.randint(5, 15), 100, jitter(bx, 2), jitter(by, 2), 0,
                    "IDLE", skills, [], [], [], [nearby_entity("Bank booth", bx, by, 1, ["Bank"])], [], [],
                    13104, world, "Inventory", "Accurate", tick,
                    bank_open=True, bank_contents=[f"{ingredients[0]}(x{random.randint(50, 200)})"],
                    bank_unique=5, session_notes="\n".join(notes) if notes else None,
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Withdrawing ore to smelt {bar.lower()}s.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(bx, 2), jitter(by, 2), "IDLE", 0, actions_list))
                tick += random.randint(5, 10)

                # Step 2: Walk to furnace
                inv = [(ing, ore_count) for ing in ingredients]
                actions_list = [{"action": "PATH_TO", "x": furnace_x, "y": furnace_y, "plane": 0, "goal": f"Smelt {bar.lower()}s"}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
                    random.randint(5, 20), 100, jitter(bx, 2), jitter(by, 2), 0,
                    "IDLE", skills, inv, [], [], [], [], [],
                    13104, world, "Inventory", "Accurate", tick,
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Got ore. Walking to Al Kharid furnace.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(bx, 2), jitter(by, 2), "IDLE", ore_count * len(ingredients), actions_list))
                tick += random.randint(10, 20)

                # Step 3: Use ore on furnace
                nearby_obj = [nearby_entity("Furnace", furnace_x, furnace_y - 1, random.randint(1, 3), ["Smelt"])]
                actions_list = [{"action": "USE_ITEM_ON_OBJECT", "item": ingredients[0], "object": "Furnace"}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                    random.randint(10, 25), 100, jitter(furnace_x), jitter(furnace_y), 0,
                    "IDLE", skills, inv, [], [], nearby_obj, [], [],
                    13104, world, "Inventory", "Accurate", tick,
                    current_goal=f"Smelt {bar.lower()}s",
                    action_results=[f"PATH_TO({furnace_x},{furnace_y},0) -> SUCCESS: Arrived at destination."],
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"At furnace. Using {ingredients[0].lower()} on furnace.\n{json.dumps(actions_list)}"))
                notes.append(compress_to_note(jitter(furnace_x), jitter(furnace_y), "IDLE", ore_count * len(ingredients), actions_list))
                tick += random.randint(3, 5)

                # Step 4: Make interface -> smelt
                actions_list = [{"action": "MAKE_ITEM", "name": bar}, {"action": "WAIT_ANIMATION", "ticks": 30}]
                state = serialize_game_state(
                    name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                    random.randint(10, 25), 100, jitter(furnace_x), jitter(furnace_y), 0,
                    "IDLE", skills, inv, [], [], nearby_obj, [], [],
                    13104, world, "Inventory", "Accurate", tick,
                    make_interface_open=True, current_goal=f"Smelt {bar.lower()}s",
                    action_results=[f"USE_ITEM_ON_OBJECT({ingredients[0]}/Furnace) -> SUCCESS"],
                    session_notes="\n".join(notes),
                )
                chain_examples.append(make_example(pick_prompt(task), state,
                    f"Smelting interface open. Making {bar.lower()}.\n{json.dumps(actions_list)}"))
        return chain_examples

    def _fishing_chain():
        """Fishing loop: fish -> full inv -> bank -> return."""
        chain_examples = []
        for fish_data in FISHING_DATA[:3]:
            for loc in fish_data["locations"][:1]:
                for _ in range(2):
                    notes = []
                    method = fish_data["method"]
                    tool_name = fish_data["tool"]
                    fish_name = random.choice(fish_data["fish"])
                    level = random.randint(max(fish_data["level"], 1), min(fish_data["level"] + 15, 99))
                    skills = make_default_skills()
                    skills["Fish"] = level
                    xp = {"Fish": random_xp_at_level(level)}
                    tick = random_tick()
                    world = random_world()
                    name = random_player()
                    cx, cy = random.choice(loc["coords"])
                    region = loc["region"]
                    bank = pick_bank_near(region)
                    bx, by, bp = bank["coords"][0], bank["coords"][1], bank["plane"]
                    cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
                    task = f"Fish and bank at {loc['name']}."
                    nearby_npc = [nearby_entity("Fishing spot", cx, cy, random.randint(1, 3), [method])]

                    # Step 1: Start fishing
                    inv = [(tool_name, 1)]
                    actions_list = [{"action": "INTERACT_NPC", "name": "Fishing spot", "option": method, "goal": f"Fish {fish_name.lower().replace('raw ','')}"}, {"action": "WAIT_ANIMATION"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                        random.randint(1, 10), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, session_notes="\n".join(notes) if notes else None,
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"At fishing spot. Starting to fish.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", 1, actions_list))
                    tick += random.randint(30, 60)

                    # Step 2: Full inv -> bank
                    inv = [(tool_name, 1), (fish_name, 27)]
                    actions_list = [{"action": "PATH_TO", "x": bx, "y": by, "plane": bp, "goal": f"Bank fish at {bank['name']}"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                        random.randint(5, 15), 100, jitter(cx), jitter(cy), 0,
                        "IDLE", skills, inv, [], nearby_npc, [], [], [],
                        region, world, "Inventory", "Accurate", tick,
                        xp_dict=xp, current_goal=f"Fish {fish_name.lower().replace('raw ','')}",
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Inventory full. Banking fish.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(cx), jitter(cy), "IDLE", 28, actions_list))
                    tick += random.randint(20, 40)

                    # Step 3: At bank, deposit
                    nearby_bank = [nearby_entity("Bank booth", bx, by, random.randint(1, 3), ["Bank", "Collect"])]
                    actions_list = [{"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"}, {"action": "BANK_DEPOSIT_ALL"}, {"action": "BANK_WITHDRAW", "name": tool_name, "quantity": 1}, {"action": "BANK_CLOSE"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 90), True,
                        random.randint(5, 15), 100, jitter(bx, 2), jitter(by, 2), bp,
                        "IDLE", skills, inv, [], [], nearby_bank, [], [],
                        bank["region"], world, "Inventory", "Accurate", tick,
                        current_goal=f"Bank fish at {bank['name']}",
                        action_results=[f"PATH_TO({bx},{by},{bp}) -> SUCCESS: Arrived at destination."],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"At {bank['name']}. Depositing all and re-equipping.\n{json.dumps(actions_list)}"))
                    notes.append(compress_to_note(jitter(bx, 2), jitter(by, 2), "IDLE", 28, actions_list))
                    tick += random.randint(5, 10)

                    # Step 4: Walk back to fishing spot
                    inv = [(tool_name, 1)]
                    actions_list = [{"action": "PATH_TO", "x": cx, "y": cy, "plane": 0, "goal": f"Return to fish at {loc['name']}"}]
                    state = serialize_game_state(
                        name, cb, 10, 10, 1, 1, random.randint(50, 90), True,
                        random.randint(1, 8), 100, jitter(bx, 2), jitter(by, 2), bp,
                        "IDLE", skills, inv, [], [], nearby_bank, [], [],
                        bank["region"], world, "Inventory", "Accurate", tick,
                        action_results=["INTERACT_OBJECT(Bank booth/Bank) -> SUCCESS", "BANK_DEPOSIT_ALL -> SUCCESS", f"BANK_WITHDRAW({tool_name}/1) -> SUCCESS", "BANK_CLOSE -> SUCCESS"],
                        session_notes="\n".join(notes),
                    )
                    chain_examples.append(make_example(pick_prompt(task), state,
                        f"Banked fish. Heading back to {loc['name']}.\n{json.dumps(actions_list)}"))
        return chain_examples

    examples.extend(_mining_chain())
    examples.extend(_woodcutting_chain())
    examples.extend(_combat_chain())
    examples.extend(_power_mining_chain())
    examples.extend(_cooking_chain())
    examples.extend(_smithing_chain())
    examples.extend(_fishing_chain())
    return examples


def gen_session_notes_scenarios():
    """Generate session notes edge case examples (~200 examples)."""
    examples = []

    # 1. Avoid repeated failures (~50): notes show [FAILURES] -> try different approach
    for _ in range(50):
        ore_data = random.choice(MINING_DATA[:3])
        loc = random.choice(ore_data["locations"])
        cx, cy = random.choice(loc["coords"])
        region = loc["region"]
        level = random.randint(ore_data["level"], ore_data["level"] + 15)
        skills = make_default_skills()
        skills["Mine"] = level
        xp = {"Mine": random_xp_at_level(level)}
        tick = random_tick()
        name = random_player()
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        tool = "Bronze pickaxe" if level < 6 else "Iron pickaxe"
        inv = [(tool, 1), (ore_data["item"], random.randint(3, 15))]
        used = sum(q for _, q in inv)

        # Build notes showing recent failure
        failure_actions = [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
        notes_lines = [
            compress_to_note(cx-2, cy+1, "IDLE", used-2, [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
            compress_to_note(cx, cy, "IDLE", used-1, [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
            compress_to_note(cx, cy, "IDLE", used, failure_actions, failed=True),
        ]
        session_notes = "\n".join(notes_lines)

        # Second ore available nearby
        other_ore = ore_data["ore"]
        nearby_obj = [
            nearby_entity("Rocks", cx, cy, 2, ["Mine"]),
            nearby_entity(other_ore, cx + 5, cy + 3, 5, ["Mine"]),
        ]

        task = f"Mine {ore_data['item'].lower()}."
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(70, 100), True,
            random.randint(3, 10), 100, jitter(cx), jitter(cy), 0,
            "IDLE", skills, inv, [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal=f"Mine {ore_data['item'].lower()}",
            action_results=[f"INTERACT_OBJECT({ore_data['ore']}/Mine) -> FAILED: Could not find object"],
            game_messages=["There is no ore currently available in this rock."],
            session_notes=session_notes,
        )
        reasoning = f"Session notes show I just failed mining this rock. The ore is depleted. Switching to the other {other_ore.lower()} nearby."
        actions = json.dumps([{"action": "INTERACT_OBJECT", "name": other_ore, "option": "Mine"}, {"action": "WAIT_ANIMATION"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # 2. Remember where you came from (~40): notes show bank trip -> return to skilling
    for _ in range(40):
        skill_type = random.choice(["mining", "woodcutting", "fishing"])
        if skill_type == "mining":
            data = random.choice(MINING_DATA[:3])
            loc = random.choice(data["locations"])
            skill_loc_name = loc["name"]
            cx, cy = random.choice(loc["coords"])
            region = loc["region"]
            ore_name = data["ore"]
            item_name = data["item"]
            tool = "Bronze pickaxe"
            skill_key = "Mine"
            skill_action = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
            nearby_skill = [nearby_entity(ore_name, cx, cy, 3, ["Mine"])]
        elif skill_type == "woodcutting":
            data = random.choice(WOODCUTTING_DATA[:3])
            loc = random.choice(data["locations"])
            skill_loc_name = loc["name"]
            cx, cy = random.choice(loc["coords"])
            region = loc["region"]
            ore_name = data["tree"]
            item_name = data["item"]
            tool = "Bronze axe"
            skill_key = "WC"
            skill_action = [{"action": "INTERACT_OBJECT", "name": ore_name, "option": "Chop down"}, {"action": "WAIT_ANIMATION"}]
            nearby_skill = [nearby_entity(ore_name, cx, cy, 3, ["Chop down"])]
        else:
            data = random.choice(FISHING_DATA[:2])
            loc = random.choice(data["locations"])
            skill_loc_name = loc["name"]
            cx, cy = random.choice(loc["coords"])
            region = loc["region"]
            ore_name = "Fishing spot"
            item_name = data["fish"][0]
            tool = data["tool"]
            skill_key = "Fish"
            method = data["method"]
            skill_action = [{"action": "INTERACT_NPC", "name": "Fishing spot", "option": method}, {"action": "WAIT_ANIMATION"}]
            nearby_skill = []

        bank = pick_bank_near(region)
        bx, by, bp = bank["coords"][0], bank["coords"][1], bank["plane"]
        level = random.randint(1, 40)
        skills = make_default_skills()
        skills[skill_key] = level
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        tick = random_tick()
        name = random_player()

        # Notes show: was skilling -> full inv -> walked to bank -> deposited -> now needs to go back
        notes_lines = [
            compress_to_note(cx, cy, "IDLE", 15, skill_action),
            compress_to_note(cx, cy, "IDLE", 28, [{"action": "PATH_TO", "x": bx, "y": by}]),
            compress_to_note(bx, by, "IDLE", 28, [{"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"}, {"action": "BANK_DEPOSIT_ALL"}, {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1}, {"action": "BANK_CLOSE"}]),
        ]

        inv = [(tool, 1)]
        task = f"Gather {item_name.lower()} at {skill_loc_name}."
        actions_list = [{"action": "PATH_TO", "x": cx, "y": cy, "plane": 0, "goal": f"Return to {skill_loc_name}"}]
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
            random.randint(2, 8), 100, jitter(bx, 2), jitter(by, 2), bp,
            "IDLE", skills, inv, [], [], [nearby_entity("Bank booth", bx, by, 2, ["Bank"])], [], [],
            bank["region"], random_world(), "Inventory", "Accurate", tick,
            action_results=["BANK_DEPOSIT_ALL -> SUCCESS", f"BANK_WITHDRAW({tool}/1) -> SUCCESS", "BANK_CLOSE -> SUCCESS"],
            session_notes="\n".join(notes_lines),
        )
        reasoning = f"Session notes show I was gathering at {skill_loc_name} and just banked. Heading back to continue."
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{json.dumps(actions_list)}"))

    # 3. Long session notes (~30): 15-20 lines of notes
    for _ in range(30):
        level = random.randint(15, 60)
        skills = make_default_skills()
        skills["Mine"] = level
        xp = {"Mine": random_xp_at_level(level)}
        tick = random_tick()
        name = random_player()
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        ore_data = random.choice(MINING_DATA[:3])
        loc = random.choice(ore_data["locations"])
        cx, cy = random.choice(loc["coords"])
        region = loc["region"]
        bank = pick_bank_near(region)
        bx, by = bank["coords"]
        tool = "Iron pickaxe" if level >= 6 else "Bronze pickaxe"

        # Build long session notes (simulating many cycles)
        notes_lines = []
        for cycle in range(random.randint(2, 3)):
            for inv_step in [5, 12, 20, 28]:
                act = [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
                notes_lines.append(compress_to_note(cx + random.randint(-2, 2), cy + random.randint(-2, 2), "IDLE", inv_step, act))
            notes_lines.append(compress_to_note(cx, cy, "IDLE", 28, [{"action": "PATH_TO", "x": bx, "y": by}]))
            notes_lines.append(compress_to_note(bx, by, "IDLE", 28, [{"action": "BANK_DEPOSIT_ALL"}, {"action": "BANK_CLOSE"}]))
            notes_lines.append(compress_to_note(bx, by, "IDLE", 1, [{"action": "PATH_TO", "x": cx, "y": cy}]))

        inv_count = random.randint(10, 25)
        inv = [(tool, 1), (ore_data["item"], inv_count)]
        used = inv_count + 1
        nearby_obj = [nearby_entity(ore_data["ore"], cx, cy, random.randint(1, 4), ["Mine"])]

        task = f"Mine {ore_data['item'].lower()} at {loc['name']}."
        actions_list = [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]
        state = serialize_game_state(
            name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
            random.randint(5, 20), 100, jitter(cx), jitter(cy), 0,
            "IDLE", skills, inv, [], [], nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            xp_dict=xp, current_goal=f"Mine {ore_data['item'].lower()}",
            session_notes="\n".join(notes_lines),
        )
        reasoning = f"Long session, multiple bank trips completed. {used}/28 inventory. Continuing to mine."
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{json.dumps(actions_list)}"))

    # 4. Interrupted by combat (~40): notes show skilling then combat interrupt
    for _ in range(40):
        ore_data = random.choice(MINING_DATA[:3])
        loc = random.choice(ore_data["locations"])
        cx, cy = random.choice(loc["coords"])
        region = loc["region"]
        level = random.randint(1, 30)
        skills = make_default_skills()
        skills["Mine"] = level
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        tick = random_tick()
        name = random_player()
        tool = "Bronze pickaxe"
        attacker = random.choice(["Goblin", "Giant rat", "Mugger"])
        atk_level = random.randint(1, 5)

        notes_lines = [
            compress_to_note(cx, cy, "IDLE", 5, [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
            compress_to_note(cx, cy, "IDLE", 10, [{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
        ]

        max_hp = 10
        current_hp = random.randint(4, 9)
        food_count = random.randint(0, 5)
        inv = [(tool, 1), (ore_data["item"], random.randint(5, 15))]
        if food_count > 0:
            inv.append(("Trout", food_count))
        used = sum(q for _, q in inv)

        nearby_npc = [nearby_entity(attacker, jitter(cx), jitter(cy), 1, ["Attack"], level=atk_level)]
        nearby_obj = [nearby_entity(ore_data["ore"], cx, cy, 2, ["Mine"])]

        task = f"Mine {ore_data['item'].lower()}."
        state = serialize_game_state(
            name, cb, current_hp, max_hp, 1, 1, random.randint(60, 100), True,
            random.randint(3, 10), 100, jitter(cx), jitter(cy), 0,
            "IN_COMBAT", skills, inv, [], nearby_npc, nearby_obj, [], [],
            region, random_world(), "Inventory", "Accurate", tick,
            current_goal=f"Mine {ore_data['item'].lower()}",
            interacting_with=attacker, under_attack=[f"{attacker}(lvl:{atk_level})"],
            session_notes="\n".join(notes_lines),
        )
        if food_count > 0 and current_hp < max_hp // 2:
            reasoning = f"Was mining but got attacked by {attacker.lower()}! HP low at {current_hp}/{max_hp}. Eating food first."
            actions = json.dumps([{"action": "EAT_FOOD", "name": "Trout"}, {"action": "WAIT_ANIMATION", "ticks": 10}])
        else:
            reasoning = f"Attacked by {attacker.lower()} while mining. Low level enemy, ignoring it and continuing to mine."
            actions = json.dumps([{"action": "INTERACT_OBJECT", "name": ore_data["ore"], "option": "Mine"}, {"action": "WAIT_ANIMATION"}])
        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{actions}"))

    # 5. Goal continuity (~40): notes show progression -> continue next step
    for _ in range(40):
        scenario = random.choice(["smelt_after_mine", "cook_after_fish", "fletch_after_chop"])
        tick = random_tick()
        name = random_player()
        cb = combat_level_for_skills(1, 1, 1, 10, 1, 1, 1)
        skills = make_default_skills()

        if scenario == "smelt_after_mine":
            skills["Mine"] = random.randint(15, 40)
            skills["Smith"] = random.randint(15, 40)
            notes_lines = [
                compress_to_note(3300, 3300, "IDLE", 5, [{"action": "INTERACT_OBJECT", "name": "Iron rocks", "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
                compress_to_note(3300, 3300, "IDLE", 15, [{"action": "INTERACT_OBJECT", "name": "Iron rocks", "option": "Mine"}, {"action": "WAIT_ANIMATION"}]),
                compress_to_note(3300, 3300, "IDLE", 28, [{"action": "PATH_TO", "x": 3275, "y": 3186}]),
            ]
            inv = [("Iron ore", 28)]
            nearby_obj = [nearby_entity("Furnace", 3275, 3185, 2, ["Smelt"])]
            task = "Mine iron ore and smelt into bars."
            actions_list = [{"action": "USE_ITEM_ON_OBJECT", "item": "Iron ore", "object": "Furnace", "goal": "Smelt iron bars"}]
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                random.randint(10, 25), 100, jitter(3275), jitter(3186), 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                13104, random_world(), "Inventory", "Accurate", tick,
                action_results=["PATH_TO(3275,3186,0) -> SUCCESS: Arrived at destination."],
                session_notes="\n".join(notes_lines),
            )
            reasoning = "Session notes show I mined a full inventory of iron ore and walked to the furnace. Time to smelt."
        elif scenario == "cook_after_fish":
            skills["Fish"] = random.randint(20, 50)
            skills["Cook"] = random.randint(15, 50)
            notes_lines = [
                compress_to_note(3110, 3434, "IDLE", 10, [{"action": "INTERACT_NPC", "name": "Fishing spot", "option": "Lure"}, {"action": "WAIT_ANIMATION"}]),
                compress_to_note(3110, 3434, "IDLE", 28, [{"action": "PATH_TO", "x": 3211, "y": 3215}]),
            ]
            inv = [("Raw trout", 28)]
            nearby_obj = [nearby_entity("Cooking range", 3211, 3215, 2, ["Cook"])]
            task = "Fish trout and cook them."
            actions_list = [{"action": "USE_ITEM_ON_OBJECT", "item": "Raw trout", "object": "Cooking range", "goal": "Cook trout"}]
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(60, 100), True,
                random.randint(5, 15), 100, jitter(3211), jitter(3215), 0,
                "IDLE", skills, inv, [], [], nearby_obj, [], [],
                12850, random_world(), "Inventory", "Accurate", tick,
                action_results=["PATH_TO(3211,3215,0) -> SUCCESS: Arrived at destination."],
                session_notes="\n".join(notes_lines),
            )
            reasoning = "Fished a full inventory and walked to the range. Now cooking the trout."
        else:  # fletch_after_chop
            skills["WC"] = random.randint(15, 40)
            skills["Fletch"] = random.randint(5, 30)
            notes_lines = [
                compress_to_note(3167, 3416, "IDLE", 10, [{"action": "INTERACT_OBJECT", "name": "Oak tree", "option": "Chop down"}, {"action": "WAIT_ANIMATION"}]),
                compress_to_note(3167, 3416, "IDLE", 27, [{"action": "INTERACT_OBJECT", "name": "Oak tree", "option": "Chop down"}, {"action": "WAIT_ANIMATION"}]),
            ]
            inv = [("Knife", 1), ("Oak logs", 27)]
            task = "Chop oaks and fletch them."
            actions_list = [{"action": "USE_ITEM_ON_ITEM", "item1": "Knife", "item2": "Oak logs", "goal": "Fletch oak logs"}]
            state = serialize_game_state(
                name, cb, 10, 10, 1, 1, random.randint(80, 100), True,
                random.randint(1, 5), 100, jitter(3167), jitter(3416), 0,
                "IDLE", skills, inv, [], [], [], [], [],
                12342, random_world(), "Inventory", "Accurate", tick,
                session_notes="\n".join(notes_lines),
            )
            reasoning = "Chopped a full inventory of oak logs. Now fletching them with knife."

        examples.append(make_example(pick_prompt(task), state, f"{reasoning}\n{json.dumps(actions_list)}"))

    return examples


def gen_quest_scenarios():
    """Generate F2P quest walkthrough examples (~400 examples)."""
    examples = []

    # Quest data: each quest has steps that generate connected examples with session notes
    QUESTS = {
        "Cook's Assistant": {
            "task": "Complete Cook's Assistant quest.",
            "steps": [
                {"pos": (3208, 3214, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Cook", "x": 3208, "y": 3214, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [], "dialogue": None,
                 "actions": [{"action": "INTERACT_NPC", "name": "Cook", "option": "Talk-to", "goal": "Start Cook's Assistant quest"}],
                 "reasoning": "Starting Cook's Assistant. Talking to the Cook in Lumbridge castle."},
                {"pos": (3208, 3214, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Cook", "x": 3208, "y": 3214, "dist": 1, "actions": ["Talk-to"]}],
                 "nearby_objs": [], "dialogue": "npc_continue", "dialogue_speaker": "Cook",
                 "dialogue_text": "I need an egg, a bucket of milk, and a pot of flour.",
                 "actions": [{"action": "CONTINUE_DIALOGUE"}],
                 "reasoning": "Cook needs an egg, milk, and flour. Continuing dialogue."},
                {"pos": (3208, 3214, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [], "nearby_objs": [], "dialogue": "options",
                 "dialogue_options": ["1: I'll get right on it.", "2: Actually, I know where to find some."],
                 "actions": [{"action": "SELECT_DIALOGUE", "option": "1"}],
                 "reasoning": "Accepting the quest. Selecting option 1."},
                {"pos": (3235, 3295, 0), "region": 12851, "status": "IDLE",
                 "inv": [("Bucket", 1), ("Pot", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "nearby_ground": [{"name": "Egg", "x": 3236, "y": 3295, "dist": 1, "actions": ["Take"]}],
                 "actions": [{"action": "PICKUP_ITEM", "name": "Egg", "goal": "Collect quest items: egg, milk, flour"}],
                 "reasoning": "At the chicken farm east of Lumbridge. Picking up the egg."},
                {"pos": (3253, 3270, 0), "region": 12851, "status": "IDLE",
                 "inv": [("Bucket", 1), ("Pot", 1), ("Egg", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Dairy cow", "x": 3253, "y": 3271, "dist": 2, "actions": ["Milk"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Dairy cow", "option": "Milk"}],
                 "reasoning": "Got the egg. Now milking the dairy cow for a bucket of milk."},
                {"pos": (3166, 3303, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Pot of flour", 1), ("Bucket of milk", 1), ("Egg", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 3208, "y": 3214, "plane": 0, "goal": "Return to Cook with all items"}],
                 "reasoning": "Got all three items. Walking back to the Cook in Lumbridge."},
                {"pos": (3208, 3214, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Pot of flour", 1), ("Bucket of milk", 1), ("Egg", 1)],
                 "nearby_npcs": [{"name": "Cook", "x": 3208, "y": 3214, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Cook", "option": "Talk-to"}],
                 "reasoning": "Back at the Cook with all ingredients. Delivering them."},
            ],
        },
        "Sheep Shearer": {
            "task": "Complete Sheep Shearer quest.",
            "steps": [
                {"pos": (3190, 3272, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Fred the Farmer", "x": 3190, "y": 3273, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Fred the Farmer", "option": "Talk-to", "goal": "Start Sheep Shearer quest"}],
                 "reasoning": "Talking to Fred the Farmer to start Sheep Shearer."},
                {"pos": (3190, 3272, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [], "nearby_objs": [], "dialogue": "npc_continue", "dialogue_speaker": "Fred the Farmer",
                 "dialogue_text": "I need 20 balls of wool. Can you help?",
                 "actions": [{"action": "CONTINUE_DIALOGUE"}],
                 "reasoning": "Fred needs 20 balls of wool. Continuing."},
                {"pos": (3200, 3268, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Shears", 1)],
                 "nearby_npcs": [{"name": "Sheep", "x": 3201, "y": 3268, "dist": 2, "actions": ["Shear"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Sheep", "option": "Shear", "goal": "Shear 20 sheep for wool"}, {"action": "WAIT_ANIMATION"}],
                 "reasoning": "Shearing sheep to collect wool."},
                {"pos": (3209, 3213, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Wool", 20)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Spinning wheel", "x": 3209, "y": 3213, "dist": 2, "actions": ["Spin"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Spinning wheel", "option": "Spin", "goal": "Spin wool into balls of wool"}],
                 "reasoning": "Got 20 wool. Using spinning wheel to make balls of wool."},
                {"pos": (3190, 3272, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Ball of wool", 20)],
                 "nearby_npcs": [{"name": "Fred the Farmer", "x": 3190, "y": 3273, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Fred the Farmer", "option": "Talk-to"}],
                 "reasoning": "Got 20 balls of wool. Delivering to Fred."},
            ],
        },
        "Romeo & Juliet": {
            "task": "Complete Romeo & Juliet quest.",
            "steps": [
                {"pos": (3211, 3423, 0), "region": 12853, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Romeo", "x": 3211, "y": 3423, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Romeo", "option": "Talk-to", "goal": "Start Romeo & Juliet quest"}],
                 "reasoning": "Talking to Romeo in Varrock square."},
                {"pos": (3159, 3427, 0), "region": 12342, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Juliet", "x": 3159, "y": 3427, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Juliet", "option": "Talk-to", "goal": "Talk to Juliet"}],
                 "reasoning": "Romeo wants me to talk to Juliet. She's in the house west of Varrock."},
                {"pos": (3253, 3401, 0), "region": 12854, "status": "IDLE", "inv": [("Message", 1)],
                 "nearby_npcs": [{"name": "Father Lawrence", "x": 3253, "y": 3401, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Father Lawrence", "option": "Talk-to", "goal": "Talk to Father Lawrence"}],
                 "reasoning": "Juliet gave me a message. Taking it to Father Lawrence."},
                {"pos": (3270, 3369, 0), "region": 12854, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Apothecary", "x": 3196, "y": 3404, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Apothecary", "option": "Talk-to", "goal": "Get cadava potion from Apothecary"}],
                 "reasoning": "Father Lawrence says to see the Apothecary. Talking to him."},
                {"pos": (3268, 3372, 0), "region": 12854, "status": "IDLE", "inv": [("Cadava berries", 1)],
                 "nearby_npcs": [{"name": "Apothecary", "x": 3196, "y": 3404, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Apothecary", "option": "Talk-to"}],
                 "reasoning": "Picked cadava berries. Bringing them to the Apothecary for the potion."},
                {"pos": (3159, 3427, 0), "region": 12342, "status": "IDLE", "inv": [("Cadava potion", 1)],
                 "nearby_npcs": [{"name": "Juliet", "x": 3159, "y": 3427, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Juliet", "option": "Talk-to"}],
                 "reasoning": "Got the cadava potion. Delivering it to Juliet."},
                {"pos": (3211, 3423, 0), "region": 12853, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Romeo", "x": 3211, "y": 3423, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Romeo", "option": "Talk-to"}],
                 "reasoning": "Juliet took the potion. Returning to Romeo to finish the quest."},
            ],
        },
        "Doric's Quest": {
            "task": "Complete Doric's Quest.",
            "steps": [
                {"pos": (2951, 3451, 0), "region": 11061, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Doric", "x": 2951, "y": 3451, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Doric", "option": "Talk-to", "goal": "Start Doric's Quest"}],
                 "reasoning": "Talking to Doric north of Falador."},
                {"pos": (2951, 3451, 0), "region": 11061, "status": "IDLE", "inv": [],
                 "nearby_npcs": [], "nearby_objs": [], "dialogue": "npc_continue", "dialogue_speaker": "Doric",
                 "dialogue_text": "I need 6 clay, 4 copper ore, and 2 iron ore.",
                 "actions": [{"action": "CONTINUE_DIALOGUE"}],
                 "reasoning": "Doric needs 6 clay, 4 copper ore, 2 iron ore. Continuing."},
                {"pos": (3185, 3436, 0), "region": 12342, "status": "IDLE",
                 "inv": [("Clay", 6), ("Copper ore", 4), ("Iron ore", 2)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 2951, "y": 3451, "plane": 0, "goal": "Deliver ores to Doric"}],
                 "reasoning": "Got all the ores from the bank. Walking back to Doric."},
                {"pos": (2951, 3451, 0), "region": 11061, "status": "IDLE",
                 "inv": [("Clay", 6), ("Copper ore", 4), ("Iron ore", 2)],
                 "nearby_npcs": [{"name": "Doric", "x": 2951, "y": 3451, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Doric", "option": "Talk-to"}],
                 "reasoning": "At Doric with all the materials. Delivering them."},
            ],
        },
        "Restless Ghost": {
            "task": "Complete The Restless Ghost quest.",
            "steps": [
                {"pos": (3240, 3210, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Father Aereck", "x": 3240, "y": 3210, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Father Aereck", "option": "Talk-to", "goal": "Start Restless Ghost quest"}],
                 "reasoning": "Talking to Father Aereck in Lumbridge church."},
                {"pos": (3145, 3175, 0), "region": 12593, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Father Urhney", "x": 3145, "y": 3175, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Father Urhney", "option": "Talk-to", "goal": "Get ghostspeak amulet"}],
                 "reasoning": "Father Aereck told me to visit Father Urhney in the swamp."},
                {"pos": (3250, 3193, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Ghostspeak amulet", 1)],
                 "nearby_npcs": [{"name": "Restless ghost", "x": 3250, "y": 3193, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "equipment": [("Amulet", "Ghostspeak amulet")],
                 "actions": [{"action": "INTERACT_NPC", "name": "Restless ghost", "option": "Talk-to", "goal": "Talk to the ghost"}],
                 "reasoning": "Wearing the ghostspeak amulet. Talking to the restless ghost."},
                {"pos": (3120, 9565, 0), "region": 12182, "status": "IDLE",
                 "inv": [("Ghostspeak amulet", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Coffin", "x": 3120, "y": 9565, "dist": 2, "actions": ["Search"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Coffin", "option": "Search", "goal": "Find the ghost's skull"}],
                 "reasoning": "The ghost told me its skull is in Wizard Tower basement. Searching the coffin."},
                {"pos": (3250, 3193, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Ghost's skull", 1), ("Ghostspeak amulet", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Coffin", "x": 3250, "y": 3193, "dist": 2, "actions": ["Open", "Search"]}],
                 "actions": [{"action": "USE_ITEM_ON_OBJECT", "item": "Ghost's skull", "object": "Coffin"}],
                 "reasoning": "Found the skull. Returning it to the ghost's coffin in Lumbridge."},
            ],
        },
        "Imp Catcher": {
            "task": "Complete Imp Catcher quest.",
            "steps": [
                {"pos": (3108, 3162, 0), "region": 12340, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Wizard Mizgog", "x": 3108, "y": 3162, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Wizard Mizgog", "option": "Talk-to", "goal": "Start Imp Catcher quest"}],
                 "reasoning": "Talking to Wizard Mizgog in the Wizard Tower."},
                {"pos": (3200, 3200, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Red bead", 1), ("Yellow bead", 1)],
                 "nearby_npcs": [{"name": "Imp", "x": 3201, "y": 3201, "dist": 3, "actions": ["Attack"], "level": 2}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Imp", "option": "Attack", "goal": "Kill imps for beads"}, {"action": "WAIT_ANIMATION", "ticks": 15}],
                 "reasoning": "Need black and white beads still. Killing imps to get them."},
                {"pos": (3108, 3162, 0), "region": 12340, "status": "IDLE",
                 "inv": [("Red bead", 1), ("Yellow bead", 1), ("Black bead", 1), ("White bead", 1)],
                 "nearby_npcs": [{"name": "Wizard Mizgog", "x": 3108, "y": 3162, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Wizard Mizgog", "option": "Talk-to"}],
                 "reasoning": "Got all four beads. Delivering to Wizard Mizgog."},
            ],
        },
        "Witch's Potion": {
            "task": "Complete Witch's Potion quest.",
            "steps": [
                {"pos": (2967, 3205, 0), "region": 11826, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Hetty", "x": 2967, "y": 3205, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Hetty", "option": "Talk-to", "goal": "Start Witch's Potion quest"}],
                 "reasoning": "Talking to Hetty in Rimmington."},
                {"pos": (3200, 3200, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Eye of newt", 1), ("Onion", 1), ("Cooked meat", 1)],
                 "nearby_npcs": [{"name": "Rat", "x": 3201, "y": 3200, "dist": 2, "actions": ["Attack"], "level": 1}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Rat", "option": "Attack", "goal": "Kill a rat for its tail"}, {"action": "WAIT_ANIMATION", "ticks": 10}],
                 "reasoning": "Need a rat's tail. Killing a rat."},
                {"pos": (2967, 3205, 0), "region": 11826, "status": "IDLE",
                 "inv": [("Eye of newt", 1), ("Onion", 1), ("Cooked meat", 1), ("Rat's tail", 1)],
                 "nearby_npcs": [{"name": "Hetty", "x": 2967, "y": 3205, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Hetty", "option": "Talk-to"}],
                 "reasoning": "Got all ingredients. Returning to Hetty."},
                {"pos": (2967, 3205, 0), "region": 11826, "status": "IDLE", "inv": [],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Cauldron", "x": 2967, "y": 3205, "dist": 1, "actions": ["Drink from"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Cauldron", "option": "Drink from"}],
                 "reasoning": "Hetty made the potion. Drinking from the cauldron to complete the quest."},
            ],
        },
        "Rune Mysteries": {
            "task": "Complete Rune Mysteries quest.",
            "steps": [
                {"pos": (3210, 3220, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Duke Horacio", "x": 3210, "y": 3220, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Duke Horacio", "option": "Talk-to", "goal": "Start Rune Mysteries quest"}],
                 "reasoning": "Talking to Duke Horacio in Lumbridge castle."},
                {"pos": (3210, 3220, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Air talisman", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 3108, "y": 3162, "plane": 0, "goal": "Deliver talisman to Sedridor"}],
                 "reasoning": "Duke gave me an air talisman. Taking it to Sedridor at the Wizard Tower."},
                {"pos": (3108, 3162, 0), "region": 12340, "status": "IDLE",
                 "inv": [("Air talisman", 1)],
                 "nearby_npcs": [{"name": "Sedridor", "x": 3108, "y": 3162, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Sedridor", "option": "Talk-to"}],
                 "reasoning": "At the Wizard Tower. Delivering the talisman to Sedridor."},
                {"pos": (3253, 3401, 0), "region": 12854, "status": "IDLE",
                 "inv": [("Research package", 1)],
                 "nearby_npcs": [{"name": "Aubury", "x": 3253, "y": 3401, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Aubury", "option": "Talk-to", "goal": "Deliver package to Aubury"}],
                 "reasoning": "Sedridor gave me a research package. Delivering to Aubury in Varrock."},
                {"pos": (3108, 3162, 0), "region": 12340, "status": "IDLE",
                 "inv": [("Notes", 1)],
                 "nearby_npcs": [{"name": "Sedridor", "x": 3108, "y": 3162, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Sedridor", "option": "Talk-to"}],
                 "reasoning": "Aubury gave me notes. Returning to Sedridor to complete the quest."},
            ],
        },
        "Vampire Slayer": {
            "task": "Complete Vampire Slayer quest.",
            "steps": [
                {"pos": (3096, 3266, 0), "region": 12593, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Morgan", "x": 3096, "y": 3266, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Morgan", "option": "Talk-to", "goal": "Start Vampire Slayer quest"}],
                 "reasoning": "Talking to Morgan in Draynor Village."},
                {"pos": (3100, 3268, 0), "region": 12593, "status": "IDLE",
                 "inv": [],
                 "nearby_npcs": [], "nearby_objs": [],
                 "nearby_ground": [{"name": "Garlic", "x": 3100, "y": 3268, "dist": 1, "actions": ["Take"]}],
                 "actions": [{"action": "PICKUP_ITEM", "name": "Garlic", "goal": "Get garlic for the vampire fight"}],
                 "reasoning": "Need garlic from the cupboard upstairs in Morgan's house."},
                {"pos": (3204, 3472, 0), "region": 12853, "status": "IDLE",
                 "inv": [("Garlic", 1)],
                 "nearby_npcs": [{"name": "Dr Harlow", "x": 3204, "y": 3472, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Dr Harlow", "option": "Talk-to", "goal": "Get stake from Dr Harlow"}],
                 "reasoning": "Going to Dr Harlow in the Blue Moon Inn to get a stake."},
                {"pos": (3077, 3249, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Garlic", 1), ("Stake", 1), ("Hammer", 1), ("Lobster", 10)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Coffin", "x": 3077, "y": 3249, "dist": 2, "actions": ["Open"]}],
                 "equipment": [("Weapon", "Steel scimitar")],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Coffin", "option": "Open", "goal": "Fight Count Draynor"}],
                 "reasoning": "In Draynor Manor basement with garlic, stake, and hammer. Opening the coffin to fight Count Draynor."},
                {"pos": (3077, 3249, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Garlic", 1), ("Stake", 1), ("Hammer", 1), ("Lobster", 8)],
                 "nearby_npcs": [{"name": "Count Draynor", "x": 3077, "y": 3249, "dist": 1, "actions": ["Attack"], "level": 34}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Count Draynor", "option": "Attack"}, {"action": "WAIT_ANIMATION", "ticks": 30}],
                 "reasoning": "Count Draynor appeared! Attacking him with the stake."},
            ],
        },
        "Goblin Diplomacy": {
            "task": "Complete Goblin Diplomacy quest.",
            "steps": [
                {"pos": (2957, 3512, 0), "region": 11062, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "General Wartface", "x": 2957, "y": 3512, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "General Wartface", "option": "Talk-to", "goal": "Start Goblin Diplomacy quest"}],
                 "reasoning": "Talking to General Wartface in Goblin Village."},
                {"pos": (2957, 3512, 0), "region": 11062, "status": "IDLE",
                 "inv": [("Orange goblin mail", 1), ("Blue goblin mail", 1), ("Goblin mail", 1)],
                 "nearby_npcs": [{"name": "General Wartface", "x": 2957, "y": 3512, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "General Wartface", "option": "Talk-to"}],
                 "reasoning": "Got all three goblin mails. Showing them to the generals."},
            ],
        },
        "Ernest the Chicken": {
            "task": "Complete Ernest the Chicken quest.",
            "steps": [
                {"pos": (3108, 3330, 0), "region": 12593, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Veronica", "x": 3108, "y": 3330, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Veronica", "option": "Talk-to", "goal": "Start Ernest the Chicken quest"}],
                 "reasoning": "Talking to Veronica outside Draynor Manor."},
                {"pos": (3109, 3361, 0), "region": 12593, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Professor Oddenstein", "x": 3109, "y": 3361, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Professor Oddenstein", "option": "Talk-to", "goal": "Talk to Professor Oddenstein"}],
                 "reasoning": "Going upstairs to talk to Professor Oddenstein in Draynor Manor."},
                {"pos": (3108, 3350, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Oil can", 1), ("Pressure gauge", 1), ("Rubber tube", 1)],
                 "nearby_npcs": [{"name": "Professor Oddenstein", "x": 3109, "y": 3361, "dist": 5, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Professor Oddenstein", "option": "Talk-to"}],
                 "reasoning": "Found all three items. Returning them to the Professor."},
            ],
        },
        "Demon Slayer": {
            "task": "Complete Demon Slayer quest.",
            "steps": [
                {"pos": (3204, 3424, 0), "region": 12853, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Gypsy Aris", "x": 3204, "y": 3424, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Gypsy Aris", "option": "Talk-to", "goal": "Start Demon Slayer quest"}],
                 "reasoning": "Talking to Gypsy Aris in Varrock square."},
                {"pos": (3235, 3367, 0), "region": 12853, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Sir Prysin", "x": 3204, "y": 3472, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Sir Prysin", "option": "Talk-to", "goal": "Get Silverlight keys from Sir Prysin"}],
                 "reasoning": "Gypsy says I need Silverlight. Finding Sir Prysin in Varrock palace."},
                {"pos": (3235, 3383, 0), "region": 12853, "status": "IDLE",
                 "inv": [("Silverlight", 1), ("Lobster", 15)],
                 "nearby_npcs": [{"name": "Delrith", "x": 3227, "y": 3370, "dist": 5, "actions": ["Attack"], "level": 27}],
                 "nearby_objs": [],
                 "equipment": [("Weapon", "Silverlight")],
                 "actions": [{"action": "INTERACT_NPC", "name": "Delrith", "option": "Attack", "goal": "Kill Delrith"}, {"action": "WAIT_ANIMATION", "ticks": 30}],
                 "reasoning": "Got Silverlight equipped. Time to kill Delrith at the stone circle."},
            ],
        },
        "X Marks the Spot": {
            "task": "Complete X Marks the Spot quest.",
            "steps": [
                {"pos": (3227, 3242, 0), "region": 12850, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Veos", "x": 3227, "y": 3242, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Veos", "option": "Talk-to", "goal": "Start X Marks the Spot quest"}],
                 "reasoning": "Talking to Veos in Lumbridge to start X Marks the Spot."},
                {"pos": (3230, 3209, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Spade", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Spade", "option": "Dig", "goal": "Dig at first clue location"}],
                 "reasoning": "First clue: dig near the Lumbridge Guide. Using spade."},
                {"pos": (3203, 3212, 0), "region": 12850, "status": "IDLE",
                 "inv": [("Spade", 1), ("Treasure scroll", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 3109, "y": 3264, "plane": 0, "goal": "Go to next clue location in Draynor"}],
                 "reasoning": "Found a clue scroll. Following it to the next dig spot."},
                {"pos": (3078, 3260, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Spade", 1), ("Ancient casket", 1)],
                 "nearby_npcs": [{"name": "Veos", "x": 3228, "y": 3242, "dist": 50, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 3228, "y": 3242, "plane": 0, "goal": "Return casket to Veos"}],
                 "reasoning": "Found the ancient casket. Returning to Veos in Lumbridge."},
            ],
        },
        "Pirate's Treasure": {
            "task": "Complete Pirate's Treasure quest.",
            "steps": [
                {"pos": (3050, 3253, 0), "region": 11570, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Redbeard Frank", "x": 3050, "y": 3253, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Redbeard Frank", "option": "Talk-to", "goal": "Start Pirate's Treasure quest"}],
                 "reasoning": "Talking to Redbeard Frank at Port Sarim."},
                {"pos": (2926, 3143, 0), "region": 10804, "status": "IDLE",
                 "inv": [("Karamjan rum", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Crate", "x": 2926, "y": 3143, "dist": 2, "actions": ["Search", "Fill"]}],
                 "actions": [{"action": "USE_ITEM_ON_OBJECT", "item": "Karamjan rum", "object": "Crate", "goal": "Smuggle rum in the crate"}],
                 "reasoning": "Hiding the rum in the crate to smuggle it off Karamja."},
                {"pos": (3050, 3253, 0), "region": 11570, "status": "IDLE",
                 "inv": [("Pirate message", 1), ("Spade", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 2999, "y": 3383, "plane": 0, "goal": "Dig for treasure in Falador park"}],
                 "reasoning": "Got the pirate message. Heading to Falador park to dig for treasure."},
            ],
        },
        "The Knight's Sword": {
            "task": "Complete The Knight's Sword quest.",
            "steps": [
                {"pos": (2978, 3342, 0), "region": 11828, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Squire", "x": 2978, "y": 3342, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Squire", "option": "Talk-to", "goal": "Start The Knight's Sword quest"}],
                 "reasoning": "Talking to the Squire in Falador castle."},
                {"pos": (3001, 3144, 0), "region": 11570, "status": "IDLE",
                 "inv": [("Redberry pie", 1)],
                 "nearby_npcs": [{"name": "Thurgo", "x": 3001, "y": 3144, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Thurgo", "option": "Talk-to", "goal": "Give Thurgo the pie and ask about the sword"}],
                 "reasoning": "At Thurgo's hut with a redberry pie. He loves pies!"},
                {"pos": (3019, 3145, 0), "region": 11570, "status": "IDLE",
                 "inv": [("Iron bar", 2), ("Blurite ore", 1)],
                 "nearby_npcs": [{"name": "Thurgo", "x": 3001, "y": 3144, "dist": 5, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Thurgo", "option": "Talk-to"}],
                 "reasoning": "Got the iron bars and blurite ore. Giving them to Thurgo to forge the sword."},
                {"pos": (2978, 3342, 0), "region": 11828, "status": "IDLE",
                 "inv": [("Blurite sword", 1)],
                 "nearby_npcs": [{"name": "Squire", "x": 2978, "y": 3342, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Squire", "option": "Talk-to"}],
                 "reasoning": "Thurgo made the sword. Delivering it to the Squire."},
            ],
        },
        "Prince Ali Rescue": {
            "task": "Complete Prince Ali Rescue quest.",
            "steps": [
                {"pos": (3301, 3163, 0), "region": 13104, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Hassan", "x": 3301, "y": 3163, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Hassan", "option": "Talk-to", "goal": "Start Prince Ali Rescue quest"}],
                 "reasoning": "Talking to Hassan in Al Kharid palace."},
                {"pos": (3303, 3182, 0), "region": 13104, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Osman", "x": 3286, "y": 3180, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Osman", "option": "Talk-to", "goal": "Talk to Osman about the rescue plan"}],
                 "reasoning": "Hassan says to talk to Osman. Finding him."},
                {"pos": (3128, 3261, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Blonde wig", 1), ("Paste", 1), ("Pink skirt", 1), ("Rope", 1), ("Beer", 3)],
                 "nearby_npcs": [{"name": "Lady Keli", "x": 3128, "y": 3261, "dist": 3, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "USE_ITEM_ON_NPC", "item": "Rope", "npc": "Lady Keli", "goal": "Tie up Lady Keli"}],
                 "reasoning": "Got all disguise items. Tying up Lady Keli with the rope."},
                {"pos": (3124, 3261, 0), "region": 12593, "status": "IDLE",
                 "inv": [("Blonde wig", 1), ("Paste", 1), ("Pink skirt", 1), ("Bronze key", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Prison door", "x": 3124, "y": 3261, "dist": 1, "actions": ["Open"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Prison door", "option": "Open"}],
                 "reasoning": "Lady Keli tied up. Opening the prison door to free Prince Ali."},
            ],
        },
        "Shield of Arrav": {
            "task": "Complete Shield of Arrav quest.",
            "steps": [
                {"pos": (3209, 3491, 0), "region": 12853, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Charlie the Tramp", "x": 3209, "y": 3491, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Charlie the Tramp", "option": "Talk-to", "goal": "Start Shield of Arrav quest"}],
                 "reasoning": "Talking to Charlie the Tramp near the Varrock palace."},
                {"pos": (3185, 3385, 0), "region": 12342, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Katrine", "x": 3185, "y": 3385, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Katrine", "option": "Talk-to", "goal": "Join the Black Arm Gang"}],
                 "reasoning": "Joining the Black Arm Gang to get one half of the shield."},
                {"pos": (3185, 3385, 0), "region": 12342, "status": "IDLE",
                 "inv": [("Broken shield", 1)],
                 "nearby_npcs": [], "nearby_objs": [],
                 "actions": [{"action": "PATH_TO", "x": 3256, "y": 3381, "plane": 0, "goal": "Go to Varrock museum to exchange shield"}],
                 "reasoning": "Got the shield half. Going to the museum to exchange for a certificate."},
            ],
        },
        "Black Knights' Fortress": {
            "task": "Complete Black Knights' Fortress quest.",
            "steps": [
                {"pos": (2959, 3338, 0), "region": 11828, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Sir Amik Varze", "x": 2959, "y": 3338, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Sir Amik Varze", "option": "Talk-to", "goal": "Start Black Knights' Fortress quest"}],
                 "reasoning": "Talking to Sir Amik Varze in Falador castle."},
                {"pos": (3016, 3514, 0), "region": 11575, "status": "IDLE",
                 "inv": [("Iron chainbody", 1), ("Bronze med helm", 1), ("Cabbage", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Sturdy door", "x": 3016, "y": 3514, "dist": 2, "actions": ["Push"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Sturdy door", "option": "Push", "goal": "Infiltrate Black Knights' Fortress"}],
                 "reasoning": "At the fortress with disguise. Pushing through the door."},
                {"pos": (3030, 3510, 0), "region": 11575, "status": "IDLE",
                 "inv": [("Cabbage", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Hole", "x": 3030, "y": 3510, "dist": 1, "actions": ["Use"]}],
                 "actions": [{"action": "USE_ITEM_ON_OBJECT", "item": "Cabbage", "object": "Hole"}],
                 "reasoning": "Found the cauldron room. Using the cabbage on the hole to sabotage the potion."},
            ],
        },
        "Dragon Slayer I": {
            "task": "Complete Dragon Slayer I quest.",
            "steps": [
                {"pos": (3190, 3360, 0), "region": 12342, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Guildmaster", "x": 3190, "y": 3360, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Guildmaster", "option": "Talk-to", "goal": "Start Dragon Slayer I quest"}],
                 "reasoning": "Talking to the Guildmaster in Champions' Guild."},
                {"pos": (3190, 3360, 0), "region": 12342, "status": "IDLE", "inv": [],
                 "nearby_npcs": [], "nearby_objs": [], "dialogue": "npc_continue", "dialogue_speaker": "Guildmaster",
                 "dialogue_text": "You must prove yourself by slaying the dragon Elvarg on Crandor.",
                 "actions": [{"action": "CONTINUE_DIALOGUE"}],
                 "reasoning": "The Guildmaster wants me to slay Elvarg. Continuing."},
                {"pos": (3047, 3205, 0), "region": 11570, "status": "IDLE",
                 "inv": [("Map part 1", 1), ("Map part 2", 1), ("Map part 3", 1), ("Unfired bowl", 1), ("Wizard mind bomb", 1), ("Silk", 1), ("Lobster pot", 1)],
                 "nearby_npcs": [{"name": "Klarense", "x": 3047, "y": 3205, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Klarense", "option": "Talk-to", "goal": "Buy the ship Lady Lumbridge"}],
                 "reasoning": "Got all 3 map pieces. Buying the ship from Klarense at Port Sarim."},
                {"pos": (2851, 3237, 0), "region": 10804, "status": "IDLE",
                 "inv": [("Anti-dragon shield", 1), ("Lobster", 20), ("Strength potion(3)", 1)],
                 "nearby_npcs": [{"name": "Elvarg", "x": 2851, "y": 3237, "dist": 5, "actions": ["Attack"], "level": 83}],
                 "nearby_objs": [],
                 "equipment": [("Weapon", "Rune scimitar"), ("Shield", "Anti-dragon shield"), ("Body", "Adamant platebody")],
                 "actions": [{"action": "INTERACT_NPC", "name": "Elvarg", "option": "Attack", "goal": "Kill Elvarg"}, {"action": "WAIT_ANIMATION", "ticks": 30}],
                 "reasoning": "On Crandor with anti-dragon shield equipped. Time to fight Elvarg!"},
            ],
        },
        "The Corsair Curse": {
            "task": "Complete The Corsair Curse quest.",
            "steps": [
                {"pos": (3029, 3273, 0), "region": 11570, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Captain Tock", "x": 3029, "y": 3273, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Captain Tock", "option": "Talk-to", "goal": "Start The Corsair Curse quest"}],
                 "reasoning": "Talking to Captain Tock at Draynor."},
                {"pos": (2548, 2881, 0), "region": 9875, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Chief Tess", "x": 2548, "y": 2881, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Chief Tess", "option": "Talk-to", "goal": "Investigate the curse at Corsair Cove"}],
                 "reasoning": "At Corsair Cove. Talking to Chief Tess about the curse."},
            ],
        },
        "Misthalin Mystery": {
            "task": "Complete Misthalin Mystery quest.",
            "steps": [
                {"pos": (3234, 3155, 0), "region": 12594, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Abigale", "x": 3234, "y": 3155, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Abigale", "option": "Talk-to", "goal": "Start Misthalin Mystery quest"}],
                 "reasoning": "Talking to Abigale in Lumbridge Swamp."},
                {"pos": (3109, 3329, 0), "region": 12593, "status": "IDLE", "inv": [("Notes", 1)],
                 "nearby_npcs": [], "nearby_objs": [{"name": "Bookcase", "x": 3109, "y": 3329, "dist": 2, "actions": ["Search"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Bookcase", "option": "Search", "goal": "Search for clues in the manor"}],
                 "reasoning": "In the manor. Searching the bookcase for clues."},
            ],
        },
        "Below Ice Mountain": {
            "task": "Complete Below Ice Mountain quest.",
            "steps": [
                {"pos": (3007, 3476, 0), "region": 11061, "status": "IDLE", "inv": [],
                 "nearby_npcs": [{"name": "Willow", "x": 3007, "y": 3476, "dist": 2, "actions": ["Talk-to"]}],
                 "nearby_objs": [],
                 "actions": [{"action": "INTERACT_NPC", "name": "Willow", "option": "Talk-to", "goal": "Start Below Ice Mountain quest"}],
                 "reasoning": "Talking to Willow near Ice Mountain."},
                {"pos": (2999, 9470, 0), "region": 11061, "status": "IDLE", "inv": [("Pickaxe", 1)],
                 "nearby_npcs": [{"name": "Marley", "x": 2999, "y": 9470, "dist": 3, "actions": ["Talk-to"]}],
                 "nearby_objs": [{"name": "Rocks", "x": 3000, "y": 9470, "dist": 2, "actions": ["Mine"]}],
                 "actions": [{"action": "INTERACT_OBJECT", "name": "Rocks", "option": "Mine", "goal": "Mine through the rocks beneath Ice Mountain"}, {"action": "WAIT_ANIMATION"}],
                 "reasoning": "Below Ice Mountain. Mining through the rocks to proceed."},
            ],
        },
    }

    # Generate examples from quest data
    for quest_name, quest_data in QUESTS.items():
        notes = []
        task = quest_data["task"]
        tick = random_tick()
        world = random_world()
        name = random_player()
        cb = random.randint(3, 50)
        skills = make_default_skills()
        if quest_name == "Dragon Slayer I":
            skills.update({"Atk": 40, "Str": 40, "Def": 40})
            cb = combat_level_for_skills(40, 40, 40, 40, 1, 1, 1)
        elif quest_name in ("Vampire Slayer", "Demon Slayer"):
            skills.update({"Atk": 25, "Str": 25, "Def": 20})
            cb = combat_level_for_skills(25, 25, 20, 20, 1, 1, 1)

        for step_idx, step in enumerate(quest_data["steps"]):
            px, py, plane = step["pos"]
            region = step["region"]
            status = step.get("status", "IDLE")
            inv = step.get("inv", [])
            used = sum(q for _, q in inv) if inv else 0
            equip = [(s, n) for s, n in step.get("equipment", [])] if "equipment" in step else []

            nearby_npcs_raw = step.get("nearby_npcs", [])
            nearby_npcs = [nearby_entity(n["name"], n["x"], n["y"], n.get("dist", 2),
                                         n.get("actions", []), level=n.get("level", 0))
                          for n in nearby_npcs_raw]

            nearby_objs_raw = step.get("nearby_objs", [])
            nearby_objs = [nearby_entity(o["name"], o["x"], o["y"], o.get("dist", 2),
                                         o.get("actions", []))
                          for o in nearby_objs_raw]

            nearby_ground_raw = step.get("nearby_ground", [])
            nearby_ground = [nearby_entity(g["name"], g["x"], g["y"], g.get("dist", 1),
                                           g.get("actions", ["Take"]))
                            for g in nearby_ground_raw]

            # Build game state kwargs
            gs_kwargs = {
                "current_goal": f"Quest: {quest_name}" if step_idx > 0 else None,
                "session_notes": "\n".join(notes) if notes else None,
            }

            # Add dialogue if present
            if step.get("dialogue"):
                gs_kwargs["dialogue"] = step["dialogue"]
                if step.get("dialogue_speaker"):
                    gs_kwargs["dialogue_speaker"] = step["dialogue_speaker"]
                if step.get("dialogue_text"):
                    gs_kwargs["dialogue_text"] = step["dialogue_text"]
                if step.get("dialogue_options"):
                    gs_kwargs["dialogue_options"] = step["dialogue_options"]

            state = serialize_game_state(
                name, cb, random.randint(15, 40), 40, random.randint(1, 30), 30,
                random.randint(50, 100), True, random.randint(2, 15), 100,
                px, py, plane,
                status, skills, inv, equip,
                nearby_npcs, nearby_objs, nearby_ground, [],
                region, world, "Inventory", "Accurate", tick,
                **gs_kwargs,
            )

            actions_list = step["actions"]
            reasoning = step["reasoning"]
            response = f"{reasoning}\n{json.dumps(actions_list)}"
            examples.append(make_example(pick_prompt(task), state, response))

            # Accumulate session notes
            notes.append(compress_to_note(px, py, status, used, actions_list))
            tick += random.randint(10, 30)

    return examples


def gen_equipment_upgrade_scenarios():
    """Generate scenarios where the model notices inferior equipment and upgrades."""
    examples = []

    upgrade_configs = [
        # (tool_type, skill_name, activity_desc, resource_name, resource_actions, location_region, loc_coords, bank)
        ("pickaxe", "Mining", "mining", "Iron rocks", ["Mine"], 13105, (3296, 3310), "Al Kharid"),
        ("pickaxe", "Mining", "mining", "Coal rocks", ["Mine"], 11062, (3035, 3508), "Edgeville"),
        ("axe", "Woodcutting", "woodcutting", "Willow tree", ["Chop down"], 12593, (3090, 3232), "Draynor"),
        ("axe", "Woodcutting", "woodcutting", "Oak tree", ["Chop down"], 12342, (3167, 3416), "Varrock West"),
        ("scimitar", "Attack", "combat training", "Cow", ["Attack"], 12851, (3253, 3270), "Lumbridge"),
        ("scimitar", "Attack", "combat training", "Guard", ["Attack"], 12853, (3258, 3408), "Varrock West"),
    ]

    for tool_type, skill_name, activity, resource, res_actions, region, coords, bank_name in upgrade_configs:
        for _ in range(7):  # ~7 per config = ~42 per tool type
            skill_level = random.choice([6, 10, 21, 25, 31, 35, 41, 45])
            worse = worse_tool_for_level(tool_type, skill_level)
            best = best_tool_for_level(tool_type, skill_level)
            if worse == best:
                continue  # Can't demonstrate upgrade at this level

            name = random_player()
            cb = combat_level_for_skills(
                skill_level if skill_name == "Attack" else random.randint(1, 20),
                random.randint(1, 30), random.randint(1, 30),
                random.randint(10, 50), random.randint(1, 20),
                random.randint(1, 10), random.randint(1, 10)
            )
            tick = random_tick()
            world = random_world()
            px, py = jitter(coords[0]), jitter(coords[1])

            skills = make_default_skills(1)
            skills[skill_name] = skill_level
            if skill_name != "Hitpoints":
                skills["Hitpoints"] = random.randint(10, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}

            # Equip the worse tool
            equip = [(worse, "Weapon" if tool_type == "scimitar" else "Weapon")]
            if tool_type != "scimitar":
                equip = [(worse, "Weapon")]

            inv_items = random.randint(0, 15)
            inv = []
            if tool_type == "pickaxe":
                if inv_items > 0:
                    inv.append(("Iron ore", min(inv_items, 10)))
            elif tool_type == "axe":
                if inv_items > 0:
                    inv.append(("Willow logs", min(inv_items, 10)))
            else:
                inv.append(("Lobster", random.randint(3, 10)))

            # Nearby resource
            nearby_objs = [nearby_entity(resource, jitter(px, 5), jitter(py, 5), random.randint(1, 5), actions=res_actions)]

            bank = pick_bank_near(region)
            task = f"Train {skill_name} efficiently."

            state = serialize_game_state(
                name, cb, random.randint(10, 40), 40, random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                px, py, 0,
                "IDLE", skills, inv, equip,
                [], nearby_objs, [], [],
                region, world, "Inventory", "Accurate", tick,
                current_goal=task,
            )

            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_WITHDRAW", "name": best, "quantity": 1},
                {"action": "EQUIP_ITEM", "name": best},
                {"action": "BANK_CLOSE"},
            ]

            reasoning = (
                f"I'm {activity} with a {worse} but I'm level {skill_level} {skill_name} — "
                f"I can use a {best} which is much faster. I should go to the bank and upgrade before continuing."
            )
            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(task), state, response))

    # Mid-chain upgrades with session notes
    for tool_type, skill_name in [("pickaxe", "Mining"), ("axe", "Woodcutting"), ("scimitar", "Attack")]:
        for _ in range(10):
            skill_level = random.choice([21, 31, 41])
            worse = worse_tool_for_level(tool_type, skill_level)
            best = best_tool_for_level(tool_type, skill_level)
            if worse == best:
                continue

            notes = []
            for i in range(random.randint(3, 6)):
                act_name = "INTERACT_OBJECT(Iron rocks)" if tool_type == "pickaxe" else (
                    "INTERACT_OBJECT(Oak tree)" if tool_type == "axe" else "INTERACT_NPC(Cow)"
                )
                notes.append(f"- @(3200,{3200+i*5}) IDLE inv:{i*3}/28 -> {act_name},WAIT_ANIMATION(20t)")

            name = random_player()
            skills = make_default_skills(1)
            skills[skill_name] = skill_level
            skills["Hitpoints"] = random.randint(10, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            bank = random.choice(BANK_LOCATIONS)

            state = serialize_game_state(
                name, random.randint(10, 60), random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                3200, 3220, 0,
                "IDLE", skills, [(worse, 1)], [(worse, "Weapon")],
                [], [], [], [],
                12850, random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=f"Train {skill_name} efficiently.",
                session_notes="\n".join(notes),
            )

            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_WITHDRAW", "name": best, "quantity": 1},
                {"action": "EQUIP_ITEM", "name": best},
                {"action": "BANK_CLOSE"},
            ]

            reasoning = (
                f"Looking at my session notes, I've been grinding {skill_name} with a {worse} for several turns. "
                f"But I'm level {skill_level} — I should be using a {best}! Let me upgrade immediately."
            )
            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(f"Train {skill_name} efficiently."), state, response))

    return examples


def gen_level_transition_scenarios():
    """Generate scenarios where the model just leveled up and switches resources."""
    examples = []

    # Mining level-ups
    mining_transitions = [
        (15, "Iron rocks", "Copper rocks", "Iron ore", "Copper ore",
         [{"name": "Al Kharid Mine", "region": 13105, "coords": (3296, 3310)}]),
        (30, "Coal rocks", "Iron rocks", "Coal", "Iron ore",
         [{"name": "Barbarian Village", "region": 11062, "coords": (3035, 3508)}]),
        (40, "Gold rocks", "Coal rocks", "Gold ore", "Coal",
         [{"name": "Al Kharid Mine", "region": 13105, "coords": (3295, 3296)}]),
    ]

    for lvl, new_res, old_res, new_item, old_item, locs in mining_transitions:
        for _ in range(13):
            loc = random.choice(locs)
            name = random_player()
            px, py = jitter(loc["coords"][0]), jitter(loc["coords"][1])
            pick = best_tool_for_level("pickaxe", lvl)

            skills = make_default_skills(1)
            skills["Mining"] = lvl
            skills["Hitpoints"] = random.randint(10, 40)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}

            inv = [(pick, 1), (old_item, random.randint(3, 15))]
            equip = [(pick, "Weapon")]

            nearby_objs = [
                nearby_entity(old_res, jitter(px, 5), jitter(py, 5), random.randint(1, 4), actions=["Mine"]),
            ]
            # Sometimes new resource is nearby too
            if random.random() < 0.5:
                nearby_objs.append(
                    nearby_entity(new_res, jitter(px, 8), jitter(py, 8), random.randint(3, 8), actions=["Mine"])
                )

            action_results = [f"Congratulations, you just advanced a Mining level. You have reached level {lvl}."]
            state = serialize_game_state(
                name, random.randint(10, 50), random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                px, py, 0,
                "IDLE", skills, inv, equip,
                [], nearby_objs, [], [],
                loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal="Train Mining.",
                action_results=action_results,
            )

            # Decide: switch here or travel
            has_new_nearby = any(new_res in o["name"] for o in nearby_objs)
            if has_new_nearby:
                actions = [
                    {"action": "INTERACT_OBJECT", "name": new_res, "option": "Mine"},
                    {"action": "WAIT_ANIMATION", "ticks": 20},
                ]
                reasoning = (
                    f"I just hit level {lvl} Mining! I can now mine {new_res} which gives better XP. "
                    f"There are some nearby, let me switch to those."
                )
            else:
                new_loc = random.choice(locs)
                actions = [
                    {"action": "PATH_TO", "x": new_loc["coords"][0], "y": new_loc["coords"][1]},
                ]
                reasoning = (
                    f"I just hit level {lvl} Mining! I should switch from {old_res} to {new_res} for better XP. "
                    f"I need to travel to find some."
                )

            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt("Train Mining."), state, response))

    # Woodcutting level-ups
    wc_transitions = [
        (15, "Oak tree", "Tree", "Oak logs", "Logs",
         [{"name": "Varrock West", "region": 12342, "coords": (3167, 3416)},
          {"name": "Lumbridge", "region": 12850, "coords": (3203, 3244)}]),
        (30, "Willow tree", "Oak tree", "Willow logs", "Oak logs",
         [{"name": "Draynor Village", "region": 12593, "coords": (3090, 3232)}]),
        (45, "Maple tree", "Willow tree", "Maple logs", "Willow logs",
         [{"name": "Seers Village", "region": 10292, "coords": (2722, 3501)}]),
    ]

    for lvl, new_res, old_res, new_item, old_item, locs in wc_transitions:
        for _ in range(13):
            loc = random.choice(locs)
            name = random_player()
            px, py = jitter(loc["coords"][0]), jitter(loc["coords"][1])
            axe = best_tool_for_level("axe", lvl)

            skills = make_default_skills(1)
            skills["Woodcutting"] = lvl
            skills["Hitpoints"] = random.randint(10, 40)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}

            inv = [(axe, 1), (old_item, random.randint(3, 15))]
            equip = [(axe, "Weapon")]

            nearby_objs = [
                nearby_entity(old_res, jitter(px, 5), jitter(py, 5), random.randint(1, 4), actions=["Chop down"]),
            ]

            action_results = [f"Congratulations, you just advanced a Woodcutting level. You have reached level {lvl}."]
            state = serialize_game_state(
                name, random.randint(10, 50), random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                px, py, 0,
                "IDLE", skills, inv, equip,
                [], nearby_objs, [], [],
                loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal="Train Woodcutting.",
                action_results=action_results,
            )

            new_loc = random.choice(locs)
            actions = [
                {"action": "PATH_TO", "x": new_loc["coords"][0], "y": new_loc["coords"][1]},
            ]
            reasoning = (
                f"I just reached level {lvl} Woodcutting! I should switch from {old_res} to {new_res} "
                f"for much better XP rates. Let me head to a {new_res} spot."
            )

            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt("Train Woodcutting."), state, response))

    # Fishing level-ups
    fish_transitions = [
        (20, "Fly fishing spot", "Fishing spot", "Raw trout", "Raw shrimps",
         [{"name": "Barbarian Village", "region": 11062, "coords": (3110, 3434)}]),
        (40, "Lobster cage", "Fly fishing spot", "Raw lobster", "Raw trout",
         [{"name": "Karamja", "region": 10804, "coords": (2924, 3178)},
          {"name": "Catherby", "region": 10548, "coords": (2837, 3431)}]),
    ]

    for lvl, new_spot, old_spot, new_fish, old_fish, locs in fish_transitions:
        for _ in range(15):
            loc = random.choice(locs)
            name = random_player()
            px, py = jitter(loc["coords"][0]), jitter(loc["coords"][1])

            skills = make_default_skills(1)
            skills["Fishing"] = lvl
            skills["Hitpoints"] = random.randint(10, 40)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}

            tool = "Fly fishing rod" if "Fly" in new_spot else "Lobster pot"
            inv = [("Small fishing net", 1), (old_fish, random.randint(3, 15))]

            action_results = [f"Congratulations, you just advanced a Fishing level. You have reached level {lvl}."]
            state = serialize_game_state(
                name, random.randint(10, 50), random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                px, py, 0,
                "IDLE", skills, inv, [],
                [], [], [], [],
                loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal="Train Fishing.",
                action_results=action_results,
            )

            # Need to get right tool from bank
            bank = pick_bank_near(loc["region"])
            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_DEPOSIT_ALL"},
                {"action": "BANK_WITHDRAW", "name": tool, "quantity": 1},
            ]
            if "Fly" in new_spot:
                actions.append({"action": "BANK_WITHDRAW", "name": "Feather", "quantity": 200})
            actions.append({"action": "BANK_CLOSE"})

            reasoning = (
                f"I just hit level {lvl} Fishing! I should switch to {new_spot} for {new_fish} — "
                f"much better XP. I need to bank and get a {tool} first."
            )

            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt("Train Fishing."), state, response))

    # Combat (attack) level-ups — weapon upgrade
    atk_transitions = [
        (5, "Iron scimitar", "Bronze scimitar"),
        (20, "Mithril scimitar", "Steel scimitar"),
        (30, "Adamant scimitar", "Mithril scimitar"),
        (40, "Rune scimitar", "Adamant scimitar"),
    ]

    for lvl, new_wep, old_wep in atk_transitions:
        for _ in range(10):
            npc = random.choice([n for n in COMBAT_NPCS if n["level"] <= lvl + 10])
            loc = random.choice(npc["locations"])
            name = random_player()
            coord = random.choice(loc["coords"])
            px, py = jitter(coord[0]), jitter(coord[1])

            skills = make_default_skills(1)
            skills["Attack"] = lvl
            skills["Strength"] = random.randint(max(1, lvl - 10), lvl + 5)
            skills["Defence"] = random.randint(max(1, lvl - 15), lvl)
            skills["Hitpoints"] = random.randint(15, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = combat_level_for_skills(skills["Attack"], skills["Strength"], skills["Defence"],
                                         skills["Hitpoints"], 1, 1, 1)

            inv = [("Lobster", random.randint(5, 15))]
            equip = [(old_wep, "Weapon")]

            nearby_npcs = [nearby_entity(npc["name"], jitter(px, 5), jitter(py, 5),
                                         random.randint(1, 5), actions=["Attack"],
                                         level=npc["level"], hp_pct=100)]

            action_results = [f"Congratulations, you just advanced an Attack level. You have reached level {lvl}."]
            bank = pick_bank_near(loc["region"])

            state = serialize_game_state(
                name, cb, random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 99), True, random.randint(1, 10), 100,
                px, py, 0,
                "IDLE", skills, inv, equip,
                nearby_npcs, [], [], [],
                loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal="Train Attack.",
                action_results=action_results,
            )

            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_WITHDRAW", "name": new_wep, "quantity": 1},
                {"action": "EQUIP_ITEM", "name": new_wep},
                {"action": "BANK_CLOSE"},
            ]

            reasoning = (
                f"I just reached level {lvl} Attack! I can now wield a {new_wep} which is a big upgrade "
                f"from my {old_wep}. Let me go to the bank and equip it before continuing."
            )

            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt("Train Attack."), state, response))

    return examples


def gen_death_recovery_scenarios():
    """Generate death recovery scenarios: re-gear from bank and gravestone recovery."""
    examples = []

    LUMBRIDGE_SPAWN = (3222, 3218, 0)

    # Loadouts for re-gearing
    COMBAT_LOADOUTS = [
        {
            "task": "Train Attack on cows.",
            "equip_withdraw": [
                ("Iron scimitar", "Weapon"), ("Iron med helm", "Head"),
                ("Iron platebody", "Body"), ("Iron platelegs", "Legs"),
            ],
            "food": ("Trout", 10),
            "destination": (3253, 3270),  # Lumbridge cows
            "dest_name": "the cow field",
        },
        {
            "task": "Train Strength at Al-Kharid warriors.",
            "equip_withdraw": [
                ("Mithril scimitar", "Weapon"), ("Steel full helm", "Head"),
                ("Steel platebody", "Body"), ("Steel platelegs", "Legs"),
            ],
            "food": ("Lobster", 12),
            "destination": (3293, 3173),  # Al-Kharid
            "dest_name": "Al-Kharid warriors",
        },
        {
            "task": "Train combat at moss giants.",
            "equip_withdraw": [
                ("Rune scimitar", "Weapon"), ("Mithril platebody", "Body"),
            ],
            "food": ("Swordfish", 15),
            "destination": (3160, 9900),  # Varrock sewer
            "dest_name": "moss giants",
        },
    ]

    # Basic re-gear from bank (~30 examples)
    for loadout in COMBAT_LOADOUTS:
        for _ in range(10):
            name = random_player()
            px, py, plane = LUMBRIDGE_SPAWN
            px, py = jitter(px, 2), jitter(py, 2)

            skills = make_default_skills(random.randint(10, 40))
            skills["Hitpoints"] = random.randint(10, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                         skills.get("Defence", 1), skills["Hitpoints"], 1, 1, 1)

            state = serialize_game_state(
                name, cb, skills["Hitpoints"], skills["Hitpoints"],
                random.randint(1, 30), 30,
                100, True, 0, 100,
                px, py, plane,
                "IDLE", skills, [], [],  # Empty inv and equip after death
                [], [], [], [],
                12850, random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=loadout["task"],
                action_results=[random.choice(DEATH_MESSAGES)],
            )

            bank = pick_bank_near(12850)
            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
            ]
            for item_name, _ in loadout["equip_withdraw"]:
                actions.append({"action": "BANK_WITHDRAW", "name": item_name, "quantity": 1})
            for item_name, _ in loadout["equip_withdraw"]:
                actions.append({"action": "EQUIP_ITEM", "name": item_name})
            food_name, food_qty = loadout["food"]
            actions.append({"action": "BANK_WITHDRAW", "name": food_name, "quantity": food_qty})
            actions.append({"action": "BANK_CLOSE"})

            reasoning = (
                f"I just died and respawned in Lumbridge. I need to re-equip from the bank. "
                f"Let me grab my gear and food so I can get back to {loadout['dest_name']}."
            )
            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(loadout["task"]), state, response))

    # Gravestone recovery (~40 examples)
    DEATH_LOCATIONS = [
        {"name": "Al-Kharid warriors", "x": 3293, "y": 3173, "region": 13104},
        {"name": "Varrock guards", "x": 3258, "y": 3408, "region": 12853},
        {"name": "Lumbridge goblins", "x": 3259, "y": 3227, "region": 12850},
        {"name": "Barbarian Village miners", "x": 3035, "y": 3508, "region": 11062},
        {"name": "Moss giants", "x": 3160, "y": 9900, "region": 12853},
        {"name": "Draynor Manor", "x": 3109, "y": 3354, "region": 12593},
        {"name": "Wilderness border", "x": 3094, "y": 3518, "region": 12442},
        {"name": "Port Sarim jail", "x": 3012, "y": 3188, "region": 11570},
    ]

    LOST_GEAR = [
        [("Rune scimitar", "Weapon"), ("Mithril platebody", "Body"), ("Iron platelegs", "Legs")],
        [("Adamant scimitar", "Weapon"), ("Steel platebody", "Body"), ("Steel full helm", "Head")],
        [("Mithril scimitar", "Weapon"), ("Iron platebody", "Body")],
        [("Rune pickaxe", "Weapon")],
        [("Rune axe", "Weapon")],
    ]

    for _ in range(40):
        death_loc = random.choice(DEATH_LOCATIONS)
        lost_gear = random.choice(LOST_GEAR)
        name = random_player()
        px, py, plane = LUMBRIDGE_SPAWN
        px, py = jitter(px, 2), jitter(py, 2)

        skills = make_default_skills(random.randint(15, 45))
        skills["Hitpoints"] = random.randint(15, 50)
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                     skills.get("Defence", 1), skills["Hitpoints"], 1, 1, 1)

        # Session notes show what we were doing and what we had
        gear_names = [g[0] for g in lost_gear]
        notes_lines = [
            f"- @({death_loc['x']},{death_loc['y']}) IN_COMBAT inv:5/28 -> INTERACT_NPC({death_loc['name'].split()[0]})",
            f"- @({death_loc['x']},{death_loc['y']}) IN_COMBAT inv:5/28 -> EAT_FOOD(Lobster)",
            f"- Equipment was: {', '.join(gear_names)}",
        ]
        session_notes = "\n".join(notes_lines)

        task = "Train combat."
        state = serialize_game_state(
            name, cb, skills["Hitpoints"], skills["Hitpoints"],
            random.randint(1, 30), 30,
            100, True, 0, 100,
            px, py, plane,
            "IDLE", skills, [], [],
            [], [], [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=task,
            action_results=[random.choice(DEATH_MESSAGES)],
            session_notes=session_notes,
        )

        # Rush to gravestone
        actions = [
            {"action": "PATH_TO", "x": death_loc["x"], "y": death_loc["y"]},
            {"action": "INTERACT_OBJECT", "name": "Gravestone", "option": "Loot"},
        ]

        gear_str = ", ".join(gear_names)
        reasoning = (
            f"I just died! My gravestone is near {death_loc['name']} at ({death_loc['x']},{death_loc['y']}). "
            f"I had valuable items ({gear_str}) — I need to rush there before the timer expires to recover them."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt(task), state, response))

    # Re-gear then resume task (~25 examples)
    for _ in range(25):
        loadout = random.choice(COMBAT_LOADOUTS)
        name = random_player()
        px, py, plane = LUMBRIDGE_SPAWN
        px, py = jitter(px, 2), jitter(py, 2)

        skills = make_default_skills(random.randint(15, 40))
        skills["Hitpoints"] = random.randint(15, 50)
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                     skills.get("Defence", 1), skills["Hitpoints"], 1, 1, 1)

        # Session notes show recovery in progress
        notes_lines = [
            f"- @(3222,3218) IDLE inv:0/28 -> PATH_TO({BANK_LOCATIONS[0]['coords'][0]},{BANK_LOCATIONS[0]['coords'][1]})",
            "- @(3208,3220) IDLE inv:0/28 -> INTERACT_OBJECT(Bank booth),BANK_WITHDRAW(Iron scimitar),EQUIP_ITEM(Iron scimitar)",
        ]
        session_notes = "\n".join(notes_lines)

        # Player has re-geared, now at bank
        equip_items = loadout["equip_withdraw"][:2]  # partial gear
        equip = [(e[0], e[1]) for e in equip_items]
        food_name, food_qty = loadout["food"]
        inv = [(food_name, food_qty)]

        bank = pick_bank_near(12850)
        state = serialize_game_state(
            name, cb, skills["Hitpoints"], skills["Hitpoints"],
            random.randint(1, 30), 30,
            100, True, 0, 100,
            bank["coords"][0], bank["coords"][1], 0,
            "IDLE", skills, inv, equip,
            [], [], [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=loadout["task"],
            session_notes=session_notes,
        )

        dx, dy = loadout["destination"]
        actions = [
            {"action": "PATH_TO", "x": dx, "y": dy},
        ]

        reasoning = (
            f"I've recovered from dying and re-geared at the bank. "
            f"Now I need to head back to {loadout['dest_name']} to continue my task."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt(loadout["task"]), state, response))

    # Death during quest (~25 examples)
    QUEST_DEATH_SCENARIOS = [
        {
            "task": "Complete Vampire Slayer quest.",
            "notes": "- @(3077,3249) IDLE inv:3/28 -> INTERACT_NPC(Morgan)\n- @(3077,3249) IDLE inv:3/28 -> CONTINUE_DIALOGUE\n- @(3096,3357) IDLE inv:4/28 -> INTERACT_OBJECT(Cupboard)",
            "resume_text": "I was doing Vampire Slayer — I need to re-gear and get back to Count Draynor.",
            "gear": [("Steel scimitar", "Weapon"), ("Iron platebody", "Body")],
            "food": ("Lobster", 10),
            "destination": (3077, 9770),
            "dest_name": "Count Draynor's crypt",
        },
        {
            "task": "Complete Dragon Slayer I quest.",
            "notes": "- @(2847,3236) IDLE inv:10/28 -> INTERACT_NPC(Ned)\n- @(2847,3236) IDLE inv:10/28 -> CONTINUE_DIALOGUE",
            "resume_text": "I was doing Dragon Slayer — I died to Elvarg. I need to re-gear with better food and try again.",
            "gear": [("Rune scimitar", "Weapon"), ("Mithril platebody", "Body"), ("Iron platelegs", "Legs")],
            "food": ("Swordfish", 20),
            "destination": (2847, 3236),
            "dest_name": "Port Sarim to sail to Crandor",
        },
        {
            "task": "Complete Demon Slayer quest.",
            "notes": "- @(3228,3367) IDLE inv:5/28 -> INTERACT_NPC(Gypsy Aris)\n- @(3235,3384) IDLE inv:5/28 -> PATH_TO(3235,3384)",
            "resume_text": "I was doing Demon Slayer and died to Delrith. I need to recover Silverlight and try again.",
            "gear": [("Silverlight", "Weapon"), ("Steel platebody", "Body")],
            "food": ("Lobster", 15),
            "destination": (3227, 3370),
            "dest_name": "the stone circle south of Varrock",
        },
    ]

    for scenario in QUEST_DEATH_SCENARIOS:
        for _ in range(8):
            name = random_player()
            px, py, plane = LUMBRIDGE_SPAWN
            px, py = jitter(px, 2), jitter(py, 2)

            skills = make_default_skills(random.randint(20, 45))
            skills["Hitpoints"] = random.randint(20, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                         skills.get("Defence", 1), skills["Hitpoints"], 1, 1, 1)

            state = serialize_game_state(
                name, cb, skills["Hitpoints"], skills["Hitpoints"],
                random.randint(1, 30), 30,
                100, True, 0, 100,
                px, py, plane,
                "IDLE", skills, [], [],
                [], [], [], [],
                12850, random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=scenario["task"],
                action_results=[random.choice(DEATH_MESSAGES)],
                session_notes=scenario["notes"],
            )

            bank = pick_bank_near(12850)
            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
            ]
            for item_name, _ in scenario["gear"]:
                actions.append({"action": "BANK_WITHDRAW", "name": item_name, "quantity": 1})
            for item_name, _ in scenario["gear"]:
                actions.append({"action": "EQUIP_ITEM", "name": item_name})
            food_name, food_qty = scenario["food"]
            actions.append({"action": "BANK_WITHDRAW", "name": food_name, "quantity": food_qty})
            actions.append({"action": "BANK_CLOSE"})

            reasoning = (
                f"I just died and respawned in Lumbridge. {scenario['resume_text']} "
                f"First, let me get my gear from the bank."
            )
            response = f"{reasoning}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(scenario["task"]), state, response))

    return examples


def gen_bank_preparation_scenarios():
    """Generate complex multi-item bank withdrawal scenarios."""
    examples = []

    # Combat trip prep (~30 examples)
    COMBAT_PREPS = [
        {
            "task": "Train Attack on guards.",
            "withdrawals": [
                ("Steel scimitar", 1), ("Iron platebody", 1), ("Iron platelegs", 1),
                ("Iron med helm", 1), ("Lobster", 15),
            ],
            "equip": ["Steel scimitar", "Iron platebody", "Iron platelegs", "Iron med helm"],
            "destination": (3258, 3408),
            "reasoning": "I'm preparing for a combat training session at Varrock guards. I need my melee gear and food.",
        },
        {
            "task": "Kill moss giants for combat XP.",
            "withdrawals": [
                ("Rune scimitar", 1), ("Mithril platebody", 1), ("Steel platelegs", 1),
                ("Swordfish", 20),
            ],
            "equip": ["Rune scimitar", "Mithril platebody", "Steel platelegs"],
            "destination": (3160, 9900),
            "reasoning": "I'm gearing up for moss giants. They hit hard so I need good armor and plenty of food.",
        },
        {
            "task": "Train Ranged at cows.",
            "withdrawals": [
                ("Oak shortbow", 1), ("Iron arrow", 500), ("Leather body", 1),
                ("Leather chaps", 1), ("Trout", 5),
            ],
            "equip": ["Oak shortbow", "Leather body", "Leather chaps"],
            "destination": (3253, 3270),
            "reasoning": "Setting up for ranged training. I need my bow, arrows, and some light armor.",
        },
    ]

    for prep in COMBAT_PREPS:
        for _ in range(10):
            bank = random.choice(BANK_LOCATIONS)
            name = random_player()
            skills = make_default_skills(random.randint(10, 40))
            skills["Hitpoints"] = random.randint(15, 50)
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                         skills.get("Defence", 1), skills["Hitpoints"], 1, 1, 1)

            state = serialize_game_state(
                name, cb, random.randint(15, 50), 50,
                random.randint(1, 30), 30,
                random.randint(50, 100), True, 0, 100,
                bank["coords"][0], bank["coords"][1], bank.get("plane", 0),
                "IDLE", skills, [], [],
                [], [nearby_entity("Bank booth", jitter(bank["coords"][0], 3), jitter(bank["coords"][1], 3), 1, actions=["Bank"])], [], [],
                bank["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=prep["task"],
                bank_open=True,
            )

            actions = [{"action": "BANK_WITHDRAW", "name": w[0], "quantity": w[1]} for w in prep["withdrawals"]]
            for eq in prep["equip"]:
                actions.append({"action": "EQUIP_ITEM", "name": eq})
            actions.append({"action": "BANK_CLOSE"})
            actions.append({"action": "PATH_TO", "x": prep["destination"][0], "y": prep["destination"][1]})

            response = f"{prep['reasoning']}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(prep["task"]), state, response))

    # Skilling trip prep (~30 examples)
    SKILLING_PREPS = [
        {
            "task": "Train Fishing at Barbarian Village.",
            "withdrawals": [("Fly fishing rod", 1), ("Feather", 500)],
            "equip": [],
            "destination": (3110, 3434),
            "reasoning": "I need my fly fishing rod and feathers for trout/salmon fishing.",
        },
        {
            "task": "Train Woodcutting at willows.",
            "withdrawals": [("Rune axe", 1)],
            "equip": ["Rune axe"],
            "destination": (3090, 3232),
            "reasoning": "I just need my rune axe for woodcutting. I'll equip it for the extra inventory slot.",
        },
        {
            "task": "Train Mining at Al Kharid.",
            "withdrawals": [("Adamant pickaxe", 1)],
            "equip": ["Adamant pickaxe"],
            "destination": (3296, 3310),
            "reasoning": "Grabbing my pickaxe for iron mining. Equipping it saves an inventory slot.",
        },
        {
            "task": "Train Firemaking.",
            "withdrawals": [("Tinderbox", 1), ("Maple logs", 27)],
            "equip": [],
            "destination": (3165, 3487),
            "reasoning": "I need my tinderbox and a full inventory of maple logs for firemaking.",
        },
    ]

    for prep in SKILLING_PREPS:
        for _ in range(8):
            bank = random.choice(BANK_LOCATIONS)
            name = random_player()
            skills = make_default_skills(random.randint(10, 50))
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = random.randint(3, 40)

            state = serialize_game_state(
                name, cb, random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 100), True, 0, 100,
                bank["coords"][0], bank["coords"][1], bank.get("plane", 0),
                "IDLE", skills, [], [],
                [], [nearby_entity("Bank booth", jitter(bank["coords"][0], 3), jitter(bank["coords"][1], 3), 1, actions=["Bank"])], [], [],
                bank["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=prep["task"],
                bank_open=True,
            )

            actions = [{"action": "BANK_WITHDRAW", "name": w[0], "quantity": w[1]} for w in prep["withdrawals"]]
            for eq in prep["equip"]:
                actions.append({"action": "EQUIP_ITEM", "name": eq})
            actions.append({"action": "BANK_CLOSE"})
            actions.append({"action": "PATH_TO", "x": prep["destination"][0], "y": prep["destination"][1]})

            response = f"{prep['reasoning']}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(prep["task"]), state, response))

    # Crafting/smithing material prep (~30 examples)
    MATERIAL_PREPS = [
        {
            "task": "Smelt steel bars at Al Kharid furnace.",
            "withdrawals": [("Iron ore", 9), ("Coal", 18)],
            "equip": [],
            "destination": (3275, 3186),
            "reasoning": "Steel bars need 1 iron ore and 2 coal each. I'll take 9 iron and 18 coal for a full batch.",
        },
        {
            "task": "Smelt iron bars at Al Kharid furnace.",
            "withdrawals": [("Iron ore", 27)],
            "equip": [],
            "destination": (3275, 3186),
            "reasoning": "Iron bars just need iron ore — filling my entire inventory for maximum efficiency.",
        },
        {
            "task": "Smelt bronze bars.",
            "withdrawals": [("Copper ore", 14), ("Tin ore", 14)],
            "equip": [],
            "destination": (3275, 3186),
            "reasoning": "Bronze bars need equal parts copper and tin. Taking 14 of each fills my inventory perfectly.",
        },
        {
            "task": "Cook lobsters at the Lumbridge range.",
            "withdrawals": [("Raw lobster", 28)],
            "equip": [],
            "destination": (3211, 3215),
            "reasoning": "Full inventory of raw lobsters for cooking. The Lumbridge range has lower burn rate.",
        },
        {
            "task": "Fletch willow longbows.",
            "withdrawals": [("Knife", 1), ("Willow logs", 27)],
            "equip": [],
            "destination": (3165, 3487),
            "reasoning": "I need my knife and as many willow logs as I can carry for fletching.",
        },
    ]

    for prep in MATERIAL_PREPS:
        for _ in range(6):
            bank = random.choice(BANK_LOCATIONS)
            name = random_player()
            skills = make_default_skills(random.randint(15, 50))
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = random.randint(3, 40)

            state = serialize_game_state(
                name, cb, random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 100), True, 0, 100,
                bank["coords"][0], bank["coords"][1], bank.get("plane", 0),
                "IDLE", skills, [], [],
                [], [nearby_entity("Bank booth", jitter(bank["coords"][0], 3), jitter(bank["coords"][1], 3), 1, actions=["Bank"])], [], [],
                bank["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=prep["task"],
                bank_open=True,
            )

            actions = [{"action": "BANK_WITHDRAW", "name": w[0], "quantity": w[1]} for w in prep["withdrawals"]]
            for eq in prep["equip"]:
                actions.append({"action": "EQUIP_ITEM", "name": eq})
            actions.append({"action": "BANK_CLOSE"})
            actions.append({"action": "PATH_TO", "x": prep["destination"][0], "y": prep["destination"][1]})

            response = f"{prep['reasoning']}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(prep["task"]), state, response))

    # Quest supply prep (~30 examples)
    QUEST_PREPS = [
        {
            "task": "Complete Cook's Assistant quest.",
            "withdrawals": [("Egg", 1), ("Bucket of milk", 1), ("Pot of flour", 1)],
            "equip": [],
            "destination": (3208, 3214),
            "reasoning": "I have all the quest items in my bank. Let me grab the egg, milk, and flour to deliver to the Cook.",
        },
        {
            "task": "Complete Witch's Potion quest.",
            "withdrawals": [("Rat's tail", 1), ("Eye of newt", 1), ("Onion", 1), ("Cooked meat", 1)],
            "equip": [],
            "destination": (2968, 3215),
            "reasoning": "I need the 4 ingredients for Hetty's potion: rat tail, eye of newt, onion, and cooked meat.",
        },
        {
            "task": "Complete Doric's Quest.",
            "withdrawals": [("Copper ore", 4), ("Iron ore", 2), ("Clay", 6)],
            "equip": [],
            "destination": (2952, 3450),
            "reasoning": "Doric needs 4 copper ore, 2 iron ore, and 6 clay. Let me grab them all.",
        },
        {
            "task": "Complete Vampire Slayer quest.",
            "withdrawals": [("Stake", 1), ("Hammer", 1), ("Garlic", 1), ("Lobster", 15)],
            "equip": [],
            "destination": (3077, 9770),
            "reasoning": "I need the stake, hammer, and garlic to kill Count Draynor. Better bring lots of food too.",
        },
    ]

    for prep in QUEST_PREPS:
        for _ in range(7):
            bank = random.choice(BANK_LOCATIONS)
            name = random_player()
            skills = make_default_skills(random.randint(5, 30))
            xp = {s: random_xp_at_level(l) for s, l in skills.items()}
            cb = random.randint(3, 40)

            state = serialize_game_state(
                name, cb, random.randint(10, 40), 40,
                random.randint(1, 30), 30,
                random.randint(50, 100), True, 0, 100,
                bank["coords"][0], bank["coords"][1], bank.get("plane", 0),
                "IDLE", skills, [], [],
                [], [nearby_entity("Bank booth", jitter(bank["coords"][0], 3), jitter(bank["coords"][1], 3), 1, actions=["Bank"])], [], [],
                bank["region"], random_world(), "Inventory", "Accurate", random_tick(),
                current_goal=prep["task"],
                bank_open=True,
            )

            actions = [{"action": "BANK_WITHDRAW", "name": w[0], "quantity": w[1]} for w in prep["withdrawals"]]
            for eq in prep["equip"]:
                actions.append({"action": "EQUIP_ITEM", "name": eq})
            actions.append({"action": "BANK_CLOSE"})
            actions.append({"action": "PATH_TO", "x": prep["destination"][0], "y": prep["destination"][1]})

            response = f"{prep['reasoning']}\n{json.dumps(actions)}"
            examples.append(make_example(pick_prompt(prep["task"]), state, response))

    return examples


def gen_negative_examples():
    """Generate examples where the model avoids common mistakes."""
    examples = []

    # Don't attack higher-level NPCs (~20)
    for _ in range(20):
        name = random_player()
        player_cb = random.randint(5, 25)
        skills = make_default_skills(random.randint(1, 15))
        skills["Hitpoints"] = random.randint(10, 30)
        skills["Attack"] = random.randint(1, 15)
        skills["Strength"] = random.randint(1, 15)
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        safe_npc = random.choice([n for n in COMBAT_NPCS if n["level"] <= 5])
        danger_npc = random.choice([n for n in COMBAT_NPCS if n["level"] >= 20])
        loc = random.choice(safe_npc["locations"])
        coord = random.choice(loc["coords"])
        px, py = jitter(coord[0]), jitter(coord[1])

        nearby_npcs = [
            nearby_entity(safe_npc["name"], jitter(px, 5), jitter(py, 5), random.randint(1, 4),
                         actions=["Attack"], level=safe_npc["level"], hp_pct=100),
            nearby_entity(danger_npc["name"], jitter(px, 8), jitter(py, 8), random.randint(3, 8),
                         actions=["Attack"], level=danger_npc["level"], hp_pct=100),
        ]

        state = serialize_game_state(
            name, player_cb, skills["Hitpoints"], skills["Hitpoints"],
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IDLE", skills, [("Trout", random.randint(3, 10))], [],
            nearby_npcs, [], [], [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Train combat.",
        )

        actions = [
            {"action": "INTERACT_NPC", "name": safe_npc["name"], "option": "Attack"},
            {"action": "WAIT_ANIMATION", "ticks": 20},
        ]

        reasoning = (
            f"I see a {danger_npc['name']} (level {danger_npc['level']}) nearby but that's way too dangerous for me "
            f"at combat level {player_cb}. I'll stick to {safe_npc['name']}s (level {safe_npc['level']}) which are safe to fight."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Train combat."), state, response))

    # Don't walk into wilderness (~15)
    for _ in range(15):
        name = random_player()
        px, py = 3094 + random.randint(-5, 5), 3518 + random.randint(-3, 3)
        skills = make_default_skills(random.randint(5, 30))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = random.randint(10, 40)

        state = serialize_game_state(
            name, cb, random.randint(15, 40), 40,
            random.randint(1, 20), 30, 100, True, random.randint(0, 5), 100,
            px, py, 0,
            "IDLE", skills, [("Lobster", random.randint(5, 15))], [],
            [], [], [], [],
            12442, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Explore the area.",
        )

        # Go south (away from wilderness)
        actions = [
            {"action": "PATH_TO", "x": 3094, "y": 3491},
        ]

        reasoning = (
            "I'm very close to the Wilderness border. I should NOT go north — "
            "the Wilderness is extremely dangerous and I could lose all my items to PKers. "
            "I'll head south to Edgeville bank instead."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Explore the area."), state, response))

    # Don't drop valuable items when inventory full (~15)
    for _ in range(15):
        name = random_player()
        loc = random.choice(MINING_DATA[2]["locations"])  # Iron ore location
        coord = random.choice(loc["coords"])
        px, py = jitter(coord[0]), jitter(coord[1])
        skills = make_default_skills(random.randint(15, 40))
        skills["Mining"] = random.randint(15, 40)
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        pick = best_tool_for_level("pickaxe", skills["Mining"])
        # Full inventory with mix of items
        inv = [
            (pick, 1),
            ("Iron ore", 20),
            ("Uncut sapphire", random.randint(1, 3)),
            ("Coins", random.randint(500, 5000)),
            ("Lobster", random.randint(1, 3)),
        ]

        nearby_objs = [nearby_entity("Iron rocks", jitter(px, 5), jitter(py, 5), 2, actions=["Mine"])]
        bank = pick_bank_near(loc["region"])

        state = serialize_game_state(
            name, random.randint(10, 40), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IDLE", skills, inv, [(pick, "Weapon")],
            [], nearby_objs, [], [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Mine iron ore.",
        )

        actions = [
            {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
            {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
            {"action": "BANK_DEPOSIT_ALL"},
            {"action": "BANK_CLOSE"},
        ]

        reasoning = (
            "My inventory is full. I have some valuable items like uncut sapphires and coins that I shouldn't drop. "
            "Even though it takes a bit longer, I should bank everything to keep my valuables safe."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Mine iron ore."), state, response))

    # Don't ignore low HP in combat (~20)
    for _ in range(20):
        name = random_player()
        npc = random.choice([n for n in COMBAT_NPCS if n["level"] >= 5])
        loc = random.choice(npc["locations"])
        coord = random.choice(loc["coords"])
        px, py = jitter(coord[0]), jitter(coord[1])

        max_hp = random.randint(20, 50)
        current_hp = random.randint(3, max_hp // 3)  # Very low HP
        skills = make_default_skills(random.randint(10, 40))
        skills["Hitpoints"] = max_hp
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = combat_level_for_skills(skills.get("Attack", 1), skills.get("Strength", 1),
                                     skills.get("Defence", 1), max_hp, 1, 1, 1)

        has_food = random.random() < 0.6
        food_name = random.choice(["Lobster", "Trout", "Swordfish"])
        food_qty = random.randint(1, 5) if has_food else 0
        inv = [(food_name, food_qty)] if has_food else []

        nearby_npcs = [nearby_entity(npc["name"], jitter(px, 3), jitter(py, 3), 2,
                                     actions=["Attack"], level=npc["level"], hp_pct=random.randint(30, 80))]

        state = serialize_game_state(
            name, cb, current_hp, max_hp,
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IN_COMBAT", skills, inv, [],
            nearby_npcs, [], [], [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Train combat.",
        )

        if has_food:
            actions = [
                {"action": "EAT_FOOD", "name": food_name},
            ]
            reasoning = (
                f"My HP is critically low ({current_hp}/{max_hp})! I need to eat immediately before I die. "
                f"I'll eat a {food_name} to heal up."
            )
        else:
            # Teleport or run
            bank = pick_bank_near(loc["region"])
            actions = [
                {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
            ]
            reasoning = (
                f"My HP is dangerously low ({current_hp}/{max_hp}) and I have no food left! "
                f"I need to run away immediately to avoid dying. Heading to the bank to restock."
            )

        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Train combat."), state, response))

    # Don't use wrong tool when you have better (~15)
    for _ in range(15):
        name = random_player()
        skill_level = random.choice([21, 31, 41])
        tool_type = random.choice(["pickaxe", "axe"])

        worse = worse_tool_for_level(tool_type, skill_level)
        best = best_tool_for_level(tool_type, skill_level)
        if worse == best:
            continue

        resource = "Iron rocks" if tool_type == "pickaxe" else "Oak tree"
        res_action = "Mine" if tool_type == "pickaxe" else "Chop down"
        skill_name = "Mining" if tool_type == "pickaxe" else "Woodcutting"

        skills = make_default_skills(1)
        skills[skill_name] = skill_level
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        # Has both tools in inventory
        inv = [(worse, 1), (best, 1)]
        equip = [(worse, "Weapon")]  # Currently wearing the worse one

        loc_region = 13105 if tool_type == "pickaxe" else 12342
        px, py = (3296, 3310) if tool_type == "pickaxe" else (3167, 3416)
        px, py = jitter(px), jitter(py)
        nearby_objs = [nearby_entity(resource, jitter(px, 4), jitter(py, 4), 2, actions=[res_action])]

        state = serialize_game_state(
            name, random.randint(10, 40), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IDLE", skills, inv, equip,
            [], nearby_objs, [], [],
            loc_region, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Train {skill_name}.",
        )

        actions = [
            {"action": "EQUIP_ITEM", "name": best},
            {"action": "INTERACT_OBJECT", "name": resource, "option": res_action},
            {"action": "WAIT_ANIMATION", "ticks": 20},
        ]

        reasoning = (
            f"Wait, I'm wearing a {worse} but I have a {best} in my inventory! "
            f"The {best} is much faster at level {skill_level} {skill_name}. Let me equip it first."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt(f"Train {skill_name}."), state, response))

    # Handle "can't reach" — open door or go around (~15)
    for _ in range(15):
        name = random_player()
        # Near a door
        door_scenarios = [
            {"region": 12850, "px": 3209, "py": 3217, "door_x": 3209, "door_y": 3216,
             "target_desc": "upstairs in Lumbridge castle", "door_name": "Door"},
            {"region": 12593, "px": 3106, "py": 3352, "door_x": 3108, "door_y": 3353,
             "target_desc": "inside Draynor Manor", "door_name": "Door"},
            {"region": 11575, "px": 3016, "py": 3514, "door_x": 3016, "door_y": 3515,
             "target_desc": "inside Black Knights Fortress", "door_name": "Door"},
        ]
        scenario = random.choice(door_scenarios)
        px, py = jitter(scenario["px"], 2), jitter(scenario["py"], 2)

        skills = make_default_skills(random.randint(5, 30))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        nearby_objs = [
            nearby_entity(scenario["door_name"], scenario["door_x"], scenario["door_y"], 1, actions=["Open"]),
        ]

        state = serialize_game_state(
            name, random.randint(5, 40), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IDLE", skills, [], [],
            [], nearby_objs, [], [],
            scenario["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Explore.",
            action_results=["** GAME_FAILURE: I can't reach that! **"],
        )

        actions = [
            {"action": "INTERACT_OBJECT", "name": scenario["door_name"], "option": "Open"},
        ]

        reasoning = (
            f"I got a 'can't reach that' error. There's a closed door blocking my path. "
            f"I need to open it first before I can continue."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Explore."), state, response))

    return examples


def gen_action_result_scenarios():
    """Generate diverse action result response scenarios."""
    examples = []

    # "Inventory is full" (~15)
    for _ in range(15):
        name = random_player()
        skill = random.choice(["Mining", "Woodcutting", "Fishing"])

        if skill == "Mining":
            tool = best_tool_for_level("pickaxe", random.randint(15, 41))
            item = "Iron ore"
            resource = "Iron rocks"
            res_action = "Mine"
        elif skill == "Woodcutting":
            tool = best_tool_for_level("axe", random.randint(15, 41))
            item = "Willow logs"
            resource = "Willow tree"
            res_action = "Chop down"
        else:
            tool = "Fly fishing rod"
            item = "Raw trout"
            resource = "Fly fishing spot"
            res_action = "Lure"

        skills = make_default_skills(random.randint(15, 45))
        skills[skill] = random.randint(15, 45)
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        inv = [(tool, 1), (item, 27)]
        bank = random.choice(BANK_LOCATIONS)

        state = serialize_game_state(
            name, random.randint(10, 40), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            3200, 3200, 0,
            "IDLE", skills, inv, [(tool, "Weapon")],
            [], [], [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Train {skill}.",
            action_results=["Your inventory is too full to hold any more."],
        )

        actions = [
            {"action": "PATH_TO", "x": bank["coords"][0], "y": bank["coords"][1]},
            {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
            {"action": "BANK_DEPOSIT_ALL"},
            {"action": "BANK_CLOSE"},
        ]

        reasoning = "My inventory is full. Time to bank everything and come back for more."
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt(f"Train {skill}."), state, response))

    # "You need a higher level" (~15)
    for _ in range(15):
        name = random_player()
        required_level = random.choice([15, 30, 40, 55])
        player_level = required_level - random.randint(1, 5)
        skill = random.choice(["Mining", "Woodcutting"])

        if skill == "Mining":
            resource = "Coal rocks" if required_level == 30 else "Iron rocks"
            fallback = "Copper rocks" if required_level == 15 else "Iron rocks"
            fb_action = "Mine"
        else:
            resource = "Willow tree" if required_level == 30 else "Oak tree"
            fallback = "Tree" if required_level == 15 else "Oak tree"
            fb_action = "Chop down"

        skills = make_default_skills(1)
        skills[skill] = player_level
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        nearby_objs = [
            nearby_entity(fallback, 3202, 3202, 3, actions=[fb_action]),
        ]

        state = serialize_game_state(
            name, random.randint(10, 40), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            3200, 3200, 0,
            "IDLE", skills, [], [],
            [], nearby_objs, [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal=f"Train {skill}.",
            action_results=[f"You need a {skill} level of {required_level} to {fb_action.lower()} this."],
        )

        actions = [
            {"action": "INTERACT_OBJECT", "name": fallback, "option": fb_action},
            {"action": "WAIT_ANIMATION", "ticks": 20},
        ]

        reasoning = (
            f"I need level {required_level} {skill} but I'm only level {player_level}. "
            f"I'll train on {fallback} instead until I reach the required level."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt(f"Train {skill}."), state, response))

    # "Someone else is fighting that" (~15)
    for _ in range(15):
        name = random_player()
        npc = random.choice(COMBAT_NPCS)
        loc = random.choice(npc["locations"])
        coord = random.choice(loc["coords"])
        px, py = jitter(coord[0]), jitter(coord[1])

        skills = make_default_skills(random.randint(10, 40))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = random.randint(10, 50)

        # Multiple NPCs nearby, one occupied
        nearby_npcs = [
            nearby_entity(npc["name"], jitter(px, 3), jitter(py, 3), 2,
                         actions=["Attack"], level=npc["level"], hp_pct=60),
            nearby_entity(npc["name"], jitter(px, 6), jitter(py, 6), 5,
                         actions=["Attack"], level=npc["level"], hp_pct=100),
        ]

        state = serialize_game_state(
            name, cb, random.randint(15, 40), 40,
            random.randint(1, 20), 30, 100, True, random.randint(0, 5), 100,
            px, py, 0,
            "IDLE", skills, [("Lobster", random.randint(5, 15))], [],
            nearby_npcs, [], [], [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Train combat.",
            action_results=["** GAME_FAILURE: Someone else is already fighting that. **"],
        )

        actions = [
            {"action": "INTERACT_NPC", "name": npc["name"], "option": "Attack"},
            {"action": "WAIT_ANIMATION", "ticks": 20},
        ]

        reasoning = (
            f"Someone else is already fighting that {npc['name']}. I'll target a different one nearby."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Train combat."), state, response))

    # "You can't reach that" (~15)
    for _ in range(15):
        name = random_player()
        skills = make_default_skills(random.randint(5, 30))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        # Door blocking path
        nearby_objs = [
            nearby_entity("Door", 3209, 3216, 1, actions=["Open"]),
            nearby_entity("Bank booth", 3208, 3220, 5, actions=["Bank"]),
        ]

        state = serialize_game_state(
            name, random.randint(5, 30), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            3210, 3217, 0,
            "IDLE", skills, [], [],
            [], nearby_objs, [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Go to the bank.",
            action_results=["** GAME_FAILURE: I can't reach that! **"],
        )

        actions = [
            {"action": "INTERACT_OBJECT", "name": "Door", "option": "Open"},
        ]

        reasoning = (
            "I can't reach my destination — there's probably a door or obstacle in the way. "
            "I see a door nearby, let me try opening it."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Go to the bank."), state, response))

    # "You need to complete a quest" (~10)
    for _ in range(10):
        name = random_player()
        skills = make_default_skills(random.randint(5, 30))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        state = serialize_game_state(
            name, random.randint(5, 30), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            3200, 3200, 0,
            "IDLE", skills, [], [],
            [], [], [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Explore and train.",
            action_results=["You need to have completed the quest: Dragon Slayer I."],
        )

        # Try something else instead
        actions = [
            {"action": "PATH_TO", "x": 3253, "y": 3270},
        ]

        reasoning = (
            "I need to complete Dragon Slayer I to access this content. "
            "I'll do something else for now — let me go train combat at cows instead."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Explore and train."), state, response))

    # "Not enough coins" (~10)
    for _ in range(10):
        name = random_player()
        skills = make_default_skills(random.randint(5, 30))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}

        coins = random.randint(1, 50)
        inv = [("Coins", coins)]
        shop_npc = random.choice(SHOP_NPCS)

        state = serialize_game_state(
            name, random.randint(5, 30), random.randint(10, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            3200, 3200, 0,
            "IDLE", skills, inv, [],
            [nearby_entity(shop_npc["name"], 3202, 3202, 2, actions=["Trade"])], [], [], [],
            12850, random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Buy supplies.",
            action_results=["You don't have enough coins."],
        )

        # Go earn money
        actions = [
            {"action": "PATH_TO", "x": 3253, "y": 3270},
        ]

        reasoning = (
            f"I only have {coins} coins — not enough to buy what I need. "
            f"I should go kill some monsters and collect loot to earn more money."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Buy supplies."), state, response))

    # NPC death / loot appears (~20)
    for _ in range(20):
        name = random_player()
        npc = random.choice(COMBAT_NPCS)
        loc = random.choice(npc["locations"])
        coord = random.choice(loc["coords"])
        px, py = jitter(coord[0]), jitter(coord[1])

        skills = make_default_skills(random.randint(10, 40))
        xp = {s: random_xp_at_level(l) for s, l in skills.items()}
        cb = random.randint(15, 50)

        # Loot on ground
        loot_options = [
            [("Bones", px, py), ("Coins", px + 1, py)],
            [("Bones", px, py), ("Iron scimitar", px, py + 1)],
            [("Bones", px, py), ("Iron ore", px + 1, py)],
            [("Big bones", px, py), ("Nature rune", px, py + 1)],
        ]
        loot = random.choice(loot_options)
        ground_items = [nearby_entity(item[0], item[1], item[2], 1) for item in loot]

        # More NPCs to attack
        nearby_npcs = [
            nearby_entity(npc["name"], jitter(px, 5), jitter(py, 5), 4,
                         actions=["Attack"], level=npc["level"], hp_pct=100),
        ]

        inv_count = random.randint(3, 20)
        inv = [("Lobster", inv_count)]

        state = serialize_game_state(
            name, cb, random.randint(15, 40), 40,
            random.randint(1, 20), 30, 100, True, 0, 100,
            px, py, 0,
            "IDLE", skills, inv, [],
            nearby_npcs, [], ground_items, [],
            loc["region"], random_world(), "Inventory", "Accurate", random_tick(),
            current_goal="Train combat.",
        )

        # Pick up valuable loot, bury bones, attack next
        actions = []
        for item in loot:
            if item[0] in ("Bones", "Big bones"):
                actions.append({"action": "PICKUP_ITEM", "name": item[0]})
        # Bury bones if picked up
        for item in loot:
            if item[0] in ("Bones", "Big bones"):
                actions.append({"action": "USE_ITEM", "name": item[0], "option": "Bury"})
                break
        # Pick up valuable drops
        for item in loot:
            if item[0] not in ("Bones", "Big bones"):
                actions.append({"action": "PICKUP_ITEM", "name": item[0]})
        # Attack next NPC
        actions.append({"action": "INTERACT_NPC", "name": npc["name"], "option": "Attack"})
        actions.append({"action": "WAIT_ANIMATION", "ticks": 20})

        loot_names = [item[0] for item in loot]
        reasoning = (
            f"The {npc['name']} is dead. I see loot on the ground: {', '.join(loot_names)}. "
            f"I'll pick up the bones to bury for Prayer XP, grab any valuable drops, then attack the next one."
        )
        response = f"{reasoning}\n{json.dumps(actions)}"
        examples.append(make_example(pick_prompt("Train combat."), state, response))

    return examples


def gen_interact_option_scenarios():
    """Generate examples teaching the LLM to read [NEARBY_OBJECTS] and [NEARBY_NPCS] action lists
    and correctly use INTERACT_OBJECT/INTERACT_NPC with proper name+option fields.
    Also distinguishes PICKUP_ITEM (ground items) from INTERACT_OBJECT (world objects)."""
    examples = []

    # ── Object interactions: Pick, Mine, Chop, Open, Close, Climb, Bank ──

    # Picking objects (Potato, Cabbage, Wheat, Flax, Onion)
    pickables = [
        {"name": "Potato", "option": "Pick", "item": "Potato", "area": "Lumbridge farm", "cx": 3260, "cy": 3300},
        {"name": "Cabbage", "option": "Pick", "item": "Cabbage", "area": "Draynor Manor", "cx": 3087, "cy": 3355},
        {"name": "Wheat", "option": "Pick", "item": "Grain", "area": "Lumbridge mill", "cx": 3162, "cy": 3293},
        {"name": "Flax", "option": "Pick", "item": "Flax", "area": "Seers' Village", "cx": 2739, "cy": 3443},
        {"name": "Onion", "option": "Pick", "item": "Onion", "area": "Lumbridge farm", "cx": 3227, "cy": 3302},
    ]
    for p in pickables:
        for _ in range(4):
            cx, cy = p["cx"] + random.randint(-3, 3), p["cy"] + random.randint(-3, 3)
            inv_count = random.randint(0, 20)
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(3, 40),
                hp=random.randint(8, 20), max_hp=random.randint(10, 20),
                prayer=1, max_prayer=1, run_energy=random.randint(50, 100), run_on=True,
                weight=random.randint(0, 10), spec_pct=100,
                pos_x=cx, pos_y=cy, plane=0, status="IDLE", tick=random.randint(100, 5000),
                skills={}, inventory=[(p["item"], inv_count)] if inv_count > 0 else [],
                equipment=[],
                nearby_npcs=[nearby_entity("Farmer", cx + random.randint(3, 10), cy + random.randint(-5, 5), random.randint(3, 10))],
                nearby_objects=[nearby_entity(p["name"], cx + random.randint(1, 3), cy + random.randint(-2, 2), random.randint(1, 3), [p["option"]])],
                nearby_ground_items=[nearby_entity("Bones", cx + random.randint(5, 12), cy + random.randint(-5, 5), random.randint(5, 12))],
                nearby_players=[], region_id=12851, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
                current_goal=f"Collect {p['item'].lower()}s" if inv_count > 0 else None,
            )
            reasoning = (f"I can see {p['name']} nearby with the [Pick] option. Since it's in [NEARBY_OBJECTS], "
                         f"I need to use INTERACT_OBJECT with name=\"{p['name']}\" and option=\"{p['option']}\".")
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": p["name"], "option": p["option"],
                 **( {"goal": f"Pick {p['item'].lower()}s"} if inv_count == 0 else {})},
            ])
            examples.append(make_example(pick_prompt(f"Collect {p['item'].lower()}s."), state, f"{reasoning}\n{actions}"))

    # Distinguish PICKUP_ITEM (ground items) from INTERACT_OBJECT (world objects)
    # Scenario: both ground items AND pickable objects are nearby — model must choose correctly
    for _ in range(15):
        cx, cy = 3260 + random.randint(-5, 5), 3300 + random.randint(-5, 5)
        ground_item = random.choice(["Bones", "Cowhide", "Raw beef", "Feather"])
        pick_obj = random.choice(["Potato", "Cabbage", "Onion"])

        state = serialize_game_state(
            player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 30),
            hp=10, max_hp=10, prayer=1, max_prayer=1,
            run_energy=random.randint(50, 100), run_on=True,
            weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
            status="IDLE", tick=random.randint(100, 5000),
            skills={}, inventory=[],
            equipment=[],
            nearby_npcs=[],
            nearby_objects=[nearby_entity(pick_obj, cx + 2, cy + 1, 2, ["Pick"])],
            nearby_ground_items=[nearby_entity(ground_item, cx + 3, cy - 1, 3)],
            nearby_players=[], region_id=12851, world=random.randint(301, 400),
            tab="inventory", attack_style="Accurate",
        )
        # Half pick the object, half pick up the ground item
        if random.random() < 0.5:
            reasoning = (f"I want to pick {pick_obj.lower()}s. {pick_obj} is listed in [NEARBY_OBJECTS] with option [Pick], "
                         f"so I use INTERACT_OBJECT — NOT PICKUP_ITEM (which is only for [NEARBY_GROUND_ITEMS]).")
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": pick_obj, "option": "Pick", "goal": f"Pick {pick_obj.lower()}s"},
            ])
            examples.append(make_example(pick_prompt(f"Gather food."), state, f"{reasoning}\n{actions}"))
        else:
            reasoning = (f"I want to pick up {ground_item.lower()}. {ground_item} is in [NEARBY_GROUND_ITEMS], "
                         f"so I use PICKUP_ITEM — NOT INTERACT_OBJECT (which is for [NEARBY_OBJECTS]).")
            actions = json.dumps([
                {"action": "PICKUP_ITEM", "name": ground_item, "goal": f"Pick up {ground_item.lower()}"},
            ])
            examples.append(make_example(pick_prompt(f"Gather items."), state, f"{reasoning}\n{actions}"))

    # Opening/closing doors and gates
    door_scenarios = [
        {"name": "Door", "option": "Open", "area": "house"},
        {"name": "Gate", "option": "Open", "area": "farm"},
        {"name": "Large door", "option": "Open", "area": "castle"},
    ]
    for d in door_scenarios:
        for _ in range(4):
            cx, cy = 3200 + random.randint(-50, 50), 3200 + random.randint(-50, 50)
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(3, 50),
                hp=15, max_hp=15, prayer=1, max_prayer=1,
                run_energy=random.randint(40, 100), run_on=True,
                weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills={}, inventory=[],
                equipment=[],
                nearby_npcs=[],
                nearby_objects=[nearby_entity(d["name"], cx + 2, cy, 2, [d["option"]]),
                                nearby_entity("Iron rocks", cx + 8, cy + 3, 8, ["Mine"])],
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
                current_goal="Mine iron",
                action_results=["INTERACT_OBJECT(Iron rocks/Mine) -> FAILED: I can't reach that!"],
            )
            reasoning = (f"I can't reach the iron rocks — there's a {d['name'].lower()} in the way. "
                         f"I need to open it first. The {d['name']} in [NEARBY_OBJECTS] has option [{d['option']}].")
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": d["name"], "option": d["option"]},
                {"action": "INTERACT_OBJECT", "name": "Iron rocks", "option": "Mine"},
                {"action": "WAIT_ANIMATION"},
            ])
            examples.append(make_example(pick_prompt("Mine iron."), state, f"{reasoning}\n{actions}"))

    # Climbing stairs/ladders
    climb_scenarios = [
        {"name": "Staircase", "option": "Climb-up", "dest_plane": 1},
        {"name": "Staircase", "option": "Climb-down", "dest_plane": 0},
        {"name": "Ladder", "option": "Climb-up", "dest_plane": 1},
        {"name": "Ladder", "option": "Climb-down", "dest_plane": 0},
    ]
    for c in climb_scenarios:
        for _ in range(3):
            cx, cy = 3206 + random.randint(-3, 3), 3209 + random.randint(-3, 3)
            plane = 0 if "up" in c["option"].lower() else 1
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(3, 50),
                hp=15, max_hp=15, prayer=1, max_prayer=1,
                run_energy=random.randint(40, 100), run_on=True,
                weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=plane,
                status="IDLE", tick=random.randint(100, 5000),
                skills={}, inventory=[],
                equipment=[],
                nearby_npcs=[],
                nearby_objects=[nearby_entity(c["name"], cx + 1, cy, 1, [c["option"]])],
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
                current_goal="Go to Lumbridge bank",
            )
            reasoning = (f"I need to {c['option'].lower()} the {c['name'].lower()} to reach the next floor. "
                         f"The {c['name']} in [NEARBY_OBJECTS] has option [{c['option']}].")
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": c["name"], "option": c["option"]},
            ])
            examples.append(make_example(pick_prompt("Go to bank."), state, f"{reasoning}\n{actions}"))

    # Banking — choosing between Bank booth (object) and Banker (NPC)
    for _ in range(10):
        bank = random.choice(BANK_LOCATIONS)
        cx, cy = bank["coords"][0] + random.randint(-3, 3), bank["coords"][1] + random.randint(-3, 3)
        use_npc = random.random() < 0.4
        if use_npc:
            nearby_npcs = [nearby_entity("Banker", cx + 2, cy + 1, 2, ["Bank", "Talk-to", "Collect"])]
            nearby_objs = []
        else:
            nearby_npcs = [nearby_entity("Banker", cx + 5, cy + 3, 5, ["Bank", "Talk-to", "Collect"])]
            nearby_objs = [nearby_entity("Bank booth", cx + 1, cy, 1, ["Bank"])]

        state = serialize_game_state(
            player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(3, 80),
            hp=20, max_hp=20, prayer=1, max_prayer=1,
            run_energy=random.randint(40, 100), run_on=True,
            weight=15, spec_pct=100, pos_x=cx, pos_y=cy, plane=bank.get("plane", 0),
            status="IDLE", tick=random.randint(100, 5000),
            skills={"Mining": random.randint(10, 50)},
            inventory=[("Iron ore", random.randint(15, 28))],
            equipment=[("Weapon", "Steel pickaxe")],
            nearby_npcs=nearby_npcs,
            nearby_objects=nearby_objs,
            nearby_ground_items=[], nearby_players=[],
            region_id=bank["region"], world=random.randint(301, 400),
            tab="inventory", attack_style="Accurate",
            current_goal="Bank iron ore",
        )
        if use_npc:
            reasoning = ("I need to bank my ores. The nearest Banker NPC is 2 tiles away "
                         "with the [Bank] option. I'll use INTERACT_NPC.")
            actions = json.dumps([
                {"action": "INTERACT_NPC", "name": "Banker", "option": "Bank"},
                {"action": "BANK_DEPOSIT", "name": "Iron ore", "quantity": -1},
                {"action": "BANK_CLOSE"},
            ])
        else:
            reasoning = ("I need to bank my ores. The Bank booth is 1 tile away "
                         "with the [Bank] option. I'll use INTERACT_OBJECT.")
            actions = json.dumps([
                {"action": "INTERACT_OBJECT", "name": "Bank booth", "option": "Bank"},
                {"action": "BANK_DEPOSIT", "name": "Iron ore", "quantity": -1},
                {"action": "BANK_CLOSE"},
            ])
        examples.append(make_example(pick_prompt("Bank items."), state, f"{reasoning}\n{actions}"))

    # ── NPC interactions: Attack, Talk-to, Trade, Pickpocket ──

    # Attacking NPCs — choosing Attack from action list
    combat_targets = [
        {"name": "Cow", "level": 2, "option": "Attack"},
        {"name": "Cow calf", "level": 2, "option": "Attack"},
        {"name": "Goblin", "level": 2, "option": "Attack"},
        {"name": "Chicken", "level": 1, "option": "Attack"},
        {"name": "Giant rat", "level": 3, "option": "Attack"},
        {"name": "Man", "level": 2, "option": "Attack"},
        {"name": "Guard", "level": 21, "option": "Attack"},
    ]
    for npc in combat_targets:
        for _ in range(2):
            cx, cy = 3200 + random.randint(-80, 80), 3250 + random.randint(-80, 80)
            food = random.choice(["Trout", "Salmon", "Lobster", "Shrimps"])
            food_count = random.randint(3, 10)
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 40),
                hp=random.randint(10, 25), max_hp=25,
                prayer=1, max_prayer=1, run_energy=random.randint(50, 100), run_on=True,
                weight=10, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills={"Atk": random.randint(5, 30), "Str": random.randint(5, 30), "Def": random.randint(5, 30)},
                inventory=[(food, food_count)],
                equipment=[("Weapon", random.choice(["Iron scimitar", "Steel scimitar", "Bronze sword"]))],
                nearby_npcs=[nearby_entity(npc["name"], cx + random.randint(2, 5), cy + random.randint(-3, 3),
                                           random.randint(2, 5), [npc["option"]], level=npc["level"], hp_pct=100)],
                nearby_objects=[],
                nearby_ground_items=[],
                nearby_players=[], region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
                current_goal="Train combat",
            )
            reasoning = (f"I see a {npc['name']} (level {npc['level']}) nearby with the [{npc['option']}] option. "
                         f"I'll use INTERACT_NPC with name=\"{npc['name']}\" and option=\"{npc['option']}\".")
            actions = json.dumps([
                {"action": "INTERACT_NPC", "name": npc["name"], "option": npc["option"]},
                {"action": "WAIT_ANIMATION", "ticks": 20},
            ])
            examples.append(make_example(pick_prompt("Train combat."), state, f"{reasoning}\n{actions}"))

    # Talking to NPCs
    talk_npcs = [
        {"name": "Hans", "area": "Lumbridge", "cx": 3222, "cy": 3218},
        {"name": "Duke Horacio", "area": "Lumbridge Castle", "cx": 3210, "cy": 3220},
        {"name": "Doric", "area": "north of Falador", "cx": 2951, "cy": 3450},
        {"name": "Cook", "area": "Lumbridge kitchen", "cx": 3208, "cy": 3215},
        {"name": "Aubury", "area": "Varrock", "cx": 3253, "cy": 3401},
    ]
    for npc in talk_npcs:
        cx, cy = npc["cx"] + random.randint(-2, 2), npc["cy"] + random.randint(-2, 2)
        state = serialize_game_state(
            player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(3, 40),
            hp=15, max_hp=15, prayer=1, max_prayer=1,
            run_energy=random.randint(50, 100), run_on=True,
            weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
            status="IDLE", tick=random.randint(100, 5000),
            skills={}, inventory=[],
            equipment=[],
            nearby_npcs=[nearby_entity(npc["name"], cx + 2, cy + 1, 2, ["Talk-to"])],
            nearby_objects=[],
            nearby_ground_items=[], nearby_players=[],
            region_id=12850, world=random.randint(301, 400),
            tab="inventory", attack_style="Accurate",
        )
        reasoning = (f"I need to talk to {npc['name']}. The NPC is in [NEARBY_NPCS] with option [Talk-to]. "
                     f"I use INTERACT_NPC with name=\"{npc['name']}\" and option=\"Talk-to\".")
        actions = json.dumps([
            {"action": "INTERACT_NPC", "name": npc["name"], "option": "Talk-to", "goal": f"Talk to {npc['name']}"},
        ])
        examples.append(make_example(pick_prompt(f"Talk to {npc['name']}."), state, f"{reasoning}\n{actions}"))

    # NPCs with multiple options — model must choose the right one
    multi_option_npcs = [
        {"name": "Shop keeper", "options": ["Trade", "Talk-to"], "choose": "Trade", "reason": "buy supplies"},
        {"name": "Banker", "options": ["Bank", "Talk-to", "Collect"], "choose": "Bank", "reason": "open the bank"},
        {"name": "Man", "options": ["Talk-to", "Attack", "Pickpocket"], "choose": "Pickpocket", "reason": "train Thieving"},
        {"name": "Farmer", "options": ["Talk-to", "Attack", "Pickpocket"], "choose": "Attack", "reason": "train combat"},
        {"name": "Guard", "options": ["Talk-to", "Attack"], "choose": "Talk-to", "reason": "ask for directions"},
    ]
    for scenario in multi_option_npcs:
        for _ in range(3):
            cx, cy = 3200 + random.randint(-50, 50), 3300 + random.randint(-50, 50)
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                hp=20, max_hp=20, prayer=1, max_prayer=1,
                run_energy=random.randint(50, 100), run_on=True,
                weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills={"Atk": random.randint(5, 40), "Thiev": random.randint(1, 30)},
                inventory=[],
                equipment=[("Weapon", "Iron scimitar")],
                nearby_npcs=[nearby_entity(scenario["name"], cx + 3, cy + 1, 3,
                                           scenario["options"], level=random.randint(2, 21))],
                nearby_objects=[],
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
            )
            opt_str = ", ".join(scenario["options"])
            reasoning = (f"The {scenario['name']} has options [{opt_str}]. "
                         f"I want to {scenario['reason']}, so I choose option=\"{scenario['choose']}\".")
            actions = json.dumps([
                {"action": "INTERACT_NPC", "name": scenario["name"], "option": scenario["choose"],
                 "goal": scenario["reason"].capitalize()},
            ])
            examples.append(make_example(pick_prompt(scenario["reason"].capitalize() + "."), state, f"{reasoning}\n{actions}"))

    # Objects with multiple options — choose correctly
    multi_option_objects = [
        {"name": "Furnace", "options": ["Smelt", "Smith"], "choose": "Smelt", "reason": "smelt bars",
         "inv": [("Iron ore", 14)], "equip": []},
        {"name": "Anvil", "options": ["Smith"], "choose": "Smith", "reason": "smith items",
         "inv": [("Iron bar", 14), ("Hammer", 1)], "equip": []},
        {"name": "Range", "options": ["Cook"], "choose": "Cook", "reason": "cook food",
         "inv": [("Raw trout", 14)], "equip": []},
        {"name": "Dairy cow", "options": ["Milk", "Steal-cowbell"], "choose": "Milk", "reason": "milk the cow",
         "inv": [("Bucket", 1)], "equip": []},
        {"name": "Spinning wheel", "options": ["Spin"], "choose": "Spin", "reason": "spin flax into bowstrings",
         "inv": [("Flax", 28)], "equip": []},
    ]
    for scenario in multi_option_objects:
        for _ in range(3):
            cx, cy = 3200 + random.randint(-50, 50), 3200 + random.randint(-50, 50)
            state = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                hp=20, max_hp=20, prayer=1, max_prayer=1,
                run_energy=random.randint(50, 100), run_on=True,
                weight=10, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills={"Cooking": random.randint(1, 50), "Smith": random.randint(1, 50), "Craft": random.randint(1, 50)},
                inventory=scenario["inv"],
                equipment=scenario["equip"],
                nearby_npcs=[],
                nearby_objects=[nearby_entity(scenario["name"], cx + 2, cy, 2, scenario["options"])],
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
            )
            opt_str = ", ".join(scenario["options"])
            reasoning = (f"The {scenario['name']} in [NEARBY_OBJECTS] has options [{opt_str}]. "
                         f"I want to {scenario['reason']}, so I use option=\"{scenario['choose']}\".")
            actions_list = [
                {"action": "INTERACT_OBJECT", "name": scenario["name"], "option": scenario["choose"],
                 "goal": scenario["reason"].capitalize()},
            ]
            if scenario["choose"] in ("Smelt", "Cook", "Smith", "Spin"):
                actions_list.append({"action": "WAIT_ANIMATION", "ticks": 30})
            actions = json.dumps(actions_list)
            examples.append(make_example(pick_prompt(scenario["reason"].capitalize() + "."), state, f"{reasoning}\n{actions}"))

    # ── Recovery from wrong action format (learning from PARSE_ERROR feedback) ──
    # Show model receiving a parse error and correcting its format
    recovery_scenarios = [
        {
            "error": 'PARSE_ERROR: Unknown action "Pick" in {"action":"Pick","object":"Potato","goal":"Pick potatoes"}. '
                     'Use "name" for the target name and "option" for the verb.',
            "correction_reasoning": 'My last action was malformed — I put "Pick" in the "action" field instead of using '
                                    'INTERACT_OBJECT with option="Pick". Let me fix the format.',
            "obj_name": "Potato", "obj_option": "Pick",
        },
        {
            "error": 'PARSE_ERROR: Unknown action "Attack" in {"action":"Attack","name":"Goblin"}. '
                     'Use "name" for the target name and "option" for the verb.',
            "correction_reasoning": 'I used "Attack" as the action type, but the correct action is INTERACT_NPC '
                                    'with option="Attack". Let me use the right format.',
            "npc_name": "Goblin", "npc_option": "Attack",
        },
        {
            "error": 'PARSE_ERROR: Unknown action "Talk-to" in {"action":"Talk-to","name":"Banker"}. '
                     'Use "name" for the target name and "option" for the verb.',
            "correction_reasoning": 'I incorrectly used "Talk-to" as the action type. The correct action is INTERACT_NPC '
                                    'with name="Banker" and option="Talk-to".',
            "npc_name": "Banker", "npc_option": "Talk-to",
        },
        {
            "error": 'PARSE_ERROR: Unknown action "Mine" in {"action":"Mine","name":"Iron rocks"}. '
                     'Use "name" for the target name and "option" for the verb.',
            "correction_reasoning": 'I used "Mine" as the action type, but it should be INTERACT_OBJECT '
                                    'with name="Iron rocks" and option="Mine".',
            "obj_name": "Iron rocks", "obj_option": "Mine",
        },
        {
            "error": 'PARSE_ERROR: Unknown action "Chop down" in {"action":"Chop down","name":"Oak tree"}. '
                     'Valid actions: INTERACT_OBJECT, INTERACT_NPC, etc.',
            "correction_reasoning": 'I used "Chop down" as the action type, but it should be INTERACT_OBJECT '
                                    'with name="Oak tree" and option="Chop down".',
            "obj_name": "Oak tree", "obj_option": "Chop down",
        },
    ]
    for scenario in recovery_scenarios:
        for _ in range(3):
            cx, cy = 3200 + random.randint(-80, 80), 3250 + random.randint(-80, 80)
            is_obj = "obj_name" in scenario

            if is_obj:
                state = serialize_game_state(
                    player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                    hp=20, max_hp=20, prayer=1, max_prayer=1,
                    run_energy=random.randint(50, 100), run_on=True,
                    weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                    status="IDLE", tick=random.randint(100, 5000),
                    skills={"Mining": 30, "WC": 30},
                    inventory=[],
                    equipment=[("Weapon", "Rune pickaxe")],
                    nearby_npcs=[],
                    nearby_objects=[nearby_entity(scenario["obj_name"], cx + 2, cy, 2, [scenario["obj_option"]])],
                    nearby_ground_items=[], nearby_players=[],
                    region_id=12850, world=random.randint(301, 400),
                    tab="inventory", attack_style="Accurate",
                    action_results=[scenario["error"]],
                )
                actions = json.dumps([
                    {"action": "INTERACT_OBJECT", "name": scenario["obj_name"], "option": scenario["obj_option"]},
                    *([{"action": "WAIT_ANIMATION"}] if scenario["obj_option"] in ("Mine", "Chop down") else []),
                ])
            else:
                state = serialize_game_state(
                    player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                    hp=20, max_hp=20, prayer=1, max_prayer=1,
                    run_energy=random.randint(50, 100), run_on=True,
                    weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                    status="IDLE", tick=random.randint(100, 5000),
                    skills={"Atk": 20, "Str": 20},
                    inventory=[("Trout", 5)],
                    equipment=[("Weapon", "Iron scimitar")],
                    nearby_npcs=[nearby_entity(scenario["npc_name"], cx + 3, cy + 1, 3,
                                               [scenario["npc_option"]], level=random.randint(2, 21), hp_pct=100)],
                    nearby_objects=[],
                    nearby_ground_items=[], nearby_players=[],
                    region_id=12850, world=random.randint(301, 400),
                    tab="inventory", attack_style="Accurate",
                    action_results=[scenario["error"]],
                )
                actions = json.dumps([
                    {"action": "INTERACT_NPC", "name": scenario["npc_name"], "option": scenario["npc_option"]},
                    *([{"action": "WAIT_ANIMATION", "ticks": 20}] if scenario["npc_option"] == "Attack" else []),
                ])

            response = f"{scenario['correction_reasoning']}\n{actions}"
            examples.append(make_example(pick_prompt("Continue task."), state, response))

    # ── 2-turn: query options (no option) → get available options → use correct one ──
    # These are multi-turn conversations: LLM sends action without option, gets error with
    # available options listed, then sends correct action on turn 2.

    query_scenarios = [
        # Object scenarios
        {"type": "object", "name": "Potato", "available": "Pick", "goal": "pick potatoes for food",
         "cx": 3260, "cy": 3300, "inv": [], "equip": [],
         "skills": {}, "extra_objs": [], "extra_npcs": []},
        {"type": "object", "name": "Iron rocks", "available": "Mine", "goal": "mine iron ore",
         "cx": 3285, "cy": 3363, "inv": [], "equip": [("Weapon", "Rune pickaxe")],
         "skills": {"Mining": 30}, "extra_objs": [], "extra_npcs": []},
        {"type": "object", "name": "Oak tree", "available": "Chop down", "goal": "chop oak logs",
         "cx": 3165, "cy": 3415, "inv": [], "equip": [("Weapon", "Steel axe")],
         "skills": {"WC": 20}, "extra_objs": [], "extra_npcs": []},
        {"type": "object", "name": "Furnace", "available": "Smelt, Smith", "goal": "smelt iron bars",
         "cx": 3109, "cy": 3499, "inv": [("Iron ore", 14)], "equip": [],
         "skills": {"Smith": 15}, "extra_objs": [], "extra_npcs": [],
         "correct_option": "Smelt"},
        {"type": "object", "name": "Bank booth", "available": "Bank", "goal": "deposit my items",
         "cx": 3185, "cy": 3436, "inv": [("Iron ore", 20)], "equip": [],
         "skills": {}, "extra_objs": [], "extra_npcs": []},
        {"type": "object", "name": "Staircase", "available": "Climb-up, Climb-down", "goal": "go upstairs to Lumbridge bank",
         "cx": 3206, "cy": 3209, "inv": [], "equip": [],
         "skills": {}, "extra_objs": [], "extra_npcs": [],
         "correct_option": "Climb-up"},
        {"type": "object", "name": "Dairy cow", "available": "Milk, Steal-cowbell", "goal": "get a bucket of milk",
         "cx": 3253, "cy": 3273, "inv": [("Bucket", 1)], "equip": [],
         "skills": {}, "extra_objs": [], "extra_npcs": [],
         "correct_option": "Milk"},
        # NPC scenarios
        {"type": "npc", "name": "Cow", "available": "Attack", "goal": "train combat on cows",
         "cx": 3260, "cy": 3280, "inv": [("Trout", 5)], "equip": [("Weapon", "Iron scimitar")],
         "skills": {"Atk": 15, "Str": 10}, "extra_objs": [], "extra_npcs": [],
         "level": 2},
        {"type": "npc", "name": "Shop keeper", "available": "Trade, Talk-to", "goal": "buy supplies from the shop",
         "cx": 3211, "cy": 3414, "inv": [("Coins", 500)], "equip": [],
         "skills": {}, "extra_objs": [], "extra_npcs": [],
         "level": 0, "correct_option": "Trade"},
        {"type": "npc", "name": "Man", "available": "Talk-to, Attack, Pickpocket", "goal": "train thieving",
         "cx": 3222, "cy": 3218, "inv": [], "equip": [],
         "skills": {"Thiev": 5}, "extra_objs": [], "extra_npcs": [],
         "level": 2, "correct_option": "Pickpocket"},
    ]

    for scenario in query_scenarios:
        for _ in range(3):
            cx = scenario["cx"] + random.randint(-3, 3)
            cy = scenario["cy"] + random.randint(-3, 3)
            is_obj = scenario["type"] == "object"
            correct_option = scenario.get("correct_option", scenario["available"].split(",")[0].strip())

            # Turn 1: game state where LLM sends action without option
            if is_obj:
                nearby_objs = [nearby_entity(scenario["name"], cx + 2, cy, 2,
                                             [o.strip() for o in scenario["available"].split(",")])]
            else:
                nearby_objs = []
            if not is_obj:
                nearby_npcs = [nearby_entity(scenario["name"], cx + 3, cy + 1, 3,
                                             [o.strip() for o in scenario["available"].split(",")],
                                             level=scenario.get("level", 0), hp_pct=100)]
            else:
                nearby_npcs = []

            state_turn1 = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                hp=20, max_hp=20, prayer=1, max_prayer=1,
                run_energy=random.randint(50, 100), run_on=True,
                weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills=scenario["skills"],
                inventory=scenario["inv"],
                equipment=scenario["equip"],
                nearby_npcs=nearby_npcs,
                nearby_objects=nearby_objs,
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
            )

            # LLM's first (wrong) response: no option
            action_type = "INTERACT_OBJECT" if is_obj else "INTERACT_NPC"
            bad_reasoning = f"I need to {scenario['goal']}. Let me interact with {scenario['name']}."
            bad_actions = json.dumps([{"action": action_type, "name": scenario["name"], "goal": scenario["goal"].capitalize()}])
            bad_response = f"{bad_reasoning}\n{bad_actions}"

            # Turn 2: game state with error feedback showing available options
            error_msg = (f'{action_type}(name={scenario["name"]}) -> FAILED: '
                         f'Missing "option" for {scenario["name"]}. Available options: [{scenario["available"]}]. '
                         f'Use: {{"action":"{action_type}","name":"{scenario["name"]}","option":"<one of these>"}}')

            state_turn2 = serialize_game_state(
                player_name=random.choice(PLAYER_NAMES), combat_level=random.randint(5, 50),
                hp=20, max_hp=20, prayer=1, max_prayer=1,
                run_energy=random.randint(50, 100), run_on=True,
                weight=5, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 5000),
                skills=scenario["skills"],
                inventory=scenario["inv"],
                equipment=scenario["equip"],
                nearby_npcs=nearby_npcs,
                nearby_objects=nearby_objs,
                nearby_ground_items=[], nearby_players=[],
                region_id=12850, world=random.randint(301, 400),
                tab="inventory", attack_style="Accurate",
                action_results=[error_msg],
            )

            # LLM's corrected response
            good_reasoning = (f"I forgot the \"option\" field. The available options for {scenario['name']} are "
                              f"[{scenario['available']}]. I need \"{correct_option}\" to {scenario['goal']}.")
            good_actions_list = [{"action": action_type, "name": scenario["name"], "option": correct_option}]
            if correct_option in ("Mine", "Chop down", "Smelt", "Smith"):
                good_actions_list.append({"action": "WAIT_ANIMATION"})
            elif correct_option == "Attack":
                good_actions_list.append({"action": "WAIT_ANIMATION", "ticks": 20})
            good_actions = json.dumps(good_actions_list)
            good_response = f"{good_reasoning}\n{good_actions}"

            # Build multi-turn conversation
            system_prompt = pick_prompt(scenario["goal"].capitalize() + ".")
            examples.append({
                "conversations": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": state_turn1},
                    {"role": "assistant", "content": bad_response},
                    {"role": "user", "content": state_turn2},
                    {"role": "assistant", "content": good_response},
                ]
            })

    return examples


def gen_clear_queue_scenarios():
    """Generate examples where the LLM proactively uses CLEAR_ACTION_QUEUE to cancel previous plans."""
    examples = []

    # --- Scenario 1: Low HP emergency while skilling (~20) ---
    for _ in range(20):
        hp = random.randint(5, 15)
        max_hp = random.choice([30, 40, 50, 60])
        food = random.choice(["Lobster", "Swordfish", "Trout", "Salmon", "Tuna"])
        skill = random.choice(["Mining", "Woodcutting", "Fishing"])
        ore = random.choice(["Copper rocks", "Iron rocks", "Coal rocks"])
        ore_item = ore.replace(" rocks", " ore")
        cx, cy = 3200 + random.randint(-100, 100), 3200 + random.randint(-100, 100)
        npc_name = random.choice(["Dark wizard", "Scorpion", "Moss giant", "Giant spider"])
        food_count = random.randint(2, 5)
        ore_count = random.randint(3, 10)

        state = serialize_game_state(
            player_name="Skiller42", combat_level=random.randint(20, 70), hp=hp, max_hp=max_hp,
            prayer=random.randint(0, 30), max_prayer=30, run_energy=random.randint(20, 100),
            run_on=True, weight=random.randint(5, 25), spec_pct=100,
            pos_x=cx, pos_y=cy, plane=0, status="IDLE", tick=random.randint(100, 9000),
            skills={"Atk": random.randint(10, 40), "Str": random.randint(10, 40), "Def": random.randint(10, 40),
                    "HP": max_hp // 10, "Mining": random.randint(15, 60), "WC": random.randint(15, 60),
                    "Fishing": random.randint(15, 60)},
            inventory=[(food, food_count), (ore_item, ore_count)],
            equipment=[("Weapon", "Rune pickaxe")] if skill == "Mining" else [("Weapon", "Rune axe")] if skill == "Woodcutting" else [("Weapon", "Small fishing net")],
            nearby_npcs=[nearby_entity(npc_name, cx + 3, cy + 2, random.randint(2, 6), level=random.randint(20, 80), hp_pct=100)],
            nearby_objects=[nearby_entity(ore, cx + 2, cy, random.randint(1, 3), ["Mine"])],
            nearby_ground_items=[], nearby_players=[], region_id=12850, world=301,
            tab="inventory", attack_style="Accurate",
            current_goal=f"{skill} training",
            under_attack=npc_name,
            action_results=[f"INTERACT_OBJECT({ore}/Mine) -> OK", "WAIT_ANIMATION -> ABORTED: Under attack by " + npc_name],
        )

        reasoning = (f"I'm under attack by a {npc_name} and my HP is only {hp}/{max_hp}! "
                     f"I need to eat immediately to survive. Clearing my queue to handle this emergency.")
        actions = json.dumps([
            {"action": "CLEAR_ACTION_QUEUE"},
            {"action": "EAT_FOOD", "name": food},
        ])
        examples.append(make_example(pick_prompt(f"{skill} training."), state, f"{reasoning}\n{actions}"))

    # --- Scenario 2: Being attacked while bankstanding (~15) ---
    for _ in range(15):
        hp = random.randint(10, 25)
        max_hp = random.choice([40, 50, 60])
        attacker = random.choice(["Man", "Guard", "Dark wizard", "Thief"])
        food = random.choice(["Lobster", "Tuna", "Salmon"])
        cx, cy = 3182 + random.randint(-5, 5), 3436 + random.randint(-5, 5)
        food_count = random.randint(1, 3)
        ore_count = random.randint(5, 15)

        state = serialize_game_state(
            player_name="BankStand", combat_level=random.randint(30, 70), hp=hp, max_hp=max_hp,
            prayer=random.randint(0, 40), max_prayer=40, run_energy=random.randint(30, 100),
            run_on=True, weight=random.randint(0, 15), spec_pct=100,
            pos_x=cx, pos_y=cy, plane=0, status="IN_COMBAT", tick=random.randint(100, 9000),
            skills={"Atk": random.randint(20, 50), "Str": random.randint(20, 50), "Def": random.randint(20, 50), "HP": max_hp // 10},
            inventory=[(food, food_count), ("Iron ore", ore_count)],
            equipment=[("Body", "Iron platebody"), ("Legs", "Iron platelegs"), ("Head", "Iron full helm")],
            nearby_npcs=[nearby_entity(attacker, cx + 1, cy, 1, level=random.randint(2, 30), hp_pct=75)],
            nearby_objects=[nearby_entity("Bank booth", cx + 3, cy + 1, 3, ["Bank"])],
            nearby_ground_items=[], nearby_players=[], region_id=12850, world=301,
            tab="inventory", attack_style="Accurate",
            current_goal="Bank iron ore",
            under_attack=attacker,
        )

        reasoning = (f"I'm being attacked by a {attacker}! My HP is {hp}/{max_hp}. "
                     f"I need to eat first before continuing with banking.")
        actions = json.dumps([
            {"action": "CLEAR_ACTION_QUEUE"},
            {"action": "EAT_FOOD", "name": food},
            {"action": "INTERACT_NPC", "name": attacker, "option": "Attack"},
            {"action": "WAIT_ANIMATION", "ticks": 15},
        ])
        examples.append(make_example(pick_prompt("Bank items."), state, f"{reasoning}\n{actions}"))

    # --- Scenario 3: Suddenly see a rare drop / valuable ground item (~15) ---
    for _ in range(15):
        valuable = random.choice(["Rune scimitar", "Dragon bones", "Nature rune", "Rune med helm", "Clue scroll (medium)"])
        skill = random.choice(["Mining", "Woodcutting"])
        ore = "Iron rocks" if skill == "Mining" else "Oak tree"
        option = "Mine" if skill == "Mining" else "Chop down"
        cx, cy = 3200 + random.randint(-100, 100), 3200 + random.randint(-100, 100)
        ore_count = random.randint(10, 25)

        state = serialize_game_state(
            player_name="LootHunter", combat_level=random.randint(40, 80), hp=random.randint(25, 50),
            max_hp=50, prayer=random.randint(0, 30), max_prayer=30,
            run_energy=random.randint(40, 100), run_on=True,
            weight=random.randint(5, 20), spec_pct=100,
            pos_x=cx, pos_y=cy, plane=0, status="IDLE", tick=random.randint(100, 9000),
            skills={"Atk": random.randint(30, 60), "Str": random.randint(30, 60), "Mining": random.randint(30, 60),
                    "WC": random.randint(30, 60)},
            inventory=[("Iron ore", ore_count)],
            equipment=[("Weapon", "Rune pickaxe")] if skill == "Mining" else [("Weapon", "Rune axe")],
            nearby_npcs=[], nearby_objects=[nearby_entity(ore, cx + 2, cy + 1, 2, [option])],
            nearby_ground_items=[nearby_entity(valuable, cx + random.randint(2, 5), cy + random.randint(-3, 3), random.randint(2, 8))],
            nearby_players=[], region_id=12850, world=301,
            tab="inventory", attack_style="Accurate",
            current_goal=f"{skill} training",
            action_results=[f"INTERACT_OBJECT({ore}/{option}) -> OK", "WAIT_ANIMATION -> OK"],
        )

        reasoning = (f"I see a {valuable} on the ground! That's valuable — I should grab it before someone else does. "
                     f"Clearing my queue to pick it up immediately.")
        actions = json.dumps([
            {"action": "CLEAR_ACTION_QUEUE"},
            {"action": "PICKUP_ITEM", "name": valuable},
        ])
        examples.append(make_example(pick_prompt(f"{skill} training."), state, f"{reasoning}\n{actions}"))

    # --- Scenario 4: Player appears in dangerous area / wilderness (~10) ---
    for _ in range(10):
        cx, cy = 3090 + random.randint(-20, 20), 3520 + random.randint(0, 30)
        player_combat = random.randint(50, 100)
        my_combat = random.randint(30, 60)
        lob_count = random.randint(5, 10)
        ore_count = random.randint(3, 10)
        pker_name = f"Pk3r{random.randint(1,99)}"

        state = serialize_game_state(
            player_name="WildRunner", combat_level=my_combat, hp=random.randint(20, 40),
            max_hp=40, prayer=random.randint(0, 30), max_prayer=30,
            run_energy=random.randint(50, 100), run_on=True,
            weight=random.randint(5, 20), spec_pct=100,
            pos_x=cx, pos_y=cy, plane=0, status="IDLE", tick=random.randint(100, 9000),
            skills={"Atk": random.randint(20, 50), "Str": random.randint(20, 50), "Def": random.randint(20, 50),
                    "HP": 4, "Mining": random.randint(30, 60)},
            inventory=[("Lobster", lob_count), ("Iron ore", ore_count)],
            equipment=[("Weapon", "Rune pickaxe"), ("Body", "Iron platebody"), ("Legs", "Iron platelegs")],
            nearby_npcs=[],
            nearby_objects=[nearby_entity("Iron rocks", cx + 3, cy, 3, ["Mine"])],
            nearby_ground_items=[], region_id=12850, world=301,
            nearby_players=[nearby_entity(pker_name, cx + random.randint(8, 15), cy + random.randint(-5, 5), random.randint(5, 15), level=player_combat)],
            tab="inventory", attack_style="Accurate",
            current_goal="Mine iron in wilderness",
        )

        reasoning = (f"A player (combat {player_combat}) appeared nearby and I'm only combat {my_combat}. "
                     f"This is dangerous — I need to stop mining and run south immediately to escape.")
        actions = json.dumps([
            {"action": "CLEAR_ACTION_QUEUE"},
            {"action": "PATH_TO", "x": cx, "y": cy - 30, "goal": "Escape south"},
        ])
        examples.append(make_example(pick_prompt("Mine iron."), state, f"{reasoning}\n{actions}"))

    # --- Scenario 5: Action results show something went wrong, need to re-plan (~15) ---
    for _ in range(15):
        scenario = random.choice(["cant_reach", "npc_gone", "inventory_full"])
        cx, cy = 3200 + random.randint(-100, 100), 3200 + random.randint(-100, 100)

        if scenario == "cant_reach":
            state = serialize_game_state(
                player_name="Walker", combat_level=40, hp=30, max_hp=30,
                prayer=15, max_prayer=15, run_energy=60, run_on=True,
                weight=10, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 9000),
                skills={"Mining": 30}, inventory=[], equipment=[("Weapon", "Steel pickaxe")],
                nearby_npcs=[],
                nearby_objects=[nearby_entity("Iron rocks", cx + 5, cy, 5, ["Mine"]),
                                nearby_entity("Door", cx + 2, cy, 2, ["Open"])],
                nearby_ground_items=[], nearby_players=[], region_id=12850, world=301,
                tab="inventory", attack_style="Accurate",
                current_goal="Mine iron",
                action_results=["INTERACT_OBJECT(Iron rocks/Mine) -> FAILED: I can't reach that!"],
            )
            reasoning = ("The iron rocks are unreachable — there's a door blocking my path. "
                         "I need to open the door first, then try mining again.")
            actions = json.dumps([
                {"action": "CLEAR_ACTION_QUEUE"},
                {"action": "INTERACT_OBJECT", "name": "Door", "option": "Open"},
                {"action": "INTERACT_OBJECT", "name": "Iron rocks", "option": "Mine"},
                {"action": "WAIT_ANIMATION"},
            ])
        elif scenario == "npc_gone":
            npc = random.choice(["Cow", "Chicken", "Goblin"])
            other_npc = random.choice(["Cow", "Chicken", "Goblin"])
            while other_npc == npc:
                other_npc = random.choice(["Cow", "Chicken", "Goblin"])
            state = serialize_game_state(
                player_name="Fighter", combat_level=30, hp=25, max_hp=30,
                prayer=10, max_prayer=10, run_energy=80, run_on=True,
                weight=15, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 9000),
                skills={"Atk": 20, "Str": 20, "Def": 20},
                inventory=[("Trout", 5), ("Bones", 3)],
                equipment=[("Weapon", "Iron scimitar"), ("Body", "Iron platebody")],
                nearby_npcs=[nearby_entity(other_npc, cx + 4, cy + 2, 4, ["Attack"], level=2, hp_pct=100)],
                nearby_objects=[],
                nearby_ground_items=[nearby_entity("Bones", cx + 1, cy, 1)],
                nearby_players=[], region_id=12850, world=301,
                tab="inventory", attack_style="Accurate",
                current_goal="Train combat",
                action_results=[f"INTERACT_NPC({npc}/Attack) -> FAILED: {npc} not found nearby"],
            )
            reasoning = (f"The {npc} I was targeting is gone. I can see a {other_npc} nearby though. "
                         f"Let me attack that instead.")
            actions = json.dumps([
                {"action": "CLEAR_ACTION_QUEUE"},
                {"action": "INTERACT_NPC", "name": other_npc, "option": "Attack", "goal": "Train combat"},
                {"action": "WAIT_ANIMATION", "ticks": 20},
            ])
        else:  # inventory_full
            ore = random.choice(["Copper ore", "Iron ore", "Coal"])
            state = serialize_game_state(
                player_name="Miner", combat_level=30, hp=30, max_hp=30,
                prayer=10, max_prayer=10, run_energy=70, run_on=True,
                weight=25, spec_pct=100, pos_x=cx, pos_y=cy, plane=0,
                status="IDLE", tick=random.randint(100, 9000),
                skills={"Mining": 30},
                inventory=[(ore, 28)],
                equipment=[("Weapon", "Steel pickaxe")],
                nearby_npcs=[],
                nearby_objects=[nearby_entity("Bank booth", cx + 15, cy, 15, ["Bank"])],
                nearby_ground_items=[], nearby_players=[], region_id=12850, world=301,
                tab="inventory", attack_style="Accurate",
                current_goal="Mine ore",
                action_results=["INTERACT_OBJECT(Iron rocks/Mine) -> FAILED: Your inventory is too full to hold any more."],
            )
            reasoning = ("My inventory is full with 28 ores. I need to bank before I can continue mining.")
            actions = json.dumps([
                {"action": "CLEAR_ACTION_QUEUE"},
                {"action": "PATH_TO", "x": cx + 15, "y": cy, "goal": "Bank ores"},
            ])

        examples.append(make_example(pick_prompt("Train skills."), state, f"{reasoning}\n{actions}"))

    return examples


# ═══════════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    random.seed(42)
    os.makedirs(os.path.dirname(OUTPUT_FILE), exist_ok=True)

    generators = [
        ("Mining", gen_mining_scenarios),
        ("Woodcutting", gen_woodcutting_scenarios),
        ("Fishing", gen_fishing_scenarios),
        ("Cooking", gen_cooking_scenarios),
        ("Combat", gen_combat_scenarios),
        ("Smithing", gen_smithing_scenarios),
        ("Firemaking", gen_firemaking_scenarios),
        ("Fletching", gen_fletching_scenarios),
        ("Herblore", gen_herblore_scenarios),
        ("Banking", gen_banking_scenarios),
        ("Navigation", gen_navigation_scenarios),
        ("Dialogue", gen_dialogue_scenarios),
        ("Magic", gen_magic_scenarios),
        ("Prayer", gen_prayer_scenarios),
        ("Shopping", gen_shopping_scenarios),
        ("Grand Exchange", gen_ge_scenarios),
        ("Tutorial Island", gen_tutorial_island_scenarios),
        ("Edge Cases", gen_edge_case_scenarios),
        ("Equipment", gen_equipment_scenarios),
        ("Misc (all remaining)", gen_misc_scenarios),
        ("Session Notes Chains", gen_chain_scenarios),
        ("Session Notes Edge Cases", gen_session_notes_scenarios),
        ("Quest Walkthroughs", gen_quest_scenarios),
        ("Equipment Upgrades", gen_equipment_upgrade_scenarios),
        ("Level Transitions", gen_level_transition_scenarios),
        ("Death Recovery", gen_death_recovery_scenarios),
        ("Bank Preparation", gen_bank_preparation_scenarios),
        ("Negative Examples", gen_negative_examples),
        ("Action Result Variety", gen_action_result_scenarios),
        ("Clear Queue Emergency", gen_clear_queue_scenarios),
        ("Interact Options", gen_interact_option_scenarios),
    ]

    all_examples = []
    for name, gen_fn in generators:
        examples = gen_fn()
        print(f"  {name}: {len(examples)} examples")
        all_examples.extend(examples)

    # If under target, run additional passes with different seeds to create variations
    target = 7500
    pass_num = 2
    while len(all_examples) < target and pass_num <= 6:
        random.seed(42 + pass_num * 1000)
        for name, gen_fn in generators:
            if len(all_examples) >= target:
                break
            extras = gen_fn()
            # Only add enough to reach target
            remaining = target - len(all_examples)
            all_examples.extend(extras[:remaining])
        print(f"  Pass {pass_num}: now at {len(all_examples)} examples")
        pass_num += 1

    # Shuffle
    random.shuffle(all_examples)

    # Write output
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        for example in all_examples:
            f.write(json.dumps(example, ensure_ascii=False) + "\n")

    print(f"\nTotal: {len(all_examples)} synthetic gameplay examples")
    print(f"Written to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
