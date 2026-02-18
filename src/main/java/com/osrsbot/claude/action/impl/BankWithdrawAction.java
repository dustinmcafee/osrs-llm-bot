package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

import java.awt.event.KeyEvent;

/**
 * Withdraws an item from the bank. Uses the bank search feature when the item
 * isn't visible in the current tab/scroll position, eliminating tab-switching
 * and scrolling issues.
 */
public class BankWithdrawAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 1800; // 3 game ticks
    private static final int SEARCH_FILTER_TIMEOUT_MS = 1200; // 2 game ticks for search to filter


    // Bank search button: group 12, child 41
    private static final int BANK_SEARCH_GROUP = 12;
    private static final int BANK_SEARCH_CHILD = 41;

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Check bank is open, count inventory, try direct item lookup first
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                if (client.getItemContainer(InventoryID.BANK) == null)
                {
                    return new Object[]{ "BANK_NOT_OPEN" };
                }

                // Count inventory before withdrawal for verification
                int invCount = countInInventory(client, itemUtils, action.getName());

                // Try to find item directly in the visible bank container
                Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
                if (bankContainer != null)
                {
                    Widget item = itemUtils.findInWidget(bankContainer, action.getName());
                    if (item != null)
                    {
                        java.awt.Rectangle bounds = item.getBounds();
                        if (bounds != null)
                        {
                            return new Object[]{ "FOUND_DIRECT",
                                new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                                invCount };
                        }
                    }
                }

                // Item not visible — get search button bounds for search fallback
                Widget searchBtn = client.getWidget(BANK_SEARCH_GROUP, BANK_SEARCH_CHILD);
                if (searchBtn == null || searchBtn.isHidden())
                {
                    return new Object[]{ "NO_SEARCH_BUTTON", null, invCount };
                }

                java.awt.Rectangle searchBounds = searchBtn.getBounds();
                if (searchBounds == null)
                {
                    return new Object[]{ "NO_SEARCH_BOUNDS", null, invCount };
                }

                return new Object[]{ "NEED_SEARCH",
                    new java.awt.Point((int) searchBounds.getCenterX(), (int) searchBounds.getCenterY()),
                    invCount };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankWithdraw lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        int invCountBefore = (int) lookupData[2];

        if ("BANK_NOT_OPEN".equals(status))
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: bank not open for " + action.getName());
        }

        java.awt.Point itemPoint;

        if ("FOUND_DIRECT".equals(status))
        {
            // Fast path: item is visible, use it directly
            itemPoint = (java.awt.Point) lookupData[1];
        }
        else
        {
            // Slow path: use bank search to find the item
            java.awt.Point searchBtnPoint = (java.awt.Point) lookupData[1];
            if (searchBtnPoint == null)
            {
                return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: " + status + " for " + action.getName());
            }

            // Click the search button
            human.moveAndClick(searchBtnPoint.x, searchBtnPoint.y);
            human.shortPause(); // wait for chatbox input to appear

            // Type the item name
            human.typeText(action.getName());

            // Wait for the bank to filter and then find the item
            itemPoint = waitForSearchResult(client, clientThread, itemUtils, action.getName(), human);
            if (itemPoint == null)
            {
                // Press Escape to exit search mode before returning failure
                human.pressKey(KeyEvent.VK_ESCAPE);
                return ActionResult.failure(ActionType.BANK_WITHDRAW,
                    "Bank withdraw: item not found after search for " + action.getName());
            }
        }

        // Phase 2: Right-click select on background thread
        int qty = action.getQuantity();
        if (qty == 0) qty = 1; // Default to 1 when no quantity specified
        String withdrawOption = getWithdrawOption(qty);
        boolean needsTyping = isCustomQuantity(qty);

        boolean selected = human.moveAndRightClickSelect(client, itemPoint.x, itemPoint.y, withdrawOption, action.getName());
        if (!selected)
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Withdraw option not found: " + withdrawOption);
        }

        // If custom quantity, type the number into the chatbox input and press Enter
        if (needsTyping)
        {
            human.shortPause(); // wait for chatbox input to appear
            human.typeText(String.valueOf(qty));
            human.pressKey(KeyEvent.VK_ENTER);
        }

        // Phase 3: Wait for the withdrawal to actually complete (item appears in inventory)
        if (!waitForInventoryChange(client, clientThread, itemUtils, action.getName(), invCountBefore, human))
        {
            System.err.println("[ClaudeBot] BankWithdraw: item may not have appeared in inventory for " + action.getName());
        }

        // Wait one full game tick so the bank widget refreshes before the next bank operation
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.BANK_WITHDRAW);
    }

    /**
     * After typing a search query, polls the bank container until the item appears
     * in the filtered results or timeout. Returns the item's screen position, or null.
     */
    private static java.awt.Point waitForSearchResult(Client client, ClientThread clientThread,
                                                       ItemUtils itemUtils, String itemName,
                                                       HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + SEARCH_FILTER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                java.awt.Point point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
                    if (bankContainer == null) return null;

                    Widget item = itemUtils.findInWidget(bankContainer, itemName);
                    if (item == null) return null;

                    java.awt.Rectangle bounds = item.getBounds();
                    if (bounds == null) return null;

                    return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                });
                if (point != null)
                {
                    return point;
                }
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
        }
        return null;
    }

    /**
     * Polls inventory until item count increases (withdrawal registered) or timeout.
     */
    private static boolean waitForInventoryChange(Client client, ClientThread clientThread,
                                                    ItemUtils itemUtils, String itemName,
                                                    int countBefore, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                int current = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> countInInventory(client, itemUtils, itemName));
                if (current > countBefore)
                {
                    return true;
                }
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
        }
        return false;
    }

    /**
     * Count how many of an item are in the player's inventory (bank inventory panel).
     */
    private static int countInInventory(Client client, ItemUtils itemUtils, String name)
    {
        Widget bankInv = client.getWidget(WidgetInfo.BANK_INVENTORY_ITEMS_CONTAINER);
        if (bankInv == null) return 0;
        int count = 0;
        Widget[] children = bankInv.getDynamicChildren();
        if (children == null) return 0;
        for (Widget child : children)
        {
            if (child.getItemId() > 0)
            {
                String itemName = itemUtils.getItemName(client, child.getItemId());
                if (itemName != null && itemName.equalsIgnoreCase(name))
                {
                    count += child.getItemQuantity();
                }
            }
        }
        return count;
    }

    /**
     * Returns the right-click menu option string for the given quantity.
     * OSRS bank menu options: Withdraw-1, Withdraw-5, Withdraw-10, Withdraw-All, Withdraw-X
     */
    private static String getWithdrawOption(int quantity)
    {
        if (quantity == -1) return "Withdraw-All";
        if (quantity == 1) return "Withdraw-1";
        if (quantity == 5) return "Withdraw-5";
        if (quantity == 10) return "Withdraw-10";
        return "Withdraw-X"; // any other quantity uses Withdraw-X + type number
    }

    private static boolean isCustomQuantity(int quantity)
    {
        return quantity != -1 && quantity != 1 && quantity != 5 && quantity != 10;
    }
}
