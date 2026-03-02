package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.BankMenuSwap;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
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

    private static final int COINS_ITEM_ID = 995;

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils,
                                       ClientThread clientThread, BotAction action, int minGoldReserve)
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

        // Gold reserve check: prevent withdrawing coins when bank balance is at or below the floor
        if (minGoldReserve > 0 && itemName.equalsIgnoreCase("Coins"))
        {
            try
            {
                int bankCoins = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> countCoinsInBank(client));
                if (bankCoins <= minGoldReserve)
                {
                    return ActionResult.failure(ActionType.BANK_WITHDRAW,
                        "Cannot withdraw Coins — bank has " + bankCoins + " gp which is at or below "
                        + "the minimum reserve of " + minGoldReserve + " gp. "
                        + "Earn more gold through other means (combat loot, selling items, skilling).");
                }
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] BankWithdraw: gold reserve check failed: " + t.getMessage());
            }
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

        int qty = action.getQuantity();
        if (qty == 0) qty = 1; // Default to 1 when no quantity specified

        System.out.println("[ClaudeBot] BankWithdraw: qty=" + qty + " for '" + itemName + "'");

        // Phase 2a: Search for the item
        System.out.println("[ClaudeBot] BankWithdraw: searching for '" + itemName + "'");

        boolean searchActivated = false;
        for (int attempt = 0; attempt < SEARCH_CLICK_RETRIES; attempt++)
        {
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
            return ActionResult.failure(ActionType.BANK_WITHDRAW,
                "Bank withdraw: search bar did not activate for " + itemName);
        }

        for (int i = 0; i < 20; i++)
        {
            human.pressKey(KeyEvent.VK_BACK_SPACE);
        }
        human.typeText(itemName);

        java.awt.Point itemPoint = waitForSearchResult(client, clientThread, itemUtils, itemName, human);
        if (itemPoint == null)
        {
            // Debug: list what items ARE visible in bank after search
            try
            {
                String visibleItems = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
                    if (bankContainer == null) return "BANK_CONTAINER_NULL";
                    Widget[] children = bankContainer.getDynamicChildren();
                    if (children == null) return "NO_CHILDREN";
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    for (Widget child : children)
                    {
                        if (child.getItemId() > 0 && child.getItemQuantity() > 0)
                        {
                            String name = itemUtils.getItemName(client, child.getItemId());
                            if (name == null) name = "id:" + child.getItemId();
                            sb.append(name).append("(x").append(child.getItemQuantity()).append(") ");
                            if (++count >= 20) { sb.append("..."); break; }
                        }
                    }
                    return count == 0 ? "NO_ITEMS_VISIBLE" : sb.toString();
                });
                System.out.println("[ClaudeBot] BankWithdraw: search for '" + itemName + "' found nothing. Bank contains: " + visibleItems);
            }
            catch (Throwable t)
            {
                System.out.println("[ClaudeBot] BankWithdraw: debug failed: " + t.getMessage());
            }
            closeSearch(client, human, clientThread);
            return ActionResult.failure(ActionType.BANK_WITHDRAW,
                "Bank withdraw: item not found after search for " + itemName);
        }
        System.out.println("[ClaudeBot] BankWithdraw: search found '" + itemName + "' at " + itemPoint);
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        // Phase 2b: Withdraw the item.
        // Standard quantities (1/5/10) use CC_OP menu entries → client.menuAction().
        // Withdraw-All (id=7) and Withdraw-X (id=6) are CC_OP_LOW_PRIORITY, which
        // client.menuAction() silently ignores. For these, we use the same mechanism as
        // RuneLite's MenuEntrySwapper: promote to CC_OP + move to top in onPostMenuSort,
        // then left-click so the game's normal input pipeline processes the swapped entry.
        int withdrawId = getWithdrawIdentifier(qty);
        MenuAction nativeType = getWithdrawNativeType(qty);
        String withdrawOption = getWithdrawOption(qty);

        System.out.println("[ClaudeBot] BankWithdraw: option='" + withdrawOption
            + "' id=" + withdrawId + " nativeType=" + nativeType + " for '" + itemName + "'");

        // All bank operations use PostMenuSort menu swap + left-click.
        // client.menuAction() silently fails for bank widget entries (both CC_OP and
        // CC_OP_LOW_PRIORITY), so we use the same approach RuneLite's MenuEntrySwapper uses:
        // set the desired entry via BankMenuSwap, let onPostMenuSort promote it to the
        // left-click default, then click normally.
        System.out.println("[ClaudeBot] BankWithdraw: using menu swap for " + withdrawOption
            + " (id=" + withdrawId + ", type=" + nativeType + ")");

        BankMenuSwap.setPendingSwap(withdrawId, nativeType);
        try
        {
            human.moveMouse(itemPoint.x, itemPoint.y);
            human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());
            human.click();
        }
        finally
        {
            human.getTimingEngine().sleep(100);
            BankMenuSwap.clearPendingSwap();
        }

        // For Withdraw-X: dialog opens, type amount and Enter
        if (qty != -1 && qty != 1 && qty != 5 && qty != 10)
        {
            if (!waitForInputDialog(client, clientThread, human))
            {
                closeSearch(client, human, clientThread);
                return ActionResult.failure(ActionType.BANK_WITHDRAW,
                    "Bank withdraw: quantity dialog did not open for " + itemName);
            }
            human.typeText(String.valueOf(qty));
            human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());
            human.pressKey(KeyEvent.VK_ENTER);
        }

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
     */
    private static String getWithdrawOption(int quantity)
    {
        if (quantity == -1) return "Withdraw-All";
        if (quantity == 1) return "Withdraw-1";
        if (quantity == 5) return "Withdraw-5";
        if (quantity == 10) return "Withdraw-10";
        return "Withdraw-X";
    }

    /**
     * Returns the bank menu entry identifier for the given quantity.
     * These identifiers match the CC_OP/CC_OP_LOW_PRIORITY entries on bank item widgets.
     * Sourced from RuneLite's ShiftWithdrawMode enum.
     *
     * id=2: Withdraw-1 (CC_OP)
     * id=3: Withdraw-5 (CC_OP)
     * id=4: Withdraw-10 (CC_OP)
     * id=6: Withdraw-X dialog (CC_OP_LOW_PRIORITY) — opens quantity input
     * id=7: Withdraw-All (CC_OP_LOW_PRIORITY)
     */
    private static int getWithdrawIdentifier(int quantity)
    {
        if (quantity == 1) return 2;
        if (quantity == 5) return 3;
        if (quantity == 10) return 4;
        if (quantity == -1) return 7; // Withdraw-All
        return 6; // Withdraw-X dialog
    }

    /**
     * Returns the native MenuAction type for the given withdraw quantity.
     * Identifiers 2-5 are CC_OP (directly invokable via client.menuAction).
     * Identifiers 6+ are CC_OP_LOW_PRIORITY (need PostMenuSort swap + left-click).
     */
    private static MenuAction getWithdrawNativeType(int quantity)
    {
        if (quantity == 1 || quantity == 5 || quantity == 10)
        {
            return MenuAction.CC_OP;
        }
        return MenuAction.CC_OP_LOW_PRIORITY;
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
     * Clicks a bank quantity selector button (1/5/10/All).
     * Widget group 12, child IDs: 23=1, 25=5, 27=10, 29=X, 31=All
     */
    private static void clickQuantityButton(Client client, HumanSimulator human,
                                             ClientThread clientThread, int childId)
    {
        try
        {
            java.awt.Point btn = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget qtyBtn = client.getWidget(BANK_SEARCH_GROUP, childId);
                if (qtyBtn == null || qtyBtn.isHidden()) return null;
                java.awt.Rectangle bounds = qtyBtn.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
            if (btn != null)
            {
                human.moveAndClick(btn.x, btn.y);
                human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());
            }
            else
            {
                System.err.println("[ClaudeBot] BankWithdraw: quantity button not found (child=" + childId + ")");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankWithdraw: quantity button click failed: " + t.getMessage());
        }
    }

    /**
     * Counts coins (item ID 995) in the bank container.
     * Must be called on the client thread.
     */
    private static int countCoinsInBank(Client client)
    {
        net.runelite.api.ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank == null) return 0;
        net.runelite.api.Item[] items = bank.getItems();
        if (items == null) return 0;
        for (net.runelite.api.Item item : items)
        {
            if (item.getId() == COINS_ITEM_ID)
            {
                return item.getQuantity();
            }
        }
        return 0;
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

                    // Fallback: check bank search button
                    Widget searchInput = client.getWidget(InterfaceID.Bankmain.SEARCH);
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

    /**
     * Detects the quantity input dialog opening by watching VarClientInt 5 transitions.
     * When bank search is active, VarClientInt 5 is already non-zero, so we can't just
     * check != 0. Instead we detect: value → 0 → non-zero (search closed, dialog opened)
     * or value changing to a different non-zero number (direct transition).
     */
    private static boolean waitForInputDialog(Client client, ClientThread clientThread, HumanSimulator human)
    {
        int initialValue;
        try
        {
            initialValue = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getVarcIntValue(5));
        }
        catch (Throwable t) { initialValue = 0; }

        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        boolean sawZero = (initialValue == 0);

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                int v = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getVarcIntValue(5));
                if (v == 0)
                {
                    sawZero = true;
                }
                else if (sawZero)
                {
                    // 0 → non-zero: dialog opened after search closed
                    System.out.println("[ClaudeBot] BankWithdraw: input dialog open (VarcInt5=" + v + " after 0 transition)");
                    return true;
                }
                else if (initialValue != 0 && v != initialValue)
                {
                    // Value changed without going through 0: direct transition
                    System.out.println("[ClaudeBot] BankWithdraw: input dialog open (VarcInt5 " + initialValue + " → " + v + ")");
                    return true;
                }
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(50); // poll fast for transitions
        }
        System.err.println("[ClaudeBot] BankWithdraw: input dialog did not open (initial=" + initialValue + " sawZero=" + sawZero + ")");
        return false;
    }

    /**
     * Polls until the chatbox input closes (VarClientInt 5 == 0).
     * Used after pressing Enter on a quantity dialog to wait for it to dismiss.
     */
    private static void waitForInputClosed(Client client, ClientThread clientThread, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                int v = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getVarcIntValue(5));
                if (v == 0) return;
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
        }
    }
}
