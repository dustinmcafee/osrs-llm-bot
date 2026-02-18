package com.osrsbot.claude.util;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class ObjectUtils
{
    @Inject
    private Client client;

    /**
     * Finds nearest object by name. When multiple objects share the same name and distance
     * (e.g. overlapping staircases), this doesn't disambiguate — use findNearest(client, name, option)
     * when you have a specific action in mind.
     */
    public TileObject findNearest(Client client, String name)
    {
        return findNearest(client, name, null);
    }

    /**
     * Finds nearest object by name, preferring objects where the desired action is at the
     * lowest index (primary action). This fixes overlapping objects like Lumbridge staircases
     * where multiple objects share a name but only one actually responds to a given action.
     *
     * When option is non-null and multiple objects tie on distance, the one where the option
     * is at the lowest action index wins. This ensures "Climb-down" picks the DOWN-staircase
     * (where Climb-down is index 0) over the UP-staircase (where it's index 2).
     */
    public TileObject findNearest(Client client, String name, String option)
    {
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        WorldPoint playerPos = local.getWorldLocation();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        TileObject nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        int nearestActionIdx = Integer.MAX_VALUE; // lower = better match for the desired action

        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj == null) continue;
                    TileObject found = checkObject(client, obj, name, option, playerPos, nearestDist, nearestActionIdx);
                    if (found != null)
                    {
                        nearest = found;
                        nearestDist = found.getWorldLocation().distanceTo(playerPos);
                        nearestActionIdx = getOptionIndex(client, found, option);
                    }
                }

                for (TileObject candidate : new TileObject[]{ tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject() })
                {
                    TileObject found = checkObject(client, candidate, name, option, playerPos, nearestDist, nearestActionIdx);
                    if (found != null)
                    {
                        nearest = found;
                        nearestDist = found.getWorldLocation().distanceTo(playerPos);
                        nearestActionIdx = getOptionIndex(client, found, option);
                    }
                }
            }
        }

        return nearest;
    }

    private TileObject checkObject(Client client, TileObject obj, String name, String option,
                                   WorldPoint playerPos, int currentNearestDist, int currentBestActionIdx)
    {
        if (obj == null) return null;

        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return null;

        String objName = comp.getName();
        if (comp.getImpostorIds() != null)
        {
            ObjectComposition impostor = comp.getImpostor();
            if (impostor != null) objName = impostor.getName();
        }

        if (objName == null || !objName.equalsIgnoreCase(name)) return null;

        int dist = obj.getWorldLocation().distanceTo(playerPos);

        if (dist < currentNearestDist)
        {
            return obj; // Closer — always wins
        }

        // Same distance: prefer the object where the desired action has lower index
        if (dist == currentNearestDist && option != null)
        {
            int actionIdx = getOptionIndex(client, obj, option);
            if (actionIdx >= 0 && actionIdx < currentBestActionIdx)
            {
                return obj; // Same distance but better action match
            }
        }

        return null;
    }

    /**
     * Returns the index of the given option in the object's action list, or Integer.MAX_VALUE if not found.
     */
    private int getOptionIndex(Client client, TileObject obj, String option)
    {
        if (option == null) return Integer.MAX_VALUE;
        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return Integer.MAX_VALUE;
        if (comp.getImpostorIds() != null)
        {
            ObjectComposition impostor = comp.getImpostor();
            if (impostor != null) comp = impostor;
        }
        String[] actions = comp.getActions();
        if (actions == null) return Integer.MAX_VALUE;
        for (int i = 0; i < actions.length; i++)
        {
            if (actions[i] != null && actions[i].equalsIgnoreCase(option)) return i;
        }
        return Integer.MAX_VALUE;
    }

    public int getActionIndex(Client client, TileObject obj, String option)
    {
        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return -1;

        if (comp.getImpostorIds() != null)
        {
            ObjectComposition impostor = comp.getImpostor();
            if (impostor != null) comp = impostor;
        }

        String[] actions = comp.getActions();
        for (int i = 0; i < actions.length; i++)
        {
            if (actions[i] != null && actions[i].equalsIgnoreCase(option))
            {
                return i;
            }
        }
        return -1;
    }

    public MenuAction getMenuAction(int actionIndex)
    {
        switch (actionIndex)
        {
            case 0: return MenuAction.GAME_OBJECT_FIRST_OPTION;
            case 1: return MenuAction.GAME_OBJECT_SECOND_OPTION;
            case 2: return MenuAction.GAME_OBJECT_THIRD_OPTION;
            case 3: return MenuAction.GAME_OBJECT_FOURTH_OPTION;
            case 4: return MenuAction.GAME_OBJECT_FIFTH_OPTION;
            default: return MenuAction.GAME_OBJECT_FIRST_OPTION;
        }
    }
}
