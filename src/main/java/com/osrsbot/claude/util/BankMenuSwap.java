package com.osrsbot.claude.util;

import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

/**
 * Coordinates bank menu entry swaps between action classes and the plugin's
 * PostMenuSort event handler. Uses the same mechanism as RuneLite's built-in
 * MenuEntrySwapper: promote CC_OP_LOW_PRIORITY entries to CC_OP and move them
 * to the top of the menu array (last position = left-click default).
 *
 * Action classes set the pending swap fields, the plugin's onPostMenuSort
 * handler applies the swap every frame, and the action class clears the fields
 * after clicking.
 */
public class BankMenuSwap
{
    /** Menu entry identifier to swap (e.g. 6 for Withdraw-X, 7 for Withdraw-All). -1 = no swap. */
    private static volatile int pendingIdentifier = -1;

    /** The original MenuAction type to match (usually CC_OP_LOW_PRIORITY). */
    private static volatile MenuAction pendingType = null;

    /**
     * Request a bank menu entry swap. The plugin's onPostMenuSort handler will
     * promote the matching entry to CC_OP and move it to the left-click position
     * on every frame until cleared.
     *
     * @param identifier the menu entry identifier (e.g. 6 for Withdraw-X, 7 for Withdraw-All)
     * @param type       the original MenuAction type to match (CC_OP or CC_OP_LOW_PRIORITY)
     */
    public static void setPendingSwap(int identifier, MenuAction type)
    {
        pendingType = type;
        pendingIdentifier = identifier;
    }

    /**
     * Clear the pending swap. Call this after the click has been dispatched.
     */
    public static void clearPendingSwap()
    {
        pendingIdentifier = -1;
        pendingType = null;
    }

    /**
     * Called from the plugin's @Subscribe onPostMenuSort handler.
     * If a swap is pending, finds the matching entry, promotes it to CC_OP,
     * moves it to the last array position (left-click default), and commits.
     *
     * This mirrors RuneLite's MenuEntrySwapperPlugin.bankModeSwap() exactly.
     */
    public static void processSwap(Client client)
    {
        int id = pendingIdentifier;
        MenuAction type = pendingType;
        if (id < 0 || type == null) return;

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        if (entries == null || entries.length == 0) return;

        for (int i = entries.length - 1; i >= 0; i--)
        {
            MenuEntry entry = entries[i];
            if (entry.getType() == type && entry.getIdentifier() == id)
            {
                // Promote CC_OP_LOW_PRIORITY to CC_OP so the game treats it as left-clickable
                if (type != MenuAction.CC_OP)
                {
                    entry.setType(MenuAction.CC_OP);
                }

                // Swap to last position (top of menu = left-click default)
                if (i != entries.length - 1)
                {
                    MenuEntry old = entries[entries.length - 1];
                    entries[i] = old;
                    entries[entries.length - 1] = entry;
                }

                menu.setMenuEntries(entries);
                return;
            }
        }
    }
}
