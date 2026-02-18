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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
    private static final int STUCK_DETECT_DIST = 3;

    /** Actions that indicate a closed/locked door or gate we can open */
    private static final Set<String> DOOR_OPEN_ACTIONS = new HashSet<>(Arrays.asList(
        "open", "go-through", "walk-through", "push-open"
    ));

    /** Track last position to detect stuck state across calls */
    private static WorldPoint lastPosition = null;
    private static int stuckCounter = 0;

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

        // Get player position, world type, and agility level on client thread
        Object[] clientData;
        try
        {
            clientData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                WorldPoint pos = local != null ? local.getWorldLocation() : null;
                EnumSet<WorldType> worldType = client.getWorldType();
                boolean members = worldType != null && worldType.contains(WorldType.MEMBERS);
                int agility = client.getRealSkillLevel(Skill.AGILITY);
                return new Object[]{ pos, members, agility };
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Failed to get player position");
        }

        WorldPoint playerPos = (WorldPoint) clientData[0];
        boolean membersWorld = (boolean) clientData[1];
        int agilityLevel = (int) clientData[2];

        if (playerPos == null)
        {
            return ActionResult.failure(ActionType.PATH_TO, "Player not found");
        }

        WorldPoint target = new WorldPoint(targetX, targetY, playerPos.getPlane());

        // Check if already at destination
        if (playerPos.distanceTo(target) <= ARRIVAL_DISTANCE)
        {
            pathfinderService.invalidate();
            lastPosition = null;
            stuckCounter = 0;
            return ActionResult.success(ActionType.PATH_TO);
        }

        // Detect stuck: player hasn't moved since last PATH_TO call
        if (lastPosition != null && playerPos.distanceTo(lastPosition) <= STUCK_DETECT_DIST)
        {
            stuckCounter++;
            if (stuckCounter >= 2)
            {
                // Player is stuck — block tiles ahead on the path and force recompute
                System.out.println("[ClaudeBot] PATH_TO: stuck at " + playerPos
                    + " for " + stuckCounter + " calls, blocking area ahead");

                // Block tiles in the direction of travel
                int dirX = Integer.signum(target.getX() - playerPos.getX());
                int dirY = Integer.signum(target.getY() - playerPos.getY());
                WorldPoint blockCenter = new WorldPoint(
                    playerPos.getX() + dirX * 3,
                    playerPos.getY() + dirY * 3,
                    playerPos.getPlane());
                pathfinderService.blockArea(blockCenter, 2);
                pathfinderService.invalidate();
                stuckCounter = 0; // Reset after blocking
            }
        }
        else
        {
            stuckCounter = 0;
        }
        lastPosition = playerPos;

        // Get or compute path (restricted to F2P areas + filtered by agility level)
        List<WorldPoint> path = pathfinderService.getPath(playerPos, target, membersWorld, agilityLevel);
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

        // Check for blocking doors/gates near the player and open them
        ActionResult doorResult = handleBlockingDoor(client, human, objectUtils,
            clientThread, playerPos, target, remaining);
        if (doorResult != null)
        {
            pathfinderService.invalidate();
            return doorResult;
        }

        // Scan ahead for the next transport step
        int transportIdx = findNextTransport(path, currentIdx, pathfinderService, membersWorld);

        if (transportIdx >= 0 && transportIdx - currentIdx <= MAX_WAYPOINT_DIST)
        {
            // There's a transport within walking range — handle it
            WorldPoint transportStart = path.get(transportIdx);
            WorldPoint transportEnd = path.get(transportIdx + 1);
            Transport transport = pathfinderService.getTransportBetween(transportStart, transportEnd, membersWorld);

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
                                          PathfinderService pathfinderService,
                                          boolean membersWorld)
    {
        for (int i = currentIdx; i < path.size() - 1; i++)
        {
            Transport t = pathfinderService.getTransportBetween(path.get(i), path.get(i + 1), membersWorld);
            if (t != null)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks for a closed door/gate within 1 tile of the player in the direction of travel.
     * If found, opens it and walks through. Returns ActionResult if handled, null if no door.
     *
     * This handles doors NOT in transports.txt — the collision map has them blocked,
     * so BFS routes around them. We detect them at runtime, open them, then walk through
     * manually so the game's live pathfinding takes advantage of the open door.
     */
    private static ActionResult handleBlockingDoor(Client client, HumanSimulator human,
                                                    ObjectUtils objectUtils,
                                                    ClientThread clientThread,
                                                    WorldPoint playerPos, WorldPoint target,
                                                    int remaining)
    {
        // Direction from player to target
        int dirX = Integer.signum(target.getX() - playerPos.getX());
        int dirY = Integer.signum(target.getY() - playerPos.getY());

        // Only check if we have a clear direction to travel
        if (dirX == 0 && dirY == 0) return null;

        // Phase 1: Find a nearby closed door on client thread
        Object[] doorData;
        try
        {
            final int fdirX = dirX;
            final int fdirY = dirY;
            doorData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                if (local == null) return null;

                Scene scene = client.getScene();
                Tile[][][] tiles = scene.getTiles();
                int plane = client.getPlane();
                int psx = local.getLocalLocation().getSceneX();
                int psy = local.getLocalLocation().getSceneY();

                // Scan tiles within 1 tile of player
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        if (dx == 0 && dy == 0) continue;

                        // Only check tiles in the general direction of travel
                        // (don't open doors behind us)
                        if (fdirX != 0 && Integer.signum(dx) == -fdirX) continue;
                        if (fdirY != 0 && Integer.signum(dy) == -fdirY) continue;

                        int sx = psx + dx;
                        int sy = psy + dy;
                        if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE)
                            continue;

                        Tile tile = tiles[plane][sx][sy];
                        if (tile == null) continue;

                        // Check wall objects (most doors/gates)
                        WallObject wall = tile.getWallObject();
                        if (wall != null)
                        {
                            Object[] data = checkDoorObject(client, wall, dx, dy);
                            if (data != null) return data;
                        }

                        // Check game objects (some gates, barriers)
                        for (GameObject obj : tile.getGameObjects())
                        {
                            if (obj == null) continue;
                            Object[] data = checkDoorObject(client, obj, dx, dy);
                            if (data != null) return data;
                        }
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            return null; // Silently fail — don't block normal pathfinding
        }

        if (doorData == null) return null;

        java.awt.Point screenPoint = (java.awt.Point) doorData[0];
        String action = (String) doorData[1];
        String name = (String) doorData[2];
        int doorDx = (int) doorData[3];
        int doorDy = (int) doorData[4];

        System.out.println("[ClaudeBot] PATH_TO: detected blocking " + name + " — " + action);

        // Phase 2: Move mouse to door
        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

        // Phase 3: Fire the Open action via menu entries
        final String fAction = action;
        final String fName = name;
        clientThread.invokeLater(() -> {
            try
            {
                net.runelite.api.MenuEntry[] entries = client.getMenuEntries();
                net.runelite.api.MenuEntry match = null;
                if (entries != null)
                {
                    for (net.runelite.api.MenuEntry entry : entries)
                    {
                        String entryOption = entry.getOption();
                        String entryTarget = entry.getTarget();
                        if (entryOption != null
                            && DOOR_OPEN_ACTIONS.contains(entryOption.toLowerCase())
                            && entryTarget != null
                            && entryTarget.toLowerCase().contains(fName.toLowerCase()))
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
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] PATH_TO door open failed: " + t.getMessage());
            }
        });

        // Wait for door to open (one game tick)
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Walk through the door — click 2 tiles past it on the minimap
        int throughX = playerPos.getX() + doorDx * 2;
        int throughY = playerPos.getY() + doorDy * 2;
        walkTowardMinimap(client, human, clientThread, playerPos,
            new WorldPoint(throughX, throughY, playerPos.getPlane()), remaining);

        System.out.println("[ClaudeBot] PATH_TO: opened " + name + " and walking through, "
            + remaining + " tiles remaining");
        return ActionResult.success(ActionType.PATH_TO);
    }

    /**
     * Checks if a tile object is a door/gate with an "Open" action.
     * Returns [screenPoint, action, name, dx, dy] if it is, null otherwise.
     */
    private static Object[] checkDoorObject(Client client, TileObject obj, int dx, int dy)
    {
        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return null;

        if (comp.getImpostorIds() != null)
        {
            ObjectComposition impostor = comp.getImpostor();
            if (impostor != null) comp = impostor;
        }

        String name = comp.getName();
        if (name == null || name.isEmpty() || name.equals("null")) return null;

        // Check if any action is a door-open action
        String[] actions = comp.getActions();
        if (actions == null) return null;

        String openAction = null;
        for (String a : actions)
        {
            if (a != null && DOOR_OPEN_ACTIONS.contains(a.toLowerCase()))
            {
                openAction = a;
                break;
            }
        }

        if (openAction == null) return null;

        // Get screen position
        java.awt.Point screenPoint = null;
        if (obj.getClickbox() != null)
        {
            java.awt.Rectangle bounds = obj.getClickbox().getBounds();
            screenPoint = new java.awt.Point(
                (int) bounds.getCenterX(), (int) bounds.getCenterY());
        }

        return new Object[]{ screenPoint, openAction, name, dx, dy };
    }
}
