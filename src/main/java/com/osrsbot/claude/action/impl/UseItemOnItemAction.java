package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;

public class UseItemOnItemAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 1800; // 3 game ticks

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils,
                                        ClientThread clientThread, BotAction action)
    {
        if (action.getItem1() == null || action.getItem1().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "No item1 name specified");
        }
        if (action.getItem2() == null || action.getItem2().isEmpty())
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "No item2 name specified");
        }

        // Ensure inventory tab is open before looking up items
        OpenTabAction.ensureTab(client, human, clientThread, "inventory");

        // Phase 1: Widget lookups on client thread + count total inventory for verification
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item1 = itemUtils.findInInventory(client, action.getItem1());
                if (item1 == null) return new Object[]{ "ITEM1_NOT_FOUND" };

                Widget item2 = itemUtils.findInInventory(client, action.getItem2());
                if (item2 == null) return new Object[]{ "ITEM2_NOT_FOUND" };

                java.awt.Point p1 = getWidgetCenter(item1);
                java.awt.Point p2 = getWidgetCenter(item2);

                int totalItems = countTotalInventoryItems(client);
                return new Object[]{ "OK", p1, p2, totalItems };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] UseItemOnItem lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Lookup failed: " + t.getMessage());
        }

        String status = (String) lookupData[0];
        if ("ITEM1_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item not found: " + action.getItem1());
        }
        if ("ITEM2_NOT_FOUND".equals(status))
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item not found: " + action.getItem2());
        }

        java.awt.Point p1 = (java.awt.Point) lookupData[1];
        java.awt.Point p2 = (java.awt.Point) lookupData[2];
        int totalBefore = (int) lookupData[3];

        if (p1 == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item 1 widget not visible");
        }
        if (p2 == null)
        {
            return ActionResult.failure(ActionType.USE_ITEM_ON_ITEM, "Item 2 widget not visible");
        }

        // Phase 2: Click sequence on background thread
        human.moveAndClick(p1.x, p1.y);
        human.shortPause();
        human.moveAndClick(p2.x, p2.y);

        // Phase 3: Wait for inventory to change (item consumed or transformed).
        // May timeout legitimately for slow actions (firemaking takes >2 ticks
        // for the log to actually convert) or when the click opens a Make
        // interface. Don't infer failure from no-change — let the
        // ActionExecutor's generic message-attribution wrap and the following
        // WAIT_ANIMATION provide the real success/failure signal.
        waitForInventoryChange(client, clientThread, totalBefore, human);

        return ActionResult.success(ActionType.USE_ITEM_ON_ITEM);
    }

    private static java.awt.Point getWidgetCenter(Widget widget)
    {
        java.awt.Rectangle bounds = widget.getBounds();
        if (bounds == null) return null;
        return new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }

    /**
     * Polls until total inventory item count changes or timeout.
     * @return true if the count changed before the deadline, false on timeout
     */
    private static boolean waitForInventoryChange(Client client, ClientThread clientThread,
                                                    int countBefore, HumanSimulator human)
    {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            human.getTimingEngine().sleep(VERIFY_POLL_MS);
            try
            {
                int current = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> countTotalInventoryItems(client));
                if (current != countBefore) return true;
            }
            catch (Throwable t) {}
        }
        return false;
    }

    /**
     * Count total number of occupied inventory slots.
     */
    private static int countTotalInventoryItems(Client client)
    {
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null) return 0;
        int count = 0;
        Widget[] children = inventory.getDynamicChildren();
        if (children == null) return 0;
        for (Widget child : children)
        {
            if (child.getItemId() > 0) count++;
        }
        return count;
    }
}
