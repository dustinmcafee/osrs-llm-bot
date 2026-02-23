package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.action.LastInteractedObject;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

/**
 * Waits until the player's current animation completes (returns to IDLE).
 * Bails out immediately if the player is under attack.
 *
 * Tracks inventory changes during the wait to detect whether the skilling
 * action produced a result (e.g. ore mined, log chopped, fish caught).
 * If the animation stops without an inventory gain, reports that the
 * resource was likely taken by another player.
 *
 * Optional "ticks" field sets max wait time (default 20 ticks = 12 seconds).
 */
public class WaitAnimationAction
{
    private static final int POLL_MS = 300; // poll ~twice per game tick
    private static final int DEFAULT_MAX_TICKS = 20;
    private static final int MS_PER_TICK = 600;

    /** Max time to wait for an animation to START before giving up */
    private static final int START_GRACE_TICKS = 3; // 1.8 seconds

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        int maxTicks = action.getTicks() > 0 ? action.getTicks() : DEFAULT_MAX_TICKS;
        long timeoutMs = (long) maxTicks * MS_PER_TICK;
        long deadline = System.currentTimeMillis() + timeoutMs;
        long startGraceDeadline = System.currentTimeMillis() + ((long) START_GRACE_TICKS * MS_PER_TICK);
        boolean checkCombat = !"ignore_combat".equalsIgnoreCase(action.getOption());

        // Snapshot inventory item count before the animation, so we can detect
        // whether the action produced a result (e.g., gained ore/log/fish)
        int invCountBefore = 0;
        try
        {
            invCountBefore = ClientThreadRunner.runOnClientThread(clientThread,
                () -> countInventoryItems(client));
        }
        catch (Throwable t) {}

        // First, wait briefly for animation to start (if issued right after an interact)
        human.getTimingEngine().sleep(MS_PER_TICK);

        boolean wasAnimating = false;
        int observedAnimId = AnimationID.IDLE;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Object[] state = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Player local = client.getLocalPlayer();
                    if (local == null) return new Object[]{ AnimationID.IDLE, null };

                    int anim = local.getAnimation();

                    // Check if anything is attacking us
                    for (NPC npc : client.getNpcs())
                    {
                        if (npc != null && npc.getInteracting() == local
                            && npc.getCombatLevel() > 0)
                        {
                            return new Object[]{ anim, npc.getName() + " (lvl " + npc.getCombatLevel() + ")" };
                        }
                    }

