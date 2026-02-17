package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.TileUtils;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

public class WalkToAction
{
    public static ActionResult execute(Client client, HumanSimulator human, TileUtils tileUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Coordinate conversion on client thread
        java.awt.Point screenPoint;
        try
        {
            screenPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                WorldPoint target = new WorldPoint(action.getX(), action.getY(), client.getPlane());
                return tileUtils.worldToScreen(client, target);
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] WalkTo lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.WALK_TO, "Lookup failed: " + t.getMessage());
        }

        if (screenPoint == null)
        {
            return ActionResult.failure(ActionType.WALK_TO, "Target tile not visible on screen");
        }

        // Phase 2: Click on background thread
        human.moveAndClick(screenPoint.x, screenPoint.y);
        return ActionResult.success(ActionType.WALK_TO);
    }
}
