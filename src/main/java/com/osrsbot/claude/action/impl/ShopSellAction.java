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
 * Sells an item to an NPC shop.
 *
 * Phase 1 (client thread): Find the item in the player's inventory panel
 * within the shop view (group 301, child 0).
 * Phase 2 (background thread): Move mouse to the item for natural behavior.
 * Phase 3 (client thread): Read real menu entries and fire the matching "Sell X"
 * action via client.menuAction() — avoids fragile right-click menu pixel clicking.
 */
public class ShopSellAction
{
    private static final int SHOP_INV_GROUP_ID = 301;
    private static final int SHOP_INV_CHILD = 0;

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.SHOP_SELL, "No item name provided");
        }

        int quantity = action.getQuantity();
        if (quantity <= 0) quantity = 1;

        // Phase 1: Find item in shop inventory widget on client thread
        Point itemPoint;
        try
        {
            itemPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget shopInv = client.getWidget(SHOP_INV_GROUP_ID, SHOP_INV_CHILD);
                if (shopInv == null || shopInv.isHidden()) return null;

                Widget[] children = shopInv.getDynamicChildren();
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
            return ActionResult.failure(ActionType.SHOP_SELL, "Shop inventory lookup failed: " + t.getMessage());
        }

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.SHOP_SELL, "Item not found in inventory: " + itemName);
        }

        // Phase 2: Move mouse to item on background thread (humanized)
        human.moveMouse(itemPoint.x, itemPoint.y);
        human.shortPause();

        // Phase 3: Fire menu action using real menu entries from the game.
        // This avoids fragile right-click menu pixel clicking.
        String sellOption = getSellOption(quantity);
        String fItemName = itemName;

        System.out.println("[ClaudeBot] ShopSell: option=" + sellOption + " for '" + fItemName + "'");

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

                        if (entryOption.equalsIgnoreCase(sellOption))
                        {
                            match = entry;
                            break;
                        }
                        // Track "Sell 1" as fallback
                        if (entryOption.equalsIgnoreCase("Sell 1"))
                        {
                            fallback = entry;
                        }
                    }
                }

                MenuEntry chosen = match != null ? match : fallback;
                if (chosen != null)
                {
                    System.out.println("[ClaudeBot] ShopSell: using menu entry: " + chosen.getOption()
                        + " target=" + chosen.getTarget());
                    client.menuAction(
                        chosen.getParam0(), chosen.getParam1(),
                        chosen.getType(), chosen.getIdentifier(),
                        -1, chosen.getOption(), chosen.getTarget()
                    );
                }
                else
                {
                    System.err.println("[ClaudeBot] ShopSell: no matching menu entry for '" + sellOption + "' on '" + fItemName + "'");
                }
            }
            catch (Throwable t)
            {
                System.err.println("[ClaudeBot] ShopSell menuAction FAILED: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
        });

        // Wait one game tick so shop/inventory refreshes before next action
        human.getTimingEngine().sleep(human.getTimingEngine().nextTickDelay());

        return ActionResult.success(ActionType.SHOP_SELL);
    }

    private static String getSellOption(int quantity)
    {
        if (quantity >= 50) return "Sell 50";
        if (quantity >= 10) return "Sell 10";
        if (quantity >= 5) return "Sell 5";
        return "Sell 1";
    }
}
