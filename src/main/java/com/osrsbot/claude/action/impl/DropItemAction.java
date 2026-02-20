package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

import java.awt.event.KeyEvent;

public class DropItemAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.DROP_ITEM, "No item name specified");
        }

        // Phase 1: Widget lookup on client thread — also count items for verification
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getName());
                if (item == null) return null;
                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;

                int invCount = countInInventory(client, itemUtils, action.getName());
                return new Object[]{
                    new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                    invCount
                };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] DropItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.DROP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.DROP_ITEM, "Item not found: " + action.getName());
        }

        java.awt.Point point = (java.awt.Point) lookupData[0];
        int invCountBefore = (int) lookupData[1];

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

        // Phase 3: Wait for inventory to update before returning.
        // Without this, consecutive DROP_ITEM actions for the same item will all
        // find the same inventory slot (stale widget data) and only the first drop
        // actually succeeds.
        if (!waitForInventoryDecrease(client, clientThread, itemUtils, action.getName(), invCountBefore, human))
        {
            System.err.println("[ClaudeBot] DropItem: item count did not decrease for " + action.getName());
        }

        return ActionResult.success(ActionType.DROP_ITEM);
    }

    /**
     * Polls inventory until item count decreases (drop registered) or timeout.
     */
    private static boolean waitForInventoryDecrease(Client client, ClientThread clientThread,
                                                      ItemUtils itemUtils, String itemName,
                                                      int countBefore, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                int current = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> countInInventory(client, itemUtils, itemName));
                if (current < countBefore)
                {
                    return true;
                }
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
        }
        return false;
    }

    /**
     * Count how many of an item are in the player's inventory.
     */
    private static int countInInventory(Client client, ItemUtils itemUtils, String name)
    {
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null) return 0;
        int count = 0;
        Widget[] children = inventory.getDynamicChildren();
        if (children == null) return 0;
        for (Widget child : children)
        {
            if (child.getItemId() > 0)
            {
                String itemName = itemUtils.getItemName(client, child.getItemId());
                if (itemName != null && itemName.equalsIgnoreCase(name))
                {
                    count += child.getItemQuantity();
                }
            }
        }
        return count;
    }
}
