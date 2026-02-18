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

public class BankWithdrawAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        // Phase 1: Widget lookup on client thread
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                if (client.getItemContainer(InventoryID.BANK) == null)
                {
                    return new Object[]{ "BANK_NOT_OPEN" };
                }

                Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
                if (bankContainer == null)
                {
                    return new Object[]{ "NO_BANK_WIDGET" };
                }

                Widget item = itemUtils.findInWidget(bankContainer, action.getName());
                if (item == null)
                {
                    return new Object[]{ "ITEM_NOT_FOUND" };
                }

                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null)
                {
                    return new Object[]{ "NO_BOUNDS" };
                }

                return new Object[]{ "OK", new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()) };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankWithdraw lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if (!"OK".equals(status))
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Bank withdraw: " + status + " for " + action.getName());
        }

        java.awt.Point point = (java.awt.Point) lookupData[1];

        // Phase 2: Right-click select on background thread
        int qty = action.getQuantity();
        if (qty == 0) qty = 1; // Default to 1 when no quantity specified
        String withdrawOption = getWithdrawOption(qty);
        boolean needsTyping = isCustomQuantity(qty);

        boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, withdrawOption, action.getName());
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
            human.shortPause();
        }

        return ActionResult.success(ActionType.BANK_WITHDRAW);
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
