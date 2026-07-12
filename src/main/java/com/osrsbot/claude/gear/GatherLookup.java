package com.osrsbot.claude.gear;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill prerequisites for gathering interactions on world objects (chop a
 * tree, mine a rock, etc.). Keyed by the OSRS object name as it appears
 * in the menu / [NEARBY_OBJECTS] block. Returns null when no requirement
 * is known — the model gets no annotation and can try the action.
 *
 * Skilling tools (axe + pickaxe wielding levels) live in {@link GearLookup}.
 */
public final class GatherLookup
{
    private GatherLookup() {}

    private static final Map<String, SkillRequirement> OBJECTS = new HashMap<>();
    static
    {
        // Trees (Woodcutting)
        OBJECTS.put("tree",          new SkillRequirement("WC",  1));
        OBJECTS.put("dead tree",     new SkillRequirement("WC",  1));
        OBJECTS.put("evergreen",     new SkillRequirement("WC",  1));
        OBJECTS.put("oak",           new SkillRequirement("WC", 15));
        OBJECTS.put("oak tree",      new SkillRequirement("WC", 15));
        OBJECTS.put("willow",        new SkillRequirement("WC", 30));
        OBJECTS.put("willow tree",   new SkillRequirement("WC", 30));
        OBJECTS.put("teak tree",     new SkillRequirement("WC", 35));
        OBJECTS.put("maple tree",    new SkillRequirement("WC", 45));
        OBJECTS.put("hollow tree",   new SkillRequirement("WC", 45));
        OBJECTS.put("mahogany tree", new SkillRequirement("WC", 50));
        OBJECTS.put("yew",           new SkillRequirement("WC", 60));
        OBJECTS.put("yew tree",      new SkillRequirement("WC", 60));
        OBJECTS.put("magic tree",    new SkillRequirement("WC", 75));
        OBJECTS.put("redwood tree",  new SkillRequirement("WC", 90));

        // Rocks (Mining)
        OBJECTS.put("clay rocks",     new SkillRequirement("Mine",  1));
        OBJECTS.put("copper rocks",   new SkillRequirement("Mine",  1));
        OBJECTS.put("tin rocks",      new SkillRequirement("Mine",  1));
        OBJECTS.put("iron rocks",     new SkillRequirement("Mine", 15));
        OBJECTS.put("silver rocks",   new SkillRequirement("Mine", 20));
        OBJECTS.put("coal rocks",     new SkillRequirement("Mine", 30));
        OBJECTS.put("gold rocks",     new SkillRequirement("Mine", 40));
        OBJECTS.put("mithril rocks",  new SkillRequirement("Mine", 55));
        OBJECTS.put("adamantite rocks", new SkillRequirement("Mine", 70));
        OBJECTS.put("runite rocks",   new SkillRequirement("Mine", 85));

        // Fishing spots are typically named "Fishing spot" — gating depends
        // on which option is used (Net=20, Bait=20 for trout/salmon, Cage=40
        // for lobster, Harpoon=35 for tuna/swordfish, Big Net=16). We can't
        // disambiguate by object name alone; the option matters. Skipping
        // for now — the model has the [SKILLS] line and the option tooltips.
    }

    /** Returns the gather requirement for the given object name, or null
     *  if no requirement is known. Case-insensitive. */
    public static SkillRequirement requirementFor(String objectName)
    {
        if (objectName == null) return null;
        return OBJECTS.get(objectName.toLowerCase().trim());
    }

    /** Checks whether the given player level satisfies the requirement.
     *  If the requirement is null, returns true (no gating known). */
    public static boolean meets(SkillRequirement req, int playerLevel)
    {
        return req == null || playerLevel >= req.level;
    }

    /** Convenience: returns Collections.emptyMap() to keep callers safe
     *  if they iterate. Currently only used externally for the public
     *  inspection path. */
    public static Map<String, SkillRequirement> all()
    {
        return Collections.unmodifiableMap(OBJECTS);
    }
}
