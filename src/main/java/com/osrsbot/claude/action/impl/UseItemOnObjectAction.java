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
import net.runelite.client.callback.ClientThread;

public class UseItemOnObjectAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ObjectUtils objectUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Lookup on client thread (blocks background thread until complete)
        // Widget APIs, Object APIs, and ItemManager all may require the client thread.
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

                return new Object[]{ "OK", itemPoint, objPoint };
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

        return ActionResult.success(ActionType.USE_ITEM_ON_OBJECT);
    }
}
