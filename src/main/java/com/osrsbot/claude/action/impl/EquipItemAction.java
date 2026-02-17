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

public class EquipItemAction
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
            System.err.println("[ClaudeBot] EquipItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.EQUIP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.EQUIP_ITEM, "Item not found: " + action.getName());
        }

        // Phase 2: Right-click select on background thread (MenuInteractor handles client thread internally)
        boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, "Wield", action.getName());
        if (!selected)
        {
            // Re-lookup position (widget may have shifted)
            java.awt.Point point2;
            try
            {
                point2 = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget item = itemUtils.findInInventory(client, action.getName());
                    if (item == null) return null;
                    java.awt.Rectangle bounds = item.getBounds();
                    if (bounds == null) return null;
                    return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                });
            }
            catch (Throwable t)
            {
                return ActionResult.failure(ActionType.EQUIP_ITEM, "Re-lookup failed: " + t.getMessage());
            }

            if (point2 != null)
            {
                selected = human.moveAndRightClickSelect(client, point2.x, point2.y, "Wear", action.getName());
            }
        }

        if (!selected)
        {
            return ActionResult.failure(ActionType.EQUIP_ITEM, "Equip option not found for: " + action.getName());
        }

        return ActionResult.success(ActionType.EQUIP_ITEM);
    }
}
