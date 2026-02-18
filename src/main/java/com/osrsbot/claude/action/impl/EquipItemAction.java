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

/**
 * Equips an item from the inventory by left-clicking it — the same way a human does it.
 *
 * In OSRS, equippable items have Wield/Wear/Equip as their default left-click action,
 * so a single left-click equips them. No right-click menu navigation needed.
 */
public class EquipItemAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Find item widget on client thread
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getName());
                if (item == null) return null;

                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;

                // Return both the center point and the slot index for debugging
                return new Object[]{
                    new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                    item.getIndex(),
                    bounds
                };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] EquipItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.EQUIP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.EQUIP_ITEM, "Item not found: " + action.getName());
        }

        java.awt.Point point = (java.awt.Point) lookupData[0];
        int slotIndex = (int) lookupData[1];
        java.awt.Rectangle bounds = (java.awt.Rectangle) lookupData[2];

        System.out.println("[ClaudeBot] EquipItem '" + action.getName() + "' slot=" + slotIndex
            + " bounds=(" + bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height + ")"
            + " click=(" + point.x + "," + point.y + ")");

        // Phase 2: Left-click the item (equippable items equip on left-click)
        human.moveAndClick(point.x, point.y);

        // Wait one game tick so inventory/equipment state refreshes before next action
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.EQUIP_ITEM);
    }
}
