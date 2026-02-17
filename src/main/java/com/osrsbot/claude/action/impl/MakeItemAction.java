package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Clicks an item in a make/crafting/smithing interface.
 *
 * Searches multiple common widget groups that OSRS uses for "make X" interfaces:
 * - Group 270: generic chatbox make interface (most common post-2018)
 * - Group 312: smithing anvil interface
 * - Group 446: other make interfaces
 *
 * All interaction through HumanSimulator for realistic mouse movement.
 */
public class MakeItemAction
{
    // Widget groups for various make interfaces
    private static final int[] MAKE_GROUPS = { 270, 312, 446 };

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.MAKE_ITEM, "No item name provided");
        }

        // Phase 1: Search make interface widgets on client thread
        Point itemPoint;
        try
        {
            itemPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                for (int groupId : MAKE_GROUPS)
                {
                    Point found = searchMakeGroup(client, itemManager, groupId, itemName);
                    if (found != null) return found;
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.MAKE_ITEM, "Make interface lookup failed: " + t.getMessage());
        }

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.MAKE_ITEM,
                "Item not found in make interface: " + itemName);
        }

        // Phase 2: Click the item widget with humanized mouse
        human.moveAndClick(itemPoint.x, itemPoint.y);
        human.shortPause();

        // If quantity specified and > 1, we may need to handle quantity input
        // The game typically makes-all on a single click or provides a quantity dialogue
        int quantity = action.getQuantity();
        if (quantity > 1)
        {
            // Some interfaces require typing a number after right-clicking
            // For now, a single left-click makes the default quantity (usually "Make All" or "Make 1")
            // The AI can use CLICK_WIDGET to interact with quantity buttons if needed
        }

        return ActionResult.success(ActionType.MAKE_ITEM);
    }

    /**
     * Searches a widget group for an item matching the given name.
     * Checks both widget actions/names and item compositions for dynamic children.
     */
    private static Point searchMakeGroup(Client client, ItemManager itemManager, int groupId, String itemName)
    {
        // Try the root widget first
        Widget root = client.getWidget(groupId, 0);
        if (root == null || root.isHidden()) return null;

        // Search through all children of the group
        for (int childIdx = 0; childIdx < 100; childIdx++)
        {
            Widget child = client.getWidget(groupId, childIdx);
            if (child == null) continue;
            if (child.isHidden()) continue;

            // Check widget actions
            Point found = checkWidgetForItem(child, itemManager, itemName);
            if (found != null) return found;

            // Check dynamic children (item containers)
            Widget[] dynChildren = child.getDynamicChildren();
            if (dynChildren != null)
            {
                for (Widget dynChild : dynChildren)
                {
                    if (dynChild == null || dynChild.isHidden()) continue;
                    found = checkWidgetForItem(dynChild, itemManager, itemName);
                    if (found != null) return found;
                }
            }

            // Check static children
            Widget[] staticChildren = child.getStaticChildren();
            if (staticChildren != null)
            {
                for (Widget staticChild : staticChildren)
                {
                    if (staticChild == null || staticChild.isHidden()) continue;
                    found = checkWidgetForItem(staticChild, itemManager, itemName);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static Point checkWidgetForItem(Widget widget, ItemManager itemManager, String itemName)
    {
        // Check widget name/text
        String widgetName = widget.getName();
        if (widgetName != null)
        {
            String stripped = widgetName.replaceAll("<[^>]+>", "").trim();
            if (stripped.equalsIgnoreCase(itemName))
            {
                return getCenter(widget);
            }
        }

        String widgetText = widget.getText();
        if (widgetText != null)
        {
            String stripped = widgetText.replaceAll("<[^>]+>", "").trim();
            if (stripped.equalsIgnoreCase(itemName))
            {
                return getCenter(widget);
            }
        }

        // Check actions for the item name
        String[] actions = widget.getActions();
        if (actions != null)
        {
            for (String act : actions)
            {
                if (act != null && act.toLowerCase().contains(itemName.toLowerCase()))
                {
                    return getCenter(widget);
                }
            }
        }

        // Check item ID via ItemManager
        if (widget.getItemId() > 0)
        {
            try
            {
                String compositionName = itemManager.getItemComposition(widget.getItemId()).getName();
                if (compositionName != null && compositionName.equalsIgnoreCase(itemName))
                {
                    return getCenter(widget);
                }
            }
            catch (Throwable ignored) {}
        }

        return null;
    }

    private static Point getCenter(Widget widget)
    {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) return null;
        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }
}
