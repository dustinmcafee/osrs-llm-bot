package com.osrsbot.claude.action.impl;

import com.osrsbot.claude.action.ActionResult;
import com.osrsbot.claude.action.ActionType;
import com.osrsbot.claude.action.BotAction;
import com.osrsbot.claude.human.HumanSimulator;
import com.osrsbot.claude.util.ClientThreadRunner;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;

/**
 * Buys an item on the Grand Exchange.
 *
 * This is the most complex action — involves multiple sequential UI interactions,
 * all performed through HumanSimulator to look like a human clicking through the GE.
 *
 * Steps (all via humanized mouse movement and clicks):
 * 1. Check GE interface is open (widget group 465)
 * 2. Find an empty offer slot and click it
 * 3. Click the "Buy" button
 * 4. Type the item name into the search box (humanized typing)
 * 5. Click the matching search result
 * 6. Set quantity if needed (click +/- buttons or type)
 * 7. Set price if specified (click price input, type amount)
 * 8. Click confirm
 */
public class GeBuyAction
{
    private static final int GE_GROUP_ID = 465;
    private static final int GE_SEARCH_GROUP_ID = 162; // chatbox search results

    public static ActionResult execute(Client client, HumanSimulator human,
                                       ClientThread clientThread, BotAction action)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.GE_BUY, "No item name provided");
        }

        int quantity = action.getQuantity();
        if (quantity <= 0) quantity = 1;

        int price = action.getX(); // price is stored in the x field per spec

        // Step 1: Check GE is open and find an empty buy slot
        Point buyButtonPoint;
        try
        {
            buyButtonPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden()) return null;

                // Look for the buy button in GE offer setup
                // Child 7 is typically the buy button area
                // Search for a widget with "Buy" action
                for (int childIdx = 0; childIdx < 30; childIdx++)
                {
                    Widget child = client.getWidget(GE_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String[] actions = child.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.equalsIgnoreCase("Create Buy offer"))
                            {
                                Rectangle bounds = child.getBounds();
                                if (bounds != null)
                                {
                                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                                }
                            }
                        }
                    }

                    // Check dynamic children for buy offer slots
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
                                    if (act != null && act.equalsIgnoreCase("Create Buy offer"))
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
            return ActionResult.failure(ActionType.GE_BUY, "GE lookup failed: " + t.getMessage());
        }

        if (buyButtonPoint == null)
        {
            return ActionResult.failure(ActionType.GE_BUY,
                "GE not open or no empty buy slot found. Open the GE first with INTERACT_NPC.");
        }

        // Step 2: Click the buy slot (humanized)
        human.moveAndClick(buyButtonPoint.x, buyButtonPoint.y);
        human.shortPause();

        // Brief wait for search interface to appear
        human.getTimingEngine().sleep(human.getTimingEngine().nextShortPause());

        // Step 3: Type the item name into the search box (humanized typing)
        human.typeText(itemName);

        // Wait for search results to populate
        human.getTimingEngine().sleep(1000 + (int)(Math.random() * 500));

        // Step 4: Find and click the matching search result
        Point resultPoint;
        try
        {
            resultPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // Search results appear in the chatbox search area (group 162)
                Widget searchResults = client.getWidget(GE_SEARCH_GROUP_ID, 0);
                if (searchResults == null) return null;

                // Search through children for matching item
                for (int childIdx = 0; childIdx < 50; childIdx++)
                {
                    Widget child = client.getWidget(GE_SEARCH_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    // Check widget name and text
                    String text = child.getText();
                    String name = child.getName();
                    String stripped = null;

                    if (text != null)
                    {
                        stripped = text.replaceAll("<[^>]+>", "").trim();
                    }
                    if (stripped == null && name != null)
                    {
                        stripped = name.replaceAll("<[^>]+>", "").trim();
                    }

                    if (stripped != null && stripped.equalsIgnoreCase(itemName))
                    {
                        Rectangle bounds = child.getBounds();
                        if (bounds != null)
                        {
                            return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                        }
                    }

                    // Check dynamic children
                    Widget[] dynChildren = child.getDynamicChildren();
                    if (dynChildren != null)
                    {
                        for (Widget dynChild : dynChildren)
                        {
                            if (dynChild == null || dynChild.isHidden()) continue;
                            String dynText = dynChild.getText();
                            String dynName = dynChild.getName();
                            String dynStripped = null;
                            if (dynText != null) dynStripped = dynText.replaceAll("<[^>]+>", "").trim();
                            if (dynStripped == null && dynName != null) dynStripped = dynName.replaceAll("<[^>]+>", "").trim();

                            if (dynStripped != null && dynStripped.equalsIgnoreCase(itemName))
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
                return null;
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_BUY, "Search result lookup failed: " + t.getMessage());
        }

        if (resultPoint != null)
        {
            human.moveAndClick(resultPoint.x, resultPoint.y);
            human.shortPause();
        }
        else
        {
            // Try pressing enter to select the first/only result
            human.pressKey(KeyEvent.VK_ENTER);
            human.shortPause();
        }

        // Wait for offer setup screen to appear
        human.getTimingEngine().sleep(human.getTimingEngine().nextShortPause());

        // Step 5: Set quantity if not default (1)
        if (quantity > 1)
        {
            setQuantity(client, human, clientThread, quantity);
        }

        // Step 6: Set price if specified
        if (price > 0)
        {
            setPrice(client, human, clientThread, price);
        }

        // Step 7: Click confirm button
        Point confirmPoint;
        try
        {
            confirmPoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                // Search for confirm button in GE interface
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
            return ActionResult.failure(ActionType.GE_BUY, "Confirm button lookup failed: " + t.getMessage());
        }

        if (confirmPoint != null)
        {
            human.moveAndClick(confirmPoint.x, confirmPoint.y);
            human.shortPause();
        }

        return ActionResult.success(ActionType.GE_BUY);
    }

    /**
     * Sets the quantity in the GE offer by finding and clicking the quantity input,
     * then typing the number — just like a human would.
     */
    private static void setQuantity(Client client, HumanSimulator human,
                                    ClientThread clientThread, int quantity)
    {
        try
        {
            // Find the quantity input widget and click it
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
            System.err.println("[ClaudeBot] GE quantity set failed: " + t.getMessage());
        }
    }

    /**
     * Sets the price in the GE offer by finding and clicking the price input,
     * then typing the amount — just like a human would.
     */
    private static void setPrice(Client client, HumanSimulator human,
                                 ClientThread clientThread, int price)
    {
        try
        {
            Point pricePoint = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                for (int childIdx = 0; childIdx < 50; childIdx++)
                {
                    Widget child = client.getWidget(GE_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    String[] actions = child.getActions();
                    if (actions != null)
                    {
                        for (String act : actions)
                        {
                            if (act != null && act.equalsIgnoreCase("Enter price"))
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

            if (pricePoint != null)
            {
                human.moveAndClick(pricePoint.x, pricePoint.y);
                human.shortPause();
                human.typeText(String.valueOf(price));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE price set failed: " + t.getMessage());
        }
    }
}
