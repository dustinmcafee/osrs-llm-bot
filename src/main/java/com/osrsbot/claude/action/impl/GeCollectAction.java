package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Collects completed GE offers to inventory.
 * Searches the GE interface (widget group 465) for "Collect" buttons
 * and clicks them to retrieve purchased items and leftover coins.
 */
public class GeCollectAction
{
    private static final int GE_GROUP_ID = 465;
    private static final int WIDGET_SEARCH_RANGE = 100;

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action)
    {
        // Step 1: Find collect button in the GE interface
        Point collectPoint;
        try
        {
            collectPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden())
                {
                    return null;
                }

                // Search for "Collect to inventory" first, then "Collect" as fallback
                Point p = findWidgetByAction(client, "Collect to inventory");
                if (p != null) return p;

                p = findWidgetByAction(client, "Collect to bank");
                if (p != null) return p;

                p = findWidgetByAction(client, "Collect");
                if (p != null) return p;

                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_COLLECT, "GE collect lookup failed: " + t.getMessage());
        }

        if (collectPoint == null)
        {
            return ActionResult.failure(ActionType.GE_COLLECT,
                "GE interface not open or no collect button found. Open the GE first with "
                + "INTERACT_OBJECT(\"Grand Exchange booth\", option=\"Exchange\").");
        }

        // Step 2: Click collect
        human.moveAndClick(collectPoint.x, collectPoint.y);
        human.shortPause();

        return ActionResult.success(ActionType.GE_COLLECT, "Collected completed GE offers");
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
