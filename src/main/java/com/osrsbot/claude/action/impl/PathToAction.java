package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.human.TimingEngine;
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
 * Autonomous pathfinding action. Walks the entire computed path in a loop,
 * handling minimap clicks, transports (doors/stairs/ladders), runtime door
 * detection, and stuck recovery. Returns only when the destination is reached,
 * the path is impossible, or a timeout is hit.
 *
 * This avoids wasting API calls having Claude re-issue PATH_TO every tick cycle.
 */
public class PathToAction
{
    private static final Random RANDOM = new Random();
    private static final int MIN_WAYPOINT_DIST = 12;
    private static final int MAX_WAYPOINT_DIST = 18;
    private static final int MINIMAP_TILE_RANGE = 17;
    private static final int ARRIVAL_DISTANCE = 2;
    private static final int STUCK_THRESHOLD = 3;
    private static final long MAX_DURATION_MS = 120_000; // 2 minutes

    /** Actions that indicate a closed/locked door or gate we can open */
    private static final Set<String> DOOR_OPEN_ACTIONS = new HashSet<>(Arrays.asList(
        "open", "go-through", "walk-through", "push-open"
    ));

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

        // Get initial player data on client thread
        Object[] clientData = getClientData(client, clientThread);
        if (clientData == null)
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

        // Already there?
        if (playerPos.distanceTo(target) <= ARRIVAL_DISTANCE)
        {
            return ActionResult.success(ActionType.PATH_TO,
                "Already at (" + targetX + "," + targetY + ")");
        }

        int startDistance = playerPos.distanceTo(target);
        System.out.println("[ClaudeBot] PATH_TO: starting autonomous walk from " + playerPos
            + " to " + target + " (" + startDistance + " tiles)");

        long startTime = System.currentTimeMillis();
        TimingEngine timing = human.getTimingEngine();
        int stuckCount = 0;
        WorldPoint lastLoopPos = null;

        while (System.currentTimeMillis() - startTime < MAX_DURATION_MS)
        {
            // --- Get current position ---
            playerPos = getPlayerPosition(client, clientThread);
            if (playerPos == null)
            {
                return ActionResult.failure(ActionType.PATH_TO, "Lost player position mid-walk");
            }

            // Update target plane in case we changed floors via transport
            target = new WorldPoint(targetX, targetY, playerPos.getPlane());

            // --- Check arrival ---
            if (playerPos.distanceTo(target) <= ARRIVAL_DISTANCE)
            {
                pathfinderService.invalidate();
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[ClaudeBot] PATH_TO: arrived at " + target
                    + " in " + elapsed + "s");
                return ActionResult.success(ActionType.PATH_TO,
                    "Arrived at (" + targetX + "," + targetY + ") in " + elapsed + "s");
            }

            // --- Stuck detection ---
            if (lastLoopPos != null && playerPos.distanceTo(lastLoopPos) <= 1)
            {
                stuckCount++;
                if (stuckCount >= STUCK_THRESHOLD)
                {
                    System.out.println("[ClaudeBot] PATH_TO: stuck at " + playerPos
                        + " for " + stuckCount + " iterations, blocking area ahead");

                    int dirX = Integer.signum(target.getX() - playerPos.getX());
                    int dirY = Integer.signum(target.getY() - playerPos.getY());
                    WorldPoint blockCenter = new WorldPoint(
                        playerPos.getX() + dirX * 3,
                        playerPos.getY() + dirY * 3,
                        playerPos.getPlane());
                    pathfinderService.blockArea(blockCenter, 2);
                    pathfinderService.invalidate();
                    stuckCount = 0;
                    // Continue loop to recompute path around blocked area
                }
            }
            else
            {
                stuckCount = 0;
            }
            lastLoopPos = playerPos;

            // --- Get/compute path ---
            List<WorldPoint> path = pathfinderService.getPath(
                playerPos, target, membersWorld, agilityLevel);
            if (path == null || path.isEmpty())
            {
                int remaining = playerPos.distanceTo(target);
                return ActionResult.failure(ActionType.PATH_TO,
                    "No path found to (" + targetX + "," + targetY + "), "
                    + remaining + " tiles away");
            }

            int currentIdx = findNearestPathIndex(path, playerPos);
            if (currentIdx < 0)
            {
                // Off the path — invalidate and retry
                pathfinderService.invalidate();
                timing.sleep(600);
                continue;
            }

            int remaining = path.size() - currentIdx - 1;

            // --- Check for blocking doors ---
            WorldPoint nextOnPath = path.get(Math.min(currentIdx + 3, path.size() - 1));
            if (tryOpenBlockingDoor(client, human, objectUtils, clientThread, playerPos, nextOnPath))
            {
                pathfinderService.invalidate();
                waitForPlayerToStop(client, clientThread, timing, 5000);
                continue;
            }

