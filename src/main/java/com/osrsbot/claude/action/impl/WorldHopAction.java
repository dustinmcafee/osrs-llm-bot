package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;

import java.util.EnumSet;

/**
 * Hops to a different OSRS world by calling client.changeWorld() on the client thread.
 * The world number is taken from the action's x field.
 */
public class WorldHopAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        int targetWorld = action.getX();
        if (targetWorld <= 0)
        {
            return ActionResult.failure(ActionType.WORLD_HOP, "No world number specified (use x field)");
        }

        int currentWorld = client.getWorld();
        if (currentWorld == targetWorld)
        {
            return ActionResult.failure(ActionType.WORLD_HOP, "Already on world " + targetWorld);
        }

        // Hop on client thread
        try
        {
            clientThread.invokeLater(() -> {
                if (client.getGameState() == GameState.LOGGED_IN)
                {
                    World world = client.createWorld();
                    world.setId(targetWorld);
                    world.setActivity("");
                    world.setAddress("");
                    world.setLocation(-1);
                    world.setPlayerCount(0);
                    world.setTypes(EnumSet.noneOf(WorldType.class));
                    client.changeWorld(world);
                }
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] WorldHop failed: " + t.getMessage());
            return ActionResult.failure(ActionType.WORLD_HOP, "Hop failed: " + t.getMessage());
        }

        // Wait for hop to complete (login/loading takes a few seconds)
        human.getTimingEngine().sleep(4000);

        System.out.println("[ClaudeBot] Hopped from world " + currentWorld + " to world " + targetWorld);
        return ActionResult.success(ActionType.WORLD_HOP);
    }
}
