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

import java.awt.event.KeyEvent;

public class DropItemAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.DROP_ITEM, "No item name specified");
        }

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

        // Phase 2: Drop the item
        // Use shift-click (fast) by default, fall back to right-click menu if option is "menu"
        String option = action.getOption();
        if (option != null && option.equalsIgnoreCase("menu"))
        {
            // Slow right-click drop via menu
            boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, "Drop", action.getName());
            if (!selected)
            {
                return ActionResult.failure(ActionType.DROP_ITEM, "Drop option not found for: " + action.getName());
            }
        }
        else
        {
            // Fast shift-click drop (how humans actually do it)
            human.keyDown(KeyEvent.VK_SHIFT);
            human.moveAndClick(point.x, point.y);
            human.keyUp(KeyEvent.VK_SHIFT);
        }

        // Wait one game tick so inventory widget refreshes before next drop
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.DROP_ITEM);
    }
}
