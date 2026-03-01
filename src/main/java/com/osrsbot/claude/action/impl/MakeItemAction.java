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
    // Widget groups for all known make/craft interfaces
    private static final int[] MAKE_GROUPS = {
        270, // SKILLMULTI — universal make interface (cook, smelt, spin, fletch, glass, pottery, loom, herblore, leather, gems)
        312, // SMITHING — anvil interface
        446, // CRAFTING_GOLD — gold jewelry at furnace
        6,   // SILVER_CRAFTING — silver jewelry at furnace
        324, // TANNER — tanning hides interface
        403, // TELETABS_CRAFT_IF — magic tablet creation at lectern
        140, // GRAPHICAL_MULTI — older 2-choice graphical dialog
        458, // POH_FURNITURE_CREATION — construction build list
        397, // POH_FURNITURE_CREATION_MENU — construction scrollable build/remove menu
        930, // FLETCHING_TABLE — newer fletching table interface
    };

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
            // Debug: log what widgets were actually found to help diagnose mismatches
            try
            {
                String debugInfo = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> debugMakeWidgets(client, itemManager));
                System.out.println("[MakeItem] FAILED to find '" + itemName + "'. Visible widgets: " + debugInfo);
            }
            catch (Throwable ignored) {}

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
     * Checks widget actions/names and item compositions, recursively
     * searching dynamic and static children up to 3 levels deep.
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

            Point found = searchWidgetRecursive(child, itemManager, itemName, 0);
            if (found != null) return found;
        }
        return null;
    }

    private static final int MAX_DEPTH = 3;

    /**
     * Recursively searches a widget and its children for an item match.
     */
    private static Point searchWidgetRecursive(Widget widget, ItemManager itemManager, String itemName, int depth)
    {
        Point found = checkWidgetForItem(widget, itemManager, itemName);
        if (found != null) return found;

        if (depth >= MAX_DEPTH) return null;

        // Check dynamic children (item containers)
        Widget[] dynChildren = widget.getDynamicChildren();
        if (dynChildren != null)
        {
            for (Widget dynChild : dynChildren)
            {
                if (dynChild == null || dynChild.isHidden()) continue;
                found = searchWidgetRecursive(dynChild, itemManager, itemName, depth + 1);
                if (found != null) return found;
            }
        }

        // Check static children
        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null)
        {
            for (Widget staticChild : staticChildren)
            {
                if (staticChild == null || staticChild.isHidden()) continue;
                found = searchWidgetRecursive(staticChild, itemManager, itemName, depth + 1);
                if (found != null) return found;
            }
        }

        return null;
    }

    private static Point checkWidgetForItem(Widget widget, ItemManager itemManager, String itemName)
    {
        String nameLower = itemName.toLowerCase();

        // Check widget name — OSRS make interfaces often embed item name + ingredients
        // in one HTML string, e.g. "<col=ff9040>Bronze bar</col><br><col=999999>1 x Tin ore..."
        // After stripping tags the result is "Bronze bar1 x Tin ore..." so we use
        // startsWith/contains instead of strict equals.
        String widgetName = widget.getName();
        if (widgetName != null)
        {
            String stripped = widgetName.replaceAll("<[^>]+>", "").trim();
            if (matchesItemName(stripped, nameLower))
            {
                return getCenter(widget);
            }
        }

        // Check widget text
        String widgetText = widget.getText();
        if (widgetText != null)
        {
            String stripped = widgetText.replaceAll("<[^>]+>", "").trim();
            if (matchesItemName(stripped, nameLower))
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
                if (act != null && act.toLowerCase().contains(nameLower))
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
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(widget.getItemId());
                if (comp != null)
                {
                    String compositionName = comp.getName();
                    if (compositionName != null && compositionName.equalsIgnoreCase(itemName))
                    {
                        return getCenter(widget);
                    }
                }
            }
            catch (Throwable ignored) {}
        }

        return null;
    }

    /**
     * Fuzzy item name matching: checks exact match first, then startsWith, then contains.
     * Handles cases where the widget text has the item name concatenated with other info
     * (ingredients, level requirements) after HTML tags are stripped.
     */
    private static boolean matchesItemName(String stripped, String nameLower)
    {
        if (stripped.isEmpty()) return false;
        String strippedLower = stripped.toLowerCase();

        // Exact match
        if (strippedLower.equals(nameLower)) return true;

        // Starts with the item name (common: "Bronze bar1 x Tin ore...")
        if (strippedLower.startsWith(nameLower)) return true;

        // Contains the item name as a word boundary (avoid "Iron" matching "Iron bar")
        // Only use contains if the query is specific enough (3+ chars)
        if (nameLower.length() >= 3 && strippedLower.contains(nameLower))
        {
            // Verify it's at a word boundary (not a substring of a longer word)
            int idx = strippedLower.indexOf(nameLower);
            int end = idx + nameLower.length();
            boolean startOk = idx == 0 || !Character.isLetter(strippedLower.charAt(idx - 1));
            boolean endOk = end >= strippedLower.length() || !Character.isLetter(strippedLower.charAt(end));
            return startOk && endOk;
        }

        return false;
    }

    private static Point getCenter(Widget widget)
    {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width == 0) return null;
        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }

    /**
     * Debug helper: collects names/texts/itemIds from all visible make interface widgets
     * so we can see what the interface actually contains when a match fails.
     */
    private static String debugMakeWidgets(Client client, ItemManager itemManager)
    {
        StringBuilder sb = new StringBuilder();
        for (int groupId : MAKE_GROUPS)
        {
            Widget root = client.getWidget(groupId, 0);
            if (root == null || root.isHidden()) continue;
            sb.append("G").append(groupId).append("[");
            for (int childIdx = 0; childIdx < 50; childIdx++)
            {
                Widget child = client.getWidget(groupId, childIdx);
                if (child == null || child.isHidden()) continue;
                appendWidgetDebug(sb, child, itemManager, "c" + childIdx);
                Widget[] dyn = child.getDynamicChildren();
                if (dyn != null)
                {
                    for (int i = 0; i < dyn.length; i++)
                    {
                        if (dyn[i] != null && !dyn[i].isHidden())
                            appendWidgetDebug(sb, dyn[i], itemManager, "c" + childIdx + ".d" + i);
                    }
                }
                Widget[] stat = child.getStaticChildren();
                if (stat != null)
                {
                    for (int i = 0; i < stat.length; i++)
                    {
                        if (stat[i] != null && !stat[i].isHidden())
                            appendWidgetDebug(sb, stat[i], itemManager, "c" + childIdx + ".s" + i);
                    }
                }
            }
            sb.append("] ");
        }
        return sb.length() > 500 ? sb.substring(0, 500) + "..." : sb.toString();
    }

    private static void appendWidgetDebug(StringBuilder sb, Widget w, ItemManager itemManager, String path)
    {
        String name = w.getName();
        String text = w.getText();
        int itemId = w.getItemId();
        boolean hasInfo = false;

        if (name != null && !name.isEmpty())
        {
            String stripped = name.replaceAll("<[^>]+>", "").trim();
            if (!stripped.isEmpty())
            {
                sb.append(path).append(".name='").append(stripped).append("' ");
                hasInfo = true;
            }
        }
        if (text != null && !text.isEmpty())
        {
            String stripped = text.replaceAll("<[^>]+>", "").trim();
            if (!stripped.isEmpty() && stripped.length() < 50)
            {
                sb.append(path).append(".text='").append(stripped).append("' ");
                hasInfo = true;
            }
        }
        if (itemId > 0)
        {
            String compName = "";
            try
            {
                net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
                if (comp != null) compName = comp.getName();
            }
            catch (Throwable ignored) {}
            sb.append(path).append(".itemId=").append(itemId).append("(").append(compName).append(") ");
            hasInfo = true;
        }
    }
}
