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
        String withdrawOption = action.getQuantity() == -1 ? "Withdraw-All" : "Withdraw-" + action.getQuantity();
        boolean selected = human.moveAndRightClickSelect(client, point.x, point.y, withdrawOption, action.getName());
        if (!selected)
        {
            return ActionResult.failure(ActionType.BANK_WITHDRAW, "Withdraw option not found: " + withdrawOption);
        }

        return ActionResult.success(ActionType.BANK_WITHDRAW);
    }
}
