package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.pathfinder.PathfinderService;
import com.osrsbot.claude.pathfinder.Transport;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ObjectUtils;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

import java.util.List;
import java.util.Random;

/**
 * Walks one segment of a computed path per invocation.
 * Claude re-issues PATH_TO each tick cycle until arrival.
 *
 * Each call either:
 * 1. Clicks the minimap to walk toward the next waypoint (~12-18 tiles ahead), or
 * 2. Interacts with a transport object (door, staircase, ladder) along the path
 *
 * The path is cached in PathfinderService and recomputed only when the target changes
 * or the player strays too far from it.
 */
public class PathToAction
{
    private static final Random RANDOM = new Random();
    private static final int MIN_WAYPOINT_DIST = 12;
    private static final int MAX_WAYPOINT_DIST = 18;
    private static final int MINIMAP_TILE_RANGE = 17;
    private static final int ARRIVAL_DISTANCE = 2;

    public static ActionResult execute(Client client, HumanSimulator human,
                                       PathfinderService pathfinderService,
                                       ObjectUtils objectUtils,
                                       ClientThread clientThread, BotAction action)
    {
        if (!pathfinderService.isLoaded())
        {
            return ActionResult.failure(ActionType.PATH_TO, "Pathfinder not loaded yet");
        }

        int targetX = action.getX();
        int targetY = action.getY();
        if (targetX <= 0 || targetY <= 0)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Invalid target coordinates");
        }

        // Get player position on client thread
        WorldPoint playerPos;
        try
        {
            playerPos = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                return local != null ? local.getWorldLocation() : null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Failed to get player position");
        }

