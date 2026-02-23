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

public class BankDepositAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks

    // Bank quantity selector buttons (group 12) — same buttons control both withdraw and deposit
    private static final int BANK_QTY_GROUP = 12;

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

        // Phase 2: Set quantity mode and click the item to deposit.
        // For standard amounts (1, 5, 10, All) we click the mode button once then the item.
        // For custom amounts (e.g. 14, 22) we decompose into standard amounts and click
        // the item multiple times, avoiding the unreliable Deposit-X dialog entirely.
        int qty = action.getQuantity();
        if (qty == 0) qty = 1;
        boolean needsDecomposition = isCustomQuantity(qty);

        System.out.println("[ClaudeBot] BankDeposit: qty=" + qty + " for '" + itemName + "'");

        if (!needsDecomposition)
        {
            // Standard quantity: one mode click + one item click
            int qtyButtonChild = getQuantityButtonChild(qty);
            clickQuantityButton(client, human, clientThread, qtyButtonChild);
            point = refindItemInInventory(client, clientThread, itemUtils, itemName, point);
            human.moveAndClick(point.x, point.y);
        }
        else
        {
            // Custom quantity: decompose into 10s, 5s, and 1s
            System.out.println("[ClaudeBot] BankDeposit: decomposing qty=" + qty + " into standard amounts");
            int remaining = qty;

            // Deposit-10 passes
            if (remaining >= 10)
            {
                clickQuantityButton(client, human, clientThread, 27); // child 27 = "10"
                point = refindItemInInventory(client, clientThread, itemUtils, itemName, point);
                while (remaining >= 10)
                {
                    human.moveAndClick(point.x, point.y);
                    human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());
                    remaining -= 10;
                }
            }
            // Deposit-5 pass
            if (remaining >= 5)
            {
                clickQuantityButton(client, human, clientThread, 25); // child 25 = "5"
                point = refindItemInInventory(client, clientThread, itemUtils, itemName, point);
                human.moveAndClick(point.x, point.y);
                human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());
                remaining -= 5;
            }
            // Deposit-1 passes
            if (remaining > 0)
            {
                clickQuantityButton(client, human, clientThread, 23); // child 23 = "1"
                point = refindItemInInventory(client, clientThread, itemUtils, itemName, point);
                while (remaining > 0)
                {
                    human.moveAndClick(point.x, point.y);
                    if (remaining > 1) human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());
                    remaining--;
                }
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

    private static boolean isCustomQuantity(int quantity)
    {
        return quantity != -1 && quantity != 1 && quantity != 5 && quantity != 10;
    }

    private static void clickQuantityButton(Client client, HumanSimulator human,
                                             ClientThread clientThread, int childId)
    {
        try
        {
            java.awt.Point btn = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget qtyBtn = client.getWidget(BANK_QTY_GROUP, childId);
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
                System.err.println("[ClaudeBot] BankDeposit: quantity button not found (child=" + childId + ")");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankDeposit: quantity button click failed: " + t.getMessage());
        }
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
     * Handles the timing gap when open-bank and deposit are batched together.
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
