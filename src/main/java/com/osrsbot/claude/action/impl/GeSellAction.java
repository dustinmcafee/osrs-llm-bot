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
import java.awt.event.KeyEvent;

/**
 * Sells an item on the Grand Exchange.
 *
 * All interactions go through HumanSimulator — the client sees natural
 * Bezier mouse curves, humanized click timing, and realistic typing.
 *
 * Steps:
 * 1. Check GE interface is open (widget group 465)
 * 2. Find an empty offer slot and click it
 * 3. Click the "Sell" button
 * 4. Find the item in the inventory panel within the GE and click it
 * 5. Set quantity if needed
 * 6. Click confirm
 */
public class GeSellAction
{
    private static final int GE_GROUP_ID = 465;
    private static final int GE_INV_GROUP_ID = 467; // GE inventory panel

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.GE_SELL, "No item name provided");
        }

        int quantity = action.getQuantity();
        if (quantity <= 0) quantity = 1;

        // Step 1: Find a sell slot in the GE
        Point sellButtonPoint;
        try
        {
            sellButtonPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden()) return null;

                // Search for sell offer button
                for (int childIdx = 0; childIdx < 30; childIdx++)
                {
                    Widget child = client.getWidget(GE_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String[] actions = child.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.equalsIgnoreCase("Create Sell offer"))
                            {
                                Rectangle bounds = child.getBounds();
                                if (bounds != null)
                                {
                                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                }
                            }
                        }
                    }

                    Widget[] dynChildren = child.getDynamicChildren();
                    if (dynChildren != null)
                    {
                        for (Widget dynChild : dynChildren)
                        {
                            if (dynChild == null || dynChild.isHidden()) continue;
                            String[] dynActions = dynChild.getActions();
                            if (dynActions != null)
                            {
                                for (String act : dynActions)
                                {
                                    if (act != null && act.equalsIgnoreCase("Create Sell offer"))
                                    {
                                        Rectangle bounds = dynChild.getBounds();
                                        if (bounds != null)
                                        {
                                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_SELL, "GE lookup failed: " + t.getMessage());
        }

        if (sellButtonPoint == null)
        {
            return ActionResult.failure(ActionType.GE_SELL,
                "GE not open or no empty sell slot found. Open the GE first with INTERACT_NPC.");
        }

        // Step 2: Click the sell slot (humanized)
        human.moveAndClick(sellButtonPoint.x, sellButtonPoint.y);
        human.shortPause();

        // Wait for inventory panel to appear
        human.getTimingEngine().sleep(human.getTimingEngine().nextShortPause());

        // Step 3: Find the item in the GE inventory panel and click it
        Point itemPoint;
        try
        {
            itemPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // Search the GE inventory panel
                Widget geInv = client.getWidget(GE_INV_GROUP_ID, 0);
                if (geInv == null || geInv.isHidden()) return null;

                Widget[] children = geInv.getDynamicChildren();
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
            return ActionResult.failure(ActionType.GE_SELL, "GE inventory lookup failed: " + t.getMessage());
        }

        if (itemPoint == null)
        {
            return ActionResult.failure(ActionType.GE_SELL, "Item not found in inventory: " + itemName);
        }

        // Click the item (humanized)
        human.moveAndClick(itemPoint.x, itemPoint.y);
        human.shortPause();

        // Wait for offer setup screen
        human.getTimingEngine().sleep(human.getTimingEngine().nextShortPause());

        // Step 4: Set quantity if not default
        if (quantity > 1)
        {
            setQuantity(client, human, clientThread, quantity);
        }

        // Step 5: Click confirm button
        Point confirmPoint;
        try
        {
            confirmPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                for (int childIdx = 0; childIdx < 50; childIdx++)
                {
                    Widget child = client.getWidget(GE_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String[] actions = child.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.equalsIgnoreCase("Confirm"))
                            {
                                Rectangle bounds = child.getBounds();
                                if (bounds != null)
                                {
                                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                }
                            }
                        }
                    }
                }
                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_SELL, "Confirm button lookup failed: " + t.getMessage());
        }

        if (confirmPoint != null)
        {
            human.moveAndClick(confirmPoint.x, confirmPoint.y);
            human.shortPause();
        }

        return ActionResult.success(ActionType.GE_SELL);
    }

    private static void setQuantity(Client client, HumanSimulator human,
                                    ClientThread clientThread, int quantity)
    {
        try
        {
            Point qtyPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                for (int childIdx = 0; childIdx < 50; childIdx++)
                {
                    Widget child = client.getWidget(GE_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String[] actions = child.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.equalsIgnoreCase("Enter quantity"))
                            {
                                Rectangle bounds = child.getBounds();
                                if (bounds != null)
                                {
                                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                }
                            }
                        }
                    }
                }
                return null;
            });

            if (qtyPoint != null)
            {
                human.moveAndClick(qtyPoint.x, qtyPoint.y);
                human.shortPause();
                human.typeText(String.valueOf(quantity));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE sell quantity set failed: " + t.getMessage());
        }
    }
}
