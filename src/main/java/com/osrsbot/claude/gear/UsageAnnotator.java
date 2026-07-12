package com.osrsbot.claude.gear;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;

import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-item USE-action annotator. Surfaces inline annotations on inventory
 * items for "what can I do with this and what's required" — covering the
 * skill-action class of interactions (Burn logs, Cook fish, Bury bones,
 * Smelt ore, Cut gems, Tan hides, Spin flax, Scatter ashes).
 *
 * Distinct from {@link RecipeRegistry} (multi-input crafting recipes) and
 * {@link RequirementsRegistry} (equipment gates). Loaded from
 * {@code item_usage.json} so the table can be expanded by editing JSON.
 */
@Singleton
public class UsageAnnotator
{
    private static final String RESOURCE = "/item_usage.json";

    private final Map<String, UsageEntry> byItem = new HashMap<>();

    public UsageAnnotator()
    {
        load();
    }

    private void load()
    {
        try (InputStream in = UsageAnnotator.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                System.err.println("[ClaudeBot] item_usage.json missing — usage annotations disabled");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet())
            {
                if (e.getKey().startsWith("_") || !e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                String verb = o.has("verb") ? o.get("verb").getAsString() : null;
                String tool = o.has("tool") ? o.get("tool").getAsString() : null;
                String facility = o.has("facility") ? o.get("facility").getAsString() : null;
                boolean members = o.has("members") && o.get("members").getAsBoolean();
                String skillCode = null;
                int level = 1;
                if (o.has("skill") && o.get("skill").isJsonArray())
                {
                    skillCode = o.getAsJsonArray("skill").get(0).getAsString();
                    level = o.getAsJsonArray("skill").get(1).getAsInt();
                }
                byItem.put(e.getKey().toLowerCase(Locale.ROOT),
                    new UsageEntry(verb, skillCode, level, tool, facility, members));
            }
            System.out.println("[ClaudeBot] item-usage loaded: " + byItem.size() + " entries");
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] failed to load item_usage.json: " + t.getMessage());
        }
    }

    /**
     * Returns a compact annotation suffix like " (Burn: needs FM 30, Tinderbox)"
     * when the player can't currently perform the item's primary use, or
     * empty string if they can (or the item has no known usage).
     *
     * @param itemName       the inventory item being annotated
     * @param client         RuneLite client for skill / world lookups
     * @param itemsInInventory item names currently in inventory — used to
     *                       check whether tool prereqs (e.g. Tinderbox) are
     *                       available
     */
    public String annotate(String itemName, Client client, Set<String> itemsInInventory)
    {
        if (itemName == null || client == null) return "";
        UsageEntry entry = byItem.get(itemName.toLowerCase(Locale.ROOT));
        if (entry == null) return "";

        java.util.List<String> blockers = new java.util.ArrayList<>();

        // Skill check
        if (entry.skillCode != null)
        {
            Skill skill = skillForCode(entry.skillCode);
            if (skill != null)
            {
                int level;
                try { level = client.getRealSkillLevel(skill); }
                catch (Throwable t) { level = 0; }
                if (level < entry.level)
                {
                    blockers.add(entry.skillCode + " " + entry.level);
                }
            }
        }

        // Tool requirement (must be in inventory)
        if (entry.tool != null && !entry.tool.isEmpty())
        {
            boolean has = false;
            if (itemsInInventory != null)
            {
                for (String n : itemsInInventory)
                {
                    if (n != null && n.equalsIgnoreCase(entry.tool)) { has = true; break; }
                }
            }
            if (!has)
            {
                blockers.add(entry.tool);
            }
        }

        // Membership gate
        if (entry.members)
        {
            try
            {
                Set<WorldType> wt = client.getWorldType();
                if (wt == null || !wt.contains(WorldType.MEMBERS))
                {
                    blockers.add("members");
                }
            }
            catch (Throwable ignored) {}
        }

        if (blockers.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(" (✗ ");
        sb.append(entry.verb != null ? entry.verb : "Use").append(": needs ");
        for (int i = 0; i < blockers.size(); i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(blockers.get(i));
        }
        if (entry.facility != null && !entry.facility.isEmpty())
        {
            sb.append("; at ").append(entry.facility);
        }
        sb.append(")");
        return sb.toString();
    }

    private static Skill skillForCode(String code)
    {
        if (code == null) return null;
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

    private static final class UsageEntry
    {
        final String verb;
        final String skillCode;
        final int level;
        final String tool;
        final String facility;
        final boolean members;

        UsageEntry(String verb, String skillCode, int level, String tool, String facility, boolean members)
        {
            this.verb = verb;
            this.skillCode = skillCode;
            this.level = level;
            this.tool = tool;
            this.facility = facility;
            this.members = members;
        }
    }
}
