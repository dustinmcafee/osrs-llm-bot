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

    public TileObject findNearest(Client client, String name)
    {
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        WorldPoint playerPos = local.getWorldLocation();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        TileObject nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj == null) continue;
                    TileObject found = checkObject(client, obj, name, playerPos, nearest, nearestDist);
                    if (found != null)
                    {
                        nearest = found;
                        nearestDist = found.getWorldLocation().distanceTo(playerPos);
                    }
                }

                TileObject wallObj = checkObject(client, tile.getWallObject(), name, playerPos, nearest, nearestDist);
                if (wallObj != null)
                {
                    nearest = wallObj;
                    nearestDist = wallObj.getWorldLocation().distanceTo(playerPos);
                }

                TileObject decoObj = checkObject(client, tile.getDecorativeObject(), name, playerPos, nearest, nearestDist);
                if (decoObj != null)
                {
                    nearest = decoObj;
                    nearestDist = decoObj.getWorldLocation().distanceTo(playerPos);
                }

                TileObject groundObj = checkObject(client, tile.getGroundObject(), name, playerPos, nearest, nearestDist);
                if (groundObj != null)
                {
                    nearest = groundObj;
                    nearestDist = groundObj.getWorldLocation().distanceTo(playerPos);
                }
            }
        }

        return nearest;
    }

    private TileObject checkObject(Client client, TileObject obj, String name, WorldPoint playerPos, TileObject currentNearest, int currentNearestDist)
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

        if (objName != null && objName.equalsIgnoreCase(name))
        {
            int dist = obj.getWorldLocation().distanceTo(playerPos);
            if (dist < currentNearestDist)
            {
                return obj;
            }
        }

        return null;
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
