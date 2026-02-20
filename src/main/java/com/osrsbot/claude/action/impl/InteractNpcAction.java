package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.NpcUtils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;

@Slf4j
public class InteractNpcAction
{
    public static ActionResult execute(Client client, HumanSimulator human, NpcUtils npcUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.INTERACT_NPC, "No NPC name specified");
        }
        if (action.getOption() == null || action.getOption().isEmpty())
        {
            // Query available options so the LLM knows what's possible
            try
            {
                String availableOptions = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    NPC npc = npcUtils.findNearest(client, action.getName());
                    if (npc == null) return "NPC not found nearby: " + action.getName();

                    NPCComposition comp = npc.getTransformedComposition();
                    if (comp == null) return "NPC has no actions: " + action.getName();

                    String[] actions = comp.getActions();
                    if (actions == null) return "NPC has no actions: " + action.getName();

                    StringBuilder sb = new StringBuilder();
                    for (String a : actions)
                    {
                        if (a != null && !a.isEmpty())
                        {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(a);
                        }
                    }
                    return sb.toString();
                });
                return ActionResult.failure(ActionType.INTERACT_NPC,
                    "Missing \"option\" for " + action.getName() + ". Available options: [" + availableOptions + "]. "
                    + "Use: {\"action\":\"INTERACT_NPC\",\"name\":\"" + action.getName() + "\",\"option\":\"<one of these>\"}");
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.INTERACT_NPC,
                    "Missing \"option\" for " + action.getName() + ". Check [NEARBY_NPCS] for available options.");
            }
        }

        // Phase 1: Lookup on client thread (blocks background thread until complete)
        // All NPC API calls (getName, getIndex, getWorldLocation, etc.) require the client thread.
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                NPC npc = npcUtils.findNearest(client, action.getName());
                if (npc == null) return null;

                int actionIndex = npcUtils.getMenuActionIndex(npc, action.getOption());
                int npcIndex = npc.getIndex();
                String npcName = npc.getName() != null ? npc.getName() : "";

                java.awt.Point screenPoint = null;
                if (npc.getCanvasTilePoly() != null)
                {
                    java.awt.Rectangle bounds = npc.getCanvasTilePoly().getBounds();
                    screenPoint = new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }

                return new Object[]{ npcIndex, npcName, actionIndex, screenPoint };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] NPC lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.INTERACT_NPC, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.INTERACT_NPC, "NPC not found: " + action.getName());
        }

        int npcIndex = (int) lookupData[0];
        String npcName = (String) lookupData[1];
        int actionIndex = (int) lookupData[2];
        java.awt.Point screenPoint = (java.awt.Point) lookupData[3];

        if (actionIndex < 0)
        {
            return ActionResult.failure(ActionType.INTERACT_NPC, "Option not found on NPC: " + action.getOption());
        }

        // Phase 2: Mouse movement on background thread (with sleeps for humanization)
        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

        // Phase 3: Menu action on client thread (fire-and-forget)
        MenuAction menuAction = getNpcMenuAction(actionIndex);
        String option = action.getOption();

        System.out.println("[ClaudeBot] NPC menuAction: idx=" + npcIndex +
            " action=" + menuAction + " option=" + option + " name=" + npcName);

        clientThread.invokeLater(() -> {
            try
            {
                client.menuAction(
                    0,              // param0
                    0,              // param1
                    menuAction,     // type
                    npcIndex,       // identifier (NPC index)
                    -1,             // itemId
                    option,         // option text
                    npcName         // target text
                );
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] NPC menuAction FAILED on client thread: " +
                    t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        });

        human.shortPause();
        return ActionResult.success(ActionType.INTERACT_NPC);
    }

    private static MenuAction getNpcMenuAction(int actionIndex)
    {
        switch (actionIndex)
        {
            case 0: return MenuAction.NPC_FIRST_OPTION;
            case 1: return MenuAction.NPC_SECOND_OPTION;
            case 2: return MenuAction.NPC_THIRD_OPTION;
            case 3: return MenuAction.NPC_FOURTH_OPTION;
            case 4: return MenuAction.NPC_FIFTH_OPTION;
            default: return MenuAction.NPC_FIRST_OPTION;
        }
    }
}
