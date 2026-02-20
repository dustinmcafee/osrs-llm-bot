package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import com.osrsbot.claude.util.NpcUtils;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

public class UseItemOnNpcAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, NpcUtils npcUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getItem() == null || action.getItem().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "No item name specified");
        }
        if (action.getNpc() == null || action.getNpc().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "No NPC name specified");
        }

        // Phase 1: Lookup on client thread (blocks background thread until complete)
        // Widget APIs, NPC APIs, and ItemManager all may require the client thread.
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getItem());
                if (item == null) return new Object[]{ "ITEM_NOT_FOUND" };

                NPC npc = npcUtils.findNearest(client, action.getNpc());
                if (npc == null) return new Object[]{ "NPC_NOT_FOUND" };

                java.awt.Point itemPoint = null;
                java.awt.Rectangle itemBounds = item.getBounds();
                if (itemBounds != null)
                {
                    itemPoint = new java.awt.Point((int) itemBounds.getCenterX(), (int) itemBounds.getCenterY());
                }

                java.awt.Point npcPoint = null;
                if (npc.getCanvasTilePoly() != null)
                {
                    java.awt.Rectangle npcBounds = npc.getCanvasTilePoly().getBounds();
                    npcPoint = new java.awt.Point((int) npcBounds.getCenterX(), (int) npcBounds.getCenterY());
                }

                return new Object[]{ "OK", itemPoint, npcPoint };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UseItemOnNpc lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if ("ITEM_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "Item not found: " + action.getItem());
        }
        if ("NPC_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "NPC not found: " + action.getNpc());
        }

        java.awt.Point itemPoint = (java.awt.Point) lookupData[1];
        java.awt.Point npcPoint = (java.awt.Point) lookupData[2];

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "Item widget not visible");
        }
        if (npcPoint == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_NPC, "NPC not visible on screen");
        }

        // Phase 2: Mouse interaction on background thread (with sleeps for humanization)
        // Click item to enter "Use" mode
        human.moveAndClick(itemPoint.x, itemPoint.y);
        human.shortPause();

        // Click NPC
        human.moveAndClick(npcPoint.x, npcPoint.y);

        return ActionResult.success(ActionType.USE_ITEM_ON_NPC);
    }
}
