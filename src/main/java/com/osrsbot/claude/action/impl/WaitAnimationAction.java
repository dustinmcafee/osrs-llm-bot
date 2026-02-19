package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * Waits until the player's current animation completes (returns to IDLE).
 * Useful for mining, woodcutting, fishing, etc. where the LLM should wait
 * for the action to finish before issuing the next command.
 *
 * Optional "ticks" field sets max wait time (default 10 ticks = 6 seconds).
 */
public class WaitAnimationAction
{
    private static final int POLL_MS = 300; // poll ~twice per game tick
    private static final int DEFAULT_MAX_TICKS = 10;
    private static final int MS_PER_TICK = 600;

    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread, BotAction action)
    {
        int maxTicks = action.getTicks() > 0 ? action.getTicks() : DEFAULT_MAX_TICKS;
        long timeoutMs = (long) maxTicks * MS_PER_TICK;
        long deadline = System.currentTimeMillis() + timeoutMs;

        // First, wait briefly for animation to start (if issued right after an interact)
        human.getTimingEngine().sleep(MS_PER_TICK);

        boolean wasAnimating = false;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                int animId = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getLocalPlayer().getAnimation());

                if (animId != AnimationID.IDLE)
                {
                    wasAnimating = true;
                }
                else if (wasAnimating)
                {
                    // Was animating, now idle — animation completed
                    return ActionResult.success(ActionType.WAIT_ANIMATION, "Animation completed");
                }
                else
                {
                    // Never started animating — might be too late or instant action
                    // Wait a bit more in case animation hasn't kicked in yet
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
