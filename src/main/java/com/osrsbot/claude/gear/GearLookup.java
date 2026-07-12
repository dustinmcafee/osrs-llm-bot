package com.osrsbot.claude.gear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Returns the skill prerequisites for equipping an OSRS item, by item name.
 *
 * Strategy: most metal weapons and armor follow a tier system (Bronze, Iron,
 * Steel, Black, Mithril, Adamant, Rune, Dragon) with uniform level gates.
 * We pattern-match the tier prefix against a small set of recognized weapon
 * and armor suffixes; everything else returns no requirement (treated as
 * "no known restriction" rather than guessing).
 *
 * F2P-focused — non-F2P gear (mystic, d'hide, granite, barrows, etc.) can
 * be added incrementally as needed.
 */
public final class GearLookup
{
    private GearLookup() {}

    /** Tier → (Atk req for weapons, Def req for armor). Black is the
     *  anomalous one (sits between Steel and Mithril at level 10). */
    private static final Map<String, int[]> TIER_LEVELS = new LinkedHashMap<>();
    static
    {
        TIER_LEVELS.put("bronze",  new int[]{ 1,  1});
        TIER_LEVELS.put("iron",    new int[]{ 1,  1});
        TIER_LEVELS.put("steel",   new int[]{ 5,  5});
        TIER_LEVELS.put("black",   new int[]{10, 10});
        TIER_LEVELS.put("mithril", new int[]{20, 20});
        TIER_LEVELS.put("adamant", new int[]{30, 30});
        TIER_LEVELS.put("rune",    new int[]{40, 40});
        TIER_LEVELS.put("dragon",  new int[]{60, 60});
    }

    /** Suffixes that identify a weapon (Atk requirement applies). */
    private static final Set<String> WEAPON_SUFFIXES = new HashSet<>(Arrays.asList(
        "dagger", "sword", "longsword", "scimitar", "mace", "warhammer",
        "battleaxe", "2h sword", "halberd", "spear", "hasta", "claws",
        "axe", "pickaxe"
    ));

    /** Suffixes that identify a piece of armor (Def requirement applies). */
    private static final Set<String> ARMOR_SUFFIXES = new HashSet<>(Arrays.asList(
        "full helm", "med helm", "platebody", "chainbody", "platelegs",
        "plateskirt", "kiteshield", "sq shield", "shield", "gauntlets",
        "boots"
    ));

    /** Quest-locked items: name → list of requirements (skill + completion notes). */
    private static final Map<String, List<SkillRequirement>> EXPLICIT;
    static
    {
        Map<String, List<SkillRequirement>> m = new LinkedHashMap<>();
        // Magic robes (F2P)
        m.put("wizard hat",   Collections.singletonList(new SkillRequirement("Mag", 1)));
        m.put("wizard robe",  Collections.singletonList(new SkillRequirement("Mag", 1)));
        m.put("zamorak monk top", Collections.singletonList(new SkillRequirement("Mag", 1)));
        // Ranged armor (F2P)
        m.put("leather body",   Collections.singletonList(new SkillRequirement("Rng", 1)));
        m.put("leather chaps",  Collections.singletonList(new SkillRequirement("Rng", 1)));
        m.put("hardleather body", Collections.singletonList(new SkillRequirement("Rng", 10)));
        m.put("studded body",   Arrays.asList(new SkillRequirement("Rng", 20),  new SkillRequirement("Def", 20)));
        m.put("studded chaps",  Collections.singletonList(new SkillRequirement("Rng", 20)));
        // Bows (F2P)
        m.put("shortbow",       Collections.singletonList(new SkillRequirement("Rng", 5)));
        m.put("oak shortbow",   Collections.singletonList(new SkillRequirement("Rng", 5)));
        m.put("willow shortbow",Collections.singletonList(new SkillRequirement("Rng", 20)));
        m.put("maple shortbow", Collections.singletonList(new SkillRequirement("Rng", 30)));
        m.put("longbow",        Collections.singletonList(new SkillRequirement("Rng", 1)));
        // Notable F2P combat gear with extra reqs
        m.put("amulet of power", Collections.<SkillRequirement>emptyList());
        EXPLICIT = m;
    }

    /**
     * Returns the level prerequisites for equipping the given item, or an
     * empty list if no known restriction applies. Match is name-based and
     * case-insensitive; unknown items return empty (meaning: no annotation).
     */
    public static List<SkillRequirement> requirementsFor(String itemName)
    {
        if (itemName == null || itemName.isEmpty())
        {
            return Collections.emptyList();
        }
        String name = itemName.toLowerCase().trim();

        // 1. Explicit overrides win.
        List<SkillRequirement> explicit = EXPLICIT.get(name);
        if (explicit != null) return explicit;

        // 2. Tiered metal weapons / armor / tools by prefix + suffix.
        for (Map.Entry<String, int[]> tier : TIER_LEVELS.entrySet())
        {
            String prefix = tier.getKey() + " ";
            if (!name.startsWith(prefix)) continue;
            String suffix = name.substring(prefix.length()).trim();

            if (WEAPON_SUFFIXES.contains(suffix))
            {
                List<SkillRequirement> reqs = new ArrayList<>(2);
                reqs.add(new SkillRequirement("Atk", tier.getValue()[0]));
                if ("dragon".equals(tier.getKey()))
                {
                    // Most dragon weapons require quest progression too.
                    // We don't enumerate which quest here — model can react
                    // to "quest" hint generically.
                    reqs.add(new SkillRequirement("Quest", 0));
                }
                return reqs;
            }
            if (ARMOR_SUFFIXES.contains(suffix))
            {
                List<SkillRequirement> reqs = new ArrayList<>(2);
                reqs.add(new SkillRequirement("Def", tier.getValue()[1]));
                if ("dragon".equals(tier.getKey()))
                {
                    reqs.add(new SkillRequirement("Quest", 0));
                }
                return reqs;
            }
        }

        return Collections.emptyList();
    }
}
