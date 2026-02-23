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
import java.util.ArrayList;
import java.util.List;

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
    private static final int WIDGET_SEARCH_RANGE = 100;
    private static final int INPUT_POLL_MS = 100;
    private static final int INPUT_TIMEOUT_MS = 2400; // 4 game ticks for chatbox input to appear

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
        Object[] step1Result;
        try
        {
            step1Result = ClientThreadRunner.runOnClientThread(clientThread, () -> {
                Widget ge = client.getWidget(GE_GROUP_ID, 0);
                if (ge == null || ge.isHidden())
                {
                    return new Object[]{ "GE_NOT_OPEN" };
                }

                Point p = findWidgetByAction(client, GE_GROUP_ID, "Create Buy offer");
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
            return ActionResult.failure(ActionType.GE_BUY, "GE lookup failed: " + t.getMessage());
        }

        String status = (String) step1Result[0];
        if ("GE_NOT_OPEN".equals(status))
        {
            return ActionResult.failure(ActionType.GE_BUY,
                "GE interface is not open. Open the GE first with INTERACT_NPC(\"Grand Exchange Clerk\", option=\"Exchange\").");
        }
        if ("NO_SLOT".equals(status))
        {
            @SuppressWarnings("unchecked")
            List<String> actions = (List<String>) step1Result[1];
            System.err.println("[ClaudeBot] GE_BUY: no 'Create Buy offer' found. Actions in group "
                + GE_GROUP_ID + ": " + actions);
            return ActionResult.failure(ActionType.GE_BUY,
                "GE is open but no empty buy offer slot found. All 8 GE slots may be in use, "
                + "or a buy/sell offer setup screen is already open.");
        }

        Point buyButtonPoint = (Point) step1Result[1];

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
                for (int childIdx = 0; childIdx < WIDGET_SEARCH_RANGE; childIdx++)
                {
                    Widget child = client.getWidget(GE_SEARCH_GROUP_ID, childIdx);
                    if (child == null || child.isHidden()) continue;

                    Point p = checkWidgetText(child, itemName);
                    if (p != null) return p;

                    // Check all child types
                    p = searchChildWidgets(child, itemName);
                    if (p != null) return p;
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
            confirmPoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "Confirm"));
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
        else
        {
            System.err.println("[ClaudeBot] GE_BUY: Confirm button not found");
        }

        return ActionResult.success(ActionType.GE_BUY);
    }

    /**
     * Polls until VarClientInt 5 (INPUT_TYPE) is non-zero, indicating the chatbox
     * is accepting text input (quantity/price dialogs).
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

    /**
     * Sets the quantity in the GE offer by finding and clicking the quantity input,
     * then typing the number — just like a human would.
     */
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
                    System.err.println("[ClaudeBot] GE buy quantity input did not appear");
                    return;
                }
                human.typeText(String.valueOf(quantity));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
            else
            {
                System.err.println("[ClaudeBot] GE_BUY: 'Enter quantity' button not found");
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
            Point pricePoint = ClientThreadRunner.runOnClientThread(clientThread,
                () -> findWidgetByAction(client, GE_GROUP_ID, "Enter price"));

            if (pricePoint != null)
            {
                human.moveAndClick(pricePoint.x, pricePoint.y);
                if (!waitForChatboxInput(client, human, clientThread))
                {
                    System.err.println("[ClaudeBot] GE buy price input did not appear");
                    return;
                }
                human.typeText(String.valueOf(price));
                human.pressKey(KeyEvent.VK_ENTER);
                human.shortPause();
            }
            else
            {
                System.err.println("[ClaudeBot] GE_BUY: 'Enter price' button not found");
            }
        }
        catch (Throwable t)
        {
            System.err.println("[ClaudeBot] GE price set failed: " + t.getMessage());
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
     * Checks if a widget's text or name matches the target item name (after stripping tags).
     */
    private static Point checkWidgetText(Widget widget, String targetName)
    {
        String text = widget.getText();
        String name = widget.getName();
        String stripped = null;

        if (text != null) stripped = stripTags(text);
        if ((stripped == null || stripped.isEmpty()) && name != null) stripped = stripTags(name);

        if (stripped != null && stripped.equalsIgnoreCase(targetName))
        {
            Rectangle bounds = widget.getBounds();
            if (bounds != null)
            {
                return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            }
        }
        return null;
    }

    /**
     * Searches dynamic, static, and regular children for text matching the target item name.
     */
    private static Point searchChildWidgets(Widget parent, String targetName)
    {
        Widget[] dynChildren = parent.getDynamicChildren();
        if (dynChildren != null)
        {
            for (Widget child : dynChildren)
            {
                if (child == null || child.isHidden()) continue;
                Point p = checkWidgetText(child, targetName);
                if (p != null) return p;
            }
        }

        Widget[] staticChildren = parent.getStaticChildren();
        if (staticChildren != null)
        {
            for (Widget child : staticChildren)
            {
                if (child == null || child.isHidden()) continue;
                Point p = checkWidgetText(child, targetName);
                if (p != null) return p;
            }
        }

        Widget[] children = parent.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                if (child == null || child.isHidden()) continue;
                Point p = checkWidgetText(child, targetName);
                if (p != null) return p;
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
