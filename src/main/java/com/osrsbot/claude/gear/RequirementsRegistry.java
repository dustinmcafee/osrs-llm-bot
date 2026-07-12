package com.osrsbot.claude.gear;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.Quest;

import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skill / quest / membership requirements for OSRS items and world objects.
 * Backed by a bundled JSON resource ({@code gear_requirements.json}).
 *
 * <p>Two layers:
 * <ol>
 *   <li>Explicit JSON entries — quest-locked, members-only, or otherwise
 *       non-formulaic items (e.g. "Dragon scimitar").</li>
 *   <li>Tier formula in code for plain metal weapons / armor / tools that
 *       follow the standard Bronze→Dragon progression. Saves a couple
 *       hundred boilerplate JSON lines.</li>
 * </ol>
 *
 * <p>Lookup is case-insensitive. An unknown name returns null — the
 * serializer emits no annotation, so the model can still try the
 * action and rely on the engine's own rejection feedback.
 */
@Singleton
public class RequirementsRegistry
{
    private static final String RESOURCE = "/gear_requirements.json";

    /** Tier prefix → (Atk req for weapons, Def req for armor). Used by the
     *  formula path for items not listed explicitly in JSON. */
    private static final Map<String, int[]> TIER_LEVELS = new LinkedHashMap<>();
    static
    {
        TIER_LEVELS.put("bronze",  new int[]{ 1,  1});
        TIER_LEVELS.put("iron",    new int[]{ 1,  1});
        TIER_LEVELS.put("steel",   new int[]{ 5,  5});
        TIER_LEVELS.put("black",   new int[]{10, 10});
        TIER_LEVELS.put("white",   new int[]{10, 10}); // wyatt
        TIER_LEVELS.put("mithril", new int[]{20, 20});
        TIER_LEVELS.put("adamant", new int[]{30, 30});
        TIER_LEVELS.put("rune",    new int[]{40, 40});
        TIER_LEVELS.put("dragon",  new int[]{60, 60});
    }

