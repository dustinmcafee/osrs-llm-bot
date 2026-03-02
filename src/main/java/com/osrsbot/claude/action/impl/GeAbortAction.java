package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Aborts (cancels) a pending GE offer.
 * Finds the offer slot matching the given item name, clicks it to open details,
 * then clicks the "Abort offer" button.
 */
public class GeAbortAction
{
    private static final int GE_GROUP_ID = 465;
    private static final int WIDGET_SEARCH_RANGE = 100;

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ItemManager itemManager, ClientThread clientThread,
                                       BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.GE_ABORT, "No item name provided");
        }

        // Step 1: Find the offer slot for this item and click it
        Object[] step1;
        try
        {
            step1 = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden())
                {
                    return new Object[]{ "GE_NOT_OPEN" };
                }

                // Use the GE offers API to find which slot has this item
                GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
                if (offers == null)
                {
                    return new Object[]{ "NO_OFFERS" };
                }

                int targetSlot = -1;
                for (int i = 0; i < offers.length; i++)
                {
                    GrandExchangeOffer offer = offers[i];
                    if (offer == null) continue;
                    GrandExchangeOfferState state = offer.getState();
                    if (state == null) continue;

                    // Only abort active offers
                    boolean isActive = state.name().startsWith("BUYING")
                        || state.name().startsWith("SELLING");
                    if (!isActive) continue;

                    int offerId = offer.getItemId();
                    if (offerId <= 0) continue;

                    try
                    {
                        net.runelite.api.ItemComposition comp = itemManager.getItemComposition(offerId);
                        if (comp != null && comp.getName() != null
                            && comp.getName().equalsIgnoreCase(itemName))
                        {
                            targetSlot = i;
                            break;
                        }
                    }
                    catch (Throwable ignored) {}
                }

                if (targetSlot < 0)
                {
                    return new Object[]{ "NOT_FOUND" };
                }

                // Find the widget for this offer slot — search for widgets with
                // "View offer" or the item name in their actions/text
                // GE slots are typically at specific child indices in group 465
                // Try clicking the slot widget. Offer slots start around child 7 in group 465,
                // with each slot being a few children apart.
                // Common pattern: children 7, 8, 9, 10, 11, 12, 13, 14 for 8 slots
                // But this varies, so search by action text matching the item.
                Point slotPoint = findOfferSlot(client, itemManager, itemName);
                if (slotPoint != null)
                {
                    return new Object[]{ "FOUND_SLOT", slotPoint };
                }

                // Fallback: try known slot child indices
                // OSRS GE slot widgets are at group 465, children 7-14 (one per slot)
                // Each has child widget 0 = clickable area
                for (int childIdx = 7; childIdx <= 14; childIdx++)
                {
                    Widget slotWidget = client.getWidget(GE_GROUP_ID, childIdx);
                    if (slotWidget == null || slotWidget.isHidden()) continue;

                    // Check if this slot has actions suggesting it's an active offer
                    String[] actions = slotWidget.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.toLowerCase().contains("view"))
                            {
                                // This might be our slot — check children for item name
                                Widget[] children = slotWidget.getDynamicChildren();
                                if (children != null)
                                {
                                    for (Widget c : children)
                                    {
                                        if (c == null) continue;
                                        String text = c.getText();
                                        if (text != null && text.replaceAll("<[^>]+>", "")
                                            .trim().equalsIgnoreCase(itemName))
                                        {
                                            Rectangle b = slotWidget.getBounds();
                                            if (b != null && b.width > 0)
                                            {
                                                return new Object[]{ "FOUND_SLOT",
                                                    new Point((int) b.getCenterX(), (int) b.getCenterY()) };
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                return new Object[]{ "SLOT_NOT_FOUND" };
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_ABORT, "GE abort lookup failed: " + t.getMessage());
        }

        String status = (String) step1[0];
        if ("GE_NOT_OPEN".equals(status))
        {
            return ActionResult.failure(ActionType.GE_ABORT,
                "GE interface not open. Open the GE first.");
        }
        if ("NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.GE_ABORT,
                "No active offer found for: " + itemName);
        }
        if ("SLOT_NOT_FOUND".equals(status) || !"FOUND_SLOT".equals(status))
        {
            return ActionResult.failure(ActionType.GE_ABORT,
                "Could not find the offer slot widget for: " + itemName);
        }

        // Click the offer slot
        Point slotPoint = (Point) step1[1];
        human.moveAndClick(slotPoint.x, slotPoint.y);
        human.shortPause();

        // Step 2: Find and click "Abort offer" button
        human.getTimingEngine().sleep(600); // wait for detail view to open

        Point abortPoint;
        try
        {
            abortPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                return findWidgetByAction(client, "Abort offer");
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_ABORT, "Abort button lookup failed: " + t.getMessage());
        }

        if (abortPoint == null)
        {
            return ActionResult.failure(ActionType.GE_ABORT,
                "Abort button not found. The offer detail view may not have opened.");
        }

        human.moveAndClick(abortPoint.x, abortPoint.y);
        human.shortPause();

        return ActionResult.success(ActionType.GE_ABORT, "Aborted GE offer for " + itemName);
    }

    private static Point findOfferSlot(Client client, ItemManager itemManager, String itemName)
    {
        String nameLower = itemName.toLowerCase();
        for (int childIdx = 0; childIdx < WIDGET_SEARCH_RANGE; childIdx++)
        {
            Widget child = client.getWidget(GE_GROUP_ID, childIdx);
            if (child == null || child.isHidden()) continue;

            // Check text/name of this widget and children
            if (widgetContainsText(child, nameLower))
            {
                String[] actions = child.getActions();
                if (actions != null)
                {
                    for (String act : actions)
                    {
                        if (act != null)
                        {
                            Rectangle b = child.getBounds();
                            if (b != null && b.width > 0)
                            {
                                return new Point((int) b.getCenterX(), (int) b.getCenterY());
                            }
                        }
                    }
                }
            }

            // Check children
            Widget[] dynChildren = child.getDynamicChildren();
            if (dynChildren != null)
            {
                for (Widget dyn : dynChildren)
                {
                    if (dyn == null || dyn.isHidden()) continue;
                    if (widgetContainsText(dyn, nameLower))
                    {
                        // Click the parent slot, not the text child
                        Rectangle b = child.getBounds();
                        if (b != null && b.width > 0)
                        {
                            return new Point((int) b.getCenterX(), (int) b.getCenterY());
                        }
                    }
                }
            }

            Widget[] staticChildren = child.getStaticChildren();
            if (staticChildren != null)
            {
                for (Widget stat : staticChildren)
                {
                    if (stat == null || stat.isHidden()) continue;
                    if (widgetContainsText(stat, nameLower))
                    {
                        Rectangle b = child.getBounds();
                        if (b != null && b.width > 0)
                        {
                            return new Point((int) b.getCenterX(), (int) b.getCenterY());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean widgetContainsText(Widget w, String nameLower)
    {
        String text = w.getText();
        if (text != null)
        {
            String stripped = text.replaceAll("<[^>]+>", "").trim().toLowerCase();
            if (stripped.contains(nameLower)) return true;
        }
        String name = w.getName();
        if (name != null)
        {
            String stripped = name.replaceAll("<[^>]+>", "").trim().toLowerCase();
            if (stripped.contains(nameLower)) return true;
        }
        return false;
    }

    private static Point findWidgetByAction(Client client, String targetAction)
    {
        String targetLower = targetAction.toLowerCase();
        for (int childIdx = 0; childIdx < WIDGET_SEARCH_RANGE; childIdx++)
        {
            Widget child = client.getWidget(GE_GROUP_ID, childIdx);
            if (child == null || child.isHidden()) continue;

            Point p = checkActions(child, targetLower);
            if (p != null) return p;

            Widget[] dynChildren = child.getDynamicChildren();
            if (dynChildren != null)
            {
                for (Widget dyn : dynChildren)
                {
                    if (dyn == null || dyn.isHidden()) continue;
                    p = checkActions(dyn, targetLower);
                    if (p != null) return p;
                }
            }

            Widget[] staticChildren = child.getStaticChildren();
            if (staticChildren != null)
            {
                for (Widget stat : staticChildren)
                {
                    if (stat == null || stat.isHidden()) continue;
                    p = checkActions(stat, targetLower);
                    if (p != null) return p;
                }
            }

            Widget[] children = child.getChildren();
            if (children != null)
            {
                for (Widget c : children)
                {
                    if (c == null || c.isHidden()) continue;
                    p = checkActions(c, targetLower);
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    private static Point checkActions(Widget widget, String targetLower)
    {
        String[] actions = widget.getActions();
        if (actions == null) return null;
        for (String act : actions)
        {
            if (act != null)
            {
                String stripped = act.replaceAll("<[^>]+>", "").trim().toLowerCase();
                if (stripped.contains(targetLower))
                {
                    Rectangle bounds = widget.getBounds();
                    if (bounds != null && bounds.width > 0)
                    {
                        return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                    }
                }
            }
        }
        return null;
    }
}
