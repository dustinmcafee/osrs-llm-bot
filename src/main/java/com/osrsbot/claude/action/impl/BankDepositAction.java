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

public class BankDepositAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks
    private static final int INPUT_TIMEOUT_MS = 2400; // 4 game ticks for chatbox input to appear


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

        // Phase 2: Right-click select on background thread
        int qty = action.getQuantity();
        if (qty == 0) qty = 1; // Default to 1 when no quantity specified
        String depositOption = getDepositOption(qty);
        boolean needsTyping = isCustomQuantity(qty);

        boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, depositOption, itemName);
        if (!selected)
        {
            return ActionResult.failure(ActionType.BANK_DEPOSIT, "Deposit option not found: " + depositOption);
        }

        // If custom quantity, wait for chatbox input dialog then type the number
        if (needsTyping)
        {
            if (!waitForChatboxInput(client, clientThread, human))
            {
                System.err.println("[ClaudeBot] BankDeposit: Deposit-X input dialog did not appear for " + itemName);
                return ActionResult.failure(ActionType.BANK_DEPOSIT,
                    "Deposit-X dialog did not appear for " + itemName);
            }
            human.typeText(String.valueOf(qty));
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
     * Returns the right-click menu option string for the given quantity.
     * OSRS bank menu options: Deposit-1, Deposit-5, Deposit-10, Deposit-All, Deposit-X
     */
    private static String getDepositOption(int quantity)
    {
        if (quantity == -1) return "Deposit-All";
        if (quantity == 1) return "Deposit-1";
        if (quantity == 5) return "Deposit-5";
        if (quantity == 10) return "Deposit-10";
        return "Deposit-X"; // any other quantity uses Deposit-X + type number
    }

    private static boolean isCustomQuantity(int quantity)
    {
        return quantity != -1 && quantity != 1 && quantity != 5 && quantity != 10;
    }

    /**
     * Polls until VarClientInt 5 (INPUT_TYPE) is non-zero, indicating the chatbox
     * is accepting text input (quantity dialogs, etc).
     */
    private static boolean waitForChatboxInput(Client client, ClientThread clientThread, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + INPUT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                boolean active = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getVarcIntValue(5) != 0);
                if (active) return true;
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
        }
        return false;
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
