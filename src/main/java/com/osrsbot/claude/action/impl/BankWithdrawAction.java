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
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks
    private static final int SEARCH_FILTER_TIMEOUT_MS = 3000; // 5 game ticks for search to filter
    private static final int SEARCH_CLICK_RETRIES = 2; // retry clicking search button


    // Bank search button background: group 12, child 36 (WidgetInfo.BANK_SEARCH_BUTTON_BACKGROUND)
    // NOTE: child 41 is BANK_DEPOSIT_INVENTORY — DO NOT use that!
    private static final int BANK_SEARCH_GROUP = 12;
    private static final int BANK_SEARCH_CHILD = 36;

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Resolve item name: Claude may send it as "item" or "name"
        String itemName = action.getItem() != null ? action.getItem() : action.getName();
        if (itemName == null)
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: no item name specified");
        }

        // Wait for bank to be open (handles batched open + withdraw)
        if (!waitForBankOpen(client, clientThread, human))
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: bank not open for " + itemName);
        }

        // Let bank UI fully render before interacting with widgets
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Phase 1: Check bank is open, check inventory space, count inventory, get search button
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                if (client.getItemContainer(InventoryID.BANK) == null)
                {
                    return new Object[]{ "BANK_NOT_OPEN" };
                }

                // Count inventory before withdrawal for verification
                int invCount = countInInventory(client, itemUtils, itemName);

                // Always use search — direct item lookup is unreliable because bank widget
                // positions can shift between the lookup and the click (especially after
                // deposit-all which re-renders the bank layout)
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

        if ("BANK_NOT_OPEN".equals(status))
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: bank not open for " + itemName);
        }

        int invCountBefore = (int) lookupData[2];

        // Always use bank search to find the item — avoids stale widget bounds
        java.awt.Point searchBtnPoint = (java.awt.Point) lookupData[1];
        if (searchBtnPoint == null)
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: " + status + " for " + itemName);
        }

        System.out.println("[ClaudeBot] BankWithdraw: searching for '" + itemName + "'");

        // Click the search button, retry if search input doesn't activate
        boolean searchActivated = false;
        for (int attempt = 0; attempt < SEARCH_CLICK_RETRIES; attempt++)
        {
            // Re-fetch search button bounds each attempt (bank may have re-rendered)
            if (attempt > 0)
            {
                System.out.println("[ClaudeBot] BankWithdraw: search retry attempt " + (attempt + 1));
                try
                {
                    java.awt.Point freshBtn = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                        Widget searchBtn = client.getWidget(BANK_SEARCH_GROUP, BANK_SEARCH_CHILD);
                        if (searchBtn == null || searchBtn.isHidden()) return null;
                        java.awt.Rectangle b = searchBtn.getBounds();
                        if (b == null) return null;
                        return new java.awt.Point((int) b.getCenterX(), (int) b.getCenterY());
                    });
                    if (freshBtn != null) searchBtnPoint = freshBtn;
                }
                catch (Throwable t) {}
            }

            human.moveAndClick(searchBtnPoint.x, searchBtnPoint.y);

            if (waitForSearchActive(client, clientThread, human))
            {
                searchActivated = true;
                break;
            }
            System.err.println("[ClaudeBot] BankWithdraw: search bar did not activate (attempt " + (attempt + 1) + ")");
        }

        if (!searchActivated)
        {
            // Don't press Escape — it would close the bank. Just return failure.
            return ActionResult.failure(ActionType.BANK_WITHDRAW,
                "Bank withdraw: search bar did not activate for " + itemName);
        }

        // Clear any stale search text from a previous search in this batch
        // (bank search re-opens with old text still in the input)
        for (int i = 0; i < 20; i++)
        {
            human.pressKey(KeyEvent.VK_BACK_SPACE);
        }

        // Type the item name
        human.typeText(itemName);

        // Wait for the bank to filter and then find the item
        java.awt.Point itemPoint = waitForSearchResult(client, clientThread, itemUtils, itemName, human);
        if (itemPoint == null)
        {
            // Click search button to toggle search off (Escape would close the bank)
            closeSearch(client, human, clientThread);
            return ActionResult.failure(ActionType.BANK_WITHDRAW,
                "Bank withdraw: item not found after search for " + itemName);
        }
        System.out.println("[ClaudeBot] BankWithdraw: search found '" + itemName + "' at " + itemPoint);

        // Let bank search results fully render before interacting
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Phase 2: Set the bank quantity mode, then left-click the item.
        // We use the bank's quantity selector buttons (widget group 12) to set the
        // default withdraw amount, then left-click. This avoids the right-click menu
        // entirely — client.menuAction() with CC_OP doesn't work for bank widgets,
        // and pixel-clicking inside right-click menus is fragile.
        int qty = action.getQuantity();
        if (qty == 0) qty = 1; // Default to 1 when no quantity specified
        boolean needsTyping = isCustomQuantity(qty);

        // Bank quantity button child IDs (group 12)
        int qtyButtonChild = getQuantityButtonChild(qty);

        System.out.println("[ClaudeBot] BankWithdraw: qty=" + qty + " button=child(" + qtyButtonChild + ") for '" + itemName + "'");

        // Click the quantity selector button if not already set to the right mode
        try
        {
            java.awt.Point qtyBtnPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget qtyBtn = client.getWidget(BANK_SEARCH_GROUP, qtyButtonChild);
                if (qtyBtn == null || qtyBtn.isHidden()) return null;
                java.awt.Rectangle bounds = qtyBtn.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });

            if (qtyBtnPoint != null)
            {
                human.moveAndClick(qtyBtnPoint.x, qtyBtnPoint.y);
                human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());

                // For Withdraw-X, a chatbox input dialog appears — type the quantity
                if (needsTyping)
                {
                    if (!waitForSearchActive(client, clientThread, human))
                    {
                        System.err.println("[ClaudeBot] BankWithdraw: Withdraw-X input dialog did not appear");
                        closeSearch(client, human, clientThread);
                        return ActionResult.failure(ActionType.BANK_WITHDRAW,
                            "Withdraw-X dialog did not appear for " + itemName);
                    }
                    human.typeText(String.valueOf(qty));
                    human.pressKey(KeyEvent.VK_ENTER);
                    human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());
                }
            }
            else
            {
                System.err.println("[ClaudeBot] BankWithdraw: quantity button not found (child=" + qtyButtonChild + ")");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankWithdraw: quantity button click failed: " + t.getMessage());
        }

        // Re-find the item position (bank may have re-rendered after quantity button click)
        java.awt.Point freshItemPoint = waitForSearchResult(client, clientThread, itemUtils, itemName, human);
        if (freshItemPoint == null) freshItemPoint = itemPoint; // fall back to original

        // Left-click the item to withdraw
        human.moveAndClick(freshItemPoint.x, freshItemPoint.y);

        // Wait one tick for the game to process the withdrawal
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Phase 3: Wait for the withdrawal to actually complete (item appears in inventory)
        boolean verified = waitForInventoryChange(client, clientThread, itemUtils, itemName, invCountBefore, human);

        // Close search filter so the next BANK_WITHDRAW in the batch starts clean.
        // Click the search button to toggle search off (Escape would close the bank).
        closeSearch(client, human, clientThread);

        if (!verified)
        {
            System.err.println("[ClaudeBot] BankWithdraw: item did not appear in inventory for " + itemName);
            return ActionResult.failure(ActionType.BANK_WITHDRAW,
                "Withdraw clicked but " + itemName + " did not appear in inventory — wrong item may have been withdrawn");
        }

        // Wait one full game tick so the bank widget refreshes before the next bank operation
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.BANK_WITHDRAW);
    }

    /**
     * Safely closes the bank search filter by clicking the search button (toggles it off).
     * Unlike Escape, this does NOT close the bank — it only toggles the search.
     */
    private static void closeSearch(Client client, HumanSimulator human, ClientThread clientThread)
    {
        try
        {
            java.awt.Point btn = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget searchBtn = client.getWidget(BANK_SEARCH_GROUP, BANK_SEARCH_CHILD);
                if (searchBtn == null || searchBtn.isHidden()) return null;
                java.awt.Rectangle b = searchBtn.getBounds();
                if (b == null) return null;
                return new java.awt.Point((int) b.getCenterX(), (int) b.getCenterY());
            });
            if (btn != null)
            {
                human.moveAndClick(btn.x, btn.y);
                human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankWithdraw: closeSearch failed: " + t.getMessage());
        }
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

                    // Skip bank placeholders (quantity 0) — they show the item but can't be withdrawn
                    if (item.getItemQuantity() <= 0)
                    {
                        System.out.println("[ClaudeBot] BankWithdraw: found '" + itemName
                            + "' but it's a placeholder (qty=" + item.getItemQuantity() + "), skipping");
                        return null;
                    }

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

    /**
     * Maps the requested quantity to the bank's quantity selector button widget child ID.
     * Widget group 12:
     *   child 23 = "1"
     *   child 25 = "5"
     *   child 27 = "10"
     *   child 29 = "X" (custom)
     *   child 31 = "All"
     */
    private static int getQuantityButtonChild(int quantity)
    {
        if (quantity == 1) return 23;
        if (quantity == 5) return 25;
        if (quantity == 10) return 27;
        if (quantity == -1) return 31; // All
        return 29; // X (custom quantity — requires typing)
    }

    /**
     * Polls until the bank interface is open, up to 1800ms (3 ticks).
     * Handles the timing gap when open-bank and withdraw are batched together.
     */
    private static boolean waitForBankOpen(Client client, ClientThread clientThread, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                boolean open = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getItemContainer(InventoryID.BANK) != null);
                if (open) return true;
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
        }
        return false;
    }

    /**
     * Polls until the bank search input is active.
     * Uses VarClientInt index 5 (INPUT_TYPE) which is non-zero when the chatbox
     * is accepting text input (bank search, quantity dialogs, etc).
     */
    private static boolean waitForSearchActive(Client client, ClientThread clientThread, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        int polls = 0;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                int[] result = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    // VarClientInt 5 = INPUT_TYPE: non-zero when chatbox accepts text
                    int inputType = client.getVarcIntValue(5);
                    if (inputType != 0) return new int[]{1, inputType};

                    // Fallback: check bank search input widget (group 12, child 80 — the text input area)
                    Widget searchInput = client.getWidget(12, 80);
                    if (searchInput != null && !searchInput.isHidden())
                    {
                        return new int[]{1, -1};
                    }

                    // Fallback: scan chatbox children (group 162) for search-related text
                    for (int child = 0; child < 50; child++)
                    {
                        Widget w = client.getWidget(162, child);
                        if (w != null && !w.isHidden())
                        {
                            String text = w.getText();
                            if (text != null && (text.contains("Show items") || text.contains("show items")))
                            {
                                return new int[]{1, -2};
                            }
                        }
                    }
                    return new int[]{0, inputType};
                });
                if (result[0] == 1)
                {
                    System.out.println("[ClaudeBot] BankWithdraw: search active (method=" + result[1] + " polls=" + polls + ")");
                    return true;
                }
                if (polls == 0)
                {
                    System.out.println("[ClaudeBot] BankWithdraw: waiting for search activation (VarcInt5=" + result[1] + ")");
                }
            }
            catch (Throwable t) {}
            polls++;
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
        }
        System.err.println("[ClaudeBot] BankWithdraw: search activation timed out after " + polls + " polls");
        return false;
    }
}
