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

public class UseItemOnItemAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Widget lookups on client thread
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item1 = itemUtils.findInInventory(client, action.getItem1());
                if (item1 == null) return new Object[]{ "ITEM1_NOT_FOUND" };

                Widget item2 = itemUtils.findInInventory(client, action.getItem2());
                if (item2 == null) return new Object[]{ "ITEM2_NOT_FOUND" };

                java.awt.Point p1 = getWidgetCenter(item1);
                java.awt.Point p2 = getWidgetCenter(item2);

                return new Object[]{ "OK", p1, p2 };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UseItemOnItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if ("ITEM1_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item not found: " + action.getItem1());
        }
        if ("ITEM2_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item not found: " + action.getItem2());
        }

        java.awt.Point p1 = (java.awt.Point) lookupData[1];
        java.awt.Point p2 = (java.awt.Point) lookupData[2];

        if (p1 == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item 1 widget not visible");
        }
        if (p2 == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item 2 widget not visible");
        }

        // Phase 2: Click sequence on background thread
        human.moveAndClick(p1.x, p1.y);
        human.shortPause();
        human.moveAndClick(p2.x, p2.y);

        return ActionResult.success(ActionType.USE_ITEM_ON_ITEM);
    }

    private static java.awt.Point getWidgetCenter(Widget widget)
    {
        java.awt.Rectangle bounds = widget.getBounds();
        if (bounds == null) return null;
        return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }
}