            // --- Check for transport ---
            int transportIdx = findNextTransport(path, currentIdx, pathfinderService, membersWorld);

            if (transportIdx >= 0 && transportIdx - currentIdx <= MAX_WAYPOINT_DIST)
            {
                WorldPoint transportStart = path.get(transportIdx);
                WorldPoint transportEnd = path.get(transportIdx + 1);
                Transport transport = pathfinderService.getTransportBetween(
                    transportStart, transportEnd, membersWorld);

                if (transport != null)
                {
                    // Walk to transport start if not there yet
                    int distToTransport = playerPos.distanceTo(transportStart);
                    if (distToTransport > ARRIVAL_DISTANCE)
                    {
                        if (!doMinimapClick(client, human, clientThread, playerPos, transportStart))
                        {
                            timing.sleep(600);
                            continue;
                        }
                        waitForPlayerToStop(client, clientThread, timing, 8000);
                        continue;
                    }

                    // At the transport — interact with it
                    System.out.println("[ClaudeBot] PATH_TO: using transport "
                        + transport.action + " " + transport.target
                        + " (remaining: " + remaining + " tiles)");
                    doTransportInteract(client, human, objectUtils, clientThread, transport);
                    waitForPlayerToStop(client, clientThread, timing, 5000);
                    continue;
                }
            }

            // --- Normal walk: click minimap waypoint ---
            int waypointDist = MIN_WAYPOINT_DIST
                + RANDOM.nextInt(MAX_WAYPOINT_DIST - MIN_WAYPOINT_DIST + 1);
            int waypointIdx = Math.min(currentIdx + waypointDist, path.size() - 1);

            // Don't walk past a transport
            if (transportIdx >= 0 && waypointIdx > transportIdx)
            {
                waypointIdx = transportIdx;
            }

            WorldPoint waypoint = path.get(waypointIdx);
            System.out.println("[ClaudeBot] PATH_TO: walking toward " + waypoint
                + " (" + remaining + " tiles remaining)");

            if (!doMinimapClick(client, human, clientThread, playerPos, waypoint))
            {
                timing.sleep(600);
                continue;
            }

