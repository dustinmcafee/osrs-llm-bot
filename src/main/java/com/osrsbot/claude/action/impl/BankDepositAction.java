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

        // Phase 2: Deposit the item.
        // Standard quantities (1/5/10) use CC_OP entries → client.menuAction().
        // Deposit-All (id=8) and Deposit-X (id=6) are CC_OP_LOW_PRIORITY →
        // use PostMenuSort menu swap (same as RuneLite's MenuEntrySwapper).
        int depositId = getDepositIdentifier(qty);
        MenuAction nativeType = getDepositNativeType(qty);
        String depositOption = getDepositOption(qty);

        System.out.println("[ClaudeBot] BankDeposit: option='" + depositOption
            + "' id=" + depositId + " nativeType=" + nativeType + " for '" + itemName + "'");

        if (nativeType == MenuAction.CC_OP)
        {
            // Standard quantity (1/5/10): fire directly via menuAction
            human.moveMouse(point.x, point.y);
            human.getTimingEngine().sleep(human.getTimingEngine().nextClickDelay());

            boolean actionFired;
            try
            {
                final int targetId = depositId;
                actionFired = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    net.runelite.api.MenuEntry[] entries = client.getMenuEntries();
                    if (entries == null) return false;
                    for (net.runelite.api.MenuEntry entry : entries)
                    {
                        if (entry.getType() == MenuAction.CC_OP
                            && entry.getIdentifier() == targetId)
                        {
                            System.out.println("[ClaudeBot] BankDeposit: firing " + depositOption
                                + " via menuAction (CC_OP id=" + targetId + ")");
                            client.menuAction(
                                entry.getParam0(), entry.getParam1(),
                                MenuAction.CC_OP, entry.getIdentifier(),
                                -1, entry.getOption(), entry.getTarget()
                            );
                            return true;
                        }
                    }
                    System.err.println("[ClaudeBot] BankDeposit: CC_OP id=" + targetId + " not found. Entries:");
                    for (net.runelite.api.MenuEntry e : entries)
                    {
                        System.err.println("[ClaudeBot]   " + e.getOption() + " type=" + e.getType()
                            + " id=" + e.getIdentifier());
                    }
                    return false;
                });
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] BankDeposit: menuAction failed: " + t.getMessage());
                actionFired = false;
            }

            if (!actionFired)
            {
                return ActionResult.failure(ActionType.BANK_DEPOSIT,
                    "Bank deposit: " + depositOption + " (id=" + depositId + ") not found for " + itemName);
            }
        }
        else
        {
            // CC_OP_LOW_PRIORITY entry (Deposit-All id=8, Deposit-X id=6):
            // Use PostMenuSort menu swap — same mechanism as RuneLite's MenuEntrySwapper.
            System.out.println("[ClaudeBot] BankDeposit: using menu swap for " + depositOption
                + " (id=" + depositId + ", CC_OP_LOW_PRIORITY)");

            BankMenuSwap.setPendingSwap(depositId, MenuAction.CC_OP_LOW_PRIORITY);
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
            if (qty != -1)
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
     * Returns the bank menu entry identifier for the given deposit quantity.
     * Sourced from RuneLite's ShiftDepositMode enum (standard bank column).
     *
     * id=2: Deposit-1 (CC_OP)     — identifier column in ShiftDepositMode: 3 for standard bank
     * id=3: Deposit-5 (CC_OP)
     * id=4: Deposit-10 (CC_OP)
     * id=6: Deposit-X dialog (CC_OP_LOW_PRIORITY)
     * id=8: Deposit-All (CC_OP_LOW_PRIORITY)
     *
     * Note: ShiftDepositMode identifiers (3/4/5/6/8) are for the deposit box.
     * Bank inventory panel uses identifiers 2/3/4/6/8 based on live testing.
     */
    private static int getDepositIdentifier(int quantity)
    {
        if (quantity == 1) return 2;
        if (quantity == 5) return 3;
        if (quantity == 10) return 4;
        if (quantity == -1) return 8; // Deposit-All
        return 6; // Deposit-X dialog
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
     * Polls until the chatbox accepts input (VarClientInt 5 != 0).
     * For deposits, no search was active, so any non-zero value means the dialog opened.
     */
    private static boolean waitForQuantityDialog(Client client, ClientThread clientThread, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        int polls = 0;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                int inputType = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getVarcIntValue(5));
                if (inputType != 0)
                {
                    System.out.println("[ClaudeBot] BankDeposit: quantity dialog detected (VarcInt5=" + inputType + " polls=" + polls + ")");
                    return true;
                }
            }
            catch (Throwable t) {}
            polls++;
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
        }
        System.err.println("[ClaudeBot] BankDeposit: quantity dialog timed out after " + polls + " polls");
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
}
