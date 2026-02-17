package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

public class ToggleRunAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Phase 1: Widget lookup on client thread
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget runOrb = client.getWidget(WidgetInfo.MINIMAP_TOGGLE_RUN_ORB);
                if (runOrb == null || runOrb.isHidden()) return null;
                java.awt.Rectangle bounds = runOrb.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] ToggleRun lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.TOGGLE_RUN, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.TOGGLE_RUN, "Run orb not visible");
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.TOGGLE_RUN);
    }
}
