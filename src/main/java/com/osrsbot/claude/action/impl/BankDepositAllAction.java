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
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 1800;


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

        // Phase 3: Wait for inventory to empty
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                boolean empty = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                    Widget bankInv = client.getWidget(WidgetInfo.BANK_INVENTORY_ITEMS_CONTAINER);
                    if (bankInv == null) return true;
                    Widget[] children = bankInv.getDynamicChildren();
                    if (children == null) return true;
                    for (Widget child : children)
                    {
                        if (child.getItemId() > 0) return false;
                    }
                    return true;
                });
                if (empty) break;
            }
            catch (Throwable t)
            {
                // Ignore and retry
            }
        }

        // Wait one full game tick so the bank widget refreshes before the next bank operation
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.BANK_DEPOSIT_ALL);
    }
}
