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
 * Toggles a prayer on or off by dynamically searching prayer widget children
 * for matching action text. This avoids hardcoded widget child indices which
 * are fragile and have caused bugs (Eagle Eye overwriting Smite, etc.).
 */
public class TogglePrayerAction
{
    private static final int PRAYER_GROUP_ID = 541;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String prayerName = action.getName();
        if (prayerName == null || prayerName.isEmpty())
        {
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "No prayer name provided");
        }

        // Open prayer tab if not open (F-key with widget-click fallback)
        OpenTabAction.ensureTab(client, human, clientThread, "prayer");

        // Phase 1: Find prayer widget by searching action text on client thread
        Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () ->
                findPrayerWidget(client, prayerName));
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] TogglePrayer lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "Prayer not found: " + prayerName);
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.TOGGLE_PRAYER);
    }

    /**
     * Searches prayer widget children for one whose actions contain the prayer name.
     * Actions look like "Activate Protect from Melee" or "Deactivate Smite".
     */
    private static Point findPrayerWidget(Client client, String prayerName)
    {
        String lowerName = prayerName.toLowerCase().trim();

        for (int childIdx = 0; childIdx < 50; childIdx++)
        {
            Widget child = client.getWidget(PRAYER_GROUP_ID, childIdx);
            if (child == null || child.isHidden()) continue;

            String[] actions = child.getActions();
            if (actions != null)
            {
                for (String act : actions)
                {
                    if (act != null && act.toLowerCase().contains(lowerName))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null && bounds.width > 0)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
            }

            // Also check widget name
            String widgetName = child.getName();
            if (widgetName != null && widgetName.toLowerCase().contains(lowerName))
            {
                Rectangle bounds = child.getBounds();
                if (bounds != null && bounds.width > 0)
                {
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
            }
        }
        return null;
    }
}
