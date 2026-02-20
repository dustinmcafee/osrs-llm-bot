package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import com.osrsbot.claude.util.TileUtils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

@Slf4j
public class PickupItemAction
{
    public static ActionResult execute(Client client, HumanSimulator human, TileUtils tileUtils, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.PICKUP_ITEM, "No item name specified");
        }

        // Phase 1: Lookup on client thread (blocks background thread until complete)
        // Scene/tile/item API calls require the client thread.
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Player local = client.getLocalPlayer();
                if (local == null) return null;

                WorldPoint playerPos = local.getWorldLocation();
                Tile[][][] tiles = client.getScene().getTiles();
                int plane = client.getPlane();

                Tile closestTile = null;
                TileItem closestItem = null;
                int closestDist = Integer.MAX_VALUE;

                for (int x = 0; x < Constants.SCENE_SIZE; x++)
                {
                    for (int y = 0; y < Constants.SCENE_SIZE; y++)
                    {
                        Tile tile = tiles[plane][x][y];
                        if (tile == null || tile.getGroundItems() == null) continue;

                        for (TileItem item : tile.getGroundItems())
                        {
                            String name = itemUtils.getItemName(client, item.getId());
                            if (name != null && name.equalsIgnoreCase(action.getName()))
                            {
                                int dist = tile.getWorldLocation().distanceTo(playerPos);
                                if (dist < closestDist)
                                {
                                    closestTile = tile;
                                    closestItem = item;
                                    closestDist = dist;
                                }
                            }
                        }
                    }
                }

                if (closestTile == null || closestItem == null) return null;

                int sceneX = closestTile.getSceneLocation().getX();
                int sceneY = closestTile.getSceneLocation().getY();
                int itemId = closestItem.getId();
                java.awt.Point screenPoint = tileUtils.worldToScreen(client, closestTile.getWorldLocation());

                return new Object[]{ sceneX, sceneY, itemId, screenPoint };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] Pickup lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.PICKUP_ITEM, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.PICKUP_ITEM, "Ground item not found: " + action.getName());
        }

        int sceneX = (int) lookupData[0];
        int sceneY = (int) lookupData[1];
        int itemId = (int) lookupData[2];
        java.awt.Point screenPoint = (java.awt.Point) lookupData[3];

        // Phase 2: Mouse movement on background thread (with sleeps for humanization)
        if (screenPoint == null)
        {
            return ActionResult.failure(ActionType.PICKUP_ITEM, "Ground item not visible on screen: " + action.getName());
        }
        human.moveMouse(screenPoint.x, screenPoint.y);
        human.shortPause();

        // Phase 3: Menu action on client thread (fire-and-forget)
        String itemName = action.getName();

        System.out.println("[ClaudeBot] Pickup menuAction: sceneXY=(" + sceneX + "," + sceneY +
            ") itemId=" + itemId + " name=" + itemName);

        clientThread.invokeLater(() -> {
            try
            {
                client.menuAction(
                    sceneX,                             // param0
                    sceneY,                             // param1
                    MenuAction.GROUND_ITEM_THIRD_OPTION,// type ("Take")
                    itemId,                             // identifier
                    itemId,                             // itemId
                    "Take",                             // option
                    itemName                            // target
                );
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] Pickup menuAction FAILED on client thread: " +
                    t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        });

        human.shortPause();
        return ActionResult.success(ActionType.PICKUP_ITEM);
    }
}
