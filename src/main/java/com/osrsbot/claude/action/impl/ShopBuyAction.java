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
 * Buys an item from an NPC shop interface.
 *
 * Phase 1 (client thread): Find the item in the shop widget (group 300, child 16).
 * Phase 2 (background thread): Right-click the item and select the appropriate
 * "Buy X" option via MenuInteractor — all through HumanSimulator for natural behavior.
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

        // Phase 2: Right-click and select buy option via humanized interaction
        String buyOption = getBuyOption(quantity);
        boolean selected = human.moveAndRightClickSelect(client, itemPoint.x, itemPoint.y, buyOption, itemName);

        if (!selected)
        {
            // Fallback: try "Buy 1" if the specific quantity wasn't available
            if (!"Buy 1".equals(buyOption))
            {
                selected = human.moveAndRightClickSelect(client, itemPoint.x, itemPoint.y, "Buy 1", itemName);
            }
        }

        if (!selected)
        {
            return ActionResult.failure(ActionType.SHOP_BUY, "Buy option not found for: " + itemName);
        }

        human.shortPause();
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
