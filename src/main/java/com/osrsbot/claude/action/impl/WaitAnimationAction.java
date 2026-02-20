package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;

/**
 * Waits until the player's current animation completes (returns to IDLE).
 * Bails out immediately if the player is under attack.
 *
 * Optional "ticks" field sets max wait time (default 20 ticks = 12 seconds).
 */
public class WaitAnimationAction
{
    private static final int POLL_MS = 300; // poll ~twice per game tick
    private static final int DEFAULT_MAX_TICKS = 20;
    private static final int MS_PER_TICK = 600;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        int maxTicks = action.getTicks() > 0 ? action.getTicks() : DEFAULT_MAX_TICKS;
        long timeoutMs = (long) maxTicks * MS_PER_TICK;
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean checkCombat = !"ignore_combat".equalsIgnoreCase(action.getOption());

        // First, wait briefly for animation to start (if issued right after an interact)
        human.getTimingEngine().sleep(MS_PER_TICK);

        boolean wasAnimating = false;

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
                }
                else if (wasAnimating)
                {
                    // Was animating, now idle — animation completed
                    return ActionResult.success(ActionType.WAIT_ANIMATION, "Animation completed");
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
            return ActionResult.success(ActionType.WAIT_ANIMATION, "Timeout after " + maxTicks + " ticks (was still animating)");
        }
        return ActionResult.success(ActionType.WAIT_ANIMATION, "No animation detected within " + maxTicks + " ticks");
    }
}
