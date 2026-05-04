package com.osrsbot.claude.util;

import net.runelite.api.*;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class NpcUtils
{
    @Inject
    private Client client;

    public NPC findNearest(Client client, String name)
    {
        // The LLM tends to copy NPC names verbatim from the prompt's display
        // string (e.g. "Dark wizard(lvl:7)" from a [NEARBY_NPCS] entry like
        // "Dark wizard(lvl:7)(x2) [Attack]"), which never matches an in-game
        // npc.getName(). Strip parenthesised and bracketed annotations so the
        // canonical NPC name (no OSRS NPC has either character in its name)
        // is what we compare against.
        String cleanedName = stripDisplayMetadata(name);
        if (cleanedName == null || cleanedName.isEmpty()) return null;

        Player local = client.getLocalPlayer();
        if (local == null) return null;

        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs())
        {
            if (npc.getName() != null && npc.getName().equalsIgnoreCase(cleanedName))
            {
                int dist = npc.getWorldLocation().distanceTo(local.getWorldLocation());
                if (dist < nearestDist)
                {
                    nearest = npc;
                    nearestDist = dist;
                }
            }
        }

        return nearest;
    }

    /**
     * Strips display-only annotations the LLM may copy from the prompt:
     * parenthesised content like "(lvl:7)", "(x2)", "(level-15)" and
     * bracketed option lists like "[Attack]". Returns the trimmed name.
     * No real OSRS NPC name contains "(" or "[", so this is lossless.
     */
    static String stripDisplayMetadata(String name)
    {
        if (name == null) return null;
        return name.replaceAll("\\s*\\([^)]*\\)\\s*", " ")
                   .replaceAll("\\s*\\[[^\\]]*\\]\\s*", " ")
                   .trim();
    }

    public int getMenuActionIndex(NPC npc, String option)
    {
        NPCComposition comp = npc.getTransformedComposition();
        if (comp == null) return -1;

        String[] actions = comp.getActions();
        if (actions == null) return -1;
        for (int i = 0; i < actions.length; i++)
        {
            if (actions[i] != null && actions[i].equalsIgnoreCase(option))
            {
                return i;
            }
        }
        return -1;
    }

    public MenuAction getMenuAction(String option)
    {
        // Map common NPC options to menu actions
        switch (option.toLowerCase())
        {
            case "attack":
                return MenuAction.NPC_SECOND_OPTION;
            case "talk-to":
                return MenuAction.NPC_FIRST_OPTION;
            case "pickpocket":
                return MenuAction.NPC_FIRST_OPTION;
            case "bank":
                return MenuAction.NPC_FIRST_OPTION;
            case "trade":
                return MenuAction.NPC_FOURTH_OPTION;
            default:
                return MenuAction.NPC_FIRST_OPTION;
        }
    }
}
