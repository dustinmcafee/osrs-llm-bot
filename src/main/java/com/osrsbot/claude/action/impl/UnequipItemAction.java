package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Unequips an item by clicking it in the Equipment tab.
 * In OSRS, clicking an equipped item removes it to inventory.
 *
 * Phase 1: Open equipment tab if not open, find the item widget.
 * Phase 2: Click the item to unequip it.
 */
public class UnequipItemAction
{
    // Equipment widget group 387, child 0 is the container
    private static final int EQUIPMENT_GROUP = 387;

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.UNEQUIP_ITEM, "No item name specified");
        }

        // Open equipment tab (F-key with widget-click fallback)
        OpenTabAction.ensureTab(client, human, clientThread, "equipment");

        // Phase 1: Find the equipped item on client thread
        Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // Search all children in the equipment widget group for the item
                Widget equipContainer = client.getWidget(WidgetInfo.EQUIPMENT);
                if (equipContainer != null && !equipContainer.isHidden())
                {
                    Widget[] children = equipContainer.getDynamicChildren();
                    if (children != null)
                    {
                        for (Widget child : children)
                        {
                            if (child.getItemId() > 0)
                            {
                                String name = itemManager.getItemComposition(child.getItemId()).getName();
                                if (name != null && name.equalsIgnoreCase(itemName))
                                {
                                    Rectangle bounds = child.getBounds();
                                    if (bounds != null)
                                    {
                                        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                    }
                                }
                            }
                        }
                    }
                }

                // Fallback: search individual equipment slot widgets (group 387)
                // Slots: 6=head, 7=cape, 8=amulet, 9=weapon, 10=body, 11=shield, 12=legs, 13=hands, 14=feet, 15=ring, 16=ammo
                for (int childIdx = 6; childIdx <= 16; childIdx++)
                {
                    Widget slotWidget = client.getWidget(EQUIPMENT_GROUP, childIdx);
                    if (slotWidget == null || slotWidget.isHidden()) continue;

                    // The item is in a dynamic child of the slot widget
                    Widget[] slotChildren = slotWidget.getDynamicChildren();
                    if (slotChildren != null)
                    {
                        for (Widget child : slotChildren)
                        {
                            if (child.getItemId() > 0)
                            {
                                String name = itemManager.getItemComposition(child.getItemId()).getName();
                                if (name != null && name.equalsIgnoreCase(itemName))
                                {
                                    Rectangle bounds = slotWidget.getBounds();
                                    if (bounds != null)
                                    {
                                        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                    }
                                }
                            }
                        }
                    }
                }

                return null;
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UnequipItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.UNEQUIP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.UNEQUIP_ITEM, "Equipped item not found: " + itemName);
        }

        // Phase 2: Click the item to unequip it
        human.moveAndClick(point.x, point.y);
        human.shortPause();

        return ActionResult.success(ActionType.UNEQUIP_ITEM);
    }
}
