package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Buys an item from an NPC shop interface.
 *
 * Phase 1 (client thread): Find the item in the shop widget (group 300, child 16).
 * Phase 2 (background thread): Move mouse to the item for natural behavior.
 * Phase 3 (client thread): Read real menu entries and fire the matching "Buy X"
 * action via client.menuAction() — avoids fragile right-click menu pixel clicking.
 */
public class ShopBuyAction
{
    private static final int SHOP_GROUP_ID = 300;
    private static final int SHOP_ITEMS_CHILD = 16;

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.SHOP_BUY, "No item name provided");
        }

        int quantity = action.getQuantity();
        if (quantity <= 0) quantity = 1;

        // Phase 1: Find item in shop widget on client thread
        Point itemPoint;
        try
        {
            itemPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget shopItems = client.getWidget(SHOP_GROUP_ID, SHOP_ITEMS_CHILD);
                if (shopItems == null || shopItems.isHidden()) return null;

                Widget[] children = shopItems.getDynamicChildren();
                if (children == null) return null;

                for (Widget child : children)
                {
                    if (child == null || child.getItemId() <= 0) continue;

                    net.runelite.api.ItemComposition comp = itemManager.getItemComposition(child.getItemId());
                    if (comp == null) continue;
                    String name = comp.getName();
                    if (name != null && name.equalsIgnoreCase(itemName))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.SHOP_BUY, "Shop lookup failed: " + t.getMessage());
        }

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.SHOP_BUY, "Item not found in shop: " + itemName);
        }

        // Phase 2: Move mouse to item on background thread (humanized)
        human.moveMouse(itemPoint.x, itemPoint.y);
        human.shortPause();

        // Phase 3: Fire menu action using real menu entries from the game.
        // This avoids fragile right-click menu pixel clicking.
        String buyOption = getBuyOption(quantity);
        String fItemName = itemName;

        System.out.println("[ClaudeBot] ShopBuy: option=" + buyOption + " for '" + fItemName + "'");

        clientThread.invokeLater(() -> {
            try
            {
                MenuEntry[] entries = client.getMenuEntries();
                MenuEntry match = null;
                MenuEntry fallback = null;

                if (entries != null)
                {
                    for (MenuEntry entry : entries)
                    {
                        String entryOption = entry.getOption();
                        String entryTarget = entry.getTarget();
                        if (entryOption == null || entryTarget == null) continue;
                        if (!entryTarget.toLowerCase().contains(fItemName.toLowerCase())) continue;

                        if (entryOption.equalsIgnoreCase(buyOption))
                        {
                            match = entry;
                            break;
                        }
                        // Track "Buy 1" as fallback
                        if (entryOption.equalsIgnoreCase("Buy 1"))
                        {
                            fallback = entry;
                        }
                    }
                }

                MenuEntry chosen = match != null ? match : fallback;
                if (chosen != null)
                {
                    System.out.println("[ClaudeBot] ShopBuy: using menu entry: " + chosen.getOption()
                        + " target=" + chosen.getTarget());
                    client.menuAction(
                        chosen.getParam0(), chosen.getParam1(),
                        chosen.getType(), chosen.getIdentifier(),
                        -1, chosen.getOption(), chosen.getTarget()
                    );
                }
                else
                {
                    System.err.println("[ClaudeBot] ShopBuy: no matching menu entry for '" + buyOption + "' on '" + fItemName + "'");
                }
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] ShopBuy menuAction FAILED: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        });

        // Wait one game tick so shop stock/inventory refreshes before next action
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.SHOP_BUY);
    }

    private static String getBuyOption(int quantity)
    {
        if (quantity >= 50) return "Buy 50";
        if (quantity >= 10) return "Buy 10";
        if (quantity >= 5) return "Buy 5";
        return "Buy 1";
    }
}