    private static final java.util.Set<String> WEAPON_SUFFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
        "dagger", "sword", "longsword", "scimitar", "mace", "warhammer",
        "battleaxe", "2h sword", "halberd", "spear", "hasta", "claws"
    ));
    /** Skilling tools — the wielding requirement is the same as combat,
     *  but the SKILL gate (WC for axes, Mining for pickaxes) is one level
     *  higher than the metal tier. */
    private static final Map<String, String> TOOL_SKILLS = new HashMap<>();
    static
    {
        TOOL_SKILLS.put("axe", "WC");
        TOOL_SKILLS.put("pickaxe", "Mine");
    }
    private static final java.util.Set<String> ARMOR_SUFFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
        "full helm", "med helm", "platebody", "chainbody", "platelegs",
        "plateskirt", "kiteshield", "sq shield", "boots", "gloves", "shield"
    ));

    /** Loaded from JSON: lowercase item name → requirement. */
    private final Map<String, Requirement> items = new HashMap<>();
    /** Loaded from JSON: lowercase object name → requirement. */
    private final Map<String, Requirement> objects = new HashMap<>();
    /** Quest display name (lowercase) → Quest enum, built once at startup. */
    private final Map<String, Quest> questByName = new HashMap<>();
    /** Quest display names that appeared in JSON but didn't map to a Quest
     *  enum — logged once at load so we can fix typos in the JSON. */
    private final List<String> unresolvedQuestNames = new ArrayList<>();

    public RequirementsRegistry()
    {
        for (Quest q : Quest.values())
        {
            try
            {
                String n = q.getName();
                if (n != null) questByName.put(n.toLowerCase(Locale.ROOT), q);
            }
            catch (Throwable ignored) {}
        }
        load();
    }

    private void load()
    {
        try (InputStream in = RequirementsRegistry.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                System.err.println("[ClaudeBot] gear_requirements.json missing from resources");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("items"))
            {
                JsonObject obj = root.getAsJsonObject("items");
                for (Map.Entry<String, JsonElement> e : obj.entrySet())
                {
                    Requirement req = parseRequirement(e.getValue().getAsJsonObject());
                    items.put(e.getKey().toLowerCase(Locale.ROOT), req);
                }
            }
            if (root.has("objects"))
            {
                JsonObject obj = root.getAsJsonObject("objects");
                for (Map.Entry<String, JsonElement> e : obj.entrySet())
                {
                    Requirement req = parseRequirement(e.getValue().getAsJsonObject());
                    objects.put(e.getKey().toLowerCase(Locale.ROOT), req);
                }
            }

            System.out.println("[ClaudeBot] requirements loaded: "
                + items.size() + " items, " + objects.size() + " objects, "
                + unresolvedQuestNames.size() + " unresolved quest names");
            for (String n : unresolvedQuestNames)
            {
                System.err.println("[ClaudeBot] requirements: unresolved quest \"" + n + "\"");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] failed to load gear_requirements.json: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    private Requirement parseRequirement(JsonObject o)
    {
        List<SkillRequirement> skills = Collections.emptyList();
        if (o.has("skills"))
        {
            JsonArray arr = o.getAsJsonArray("skills");
            skills = new ArrayList<>(arr.size());
            for (JsonElement el : arr)
            {
                JsonArray pair = el.getAsJsonArray();
                skills.add(new SkillRequirement(pair.get(0).getAsString(), pair.get(1).getAsInt()));
            }
        }
        Quest quest = null;
        String questName = null;
        if (o.has("quest") && !o.get("quest").isJsonNull())
        {
            questName = o.get("quest").getAsString();
            Quest resolved = questByName.get(questName.toLowerCase(Locale.ROOT));
            if (resolved != null)
            {
                quest = resolved;
            }
            else
            {
                unresolvedQuestNames.add(questName);
            }
        }
        boolean members = o.has("members") && o.get("members").getAsBoolean();
        return new Requirement(skills, quest, questName, members);
    }

    /** Lookup an item's requirement by name. Returns null if no
     *  requirement is known (the formula path or unknown item). */
    public Requirement lookupItem(String name)
    {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT).trim();
        Requirement explicit = items.get(lower);
        if (explicit != null) return explicit;
        return tierFormula(lower);
    }

    /** Lookup a world-object's gather requirement by name. Returns null
     *  if no requirement is known. */
    public Requirement lookupObject(String name)
    {
        if (name == null) return null;
        return objects.get(name.toLowerCase(Locale.ROOT).trim());
    }

    /** Tier-based formula for plain metal items not listed in JSON.
     *  Returns null if the name doesn't match a recognized tier+suffix. */
    private Requirement tierFormula(String lowerName)
    {
        for (Map.Entry<String, int[]> tier : TIER_LEVELS.entrySet())
        {
            String prefix = tier.getKey() + " ";
            if (!lowerName.startsWith(prefix)) continue;
            String suffix = lowerName.substring(prefix.length()).trim();

            if (WEAPON_SUFFIXES.contains(suffix))
            {
                List<SkillRequirement> reqs = new ArrayList<>(2);
                reqs.add(new SkillRequirement("Atk", tier.getValue()[0]));
                return new Requirement(reqs, null, null, false);
            }
            if (TOOL_SKILLS.containsKey(suffix))
            {
                List<SkillRequirement> reqs = new ArrayList<>(2);
                reqs.add(new SkillRequirement("Atk", tier.getValue()[0]));
                // Skilling tool gate is one level above metal tier (e.g. steel
                // axe wields at Atk 5 but chops at WC 6).
                int toolLvl = tier.getValue()[0] == 1 ? 1 : tier.getValue()[0] + 1;
                reqs.add(new SkillRequirement(TOOL_SKILLS.get(suffix), toolLvl));
                return new Requirement(reqs, null, null, false);
            }
            if (ARMOR_SUFFIXES.contains(suffix))
            {
                List<SkillRequirement> reqs = new ArrayList<>(1);
                reqs.add(new SkillRequirement("Def", tier.getValue()[1]));
                return new Requirement(reqs, null, null, false);
            }
        }
        return null;
    }

    /** A complete requirement: zero or more skill levels, an optional
     *  quest gate, and a membership flag. Immutable. */
    public static final class Requirement
    {
        public final List<SkillRequirement> skills;
        /** RuneLite enum value, or null if no quest gate (or quest name
         *  failed to resolve at load time — see {@link #questName}). */
        public final Quest quest;
        /** Original quest display name from JSON, used for prompt output
         *  even when {@link #quest} is null. */
        public final String questName;
        public final boolean members;

        public Requirement(List<SkillRequirement> skills, Quest quest, String questName, boolean members)
        {
            this.skills = skills == null ? Collections.<SkillRequirement>emptyList() : skills;
            this.quest = quest;
            this.questName = questName;
            this.members = members;
        }
    }
}
