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
import java.util.ArrayList;
import java.util.List;

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
    private static final int WIDGET_SEARCH_RANGE = 100;
    private static final int INPUT_POLL_MS = 100;
    private static final int INPUT_TIMEOUT_MS = 2400; // 4 game ticks for chatbox input to appear

    public static ActionResult execute(Client client, HumanSimulator human, ItemManager itemManager,
                                       ClientThread clientThread, BotAction action, int maxUndercutPct)
    {
        String itemName = action.getName();
        if (itemName == null || itemName.isEmpty())
        {
            return ActionResult.failure(ActionType.GE_SELL, "No item name provided");
        }

        int quantity = action.getQuantity();
        if (quantity <= 0) quantity = 1;

        // Step 1: Find a sell slot in the GE
        Object[] step1Result;
        try
        {
            step1Result = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden())
                {
                    return new Object[]{ "GE_NOT_OPEN" };
                }

                Point p = findWidgetByAction(client, GE_GROUP_ID, "Create Sell offer");
                if (p != null)
                {
                    return new Object[]{ "OK", p };
                }

                // Not found — collect diagnostics
                List<String> foundActions = collectAllActions(client, GE_GROUP_ID);
                return new Object[]{ "NO_SLOT", foundActions };
            });
        }
        catch (Throwable t)
        {
            return ActionResult.failure(ActionType.GE_SELL, "GE lookup failed: " + t.getMessage());
        }

        String status = (String) step1Result[0];
        if ("GE_NOT_OPEN".equals(status))
        {
            return ActionResult.failure(ActionType.GE_SELL,
                "GE interface is not open. Open the GE first with INTERACT_NPC(\"Grand Exchange Clerk\", option=\"Exchange\").");
        }
        if ("NO_SLOT".equals(status))
        {
            @SuppressWarnings("unchecked")
            List<String> actions = (List<String>) step1Result[1];
            System.err.println("[ClaudeBot] GE_SELL: no 'Create Sell offer' found. Actions in group "
                + GE_GROUP_ID + ": " + actions);
            return ActionResult.failure(ActionType.GE_SELL,
                "GE is open but no empty sell offer slot found. All 8 GE slots may be in use, "
                + "or a buy/sell offer setup screen is already open.");
        }

        Point sellButtonPoint = (Point) step1Result[1];

        // Step 2: Click the sell slot (humanized)
        human.moveAndClick(sellButtonPoint.x, sellButtonPoint.y);
        human.shortPause();

        // Wait for inventory panel to appear
        human.getTimingEngine().sleep(human.getTimingEngine().nextShortPause());

        // Step 3: Find the item in the GE inventory panel and click it
        // Returns [Point, Integer(itemId)] so we can look up guide price
        Object[] itemLookup;
        try
        {
            itemLookup = ClientThreadRunner.runOnClientThread(clientThread, () -> {
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
                            return new Object[]{
                                new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()),
                                child.getItemId()
                            };
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

        if (itemLookup == null)
        {
            return ActionResult.failure(ActionType.GE_SELL, "Item not found in inventory: " + itemName);
        }

        Point itemPoint = (Point) itemLookup[0];
        int foundItemId = (Integer) itemLookup[1];

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

        // Step 4b: Set price — enforce max undercut cap relative to GE guide price
        int guidePrice = 0;
        try
        {
            guidePrice = itemManager.getItemPrice(foundItemId);
            if (guidePrice > 0)
            {
                System.out.println("[ClaudeBot] GE_SELL guide price for itemId=" + foundItemId + ": " + guidePrice);
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE_SELL guide price lookup failed: " + t.getMessage());
        }

        int minPrice = (maxUndercutPct > 0 && guidePrice > 0)
            ? (int)(guidePrice * (100 - maxUndercutPct) / 100.0)
            : 0; // 0 = no cap

        int price = action.getX();
        String priceSuffix = "";
        if (price > 0)
        {
            // Exact price — clamp if below minimum
            if (minPrice > 0 && price < minPrice)
            {
                System.out.println("[ClaudeBot] GE_SELL price capped: " + price + " -> " + minPrice
                    + " (guide=" + guidePrice + ", max-" + maxUndercutPct + "%)");
                priceSuffix = " (price capped from " + price + " to " + minPrice
                    + ", guide=" + guidePrice + ", max undercut " + maxUndercutPct + "%)";
                price = minPrice;
            }
            setPrice(client, human, clientThread, price);
        }
        else if (price < 0)
        {
            // Button clicks — cap at maxUndercutPct/5 if cap is active
            int maxClicks = (maxUndercutPct > 0) ? Math.max(1, maxUndercutPct / 5) : 10;
            int clicks = Math.min(Math.abs(price), maxClicks);
            if (clicks != Math.abs(price))
            {
                System.out.println("[ClaudeBot] GE_SELL -5% clicks capped: " + Math.abs(price)
                    + " -> " + clicks + " (max " + maxUndercutPct + "%)");
                priceSuffix = " (-5% clicks capped to " + clicks + ", max undercut " + maxUndercutPct + "%)";
            }
            clickPriceMinus(client, human, clientThread, clicks);
        }

        // Step 5: Click confirm button
        Point confirmPoint;
        try
        {
            confirmPoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "Confirm"));
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
        else
        {
            System.err.println("[ClaudeBot] GE_SELL: Confirm button not found");
        }

        String msg = "Sell offer placed for " + quantity + "x " + itemName;
        if (!priceSuffix.isEmpty()) msg += priceSuffix;
        return ActionResult.success(ActionType.GE_SELL, msg);
    }

    /**
     * Polls until VarClientInt 5 (INPUT_TYPE) is non-zero, indicating the chatbox
     * is accepting text input (quantity dialogs).
     */
    private static boolean waitForChatboxInput(Client client, HumanSimulator human, ClientThread clientThread)
    {
        long deadline = System.currentTimeMillis() + INPUT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                boolean active = ClientThreadRunner.runOnClientThread(clientThread,
                    () -> client.getVarcIntValue(5) != 0);
                if (active) return true;
            }
            catch (Throwable t) {}
            human.getTimingEngine().sleep(INPUT_POLL_MS);
        }
        return false;
    }

    private static void setQuantity(Client client, HumanSimulator human,
                                    ClientThread clientThread, int quantity)
    {
        try
        {
            Point qtyPoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "Enter quantity"));

            if (qtyPoint != null)
            {
                human.moveAndClick(qtyPoint.x, qtyPoint.y);
                if (!waitForChatboxInput(client, human, clientThread))
                {
                    System.err.println("[ClaudeBot] GE sell quantity input did not appear");
                    return;
                }
                human.typeText(String.valueOf(quantity));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
            else
            {
                System.err.println("[ClaudeBot] GE_SELL: 'Enter quantity' button not found");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE sell quantity set failed: " + t.getMessage());
        }
    }

    /**
     * Sets the price in the GE offer by finding and clicking the price input,
     * then typing the amount.
     */
    private static void setPrice(Client client, HumanSimulator human,
                                 ClientThread clientThread, int price)
    {
        try
        {
            Point pricePoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "Enter price"));

            if (pricePoint != null)
            {
                human.moveAndClick(pricePoint.x, pricePoint.y);
                if (!waitForChatboxInput(client, human, clientThread))
                {
                    System.err.println("[ClaudeBot] GE sell price input did not appear");
                    return;
                }
                human.typeText(String.valueOf(price));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
            else
            {
                System.err.println("[ClaudeBot] GE_SELL: 'Enter price' button not found");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE sell price set failed: " + t.getMessage());
        }
    }

    /**
     * Clicks the "-5%" price button the specified number of times for quick underselling.
     */
    private static void clickPriceMinus(Client client, HumanSimulator human,
                                        ClientThread clientThread, int clicks)
    {
        try
        {
            Point minusPoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "-5%"));

            if (minusPoint != null)
            {
                for (int i = 0; i < clicks; i++)
                {
                    human.moveAndClick(minusPoint.x, minusPoint.y);
                    human.getTimingEngine().sleep(100 + (int)(Math.random() * 150));
                }
                human.shortPause();
            }
            else
            {
                System.err.println("[ClaudeBot] GE_SELL: '-5%' button not found");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE -5% click failed: " + t.getMessage());
        }
    }

    // ---- Shared widget search helpers ----

    /**
     * Strips HTML tags (e.g. {@code <col=ff981f>}, {@code </col>}) from widget text.
     */
    private static String stripTags(String text)
    {
        if (text == null) return null;
        return text.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * Searches a widget group for a widget whose action matches the target
     * (after stripping color tags). Checks direct children, dynamic children,
     * and static children up to WIDGET_SEARCH_RANGE.
     */
    private static Point findWidgetByAction(Client client, int groupId, String targetAction)
    {
        for (int childIdx = 0; childIdx < WIDGET_SEARCH_RANGE; childIdx++)
        {
            Widget child = client.getWidget(groupId, childIdx);
            if (child == null || child.isHidden()) continue;

            Point p = checkWidgetActions(child, targetAction);
            if (p != null) return p;

            // Check dynamic children
            Widget[] dynChildren = child.getDynamicChildren();
            if (dynChildren != null)
            {
                for (Widget dynChild : dynChildren)
                {
                    if (dynChild == null || dynChild.isHidden()) continue;
                    p = checkWidgetActions(dynChild, targetAction);
                    if (p != null) return p;
                }
            }

            // Check static children
            Widget[] staticChildren = child.getStaticChildren();
            if (staticChildren != null)
            {
                for (Widget staticChild : staticChildren)
                {
                    if (staticChild == null || staticChild.isHidden()) continue;
                    p = checkWidgetActions(staticChild, targetAction);
                    if (p != null) return p;
                }
            }

            // Check getChildren()
            Widget[] children = child.getChildren();
            if (children != null)
            {
                for (Widget c : children)
                {
                    if (c == null || c.isHidden()) continue;
                    p = checkWidgetActions(c, targetAction);
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    /**
     * Checks if a widget has an action matching the target (after stripping HTML tags).
     * Returns the widget center point if found, null otherwise.
     */
    private static Point checkWidgetActions(Widget widget, String targetAction)
    {
        String[] actions = widget.getActions();
        if (actions == null) return null;
        for (String act : actions)
        {
            if (act == null) continue;
            String stripped = stripTags(act);
            if (stripped.equalsIgnoreCase(targetAction))
            {
                Rectangle bounds = widget.getBounds();
                if (bounds != null)
                {
                    return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
                }
            }
        }
        return null;
    }

    /**
     * Collects all non-null actions from all widgets in a group for diagnostics.
     */
    private static List<String> collectAllActions(Client client, int groupId)
    {
        List<String> found = new ArrayList<>();
        for (int childIdx = 0; childIdx < WIDGET_SEARCH_RANGE; childIdx++)
        {
            Widget child = client.getWidget(groupId, childIdx);
            if (child == null || child.isHidden()) continue;

            collectActionsFromWidget(child, "w" + childIdx, found);

            Widget[] dynChildren = child.getDynamicChildren();
            if (dynChildren != null)
            {
                for (int i = 0; i < dynChildren.length; i++)
                {
                    if (dynChildren[i] == null || dynChildren[i].isHidden()) continue;
                    collectActionsFromWidget(dynChildren[i], "w" + childIdx + ".dyn" + i, found);
                }
            }

            Widget[] staticChildren = child.getStaticChildren();
            if (staticChildren != null)
            {
                for (int i = 0; i < staticChildren.length; i++)
                {
                    if (staticChildren[i] == null || staticChildren[i].isHidden()) continue;
                    collectActionsFromWidget(staticChildren[i], "w" + childIdx + ".static" + i, found);
                }
            }
        }
        return found;
    }

    private static void collectActionsFromWidget(Widget widget, String path, List<String> found)
    {
        String[] actions = widget.getActions();
        if (actions == null) return;
        for (String act : actions)
        {
            if (act != null && !act.isEmpty())
            {
                found.add(path + ":" + stripTags(act) + " (raw:" + act + ")");
            }
        }
    }
}
