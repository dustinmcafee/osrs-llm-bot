package com.osrsbot.claude.brain;

import com.osrsbot.claude.action.ActionExecutor;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.state.GameStateSnapshot;
import com.osrsbot.claude.state.InventoryState;
import com.osrsbot.claude.state.PlayerState;

import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Detects when the bot is stuck in a no-progress loop — issuing the same
 * sequence of actions over and over while neither XP nor inventory actually
 * change. The classic case is the Lumbridge Cook range, where each
 * USE_ITEM_ON_OBJECT triggers the cook's intervention dialogue, the model
 * dismisses it with CONTINUE_DIALOGUE, then tries the same action again,
 * forever. Pure repetition is fine if XP or inventory is moving (chopping
 * the same tree for logs, casting the same spell for XP); the loop signal
 * is repetition WITHOUT progress.
 *
 * Window-based: keeps the last N executed-action signatures plus their
 * paired XP/inventory snapshots. Fires only when all N signatures match
 * AND XP at the start of the window equals XP at the end AND inventory
 * hash at the start equals at the end.
 */
@Singleton
public class LoopDetector
{
    private static final int WINDOW_SIZE = 3;

    private final Deque<Frame> history = new ArrayDeque<>();
    private boolean alreadyWarnedThisLoop = false;

    /**
     * Records this tick's executed actions and game-state snapshot, then
     * returns a [LOOP_DETECTED] warning string if the last WINDOW_SIZE
     * frames show identical actions with no XP gain and no inventory change.
     * Returns Optional.empty() while the window is filling, while progress
     * is being made, or while the warning has already been emitted for the
     * current loop (the warning is one-shot per loop — it resets when the
     * model breaks the pattern).
     */
    public Optional<String> evaluate(List<ActionExecutor.ExecutedAction> recentActions,
                                       GameStateSnapshot state)
    {
        if (recentActions == null || recentActions.isEmpty() || state == null)
        {
            // No actions to compare against this tick — keep the existing
            // window intact rather than polluting it with empty entries.
            return Optional.empty();
        }

        Frame now = new Frame(
            actionSignature(recentActions),
            totalXp(state),
            inventoryHash(state)
        );
        history.addLast(now);
        while (history.size() > WINDOW_SIZE)
        {
            history.removeFirst();
        }

        if (history.size() < WINDOW_SIZE)
        {
            return Optional.empty();
        }

        Frame first = history.peekFirst();
        Frame last = history.peekLast();

        // All signatures in the window must match.
        for (Frame f : history)
        {
            if (!f.signature.equals(first.signature))
            {
                alreadyWarnedThisLoop = false;
                return Optional.empty();
            }
        }

        boolean xpUnchanged = first.totalXp == last.totalXp;
        boolean inventoryUnchanged = first.inventoryHash.equals(last.inventoryHash);

        if (!xpUnchanged || !inventoryUnchanged)
        {
            // Progress is happening — same actions but they're producing
            // results. Reset the warning latch so a future no-progress run
            // can fire again.
            alreadyWarnedThisLoop = false;
            return Optional.empty();
        }

        if (alreadyWarnedThisLoop)
        {
            return Optional.empty();
        }
        alreadyWarnedThisLoop = true;

        return Optional.of(
            "[LOOP_DETECTED] You have issued the same action sequence " + WINDOW_SIZE
            + " times in a row with NO XP gained and NO inventory change. The current "
            + "approach is not working. Pick a fundamentally different strategy: "
            + "move to a different location, target a different NPC/object, or switch "
            + "to a different skill. Do NOT repeat the same actions again."
        );
    }

    /** Clears all history. Call on plugin start/stop. */
    public void reset()
    {
        history.clear();
        alreadyWarnedThisLoop = false;
    }

    /**
     * Builds a stable signature from the action types and their primary
     * targets. Ignores ephemeral fields (parse warnings, goals) so two
     * batches that differ only in parsing artifacts compare equal.
     * Format: "TYPE:name/option|TYPE:name/option|…".
     */
    private static String actionSignature(List<ActionExecutor.ExecutedAction> actions)
    {
        StringBuilder sb = new StringBuilder();
        for (ActionExecutor.ExecutedAction ea : actions)
        {
            BotAction a = ea.action;
            sb.append(a.getType().name());
            if (a.getName() != null) sb.append(":").append(a.getName());
            if (a.getOption() != null) sb.append("/").append(a.getOption());
            if (a.getItem1() != null && a.getItem2() != null)
            {
                sb.append("[").append(a.getItem1()).append("+").append(a.getItem2()).append("]");
            }
            sb.append("|");
        }
        return sb.toString();
    }

    /** Sum of all skill XPs — any gain in any skill bumps this value. */
    private static long totalXp(GameStateSnapshot s)
    {
        PlayerState p = s.getPlayer();
        if (p == null) return 0L;
        return (long) p.getAttackXp()
            + p.getStrengthXp() + p.getDefenceXp() + p.getRangedXp()
            + p.getMagicXp() + p.getHitpointsXp() + p.getPrayerXp()
            + p.getWoodcuttingXp() + p.getMiningXp() + p.getFishingXp()
            + p.getCookingXp() + p.getFiremakingXp() + p.getCraftingXp()
            + p.getSmithingXp() + p.getFletchingXp() + p.getSlayerXp()
            + p.getFarmingXp() + p.getConstructionXp() + p.getHunterXp()
            + p.getAgilityXp() + p.getThievingXp() + p.getHerbloreXp()
            + p.getRunecraftingXp();
    }

    /** Stable string-hash of the current inventory contents (item IDs + quantities). */
    private static String inventoryHash(GameStateSnapshot s)
    {
        InventoryState inv = s.getInventory();
        if (inv == null || inv.getItems() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (InventoryState.ItemSlot slot : inv.getItems())
        {
            sb.append(slot.getItemId()).append("x").append(slot.getQuantity()).append(",");
        }
        return sb.toString();
    }

    private static final class Frame
    {
        final String signature;
        final long totalXp;
        final String inventoryHash;
        Frame(String signature, long totalXp, String inventoryHash)
        {
            this.signature = signature;
            this.totalXp = totalXp;
            this.inventoryHash = inventoryHash;
        }
    }
}
