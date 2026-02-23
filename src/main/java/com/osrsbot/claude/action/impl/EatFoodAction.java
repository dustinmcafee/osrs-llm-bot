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

public class EatFoodAction
{
    private static final int VERIFY_POLL_MS = 100;
    private static final int VERIFY_TIMEOUT_MS = 2400; // 4 game ticks

    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.EAT_FOOD, "No food name specified");
        }

        // Ensure inventory tab is open before looking up items
        OpenTabAction.ensureTab(client, human, clientThread, "inventory");

        // Phase 1: Widget lookup + "Eat" action check on client thread
        Object[] lookupData;
        try
        {
            lookupData = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget item = itemUtils.findInInventory(client, action.getName());
                if (item == null) return null;

                // Check if item actually has an "Eat" or "Drink" action
                net.runelite.api.ItemComposition comp = client.getItemDefinition(item.getItemId());
                if (comp != null)
                {
                    String[] actions = comp.getInventoryActions();
                    boolean canEat = false;
                    if (actions != null)
                    {
                        for (String a : actions)
                        {
                            if (a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink")))
                            {
                                canEat = true;
                                break;
                            }
                        }
                    }
                    if (!canEat)
                    {
                        // Return special marker to indicate not edible
                        return new Object[]{ null, action.getName(), 0 };
                    }
                }

                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;

                int invCount = countInInventory(client, itemUtils, action.getName());
                return new Object[]{
                    new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                    null, invCount
                };
            });
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] EatFood lookup failed: " + t.getMessage());
            return ActionResult.failure(ActionType.EAT_FOOD, "Lookup failed: " + t.getMessage());
        }

        if (lookupData == null)
        {
            return ActionResult.failure(ActionType.EAT_FOOD, "Food not found: " + action.getName());
        }

        java.awt.Point point = (java.awt.Point) lookupData[0];
        if (point == null)
        {
            String itemName = lookupData[1] != null ? (String) lookupData[1] : action.getName();
            return ActionResult.failure(ActionType.EAT_FOOD,
                itemName + " is not edible (no Eat/Drink action). You may need to cook it first.");
        }

        int invCountBefore = (int) lookupData[2];

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);

        // Phase 3: Wait for the eat to process (item count decreases or transforms).
        // Without this, consecutive EAT_FOOD actions for the same food all find the
        // same slot and only the first eat succeeds. Also respects the 3-tick eat
        // cooldown — if the game can't eat yet, this will timeout and report failure.
        if (!waitForInventoryDecrease(client, clientThread, itemUtils, action.getName(), invCountBefore, human))
        {
            System.err.println("[ClaudeBot] EatFood: food count did not decrease for " + action.getName()
                + " (3-tick cooldown or eat failed)");
        }

        return ActionResult.success(ActionType.EAT_FOOD);
    }

    /**
     * Polls inventory until item count decreases (eat registered) or timeout.
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
     * Count how many of a food item are in the player's inventory.
     */
    private static int countInInventory(Client client, ItemUtils itemUtils, String name)
    {
        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null) return 0;
        int count = 0;
        Widget[] children = inventory.getDynamicChildren();
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
}
