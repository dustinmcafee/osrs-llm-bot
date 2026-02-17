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
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        NPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (NPC npc : client.getNpcs())
        {
            if (npc.getName() != null && npc.getName().equalsIgnoreCase(name))
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

    public int getMenuActionIndex(NPC npc, String option)
    {
        NPCComposition comp = npc.getTransformedComposition();
        if (comp == null) return -1;

        String[] actions = comp.getActions();
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
