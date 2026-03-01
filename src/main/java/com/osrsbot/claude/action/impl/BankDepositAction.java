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
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

import java.awt.event.KeyEvent;

public class BankDepositAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks
    private static final int BANK_SEARCH_GROUP = 12;
    private static final int BANK_SEARCH_CHILD = 36;

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Resolve item name: Claude may send it as "item" or "name"
        String itemName = action.getItem() != null ? action.getItem() : action.getName();
        if (itemName == null)
        {
            return ActionResult.failure(ActionType.BANK_DEPOSIT, "Bank deposit: no item name specified");
        }

        // Wait for bank to be open (handles batched open + deposit)
        if (!waitForBankOpen(client, clientThread, human))
        {
            return ActionResult.failure(ActionType.BANK_DEPOSIT, "Bank deposit: BANK_NOT_OPEN for " + itemName);
        }

        // Close bank search if active (left over from a previous BANK_WITHDRAW)
        closeSearchIfActive(client, human, clientThread);

        // Phase 1: Widget lookup on client thread — count current inventory qty for verification
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                if (client.getItemContainer(InventoryID.BANK) == null)
                {
                    return new Object[]{ "BANK_NOT_OPEN" };
                }

                Widget bankInventory = client.getWidget(WidgetInfo.BANK_INVENTORY_ITEMS_CONTAINER);
                if (bankInventory == null)
                {
                    return new Object[]{ "NO_BANK_WIDGET" };
                }

                Widget item = itemUtils.findInWidget(bankInventory, itemName);
                if (item == null)
                {
                    return new Object[]{ "ITEM_NOT_FOUND" };
                }

                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null)
                {
                    return new Object[]{ "NO_BOUNDS" };
                }

                // Count how many of this item are in inventory for verification
                int invCount = countInInventory(client, itemUtils, itemName);

                return new Object[]{ "OK",
                    new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                    invCount };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankDeposit lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.BANK_DEPOSIT, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if (!"OK".equals(status))
        {
            return ActionResult.failure(ActionType.BANK_DEPOSIT, "Bank deposit: " + status + " for " + itemName);
        }

        java.awt.Point point = (java.awt.Point) lookupData[1];
        int invCountBefore = (int) lookupData[2];

        int qty = action.getQuantity();
        if (qty == 0) qty = 1;

        // Re-find item position in case the bank re-rendered
        point = refindItemInInventory(client, clientThread, itemUtils, itemName, point);

        // Phase 2: Deposit via PostMenuSort menu swap + left-click
        int depositId = getDepositIdentifier(qty);
        MenuAction nativeType = getDepositNativeType(qty);
        String depositOption = getDepositOption(qty);

        System.out.println("[ClaudeBot] BankDeposit: option='" + depositOption
            + "' id=" + depositId + " nativeType=" + nativeType + " for '" + itemName + "'");

        // All bank operations use PostMenuSort menu swap + left-click.
        // client.menuAction() silently fails for bank widget entries, so we use the
        // same approach as RuneLite's MenuEntrySwapper for all quantities.
        System.out.println("[ClaudeBot] BankDeposit: using menu swap for " + depositOption
            + " (id=" + depositId + ", type=" + nativeType + ")");

        BankMenuSwap.setPendingSwap(depositId, nativeType);
        try
        {
            human.moveMouse(point.x, point.y);
            human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());
            human.click();
        }
        finally
        {
            human.getTimingEngine().sleep(100);
            BankMenuSwap.clearPendingSwap();
        }

        // For Deposit-X: dialog opens, type amount and Enter
        if (qty != -1 && qty != 1 && qty != 5 && qty != 10)
        {
            if (!waitForQuantityDialog(client, clientThread, human))
            {
                return ActionResult.failure(ActionType.BANK_DEPOSIT,
                    "Bank deposit: quantity dialog did not open for " + itemName);
            }
            human.typeText(String.valueOf(qty));
            human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());
            human.pressKey(KeyEvent.VK_ENTER);
        }

        // Phase 3: Wait for the deposit to actually complete (item leaves inventory)
        if (!waitForInventoryDecrease(client, clientThread, itemUtils, itemName, invCountBefore, human))
        {
            System.err.println("[ClaudeBot] BankDeposit: item may not have been deposited for " + itemName);
            return ActionResult.failure(ActionType.BANK_DEPOSIT,
                "Deposit clicked but " + itemName + " count did not decrease — deposit may have failed");
        }

        // Wait one full game tick so the bank widget refreshes before the next bank operation
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.BANK_DEPOSIT);
    }

    /**
     * Returns the right-click menu option string for the given deposit quantity.
     */
    private static String getDepositOption(int quantity)
    {
        if (quantity == -1) return "Deposit-All";
        if (quantity == 1) return "Deposit-1";
        if (quantity == 5) return "Deposit-5";
        if (quantity == 10) return "Deposit-10";
        return "Deposit-X";
    }

    /**
     * Returns the bank inventory menu entry identifier for the given deposit quantity.
     * From live menu entry dump:
     *   Deposit-1:   id=3 (CC_OP)
     *   Deposit-5:   id=4 (CC_OP)
     *   Deposit-10:  id=5 (CC_OP)
     *   Deposit-X:   id=7 (CC_OP_LOW_PRIORITY)
     *   Deposit-All: id=8 (CC_OP_LOW_PRIORITY)
     * Note: id=6 is "Deposit-N" (last-used custom amount), NOT Deposit-X dialog.
     */
    private static int getDepositIdentifier(int quantity)
    {
        if (quantity == 1) return 3;
        if (quantity == 5) return 4;
        if (quantity == 10) return 5;
        if (quantity == -1) return 8; // Deposit-All
        return 7; // Deposit-X dialog
    }

    /**
     * Returns the native MenuAction type for the given deposit quantity.
     * Identifiers 2-5 are CC_OP, identifiers 6+ are CC_OP_LOW_PRIORITY.
     */
    private static MenuAction getDepositNativeType(int quantity)
    {
        if (quantity == 1 || quantity == 5 || quantity == 10)
        {
            return MenuAction.CC_OP;
        }
        return MenuAction.CC_OP_LOW_PRIORITY;
    }

    /**
     * Detects the quantity input dialog opening by watching VarClientInt 5 transitions.
     * VarcInt5 may be stale (nonzero) from a previous bank search or withdraw-X,
     * so we detect transitions: 0→nonzero or value change, not just nonzero.
     */
    private static boolean waitForQuantityDialog(Client client, ClientThread clientThread, HumanSimulator human)
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
                    System.out.println("[ClaudeBot] BankDeposit: quantity dialog open (VarcInt5=" + v + " after 0 transition)");
                    return true;
                }
                else if (initialValue != 0 && v != initialValue)
                {
                    System.out.println("[ClaudeBot] BankDeposit: quantity dialog open (VarcInt5 " + initialValue + " -> " + v + ")");
                    return true;
                }
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(50);
        }
        System.err.println("[ClaudeBot] BankDeposit: quantity dialog timed out (initial VarcInt5=" + initialValue + ")");
        return false;
    }

    /**
     * Polls inventory until item count decreases (deposit registered) or timeout.
     */
    private static boolean waitForInventoryDecrease(Client client, ClientThread clientThread,
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
                if (current < countBefore)
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
     * Count how many of an item are in the bank's inventory panel.
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
     * Re-finds the item position in the bank inventory panel. Falls back to previous point.
     */
    private static java.awt.Point refindItemInInventory(Client client, ClientThread clientThread,
                                                         ItemUtils itemUtils, String itemName,
                                                         java.awt.Point fallback)
    {
        try
        {
            java.awt.Point fresh = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget bankInventory = client.getWidget(WidgetInfo.BANK_INVENTORY_ITEMS_CONTAINER);
                if (bankInventory == null) return null;
                Widget item = itemUtils.findInWidget(bankInventory, itemName);
                if (item == null) return null;
                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
            return fresh != null ? fresh : fallback;
        }
        catch (Throwable t)
        {
            return fallback;
        }
    }

    /**
     * Polls until the bank interface is open, up to timeout.
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
     * If bank search is active (VarcInt5 != 0), click the search button to close it
     * and wait for VarcInt5 to return to 0. This prevents stale search state from
     * interfering with Deposit-X dialog detection.
     */
    private static void closeSearchIfActive(Client client, HumanSimulator human, ClientThread clientThread)
    {
        try
        {
            int v = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getVarcIntValue(5));
            if (v == 0) return;

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
                // Wait for VarcInt5 to return to 0
                long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline)
                {
                    human.getTimingEngine().sleep(50);
                    int cur = ClientThreadRunner.runOnClientThread(clientThread, () -> client.getVarcIntValue(5));
                    if (cur == 0) return;
                }
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankDeposit: closeSearch failed: " + t.getMessage());
        }
    }
}