        if (playerPos == null)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Player not found");
        }

        WorldPoint target = new WorldPoint(targetX, targetY, playerPos.getPlane());

        // Check if already at destination
        if (playerPos.distanceTo(target) <= ARRIVAL_DISTANCE)
        {
            pathfinderService.invalidate();
            return ActionResult.success(ActionType.PATH_TO);
        }

        // Get or compute path
        List<WorldPoint> path = pathfinderService.getPath(playerPos, target);
        if (path == null || path.isEmpty())
        {
            return ActionResult.failure(ActionType.PATH_TO,
                "No path found to (" + targetX + "," + targetY + ")");
        }

        // Find where we are on the path
        int currentIdx = findNearestPathIndex(path, playerPos);
        if (currentIdx < 0)
        {
            pathfinderService.invalidate();
            return ActionResult.failure(ActionType.PATH_TO, "Off path, recomputing");
        }

        int remaining = path.size() - currentIdx - 1;

        // Scan ahead for the next transport step
        int transportIdx = findNextTransport(path, currentIdx, pathfinderService);

        if (transportIdx >= 0 && transportIdx - currentIdx <= MAX_WAYPOINT_DIST)
        {
            // There's a transport within walking range — handle it
            WorldPoint transportStart = path.get(transportIdx);
            WorldPoint transportEnd = path.get(transportIdx + 1);
            Transport transport = pathfinderService.getTransportBetween(transportStart, transportEnd);

            if (transport != null)
            {
                // If we're not at the transport start yet, walk there first
                int distToTransport = playerPos.distanceTo(transportStart);
                if (distToTransport > ARRIVAL_DISTANCE)
                {
                    return walkTowardMinimap(client, human, clientThread, playerPos, transportStart, remaining);
                }

                // We're at the transport — interact with the object
                return executeTransport(client, human, objectUtils, clientThread, transport, remaining);
            }
        }

        // No transport nearby — walk toward a waypoint ahead on the path
        int waypointDist = MIN_WAYPOINT_DIST + RANDOM.nextInt(MAX_WAYPOINT_DIST - MIN_WAYPOINT_DIST + 1);
        int waypointIdx = Math.min(currentIdx + waypointDist, path.size() - 1);

        // Don't walk past a transport
        if (transportIdx >= 0 && waypointIdx > transportIdx)
        {
            waypointIdx = transportIdx;
        }

        WorldPoint waypoint = path.get(waypointIdx);
        return walkTowardMinimap(client, human, clientThread, playerPos, waypoint, remaining);
    }

    /**
     * Click on the minimap to walk toward a waypoint.
     */
    private static ActionResult walkTowardMinimap(Client client, HumanSimulator human,
                                                   ClientThread clientThread,
                                                   WorldPoint playerPos, WorldPoint waypoint,
                                                   int remaining)
    {
        // Clamp to minimap range if needed
        int dx = waypoint.getX() - playerPos.getX();
        int dy = waypoint.getY() - playerPos.getY();
        double dist = Math.sqrt(dx * dx + dy * dy);

        int walkX = waypoint.getX();
        int walkY = waypoint.getY();
        if (dist > MINIMAP_TILE_RANGE)
        {
            double scale = MINIMAP_TILE_RANGE / dist;
            walkX = playerPos.getX() + (int) (dx * scale);
            walkY = playerPos.getY() + (int) (dy * scale);
        }

        final int finalWalkX = walkX;
        final int finalWalkY = walkY;

        // Convert to minimap screen coordinates on client thread
        java.awt.Point screenPoint;
        try
        {
            screenPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                net.runelite.api.coords.LocalPoint localPoint =
                    LocalPoint.fromWorld(client, finalWalkX, finalWalkY);
                if (localPoint == null) return null;

                net.runelite.api.Point minimapPoint =
                    Perspective.localToMinimap(client, localPoint);
                if (minimapPoint == null) return null;

                return new java.awt.Point(minimapPoint.getX(), minimapPoint.getY());
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Minimap lookup failed: " + t.getMessage());
        }

        if (screenPoint == null)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Could not calculate minimap position");
        }

        human.moveAndClick(screenPoint.x, screenPoint.y);
        human.shortPause();

        System.out.println("[ClaudeBot] PATH_TO: walking, " + remaining + " tiles remaining");
        return ActionResult.success(ActionType.PATH_TO);
    }

    /**
     * Interact with a transport object (door, staircase, ladder, etc.)
     */
    private static ActionResult executeTransport(Client client, HumanSimulator human,
                                                  ObjectUtils objectUtils,
                                                  ClientThread clientThread,
                                                  Transport transport, int remaining)
    {
        System.out.println("[ClaudeBot] PATH_TO: executing transport — "
            + transport.action + " " + transport.target + " (id:" + transport.objectId + ")");

        // Phase 1: Find the object and get screen position
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                TileObject obj = objectUtils.findNearest(client, transport.target, transport.action);
                if (obj == null)
                {
                    // Try by name only (some objects have different action names)
                    obj = objectUtils.findNearest(client, transport.target);
                }
                if (obj == null) return null;

                int actionIndex = objectUtils.getActionIndex(client, obj, transport.action);
                MenuAction menuAction = objectUtils.getMenuAction(actionIndex);
                int sceneX = obj.getLocalLocation().getSceneX();
                int sceneY = obj.getLocalLocation().getSceneY();
                int objId = obj.getId();

                java.awt.Point screenPoint = null;
                if (obj.getClickbox() != null)
                {
                    java.awt.Rectangle bounds = obj.getClickbox().getBounds();
                    screenPoint = new java.awt.Point(
                        (int) bounds.getCenterX(), (int) bounds.getCenterY());
                }

                return new Object[]{ sceneX, sceneY, objId, menuAction, screenPoint, transport.action, transport.target };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] PATH_TO transport lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.PATH_TO,
                "Transport lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            // Object not found — might already be open (door), try walking through
            System.out.println("[ClaudeBot] PATH_TO: transport object not found, may already be open");
            System.out.println("[ClaudeBot] PATH_TO: transport object not found (may already be open)");
            return ActionResult.success(ActionType.PATH_TO);
        }

        int sceneX = (int) lookupData[0];
        int sceneY = (int) lookupData[1];
        int objId = (int) lookupData[2];
        MenuAction menuAction = (MenuAction) lookupData[3];
        java.awt.Point screenPoint = (java.awt.Point) lookupData[4];
        String action = (String) lookupData[5];
        String target = (String) lookupData[6];

        // Phase 2: Move mouse to object
        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

        // Phase 3: Interact with the object via menu entries (same pattern as InteractObjectAction)
        final int fSceneX = sceneX;
        final int fSceneY = sceneY;
        final int fObjId = objId;
        final MenuAction fMenuAction = menuAction;

        clientThread.invokeLater(() -> {
            try
            {
                // Try to use real menu entries for correct object ID
                net.runelite.api.MenuEntry[] entries = client.getMenuEntries();
                net.runelite.api.MenuEntry match = null;
                if (entries != null)
                {
                    for (net.runelite.api.MenuEntry entry : entries)
                    {
                        String entryOption = entry.getOption();
                        String entryTarget = entry.getTarget();
                        if (entryOption != null && entryOption.equalsIgnoreCase(action)
                            && entryTarget != null && entryTarget.toLowerCase().contains(target.toLowerCase()))
                        {
                            match = entry;
                            break;
                        }
                    }
                }

                if (match != null)
                {
                    client.menuAction(match.getParam0(), match.getParam1(),
                        match.getType(), match.getIdentifier(), -1,
                        match.getOption(), match.getTarget());
                }
                else
                {
                    client.menuAction(fSceneX, fSceneY, fMenuAction, fObjId, -1, action, target);
                }
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] PATH_TO transport interaction failed: " + t.getMessage());
            }
        });

        human.shortPause();
        System.out.println("[ClaudeBot] PATH_TO: used transport " + action + " " + target + ", " + remaining + " tiles remaining");
        return ActionResult.success(ActionType.PATH_TO);
    }

    /**
     * Find the index of the path point nearest to the player's current position.
     */
    private static int findNearestPathIndex(List<WorldPoint> path, WorldPoint pos)
    {
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < path.size(); i++)
        {
            int dist = pos.distanceTo(path.get(i));
            if (dist < bestDist)
            {
                bestDist = dist;
                bestIdx = i;
            }
        }

        // If we're more than 15 tiles from the nearest path point, consider off-path
        if (bestDist > 15)
        {
            return -1;
        }

        return bestIdx;
    }

    /**
     * Scan ahead from currentIdx to find the next transport step in the path.
     * A transport step is where path[i] → path[i+1] has a matching transport entry.
     */
    private static int findNextTransport(List<WorldPoint> path, int currentIdx,
                                          PathfinderService pathfinderService)
    {
        for (int i = currentIdx; i < path.size() - 1; i++)
        {
            Transport t = pathfinderService.getTransportBetween(path.get(i), path.get(i + 1));
            if (t != null)
            {
                return i;
            }
        }
        return -1;
    }
}