            waitForPlayerToStop(client, clientThread, timing, 8000);
        }

        // Timed out
        int remaining = playerPos != null ? playerPos.distanceTo(target) : -1;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("[ClaudeBot] PATH_TO: timed out after " + elapsed + "s, "
            + remaining + " tiles remaining");
        return ActionResult.failure(ActionType.PATH_TO,
            "Timed out after " + elapsed + "s, " + remaining + " tiles remaining");
    }

    // ───────────────────── Helpers ─────────────────────

    /**
     * Gets player position, world type, and agility level on the client thread.
     */
    private static Object[] getClientData(Client client, ClientThread clientThread)
    {
        try
        {
            return ClientThreadRunner.runOnClientThread(clientThread, () -> {
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
            System.err.println("[ClaudeBot] PATH_TO: getClientData failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Gets player world position on client thread.
     */
    private static WorldPoint getPlayerPosition(Client client, ClientThread clientThread)
    {
        try
        {
            return ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                return local != null ? local.getWorldLocation() : null;
            });
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * Clicks on the minimap to walk toward a waypoint. Returns true if the click succeeded.
     */
    private static boolean doMinimapClick(Client client, HumanSimulator human,
                                           ClientThread clientThread,
                                           WorldPoint playerPos, WorldPoint waypoint)
    {
        // Clamp to minimap range
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
                LocalPoint localPoint = LocalPoint.fromWorld(client, finalWalkX, finalWalkY);
                if (localPoint == null) return null;

                net.runelite.api.Point minimapPoint =
                    Perspective.localToMinimap(client, localPoint);
                if (minimapPoint == null) return null;

                return new java.awt.Point(minimapPoint.getX(), minimapPoint.getY());
            });
        }
        catch (Throwable t)
        {
            return false;
        }

        if (screenPoint == null)
        {
            return false;
        }

        human.moveAndClick(screenPoint.x, screenPoint.y);
        human.shortPause();
        return true;
    }

    /**
     * Interacts with a transport object (door, staircase, ladder, etc.)
     */
    private static void doTransportInteract(Client client, HumanSimulator human,
                                             ObjectUtils objectUtils,
                                             ClientThread clientThread,
                                             Transport transport)
    {
        // Phase 1: Find the object and get screen position
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                TileObject obj = objectUtils.findNearest(client, transport.target, transport.action);
                if (obj == null)
                {
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

                return new Object[]{ sceneX, sceneY, objId, menuAction, screenPoint,
                    transport.action, transport.target };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] PATH_TO transport lookup failed: " + t.getMessage());
            return;
        }

        if (lookupData == null)
        {
            System.out.println("[ClaudeBot] PATH_TO: transport object not found (may already be open)");
            return;
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

        // Phase 3: Interact via menu entries
        final int fSceneX = sceneX;
        final int fSceneY = sceneY;
        final int fObjId = objId;
        final MenuAction fMenuAction = menuAction;

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
                        if (entryOption != null && entryOption.equalsIgnoreCase(action)
                            && entryTarget != null
                            && entryTarget.toLowerCase().contains(target.toLowerCase()))
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
    }

    /**
     * Checks for a closed door/gate within 1 tile of the player in the given direction.
     * If found, opens it and walks through. Returns true if a door was handled.
     */
    private static boolean tryOpenBlockingDoor(Client client, HumanSimulator human,
                                                ObjectUtils objectUtils,
                                                ClientThread clientThread,
                                                WorldPoint playerPos, WorldPoint toward)
    {
        int dirX = Integer.signum(toward.getX() - playerPos.getX());
        int dirY = Integer.signum(toward.getY() - playerPos.getY());
        if (dirX == 0 && dirY == 0) return false;

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

                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        if (dx == 0 && dy == 0) continue;
                        if (fdirX != 0 && Integer.signum(dx) == -fdirX) continue;
                        if (fdirY != 0 && Integer.signum(dy) == -fdirY) continue;

                        int sx = psx + dx;
                        int sy = psy + dy;
                        if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE)
                            continue;

                        Tile tile = tiles[plane][sx][sy];
                        if (tile == null) continue;

                        WallObject wall = tile.getWallObject();
                        if (wall != null)
                        {
                            Object[] data = checkDoorObject(client, wall, dx, dy);
                            if (data != null) return data;
                        }

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
            return false;
        }

        if (doorData == null) return false;

        java.awt.Point screenPoint = (java.awt.Point) doorData[0];
        String action = (String) doorData[1];
        String name = (String) doorData[2];
        int doorDx = (int) doorData[3];
        int doorDy = (int) doorData[4];

        System.out.println("[ClaudeBot] PATH_TO: opening blocking " + name + " (" + action + ")");

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

        // Wait for door to open, then walk through
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        int throughX = playerPos.getX() + doorDx * 2;
        int throughY = playerPos.getY() + doorDy * 2;
        doMinimapClick(client, human, clientThread, playerPos,
            new WorldPoint(throughX, throughY, playerPos.getPlane()));

        return true;
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

        java.awt.Point screenPoint = null;
        if (obj.getClickbox() != null)
        {
            java.awt.Rectangle bounds = obj.getClickbox().getBounds();
            screenPoint = new java.awt.Point(
                (int) bounds.getCenterX(), (int) bounds.getCenterY());
        }

        return new Object[]{ screenPoint, openAction, name, dx, dy };
    }

    /**
     * Waits for the player to stop moving by polling position every 600ms.
     * Returns the final position, or null if position couldn't be read.
     */
    private static WorldPoint waitForPlayerToStop(Client client, ClientThread clientThread,
                                                   TimingEngine timing, int maxWaitMs)
    {
        long deadline = System.currentTimeMillis() + maxWaitMs;

        // Initial delay to let movement begin
        timing.sleep(1200);

        WorldPoint lastPos = null;
        int sameCount = 0;

        while (System.currentTimeMillis() < deadline)
        {
            WorldPoint currentPos = getPlayerPosition(client, clientThread);
            if (currentPos == null) return lastPos;

            if (lastPos != null && currentPos.equals(lastPos))
            {
                sameCount++;
                if (sameCount >= 2)
                {
                    // Player has been on the same tile for 2 consecutive polls = stopped
                    return currentPos;
                }
            }
            else
            {
                sameCount = 0;
            }

            lastPos = currentPos;
            timing.sleep(600);
        }

        return lastPos;
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

        // If more than 15 tiles from the nearest path point, consider off-path
        if (bestDist > 15)
        {
            return -1;
        }

        return bestIdx;
    }

    /**
     * Scan ahead from currentIdx to find the next transport step in the path.
     */
    private static int findNextTransport(List<WorldPoint> path, int currentIdx,
                                          PathfinderService pathfinderService,
                                          boolean membersWorld)
    {
        for (int i = currentIdx; i < path.size() - 1; i++)
        {
            Transport t = pathfinderService.getTransportBetween(
                path.get(i), path.get(i + 1), membersWorld);
            if (t != null)
            {
                return i;
            }
        }
        return -1;
    }
}
