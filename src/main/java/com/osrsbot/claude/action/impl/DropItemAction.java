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

public class DropItemAction
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
            System.err.println("[ClaudeBot] DropItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.DROP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.DROP_ITEM, "Item not found: " + action.getName());
        }

        // Phase 2: Right-click select on background thread (MenuInteractor handles client thread internally)
        boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, "Drop", action.getName());
        if (!selected)
        {
            return ActionResult.failure(ActionType.DROP_ITEM, "Drop option not found for: " + action.getName());
        }

        return ActionResult.success(ActionType.DROP_ITEM);
    }
}
