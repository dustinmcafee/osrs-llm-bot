package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import com.osrsbot.claude.util.ItemUtils;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

public class EatFoodAction
{
    public static ActionResult execute(Client client, HumanSimulator human, ItemUtils itemUtils, ClientThread clientThread, BotAction action)
    {
        if (action.getName() == null || action.getName().isEmpty())
        {
            return ActionResult.failure(ActionType.EAT_FOOD, "No food name specified");
        }

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
                        return new Object[]{ null, action.getName() };
                    }
                }

                java.awt.Rectangle bounds = item.getBounds();
                if (bounds == null) return null;
                return new Object[]{ new java.awt.Point((int) bounds.getCenterX(), (int) bounds.getCenterY()), null };
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

        // Phase 2: Click on background thread
        human.moveAndClick(point.x, point.y);
        return ActionResult.success(ActionType.EAT_FOOD);
    }
}
