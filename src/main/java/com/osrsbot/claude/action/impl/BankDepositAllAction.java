package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

/**
 * Clicks the "Deposit inventory" button in the bank interface to deposit all items at once.
 */
public class BankDepositAllAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ClientThread clientThread)
    {
        // Phase 1: Find deposit-all button on client thread
        java.awt.Point point;
        try
        {
            point = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                if (client.getItemContainer(InventoryID.BANK) == null)
                {
                    return null;
                }

                Widget depositBtn = client.getWidget(WidgetInfo.BANK_DEPOSIT_INVENTORY);
                if (depositBtn == null || depositBtn.isHidden()) return null;

                java.awt.Rectangle bounds = depositBtn.getBounds();
                if (bounds == null) return null;
                return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] BankDepositAll lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.BANK_DEPOSIT_ALL, "Lookup failed: " + t.getMessage());
        }

        if (point == null)
        {
            return ActionResult.failure(ActionType.BANK_DEPOSIT_ALL, "Bank not open or deposit button not found");
        }

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.BANK_DEPOSIT_ALL);
    }
}
