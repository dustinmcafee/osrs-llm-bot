package com.osrsbot.claude.gear;

import net.runelite.api.Client;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the best gear the player can currently equip / use, based on their
 * base skill levels, world membership, and quest state. Used to:
 *  - Render a [RECOMMENDED_GEAR] block in every prompt so the model knows
 *    what tier it should be aiming for at its current levels.
 *  - Render a [LEVEL_UP] one-shot when a skill increases, calling out the
 *    new gear unlocked at that level.
 *
 * The "best in tier" walk is data-driven: we iterate the metal tiers from
 * Dragon → Bronze and the per-skill JSON entries, pick the highest tier
 * the player satisfies, and produce a compact text summary.
 */
@Singleton
public class GearRecommender
{
    @Inject
    private RequirementsRegistry registry;

    /** Metal tiers and their wielding-skill gates, in ascending order.
     *  Combat tiers double as armor tiers (same level); tools require
     *  one level above the combat tier (with Bronze/Iron staying at 1). */
    private static final String[] TIER_NAMES =
        { "Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon" };
    private static final int[] COMBAT_TIER_REQS = { 1, 1, 5, 10, 20, 30, 40, 60 };
    private static final int[] TOOL_TIER_REQS   = { 1, 1, 6, 11, 21, 31, 41, 61 };

