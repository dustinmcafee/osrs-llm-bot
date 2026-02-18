package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.ClaudeBotConfig;
import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Hops to a different OSRS world. If a world number is specified (x field),
 * uses that; otherwise picks a random safe world filtered by config preference.
 */
public class WorldHopAction
{
    private static final Random RANDOM = new Random();

    // Curated safe free worlds (no PVP, no tournament, no beta, no fresh start)
    private static final int[] FREE_WORLDS = {
        301, 308, 316, 326, 335, 371, 379, 380, 382, 383, 384,
        393, 394, 397, 398, 399, 418, 425, 430, 431, 433, 434,
        435, 436, 437, 451, 452, 469, 470, 471, 472, 473, 497,
        498, 499, 500, 535, 536, 563, 564
    };

    // Curated safe members worlds (no PVP, no high risk, no total level, no DMM)
    private static final int[] MEMBERS_WORLDS = {
        302, 303, 304, 305, 306, 307, 309, 310, 311, 312, 314,
        315, 317, 318, 319, 320, 321, 322, 323, 324, 327, 328,
        329, 330, 331, 332, 333, 334, 336, 338, 339, 340, 341,
        342, 343, 344, 345, 346, 349, 350, 351, 352, 354, 355,
        356, 357, 358, 359, 360, 362, 367, 368, 369, 370, 374,
        375, 376, 377, 378, 386, 387, 388, 389, 390, 395, 396
    };

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action,
                                       boolean enabled, ClaudeBotConfig.WorldHopType hopType)
    {
        if (!enabled)
        {
            return ActionResult.failure(ActionType.WORLD_HOP, "World hopping is disabled in config");
        }

        int currentWorld = client.getWorld();
        int targetWorld = action.getX();

        // If no specific world requested, pick a random one
        if (targetWorld <= 0)
        {
            targetWorld = pickRandomWorld(currentWorld, hopType);
            if (targetWorld <= 0)
            {
                return ActionResult.failure(ActionType.WORLD_HOP, "No valid worlds available for type: " + hopType);
            }
        }

        if (currentWorld == targetWorld)
        {
            return ActionResult.failure(ActionType.WORLD_HOP, "Already on world " + targetWorld);
        }

        final int hopTarget = targetWorld;

        // Hop on client thread with proper World object
        try
        {
            clientThread.invokeLater(() -> {
                if (client.getGameState() == GameState.LOGGED_IN)
                {
                    World world = client.createWorld();
                    world.setId(hopTarget);
                    world.setActivity("");
                    world.setAddress("oldschool" + hopTarget + ".runescape.com");
                    world.setLocation(-1);
                    world.setPlayerCount(0);

                    // Set the correct world type flags
                    EnumSet<WorldType> types = EnumSet.noneOf(WorldType.class);
                    if (isMembersWorld(hopTarget))
                    {
                        types.add(WorldType.MEMBERS);
                    }
                    world.setTypes(types);

                    client.changeWorld(world);
                    System.out.println("[ClaudeBot] WorldHop: changing from " + client.getWorld() + " to " + hopTarget);
                }
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] WorldHop failed: " + t.getMessage());
            return ActionResult.failure(ActionType.WORLD_HOP, "Hop failed: " + t.getMessage());
        }

        // Wait for hop to complete (login/loading takes a few seconds)
        human.getTimingEngine().sleep(5000);

        System.out.println("[ClaudeBot] Hopped from world " + currentWorld + " to world " + hopTarget);
        return ActionResult.success(ActionType.WORLD_HOP);
    }

    private static int pickRandomWorld(int currentWorld, ClaudeBotConfig.WorldHopType hopType)
    {
        List<Integer> candidates = new ArrayList<>();

        if (hopType == ClaudeBotConfig.WorldHopType.FREE || hopType == ClaudeBotConfig.WorldHopType.ANY)
        {
            for (int w : FREE_WORLDS)
            {
                if (w != currentWorld) candidates.add(w);
            }
        }
        if (hopType == ClaudeBotConfig.WorldHopType.MEMBERS || hopType == ClaudeBotConfig.WorldHopType.ANY)
        {
            for (int w : MEMBERS_WORLDS)
            {
                if (w != currentWorld) candidates.add(w);
            }
        }

        if (candidates.isEmpty()) return -1;
        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    private static boolean isMembersWorld(int worldId)
    {
        for (int w : FREE_WORLDS)
        {
            if (w == worldId) return false;
        }
        return true; // If not in free list, assume members
    }
}
