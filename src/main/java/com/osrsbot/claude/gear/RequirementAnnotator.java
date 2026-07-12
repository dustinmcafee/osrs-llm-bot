package com.osrsbot.claude.gear;

import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces a compact prompt-friendly annotation for a {@link RequirementsRegistry.Requirement}
 * given the current player context (skill levels, world membership, quest
 * completion). The annotation is empty if the player satisfies everything,
 * otherwise it lists only the failing gates — keeping the prompt small.
 *
 * Format: " (✗ Atk:60, mem, Q:Lost City)"
 *   - "✗" marks at least one unmet gate
 *   - "Atk:60" lists each unmet skill (short code + required level)
 *   - "mem" appears when the item is members-only and the player is on a
 *      free world (proxy for "membership content unavailable")
 *   - "Q:<quest>" appears when a quest gate is unmet
 *
 * Skill check considers the player's BASE level (non-boosted), since
 * equip / interaction gates use base levels in OSRS.
 */
public final class RequirementAnnotator
{
    private RequirementAnnotator() {}

    /** Maps the short-form skill code used in JSON to RuneLite's Skill enum.
     *  Anything missing returns null and the gate is treated as unknown
     *  (assumed satisfied — the prompt won't claim a non-existent block). */
    private static final Map<String, Skill> SKILL_BY_CODE = new HashMap<>();
    static
    {
        SKILL_BY_CODE.put("Atk", Skill.ATTACK);
        SKILL_BY_CODE.put("Str", Skill.STRENGTH);
        SKILL_BY_CODE.put("Def", Skill.DEFENCE);
        SKILL_BY_CODE.put("Rng", Skill.RANGED);
        SKILL_BY_CODE.put("Mag", Skill.MAGIC);
        SKILL_BY_CODE.put("HP", Skill.HITPOINTS);
        SKILL_BY_CODE.put("Pray", Skill.PRAYER);
        SKILL_BY_CODE.put("WC", Skill.WOODCUTTING);
        SKILL_BY_CODE.put("Mine", Skill.MINING);
        SKILL_BY_CODE.put("Fish", Skill.FISHING);
        SKILL_BY_CODE.put("Cook", Skill.COOKING);
        SKILL_BY_CODE.put("FM", Skill.FIREMAKING);
        SKILL_BY_CODE.put("Craft", Skill.CRAFTING);
        SKILL_BY_CODE.put("Smith", Skill.SMITHING);
        SKILL_BY_CODE.put("Fletch", Skill.FLETCHING);
        SKILL_BY_CODE.put("Slay", Skill.SLAYER);
        SKILL_BY_CODE.put("Farm", Skill.FARMING);
        SKILL_BY_CODE.put("Con", Skill.CONSTRUCTION);
        SKILL_BY_CODE.put("Hunt", Skill.HUNTER);
        SKILL_BY_CODE.put("Agi", Skill.AGILITY);
        SKILL_BY_CODE.put("Thiev", Skill.THIEVING);
        SKILL_BY_CODE.put("Herb", Skill.HERBLORE);
        SKILL_BY_CODE.put("RC", Skill.RUNECRAFT);
    }

    /**
     * Returns a compact annotation suffix (with leading space), or the
     * empty string if every requirement is satisfied.
     *
     * The {@code client} is only read on the calling thread — pass a
     * client thread snapshot or call from the client thread.
     */
    public static String annotate(RequirementsRegistry.Requirement req, Client client)
    {
        if (req == null) return "";

        List<String> unmet = new ArrayList<>();

        // Skill gates
        for (SkillRequirement s : req.skills)
        {
            Skill skill = SKILL_BY_CODE.get(s.skill);
            if (skill == null) continue;
            int playerLevel;
            try { playerLevel = client.getRealSkillLevel(skill); }
            catch (Throwable t) { continue; }
            if (playerLevel < s.level)
            {
                unmet.add(s.skill + ":" + s.level);
            }
        }

        // Membership gate — treat F2P worlds as "members content unavailable".
        // We can't directly tell if the account is paid, so the world type
        // is the practical signal.
        if (req.members)
        {
            try
            {
                java.util.Set<WorldType> wt = client.getWorldType();
                if (wt == null || !wt.contains(WorldType.MEMBERS))
                {
                    unmet.add("mem");
                }
            }
            catch (Throwable ignored) {}
        }

        // Quest gate — null Quest enum means JSON name didn't resolve;
        // we still surface it as a hint so the model can react.
        if (req.quest != null)
        {
            try
            {
                QuestState state = req.quest.getState(client);
                if (state != QuestState.FINISHED)
                {
                    unmet.add("Q:" + shortQuestName(req.quest.getName()));
                }
            }
            catch (Throwable ignored)
            {
                // If the quest state can't be read, fall back to flagging
                // by name so the model knows a quest is involved.
                unmet.add("Q:" + shortQuestName(req.questName));
            }
        }
        else if (req.questName != null && !req.questName.isEmpty())
        {
            unmet.add("Q:" + shortQuestName(req.questName));
        }

        if (unmet.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (✗ ");
        for (int i = 0; i < unmet.size(); i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(unmet.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    /** Trim very long quest names to keep prompt compact. */
    private static String shortQuestName(String n)
    {
        if (n == null) return "?";
        if (n.length() <= 24) return n;
        return n.substring(0, 21) + "...";
    }
}
