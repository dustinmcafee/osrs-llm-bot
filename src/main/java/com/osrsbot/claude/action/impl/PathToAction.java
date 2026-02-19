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
 * Chunked pathfinding action. Each call walks a few steps toward the destination,
 * then returns control to Claude with a progress report. Claude sees the full game
 * state and decides: re-issue PATH_TO to continue, eat food, fight, or anything else.
 *
 * Path is cached in PathfinderService, so re-issuing PATH_TO to the same destination
 * is essentially free (no BFS recomputation).
 *
 * Returns immediately on: arrival, combat, no path, or after MAX_STEPS clicks.
 */
public class PathToAction
{
    private static final Random RANDOM = new Random();
    private static final int MIN_WAYPOINT_DIST = 7;
    private static final int MAX_WAYPOINT_DIST = 14;
    private static final int MINIMAP_TILE_RANGE = 17;
    private static final int ARRIVAL_DISTANCE = 2;
    private static final int CANVAS_WALK_MAX_DIST = 8;
    private static final double CANVAS_WALK_CHANCE = 0.35;

    /** Number of walk clicks per call before returning to Claude */
    private static final int MAX_STEPS = 3;
    /** Safety timeout per call (catches stuck waits) */
    private static final long CALL_TIMEOUT_MS = 45_000;

