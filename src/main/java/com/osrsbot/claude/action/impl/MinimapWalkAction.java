package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Clicks on the minimap to walk to a world coordinate.
 *
 * Unlike WALK_TO which clicks on the game world viewport, this clicks on the
 * minimap — useful for walking longer distances that are off-screen but within
 * minimap range (~18 tiles).
 *
 * Phase 1 (client thread): Get player position, camera angle, minimap widget bounds.
 * Phase 2 (background thread): Calculate minimap click position, apply camera rotation,
 * clamp to minimap circle, and click via HumanSimulator.
 *
 * The coordinate math rotates the tile offset by the camera yaw angle because
 * the OSRS minimap rotates with the camera — a human would see the minimap rotated
 * and click accordingly.
 */
public class MinimapWalkAction
{
    private static final double PIXELS_PER_TILE = 4.0;
    private static final int MINIMAP_RADIUS = 72; // clickable radius in pixels

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action)
    {
        int targetX = action.getX();
        int targetY = action.getY();

        if (targetX <= 0 || targetY <= 0)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Invalid target coordinates");
        }

        // Phase 1: Get player position, camera angle, and minimap bounds on client thread
        Object[] mapData;
        try
        {
            mapData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                if (local == null) return null;

                WorldPoint playerPos = local.getWorldLocation();
                int cameraYaw = client.getMapAngle(); // 0-2048, where 0 = north

                // Get minimap widget bounds
                // Widget group 164, child 25 is the minimap draw area in fixed mode
                // Widget group 161, child 24 is in resizable mode
                Widget minimap = client.getWidget(164, 25);
                Rectangle minimapBounds = null;

                if (minimap != null && !minimap.isHidden())
                {
                    minimapBounds = minimap.getBounds();
                }

                // Try resizable mode minimap
                if (minimapBounds == null)
                {
                    Widget resizableMinimap = client.getWidget(161, 24);
                    if (resizableMinimap != null && !resizableMinimap.isHidden())
                    {
                        minimapBounds = resizableMinimap.getBounds();
                    }
                }

                // Fallback: use fixed-layout minimap position
                if (minimapBounds == null)
                {
                    minimapBounds = new Rectangle(571, 11, 144, 144);
                }

                return new Object[]{ playerPos, cameraYaw, minimapBounds };
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Position lookup failed: " + t.getMessage());
        }

        if (mapData == null)
        {
            return ActionResult.failure(ActionType.MINIMAP_WALK, "Could not get player position");
        }

        WorldPoint playerPos = (WorldPoint) mapData[0];
        int cameraYaw = (int) mapData[1];
        Rectangle minimapBounds = (Rectangle) mapData[2];

        // Calculate tile offset from player to target
        int dx = targetX - playerPos.getX();
        int dy = targetY - playerPos.getY();

        // Convert camera yaw (0-2048) to radians
        // OSRS yaw: 0 = north, 512 = west, 1024 = south, 1536 = east
        // We need to rotate tile offsets by this angle
        double yawRadians = (cameraYaw / 2048.0) * (2 * Math.PI);

        // Rotate the offset by camera angle
        // Minimap: positive X = right, positive Y = up (but screen Y is inverted)
        double rotatedX = dx * Math.cos(yawRadians) + dy * Math.sin(yawRadians);
        double rotatedY = dx * Math.sin(yawRadians) - dy * Math.cos(yawRadians);

        // Scale to minimap pixels
        double pixelX = rotatedX * PIXELS_PER_TILE;
        double pixelY = rotatedY * PIXELS_PER_TILE;

        // Clamp to minimap circle radius
        double dist = Math.sqrt(pixelX * pixelX + pixelY * pixelY);
        if (dist > MINIMAP_RADIUS)
        {
            double scale = (MINIMAP_RADIUS - 2) / dist; // -2 for safety margin
            pixelX *= scale;
            pixelY *= scale;
        }

        // Convert to screen coordinates (minimap center + offset)
        int minimapCenterX = (int) minimapBounds.getCenterX();
        int minimapCenterY = (int) minimapBounds.getCenterY();

        int clickX = minimapCenterX + (int) pixelX;
        int clickY = minimapCenterY + (int) pixelY;

        // Phase 2: Click on the minimap with humanized mouse movement
        human.moveAndClick(clickX, clickY);
        human.shortPause();

        return ActionResult.success(ActionType.MINIMAP_WALK);
    }
}
