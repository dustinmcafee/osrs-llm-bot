package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import com.osrsbot.claude.util.ObjectUtils;
import net.runelite.api.Client;
import net.runelite.api.TileObject;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

public class UseItemOnObjectAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 1800; // 3 game ticks

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ObjectUtils objectUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getItem() == null || action.getItem().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "No item name specified");
        }
        if (action.getObject() == null || action.getObject().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "No object name specified");
        }

        // Phase 1: Lookup on client thread (blocks background thread until complete)
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getItem());
                if (item == null) return new Object[]{ "ITEM_NOT_FOUND" };

                TileObject obj = objectUtils.findNearest(client, action.getObject());
                if (obj == null) return new Object[]{ "OBJ_NOT_FOUND" };

                java.awt.Point itemPoint = null;
                java.awt.Rectangle itemBounds = item.getBounds();
                if (itemBounds != null)
                {
                    itemPoint = new java.awt.Point((int) itemBounds.getCenterX(), (int) itemBounds.getCenterY());
                }

                java.awt.Point objPoint = null;
                if (obj.getClickbox() != null)
                {
                    java.awt.Rectangle objBounds = obj.getClickbox().getBounds();
                    objPoint = new java.awt.Point((int) objBounds.getCenterX(), (int) objBounds.getCenterY());
                }

                int itemCount = countInInventory(client, itemUtils, action.getItem());
                return new Object[]{ "OK", itemPoint, objPoint, itemCount };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UseItemOnObject lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if ("ITEM_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "Item not found: " + action.getItem());
        }
        if ("OBJ_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "Object not found: " + action.getObject());
        }

        java.awt.Point itemPoint = (java.awt.Point) lookupData[1];
        java.awt.Point objPoint = (java.awt.Point) lookupData[2];
        int itemCountBefore = (int) lookupData[3];

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "Item widget not visible");
        }
        if (objPoint == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_OBJECT, "Object not visible on screen");
        }

        // Phase 2: Mouse interaction on background thread (with sleeps for humanization)
        // Click item to enter "Use" mode
        human.moveAndClick(itemPoint.x, itemPoint.y);
        human.shortPause();

        // Click object
        human.moveAndClick(objPoint.x, objPoint.y);

        // Phase 3: Wait for item count to decrease (item consumed).
        // May timeout if the action opens a Make interface instead — that's OK.
        waitForInventoryDecrease(client, clientThread, itemUtils, action.getItem(), itemCountBefore, human);

        return ActionResult.success(ActionType.USE_ITEM_ON_OBJECT);
    }

    private static void waitForInventoryDecrease(Client client, ClientThread clientThread,
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
                if (current < countBefore) return;
            }
            catch (Throwable t) {}
        }
    }

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