    /** Stuck detection: graduated recovery */
    private static final int STUCK_RECLICK = 4;
    private static final int STUCK_REROUTE = 8;

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
                "Arrived at (" + targetX + "," + targetY + ")");
        }

        System.out.println("[ClaudeBot] PATH_TO: walking from " + playerPos
            + " to " + target + " (" + playerPos.distanceTo(target) + " tiles)");

        long callStart = System.currentTimeMillis();
        TimingEngine timing = human.getTimingEngine();
        int steps = 0;
        int stuckCount = 0;
        WorldPoint lastLoopPos = null;

        while (steps < MAX_STEPS && System.currentTimeMillis() - callStart < CALL_TIMEOUT_MS)
        {
            // --- Get current position ---
            playerPos = getPlayerPosition(client, clientThread);
            if (playerPos == null)
            {
                return ActionResult.failure(ActionType.PATH_TO, "Lost player position mid-walk");
            }

            target = new WorldPoint(targetX, targetY, playerPos.getPlane());

            // --- Check for combat interruption ---
            String attacker = checkForAttacker(client, clientThread);
            if (attacker != null)
            {
                int distRemaining = playerPos.distanceTo(target);
                System.out.println("[ClaudeBot] PATH_TO: under attack by "
                    + attacker + " at " + playerPos);
                return ActionResult.failure(ActionType.PATH_TO,
                    "Under attack by " + attacker + " at " + playerPos
                    + ", " + distRemaining + " tiles from destination (" + targetX + "," + targetY + ")");
            }

            // --- Check arrival ---
            if (playerPos.distanceTo(target) <= ARRIVAL_DISTANCE)
            {
                pathfinderService.invalidate();
                System.out.println("[ClaudeBot] PATH_TO: arrived at " + target);
                return ActionResult.success(ActionType.PATH_TO,
                    "Arrived at (" + targetX + "," + targetY + ")");
            }

            // --- Stuck detection (graduated) ---
            if (lastLoopPos != null && playerPos.distanceTo(lastLoopPos) <= 1)
            {
                stuckCount++;
                if (stuckCount >= STUCK_REROUTE)
                {
                    System.out.println("[ClaudeBot] PATH_TO: stuck " + stuckCount
                        + " iterations, blocking and rerouting");
                    int dirX = Integer.signum(target.getX() - playerPos.getX());
                    int dirY = Integer.signum(target.getY() - playerPos.getY());
                    WorldPoint blockCenter = new WorldPoint(
                        playerPos.getX() + dirX,
                        playerPos.getY() + dirY,
                        playerPos.getPlane());
                    pathfinderService.blockArea(blockCenter, 1);
                    pathfinderService.invalidate();
                    stuckCount = 0;
                }
                else if (stuckCount >= STUCK_RECLICK)
                {
                    System.out.println("[ClaudeBot] PATH_TO: stuck " + stuckCount
                        + " iterations, re-clicking");
                    pathfinderService.invalidate();
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
                steps++;
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
                    int distToTransport = playerPos.distanceTo(transportStart);
                    if (distToTransport > ARRIVAL_DISTANCE)
                    {
                        if (!doMinimapClick(client, human, clientThread, playerPos, transportStart))
                        {
                            timing.sleep(600);
                            continue;
                        }
                        waitForPlayerToStop(client, clientThread, timing, 8000);
                        steps++;
                        continue;
                    }

                    System.out.println("[ClaudeBot] PATH_TO: using transport "
                        + transport.action + " " + transport.target
                        + " (" + remaining + " tiles remaining)");
                    doTransportInteract(client, human, objectUtils, clientThread, transport);
                    waitForPlayerToStop(client, clientThread, timing, 5000);
                    steps++;
                    continue;
                }
            }

            // --- Normal walk: canvas click for close tiles, minimap for far ---
            int waypointDist = MIN_WAYPOINT_DIST
                + RANDOM.nextInt(MAX_WAYPOINT_DIST - MIN_WAYPOINT_DIST + 1);
            int waypointIdx = Math.min(currentIdx + waypointDist, path.size() - 1);

            if (transportIdx >= 0 && waypointIdx > transportIdx)
            {
                waypointIdx = transportIdx;
            }

            WorldPoint waypoint = path.get(waypointIdx);
            int waypointTileDist = playerPos.distanceTo(waypoint);

            System.out.println("[ClaudeBot] PATH_TO: step " + (steps + 1) + "/" + MAX_STEPS
                + " toward " + waypoint + " (" + remaining + " tiles remaining)");

            boolean clicked = false;

            if (waypointTileDist <= CANVAS_WALK_MAX_DIST && RANDOM.nextDouble() < CANVAS_WALK_CHANCE)
            {
                clicked = doCanvasClick(client, human, clientThread, waypoint);
            }

            if (!clicked)
            {
                clicked = doMinimapClick(client, human, clientThread, playerPos, waypoint);
            }

            if (!clicked)
            {
                timing.sleep(600);
                continue;
            }

            waitForPlayerToStop(client, clientThread, timing, 8000);
            steps++;
        }

        // Chunk complete — return progress so Claude can see game state and decide
        int distRemaining = playerPos != null ? playerPos.distanceTo(target) : -1;
        System.out.println("[ClaudeBot] PATH_TO: chunk done, " + distRemaining + " tiles remaining");
        return ActionResult.success(ActionType.PATH_TO,
            "Walking to (" + targetX + "," + targetY + "): "
            + distRemaining + " tiles remaining. Re-issue PATH_TO to continue.");
    }

    // ───────────────────── Helpers ─────────────────────

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

    private static boolean doMinimapClick(Client client, HumanSimulator human,
                                           ClientThread clientThread,
                                           WorldPoint playerPos, WorldPoint waypoint)
    {
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

        if (screenPoint == null) return false;

        human.moveAndClick(screenPoint.x, screenPoint.y);
        human.shortPause();
        return true;
    }

    private static boolean doCanvasClick(Client client, HumanSimulator human,
                                          ClientThread clientThread, WorldPoint waypoint)
    {
        java.awt.Point screenPoint;
        try
        {
            screenPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                LocalPoint localPoint = LocalPoint.fromWorld(client, waypoint);
                if (localPoint == null) return null;

                net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
                    client, localPoint, client.getPlane());
                if (canvasPoint == null) return null;

                int cx = canvasPoint.getX();
                int cy = canvasPoint.getY();
                if (cx < 5 || cy < 5
                    || cx >= client.getCanvasWidth() - 5
                    || cy >= client.getCanvasHeight() - 5)
                {
                    return null;
                }

                return new java.awt.Point(cx, cy);
            });
        }
        catch (Throwable t)
        {
            return false;
        }

        if (screenPoint == null) return false;

        human.moveAndClick(screenPoint.x, screenPoint.y);
        human.shortPause();
        return true;
    }

    private static void doTransportInteract(Client client, HumanSimulator human,
                                             ObjectUtils objectUtils,
                                             ClientThread clientThread,
                                             Transport transport)
    {
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

        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

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

    private static boolean tryOpenBlockingDoor(Client client, HumanSimulator human,
                                                ObjectUtils objectUtils,
                                                ClientThread clientThread,
                                                WorldPoint playerPos, WorldPoint toward)
    {
        int dirX = Integer.signum(toward.getX() - playerPos.getX());
        int dirY = Integer.signum(toward.getY() - playerPos.getY());
        if (dirX == 0 && dirY == 0) return false;

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

        System.out.println("[ClaudeBot] PATH_TO: opening " + name + " (" + action + ")");

        if (screenPoint != null)
        {
            human.moveMouse(screenPoint.x, screenPoint.y);
            human.shortPause();
        }

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

        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        int throughX = playerPos.getX() + doorDx * 2;
        int throughY = playerPos.getY() + doorDy * 2;
        doMinimapClick(client, human, clientThread, playerPos,
            new WorldPoint(throughX, throughY, playerPos.getPlane()));

        return true;
    }

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

    private static WorldPoint waitForPlayerToStop(Client client, ClientThread clientThread,
                                                   TimingEngine timing, int maxWaitMs)
    {
        long deadline = System.currentTimeMillis() + maxWaitMs;
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
                if (sameCount >= 2) return currentPos;
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

        if (bestDist > 15) return -1;
        return bestIdx;
    }

    private static String checkForAttacker(Client client, ClientThread clientThread)
    {
        try
        {
            return ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                if (local == null) return null;

                for (NPC npc : client.getNpcs())
                {
                    if (npc != null && npc.getInteracting() == local
                        && npc.getCombatLevel() > 0)
                    {
                        return npc.getName() + " (lvl " + npc.getCombatLevel() + ")";
                    }
                }

                for (Player player : client.getPlayers())
                {
                    if (player != null && player != local
                        && player.getInteracting() == local)
                    {
                        return "Player: " + player.getName()
                            + " (combat " + player.getCombatLevel() + ")";
                    }
                }

                return null;
            });
        }
        catch (Throwable t)
        {
            return null;
        }
    }

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
