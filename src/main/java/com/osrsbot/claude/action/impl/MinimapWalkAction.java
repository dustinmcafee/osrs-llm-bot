package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

/**
 * Clicks on the minimap to walk to a world coordinate.
 *
 * Uses RuneLite's Perspective.localToMinimap() for coordinate conversion, which
 * correctly handles camera rotation and minimap widget positioning across all
 * client layout modes (fixed, resizable classic, resizable modern).
 *
 * For targets beyond minimap range (~18 tiles), calculates an intermediate point
 * in the correct direction and clicks there instead.
 */
public class MinimapWalkAction
{
    private static final int MINIMAP_TILE_RANGE = 17; // max tiles the minimap can show

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action)
    {
        int targetX = action.getX();
        int targetY = action.getY();

        if (targetX <= 0 || targetY <= 0)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Invalid target coordinates");
        }

        // Phase 1: Calculate minimap screen point on client thread
        java.awt.Point screenPoint;
        try
        {
            screenPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                if (local == null) return null;

                WorldPoint playerPos = local.getWorldLocation();
                int dx = targetX - playerPos.getX();
                int dy = targetY - playerPos.getY();
                double dist = Math.sqrt(dx * dx + dy * dy);

                // Clamp to minimap range — walk toward the target, not past it
                int walkX = targetX;
                int walkY = targetY;
                if (dist > MINIMAP_TILE_RANGE)
                {
                    double scale = MINIMAP_TILE_RANGE / dist;
                    walkX = playerPos.getX() + (int) (dx * scale);
                    walkY = playerPos.getY() + (int) (dy * scale);
                }

                // Convert world coords to local coords (scene-relative)
                LocalPoint localPoint = LocalPoint.fromWorld(client, walkX, walkY);
                if (localPoint == null)
                {
                    System.err.println("[ClaudeBot] MinimapWalk: LocalPoint.fromWorld returned null for ("
                        + walkX + "," + walkY + ") — outside scene");
                    return null;
                }

                // Use RuneLite's built-in conversion (handles camera rotation + widget position)
                net.runelite.api.Point minimapPoint = Perspective.localToMinimap(client, localPoint);
                if (minimapPoint == null)
                {
                    System.err.println("[ClaudeBot] MinimapWalk: Perspective.localToMinimap returned null for ("
                        + walkX + "," + walkY + ")");
                    return null;
                }

                System.out.println("[ClaudeBot] MinimapWalk: player=(" + playerPos.getX() + "," + playerPos.getY()
                    + ") target=(" + targetX + "," + targetY + ") clamped=(" + walkX + "," + walkY
                    + ") screen=(" + minimapPoint.getX() + "," + minimapPoint.getY() + ")");

                return new java.awt.Point(minimapPoint.getX(), minimapPoint.getY());
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Minimap lookup failed: " + t.getMessage());
        }

        if (screenPoint == null)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Could not calculate minimap position");
        }

        // Phase 2: Click on the minimap with humanized mouse movement
        human.moveAndClick(screenPoint.x, screenPoint.y);
        human.shortPause();

        return ActionResult.success(ActionType.MINIMAP_WALK);
    }
}
