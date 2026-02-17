package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.util.HashMap;
import java.util.Map;

public class TogglePrayerAction
{
    private static final Map<String, int[]> PRAYER_WIDGETS = new HashMap<>();

    static
    {
        PRAYER_WIDGETS.put("thick skin", new int[]{541, 5});
        PRAYER_WIDGETS.put("burst of strength", new int[]{541, 6});
        PRAYER_WIDGETS.put("clarity of thought", new int[]{541, 7});
        PRAYER_WIDGETS.put("sharp eye", new int[]{541, 8});
        PRAYER_WIDGETS.put("mystic will", new int[]{541, 9});
        PRAYER_WIDGETS.put("rock skin", new int[]{541, 10});
        PRAYER_WIDGETS.put("superhuman strength", new int[]{541, 11});
        PRAYER_WIDGETS.put("improved reflexes", new int[]{541, 12});
        PRAYER_WIDGETS.put("rapid restore", new int[]{541, 13});
        PRAYER_WIDGETS.put("rapid heal", new int[]{541, 14});
        PRAYER_WIDGETS.put("protect item", new int[]{541, 15});
        PRAYER_WIDGETS.put("hawk eye", new int[]{541, 16});
        PRAYER_WIDGETS.put("protect from magic", new int[]{541, 17});
        PRAYER_WIDGETS.put("protect from missiles", new int[]{541, 18});
        PRAYER_WIDGETS.put("protect from melee", new int[]{541, 19});
        PRAYER_WIDGETS.put("retribution", new int[]{541, 20});
        PRAYER_WIDGETS.put("redemption", new int[]{541, 21});
        PRAYER_WIDGETS.put("smite", new int[]{541, 22});
        PRAYER_WIDGETS.put("preserve", new int[]{541, 23});
        PRAYER_WIDGETS.put("chivalry", new int[]{541, 24});
        PRAYER_WIDGETS.put("piety", new int[]{541, 25});
        PRAYER_WIDGETS.put("rigour", new int[]{541, 26});
        PRAYER_WIDGETS.put("augury", new int[]{541, 27});
        PRAYER_WIDGETS.put("eagle eye", new int[]{541, 22});
        PRAYER_WIDGETS.put("mystic might", new int[]{541, 23});
    }

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        String prayerName = action.getName().toLowerCase().trim();
        int[] widgetIds = PRAYER_WIDGETS.get(prayerName);

        if (widgetIds == null)
        {
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "Unknown prayer: " + action.getName());
        }

        // Phase 1: Widget lookup on client thread
        java.awt.Point point;
        try
        {
            final int groupId = widgetIds[0];
            final int childId = widgetIds[1];
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget prayerWidget = client.getWidget(groupId, childId);
                if (prayerWidget == null || prayerWidget.isHidden()) return null;
                java.awt.Rectangle bounds = prayerWidget.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] TogglePrayer lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.TOGGLE_PRAYER, "Prayer widget not visible: " + action.getName());
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.TOGGLE_PRAYER);
    }
}