                    return new Object[]{ anim, null };
                });

                int animId = (int) state[0];
                String attacker = (String) state[1];

                // Combat interrupt — bail immediately (unless LLM opted out)
                if (checkCombat && attacker != null)
                {
                    return ActionResult.failure(ActionType.WAIT_ANIMATION,
                        "Under attack by " + attacker + "! Aborting wait.");
                }

                if (animId != AnimationID.IDLE)
                {
                    wasAnimating = true;
                    observedAnimId = animId;

                    // While actively gathering, check if the target object was depleted.
                    // In OSRS the mining animation continues even after the rock is gone,
                    // so we detect depletion by checking the game object directly.
                    String gatherType = getGatheringType(animId);
                    if (gatherType != null && LastInteractedObject.isSet())
                    {
                        boolean objectGone = checkObjectDepleted(client, clientThread);
                        if (objectGone)
                        {
                            LastInteractedObject.clear();
                            switch (gatherType)
                            {
                                case "mining":
                                    return ActionResult.failure(ActionType.WAIT_ANIMATION,
                                        "Rock was depleted by another player. Mine a different rock.");
                                case "woodcutting":
                                    return ActionResult.failure(ActionType.WAIT_ANIMATION,
                                        "Tree was felled by another player. Chop a different tree.");
                                case "fishing":
                                    return ActionResult.failure(ActionType.WAIT_ANIMATION,
                                        "Fishing spot moved or disappeared. Find a new fishing spot.");
                            }
                        }
                    }
                }
                else if (wasAnimating)
                {
                    // Was animating, now idle — animation completed
                    return buildCompletionResult(client, clientThread, invCountBefore,
                        observedAnimId, false);
                }
                else if (System.currentTimeMillis() > startGraceDeadline)
                {
                    // Never started animating within the grace period — fail fast
                    return ActionResult.failure(ActionType.WAIT_ANIMATION,
                        "Player never started animating (waited " + START_GRACE_TICKS + " ticks). "
                        + "The preceding action may have failed or the target may be unavailable.");
                }
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }

            human.getTimingEngine().sleep(POLL_MS);
        }

        if (wasAnimating)
        {
            return buildCompletionResult(client, clientThread, invCountBefore,
                observedAnimId, true);
        }
        return ActionResult.failure(ActionType.WAIT_ANIMATION,
            "No animation detected within " + maxTicks + " ticks — the action likely failed.");
    }

    /**
     * Builds the result message when animation completes.
     * Compares inventory before/after to detect whether the action produced a result.
     * For skilling animations (mining, woodcutting, fishing), reports if the resource
     * was likely taken by another player.
     */
    private static ActionResult buildCompletionResult(Client client, ClientThread clientThread,
                                                        int invCountBefore, int animId,
                                                        boolean wasTimeout)
    {
        int invCountAfter = 0;
        try
        {
            invCountAfter = ClientThreadRunner.runOnClientThread(clientThread,
                () -> countInventoryItems(client));
        }
        catch (Throwable t) {}

        int delta = invCountAfter - invCountBefore;
        String gatherType = getGatheringType(animId);

        StringBuilder msg = new StringBuilder();
        if (wasTimeout)
        {
            msg.append("Animation still running at timeout");
        }
        else
        {
            msg.append("Animation completed");
        }

        if (delta > 0)
        {
            msg.append(" (inventory +").append(delta).append(")");
        }
        else if (delta < 0)
        {
            msg.append(" (inventory ").append(delta).append(")");
        }
        else if (gatherType != null)
        {
            // Gathering with no item gained. The animation may have stopped
            // naturally or hit timeout — in OSRS the mining animation continues
            // even after the rock is depleted by another player.
            if (invCountAfter >= 28)
            {
                // Inventory is full — that's why we didn't gain an item
                msg.append(" — inventory is full (28/28). Bank or drop items before continuing.");
                return ActionResult.failure(ActionType.WAIT_ANIMATION, msg.toString());
            }

            // Resource was depleted by another player (or spot moved for fishing)
            switch (gatherType)
            {
                case "mining":
                    msg.append(" — rock was depleted by another player. Mine a different rock.");
                    break;
                case "woodcutting":
                    msg.append(" — tree was felled by another player. Chop a different tree.");
                    break;
                case "fishing":
                    msg.append(" — fishing spot moved or disappeared. Find a new fishing spot.");
                    break;
            }
            return ActionResult.failure(ActionType.WAIT_ANIMATION, msg.toString());
        }

        return ActionResult.success(ActionType.WAIT_ANIMATION, msg.toString());
    }

    /**
     * Returns the gathering type ("mining", "woodcutting", "fishing") if the
     * animation corresponds to a gathering skill where another player can
     * deplete the resource. Returns null for non-gathering animations.
     */
    private static String getGatheringType(int animId)
    {
        switch (animId)
        {
            // Mining animations
            case AnimationID.MINING_BRONZE_PICKAXE:
            case AnimationID.MINING_IRON_PICKAXE:
            case AnimationID.MINING_STEEL_PICKAXE:
            case AnimationID.MINING_BLACK_PICKAXE:
            case AnimationID.MINING_MITHRIL_PICKAXE:
            case AnimationID.MINING_ADAMANT_PICKAXE:
            case AnimationID.MINING_RUNE_PICKAXE:
            case AnimationID.MINING_DRAGON_PICKAXE:
            case AnimationID.MINING_DRAGON_PICKAXE_OR:
            case AnimationID.MINING_DRAGON_PICKAXE_UPGRADED:
            case AnimationID.MINING_INFERNAL_PICKAXE:
            case AnimationID.MINING_3A_PICKAXE:
            case AnimationID.MINING_CRYSTAL_PICKAXE:
                return "mining";
            // Woodcutting animations
            case AnimationID.WOODCUTTING_BRONZE:
            case AnimationID.WOODCUTTING_IRON:
            case AnimationID.WOODCUTTING_STEEL:
            case AnimationID.WOODCUTTING_BLACK:
            case AnimationID.WOODCUTTING_MITHRIL:
            case AnimationID.WOODCUTTING_ADAMANT:
            case AnimationID.WOODCUTTING_RUNE:
            case AnimationID.WOODCUTTING_DRAGON:
            case AnimationID.WOODCUTTING_DRAGON_OR:
            case AnimationID.WOODCUTTING_INFERNAL:
            case AnimationID.WOODCUTTING_3A_AXE:
            case AnimationID.WOODCUTTING_CRYSTAL:
                return "woodcutting";
            // Fishing animations
            case AnimationID.FISHING_BIG_NET:
            case AnimationID.FISHING_NET:
            case AnimationID.FISHING_POLE_CAST:
            case AnimationID.FISHING_CAGE:
            case AnimationID.FISHING_HARPOON:
            case AnimationID.FISHING_BARBARIAN_ROD:
            case AnimationID.FISHING_DRAGON_HARPOON:
            case AnimationID.FISHING_INFERNAL_HARPOON:
            case AnimationID.FISHING_CRYSTAL_HARPOON:
                return "fishing";
            default:
                return null;
        }
    }

    /**
     * Checks if the last interacted game object no longer exists at its tile.
     * In OSRS, depleted rocks/trees are replaced with a different object ID.
     * Returns true if the object is gone (depleted).
     */
    private static boolean checkObjectDepleted(Client client, ClientThread clientThread)
    {
        int targetId = LastInteractedObject.getObjectId();
        int wx = LastInteractedObject.getWorldX();
        int wy = LastInteractedObject.getWorldY();
        int wp = LastInteractedObject.getPlane();

        try
        {
            return ClientThreadRunner.runOnClientThread(clientThread, () -> {
                LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                if (lp == null) return false; // tile not in scene — can't check

                Scene scene = client.getScene();
                Tile[][][] tiles = scene.getTiles();
                int sx = lp.getSceneX();
                int sy = lp.getSceneY();

                if (wp < 0 || wp >= tiles.length
                    || sx < 0 || sy < 0
                    || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE)
                {
                    return false;
                }

                Tile tile = tiles[wp][sx][sy];
                if (tile == null) return true; // tile gone = object gone

                // Check all game objects on this tile for a matching ID
                for (GameObject obj : tile.getGameObjects())
                {
                    if (obj != null && obj.getId() == targetId)
                    {
                        return false; // still there
                    }
                }

                // Also check wall objects (some rocks are wall objects)
                WallObject wall = tile.getWallObject();
                if (wall != null && wall.getId() == targetId)
                {
                    return false;
                }

                // Also check ground objects
                GroundObject ground = tile.getGroundObject();
                if (ground != null && ground.getId() == targetId)
                {
                    return false;
                }

                return true; // object ID no longer at this tile → depleted
            });
        }
        catch (Throwable t)
        {
            return false; // can't check, assume still there
        }
    }

    /**
     * Count total occupied inventory slots.
     */
    private static int countInventoryItems(Client client)
    {
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null) return 0;
        int count = 0;
        Widget[] children = inventory.getDynamicChildren();
        if (children == null) return 0;
        for (Widget child : children)
        {
            if (child.getItemId() > 0) count++;
        }
        return count;
    }
}