    private static final java.util.Set<String> WEAPON_SUFFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
        "dagger", "sword", "longsword", "scimitar", "mace", "warhammer",
        "battleaxe", "2h sword", "halberd", "spear", "hasta", "claws"
    ));
    private static final java.util.Set<String> ARMOR_SUFFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
        "full helm", "med helm", "platebody", "chainbody", "platelegs",
        "plateskirt", "kiteshield", "sq shield", "boots", "gloves"
    ));

    /**
     * One-line summary of the highest tier the player can use in each
     * category, derived from current levels AND membership / quest gates
     * from the registry. Broad (no specific item), meant as a target
     * hint. The tier walked here will never include something the
     * player can't actually equip — e.g. a F2P player at Atk 60 will
     * see "Rune" for weapons, not "Dragon", because Dragon weapons are
     * members-only.
     */
    public String recommendCurrent(Client client)
    {
        if (registry == null || client == null) return "";
        int atk  = safeLevel(client, Skill.ATTACK);
        int def  = safeLevel(client, Skill.DEFENCE);
        int wc   = safeLevel(client, Skill.WOODCUTTING);
        int mine = safeLevel(client, Skill.MINING);

        return "Best tiers you can use — "
            + "Weapons: " + bestTier(client, "scimitar",  atk,  COMBAT_TIER_REQS) + " (Atk " + atk + "). "
            + "Armor: "   + bestTier(client, "platebody", def,  COMBAT_TIER_REQS) + " (Def " + def + "). "
            + "Axes: "    + bestTier(client, "axe",       wc,   TOOL_TIER_REQS)   + " (WC " + wc + "). "
            + "Picks: "   + bestTier(client, "pickaxe",   mine, TOOL_TIER_REQS)   + " (Mine " + mine + ").";
    }

    /**
     * Returns "✓" if the equipped item is the highest tier the player
     * can currently use for its category, or "(✗ upgrade to <Tier>)" if
     * a better tier is within reach. Empty string for non-tiered items
     * (capes, amulets, rings, leather, etc.) — those have no upgrade
     * relationship to flag.
     */
    public String annotateEquipped(String itemName, Client client)
    {
        if (itemName == null || client == null) return "";
        String lower = itemName.toLowerCase();

        // Identify tier prefix.
        int equippedTier = -1;
        String suffix = null;
        for (int i = 0; i < TIER_NAMES.length; i++)
        {
            String prefix = TIER_NAMES[i].toLowerCase() + " ";
            if (lower.startsWith(prefix))
            {
                equippedTier = i;
                suffix = lower.substring(prefix.length()).trim();
                break;
            }
        }
        if (equippedTier < 0 || suffix == null) return "";

        // Pick the relevant skill / req table for this category.
        int[] reqs;
        int playerLevel;
        if ("axe".equals(suffix))
        {
            reqs = TOOL_TIER_REQS;
            playerLevel = safeLevel(client, Skill.WOODCUTTING);
        }
        else if ("pickaxe".equals(suffix))
        {
            reqs = TOOL_TIER_REQS;
            playerLevel = safeLevel(client, Skill.MINING);
        }
        else if (WEAPON_SUFFIXES.contains(suffix))
        {
            reqs = COMBAT_TIER_REQS;
            playerLevel = safeLevel(client, Skill.ATTACK);
        }
        else if (ARMOR_SUFFIXES.contains(suffix))
        {
            reqs = COMBAT_TIER_REQS;
            playerLevel = safeLevel(client, Skill.DEFENCE);
        }
        else
        {
            return "";
        }

        // Highest tier the player can use for this category — gated by
        // the registry's full req (skills + members + quest), not just
        // skill level.
        int maxTier = bestTierIndex(client, suffix, playerLevel, reqs);
        if (maxTier < 0) return "";

        if (equippedTier >= maxTier)
        {
            // Already at or above the player's max — no upgrade available.
            return "✓";
        }
        return "(✗ upgrade to " + TIER_NAMES[maxTier] + ")";
    }

    /** Returns the highest tier NAME the player can use for the given
     *  category, considering skill, membership, and quest gates from
     *  the registry. Defaults to "Bronze" if nothing else qualifies. */
    private String bestTier(Client client, String representativeSuffix, int playerLevel, int[] reqs)
    {
        int idx = bestTierIndex(client, representativeSuffix, playerLevel, reqs);
        return idx >= 0 ? TIER_NAMES[idx] : TIER_NAMES[0];
    }

    /** Walk tiers high → low and return the first one whose representative
     *  item ({@code <tier> <suffix>}) is fully satisfied — meeting skill,
     *  membership, and quest gates per the registry. If the registry has
     *  no entry for a tier (the formula path), only the skill-level gate
     *  applies. Returns -1 if not even Bronze works (shouldn't happen). */
    private int bestTierIndex(Client client, String representativeSuffix, int playerLevel, int[] reqs)
    {
        for (int i = TIER_NAMES.length - 1; i >= 0; i--)
        {
            if (playerLevel < reqs[i]) continue;
            String name = TIER_NAMES[i].toLowerCase() + " " + representativeSuffix;
            RequirementsRegistry.Requirement req = registry != null
                ? registry.lookupItem(name) : null;
            if (req == null) return i; // Formula path — no extra gates known.
            if (isReqSatisfied(client, req)) return i;
            // Otherwise: skill ok, but members/quest blocks this tier — try lower.
        }
        return -1;
    }

    /** True iff every gate of a registry requirement is satisfied: skills,
     *  membership (matched against world type), and quest (matched against
     *  RuneLite's runtime quest state). Mirrors {@link RequirementAnnotator}
     *  but returns a boolean instead of an annotation. */
    private static boolean isReqSatisfied(Client client, RequirementsRegistry.Requirement req)
    {
        if (req == null) return true;
        for (SkillRequirement s : req.skills)
        {
            Skill skill = skillForCode(s.skill);
            if (skill == null) continue;
            if (safeLevel(client, skill) < s.level) return false;
        }
        if (req.members)
        {
            try
            {
                Set<WorldType> wt = client.getWorldType();
                if (wt == null || !wt.contains(WorldType.MEMBERS)) return false;
            }
            catch (Throwable t) { return false; }
        }
        if (req.quest != null)
        {
            try
            {
                if (req.quest.getState(client) != QuestState.FINISHED) return false;
            }
            catch (Throwable t) { return false; }
        }
        return true;
    }

    private static Skill skillForCode(String code)
    {
        switch (code)
        {
            case "Atk":   return Skill.ATTACK;
            case "Str":   return Skill.STRENGTH;
            case "Def":   return Skill.DEFENCE;
            case "Rng":   return Skill.RANGED;
            case "Mag":   return Skill.MAGIC;
            case "HP":    return Skill.HITPOINTS;
            case "Pray":  return Skill.PRAYER;
            case "WC":    return Skill.WOODCUTTING;
            case "Mine":  return Skill.MINING;
            case "Fish":  return Skill.FISHING;
            case "Cook":  return Skill.COOKING;
            case "FM":    return Skill.FIREMAKING;
            case "Craft": return Skill.CRAFTING;
            case "Smith": return Skill.SMITHING;
            case "Fletch":return Skill.FLETCHING;
            case "Slay":  return Skill.SLAYER;
            case "Farm":  return Skill.FARMING;
            case "Con":   return Skill.CONSTRUCTION;
            case "Hunt":  return Skill.HUNTER;
            case "Agi":   return Skill.AGILITY;
            case "Thiev": return Skill.THIEVING;
            case "Herb":  return Skill.HERBLORE;
            case "RC":    return Skill.RUNECRAFT;
            default:      return null;
        }
    }

    private static int safeLevel(Client client, Skill skill)
    {
        try { return client.getRealSkillLevel(skill); }
        catch (Throwable t) { return 1; }
    }

    /** True if a representative item for this tier is fully usable
     *  (skill, members, quest). Used to gate level-up notices so a F2P
     *  player at Atk 60 doesn't get told "Dragon weapons unlocked" when
     *  they actually need members + quest progression to use them. */
    private boolean tierAvailable(Client client, String tierName, String representativeSuffix)
    {
        if (registry == null || client == null) return true;
        String name = tierName.toLowerCase() + " " + representativeSuffix;
        RequirementsRegistry.Requirement req = registry.lookupItem(name);
        if (req == null) return true; // Formula path — nothing extra to check.
        return isReqSatisfied(client, req);
    }

    private static boolean onMembersWorld(Client client)
    {
        try
        {
            Set<WorldType> wt = client.getWorldType();
            return wt != null && wt.contains(WorldType.MEMBERS);
        }
        catch (Throwable t) { return false; }
    }

    /**
     * Returns gear unlocked at a specific level for a specific skill,
     * suitable for a [LEVEL_UP] one-shot notice. E.g. on hitting Atk 20
     * this returns "Mithril scimitar / longsword / dagger / mace ..."
     * — items in the registry whose Atk requirement equals exactly 20
     * AND whose other requirements (Def, quest, members) the player meets.
     */
    public String recommendForLevelUp(Client client, Skill skill, int newLevel)
    {
        if (registry == null || client == null) return "";

        // Walk known explicit JSON entries plus the tier formula for this
        // exact level. We piggyback off lookupItem() probes.
        List<String> matches = new ArrayList<>();

        // Tier formula: if newLevel matches a tier's Atk/Def/skill gate,
        // emit the canonical name for that tier+slot.
        addTierMatchesAtLevel(client, skill, newLevel, matches);

        if (matches.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Newly unlocked at this level: ");
        for (int i = 0; i < matches.size(); i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(matches.get(i));
        }
        return sb.toString();
    }

    /** Append tier-formula matches whose primary skill requirement equals
     *  exactly the given level. Phrasing is intentionally broad ("Mithril
     *  weapons", not "Mithril scimitar") because every variant in the tier
     *  unlocks together — listing each one was noisy. */
    private void addTierMatchesAtLevel(Client client, Skill skill, int level, List<String> out)
    {
        Map<Integer, String> combatTierNames = new LinkedHashMap<>();
        combatTierNames.put( 1, "Iron");
        combatTierNames.put( 5, "Steel");
        combatTierNames.put(10, "Black");
        combatTierNames.put(20, "Mithril");
        combatTierNames.put(30, "Adamant");
        combatTierNames.put(40, "Rune");
        combatTierNames.put(60, "Dragon");

        Map<Integer, String> toolTierNames = new LinkedHashMap<>();
        toolTierNames.put( 1, "Iron");
        toolTierNames.put( 6, "Steel");
        toolTierNames.put(11, "Black");
        toolTierNames.put(21, "Mithril");
        toolTierNames.put(31, "Adamant");
        toolTierNames.put(41, "Rune");
        toolTierNames.put(61, "Dragon");

        if (skill == Skill.ATTACK)
        {
            String tier = combatTierNames.get(level);
            if (tier != null && tierAvailable(client, tier, "scimitar"))
            {
                out.add(tier + " weapons");
            }
        }
        else if (skill == Skill.DEFENCE)
        {
            String tier = combatTierNames.get(level);
            if (tier != null && tierAvailable(client, tier, "platebody"))
            {
                out.add(tier + " armor");
            }
        }
        else if (skill == Skill.WOODCUTTING)
        {
            String tier = toolTierNames.get(level);
            if (tier != null && tierAvailable(client, tier, "axe"))
            {
                out.add(tier + " axe");
            }
            switch (level)
            {
                case 15: out.add("Oak trees"); break;
                case 30: out.add("Willow trees"); break;
                case 45: if (onMembersWorld(client)) out.add("Maple trees (members)"); break;
                case 60: out.add("Yew trees"); break;
                case 75: if (onMembersWorld(client)) out.add("Magic trees (members)"); break;
            }
        }
        else if (skill == Skill.MINING)
        {
            String tier = toolTierNames.get(level);
            if (tier != null && tierAvailable(client, tier, "pickaxe"))
            {
                out.add(tier + " pickaxe");
            }
            switch (level)
            {
                case 15: out.add("Iron ore"); break;
                case 30: out.add("Coal"); break;
                case 40: out.add("Gold ore"); break;
                case 55: out.add("Mithril ore"); break;
                case 70: out.add("Adamantite ore"); break;
                case 85: out.add("Runite ore"); break;
            }
        }
        else if (skill == Skill.FISHING)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 20: out.add("Trout (Fly fishing rod)"); break;
                case 30: out.add("Salmon"); break;
                case 35: out.add("Tuna (harpoon)"); break;
                case 40: out.add("Lobster (cage)"); break;
                case 50: out.add("Swordfish"); break;
                case 76: if (p) out.add("Shark (members)"); break;
            }
        }
        else if (skill == Skill.FIREMAKING)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 15: out.add("Oak logs"); break;
                case 30: out.add("Willow logs"); break;
                case 45: if (p) out.add("Maple logs (members)"); break;
                case 60: out.add("Yew logs"); break;
                case 75: if (p) out.add("Magic logs (members)"); break;
            }
        }
        else if (skill == Skill.COOKING)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 15: out.add("Trout"); break;
                case 25: out.add("Salmon"); break;
                case 30: out.add("Tuna"); break;
                case 40: out.add("Lobster"); break;
                case 50: out.add("Swordfish"); break;
                case 80: if (p) out.add("Shark (members)"); break;
            }
        }
        else if (skill == Skill.MAGIC)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 25: out.add("High Level Alchemy"); break;
                case 40: if (p) out.add("Mystic robes (members)"); break;
                case 55: if (p) out.add("Fire Wave (members)"); break;
            }
        }
        else if (skill == Skill.RANGED)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 20: out.add("Studded leather armor, Willow shortbow"); break;
                case 30: if (p) out.add("Maple shortbow (members)"); break;
                case 40: if (p) out.add("Green d'hide armor (members), Yew shortbow"); break;
                case 50: if (p) out.add("Blue d'hide armor (members), Magic shortbow (members)"); break;
                case 60: if (p) out.add("Red d'hide armor (members)"); break;
                case 70: if (p) out.add("Black d'hide armor (members), Crystal bow (members)"); break;
            }
        }
        else if (skill == Skill.PRAYER)
        {
            boolean p = onMembersWorld(client);
            switch (level)
            {
                case 31: out.add("Protect from Magic / Missiles / Melee"); break;
                case 43: out.add("Protect Item, Eagle Eye, Mystic Might"); break;
                case 70: if (p) out.add("Piety (members)"); break;
            }
        }
    }

}
