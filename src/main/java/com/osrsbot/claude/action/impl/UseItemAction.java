package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

public class UseItemAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Widget lookup on client thread
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getName());
                if (item == null) return null;
                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UseItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.USE_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM, "Item not found: " + action.getName());
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.USE_ITEM);
    }
}
