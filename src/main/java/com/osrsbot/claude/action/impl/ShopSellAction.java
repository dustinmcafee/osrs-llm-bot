package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
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
 * Phase 2 (background thread): Right-click and select "Sell X" via
 * humanized MenuInteractor interaction.
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

        // Phase 2: Right-click and select sell option via humanized interaction
        String sellOption = getSellOption(quantity);
        boolean selected = human.moveAndRightClickSelect(client, itemPoint.x, itemPoint.y, sellOption, itemName);

        if (!selected)
        {
            // Fallback: try "Sell 1"
            if (!"Sell 1".equals(sellOption))
            {
                selected = human.moveAndRightClickSelect(client, itemPoint.x, itemPoint.y, "Sell 1", itemName);
            }
        }

        if (!selected)
        {
            return ActionResult.failure(ActionType.SHOP_SELL, "Sell option not found for: " + itemName);
        }

        human.shortPause();
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
